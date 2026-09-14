package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.runtime.DshNodeChecker;
import com.deepseek.dshstudio.runtime.DshRuntimeManager;
import com.deepseek.dshstudio.runtime.DshRuntimeMode;
import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 运行 dsh headless 一次性任务：`dsh --profile headless "任务描述"`，
 * 输出实时追加到工具窗口的 Server Log，完成后弹通知（含退出码）。
 */
public final class DshHeadlessTaskAction extends AnAction {

    public DshHeadlessTaskAction() {
        super("Run Headless Task…",
                "Run a one-shot dsh task without the web UI (dsh --profile headless)",
                null);
    }

    @Override
    public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(e.getProject() != null);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        String task = com.intellij.openapi.ui.Messages.showMultilineInputDialog(
                project,
                "任务描述（由 headless agent 执行，完成后自动退出）：",
                "DeepSeek Harness Headless 任务",
                "",
                null,
                null);
        if (task == null || task.trim().isEmpty()) {
            return;
        }
        runHeadless(project, task.trim());
    }

    private static void runHeadless(Project project, String task) {
        DshServerManager manager = DshServerManager.getInstance(project);
        DshSettingsState settings = DshSettingsState.getInstance();
        String workdir = DshUtil.resolveWorkingDirectory(settings, project);

        // 与启动服务器走同一条运行时准备路径（首次可能需要解包内置运行时）
        try {
            DshRuntimeManager.getInstance()
                    .prepare(project, DshRuntimeMode.fromId(settings.runtimeMode));
        } catch (Exception ex) {
            showNotification(project, "无法准备 dsh 运行时", String.valueOf(ex.getMessage()),
                    NotificationType.ERROR);
            return;
        }
        List<String> command;
        try {
            command = resolveCommand(project, task);
        } catch (Exception ex) {
            showNotification(project, "无法构建 headless 命令", ex.getMessage(), NotificationType.ERROR);
            return;
        }
        // 缺 Node 或版本偏低时弹一次引导（只警告不拦截）。自定义成非 Node 命令时不打扰。
        if (DshNodeChecker.commandNeedsNode(command) && !DshNodeChecker.guideIfNeeded(project)) {
            return;
        }
        manager.appendExternalLog("$ " + String.join(" ", command) + "\n");

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "dsh headless 任务", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.directory(new File(workdir));
                    pb.redirectErrorStream(true);
                    if (settings.dshHome != null && !settings.dshHome.trim().isEmpty()) {
                        pb.environment().put("DSH_HOME", settings.dshHome.trim());
                    }
                    Process process = pb.start();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            manager.appendExternalLog(line + "\n");
                        }
                    }
                    int code = process.waitFor();
                    manager.appendExternalLog("\n[headless] 退出码 " + code + "\n");
                    showNotification(project,
                            code == 0 ? "Headless 任务完成" : "Headless 任务失败",
                            "任务「" + task + "」已结束（退出码 " + code + "），输出见工具窗口 Server Log。",
                            code == 0 ? NotificationType.INFORMATION : NotificationType.WARNING);
                } catch (Exception ex) {
                    manager.appendExternalLog("\n[headless] 执行失败: " + ex.getMessage() + "\n");
                    showNotification(project, "Headless 任务执行失败", String.valueOf(ex.getMessage()), NotificationType.ERROR);
                }
            }
        });
    }

    /**
     * 弹一条通知。
     * <p>
     * 方法名刻意不叫 {@code notify}：那是 {@link Object} 的 final 语义方法，
     * 同名重载极易与平台通知混淆（且无参版本仍指向 {@code Object.notify}）。
     */
    private static void showNotification(@NotNull Project project,
                                         @NotNull String title,
                                         @NotNull String content,
                                         @NotNull NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content, type)
                .notify(project);
    }

    /**
     * 基于 {dsh} {task} {workdir} {dshHome} 占位符解析 headless 命令。
     * <p>
     * {dsh} 由 DshUtil 按设置里的运行时来源展开（内置运行时 / 热更新版本 / 系统 npx）。
     */
    private static List<String> resolveCommand(Project project, String task) {
        DshSettingsState settings = DshSettingsState.getInstance();
        String template = DshStudioConstants.DEFAULT_HEADLESS_COMMAND
                .replace("{task}", quoteForTemplate(task));
        return DshUtil.resolveTemplate(template, settings, project);
    }

    /** 任务文本作为单个参数传递：用双引号包裹（tokenize 支持引号）。 */
    private static String quoteForTemplate(String task) {
        return "\"" + task.replace("\"", "'") + "\"";
    }

    private static void notify(Project project, String title, @Nullable String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                        .createNotification(title, content == null ? "" : content, type)
                        .notify(project);
            }
        });
    }
}
