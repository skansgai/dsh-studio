package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.DshStudioConstants;
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

        List<String> command;
        try {
            command = resolveCommand(task, workdir);
        } catch (Exception ex) {
            showNotification(project, "无法构建 headless 命令", ex.getMessage(), NotificationType.ERROR);
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

    /** 基于 {task} {workdir} {dshHome} 占位符解析 headless 命令；首 token 在 Windows 上解析为 .cmd 完整路径。 */
    private static List<String> resolveCommand(String task, String workdir) {
        DshSettingsState settings = DshSettingsState.getInstance();
        String dshHome = settings.dshHome == null ? "" : settings.dshHome.trim();
        String template = DshStudioConstants.DEFAULT_HEADLESS_COMMAND
                .replace("{task}", quoteForTemplate(task))
                .replace("{workdir}", workdir)
                .replace("{dshHome}", dshHome);
        List<String> tokens = DshUtil.tokenize(template);
        if (tokens.isEmpty()) {
            throw new IllegalStateException("headless 命令为空");
        }
        tokens.set(0, resolveLauncher(tokens.get(0)));
        return tokens;
    }

    /** 任务文本作为单个参数传递：用双引号包裹（tokenize 支持引号）。 */
    private static String quoteForTemplate(String task) {
        return "\"" + task.replace("\"", "'") + "\"";
    }

    /** Windows 下把 npx/npm 解析为带 .cmd 的完整路径（与 DshUtil.resolveCommandLine 行为一致）。 */
    private static String resolveLauncher(String first) {
        if (!DshUtil.isWindows()) {
            return first;
        }
        String lower = first.toLowerCase(java.util.Locale.ROOT);
        String base;
        if (lower.endsWith(".cmd")) {
            base = lower.substring(0, lower.length() - 4);
        } else {
            base = lower;
        }
        if (!base.equals("npx") && !base.equals("npm") && !base.equals("dsh")) {
            return first;
        }
        String resolved = DshUtil.resolveOnPath(base + ".cmd");
        if (resolved != null && !resolved.trim().isEmpty()) {
            return resolved.trim();
        }
        return base + ".cmd";
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
