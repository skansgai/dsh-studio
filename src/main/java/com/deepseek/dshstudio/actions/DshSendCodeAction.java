package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.server.DshApiClient;
import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.server.DshServerManager.ServerState;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 编辑器动作：把选中的代码（或整个文件）发送到 DeepSeek Harness 会话。
 * <p>
 * 流程：收集代码上下文 →（可选）编辑指令 → 后台确保服务器运行并提交 prompt →
 * 打开工具窗口查看 agent 的执行过程。
 */
public class DshSendCodeAction extends AnAction {

    /** 预设指令；null 表示先弹输入框由用户自由输入。 */
    @Nullable
    private final String presetInstruction;

    /** plugin.xml 注册用的无参构造（动作文本由 XML 提供）。 */
    public DshSendCodeAction() {
        this(null, null, null);
    }

    public DshSendCodeAction(@Nullable String text, @Nullable String description, @Nullable String presetInstruction) {
        super(text, description, null);
        this.presetInstruction = presetInstruction;
    }

    @Override
    public @NotNull com.intellij.openapi.actionSystem.ActionUpdateThread getActionUpdateThread() {
        // update() 读取 Editor/Document 状态，保持在 EDT
        return com.intellij.openapi.actionSystem.ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        e.getPresentation().setEnabledAndVisible(project != null && editor != null
                && editor.getDocument().getTextLength() > 0);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (project == null || editor == null) {
            return;
        }

        CodeContext context = collectCodeContext(editor, file);
        if (context == null || context.code.isEmpty()) {
            return;
        }

        String instruction = presetInstruction;
        if (instruction == null) {
            instruction = com.intellij.openapi.ui.Messages.showMultilineInputDialog(
                    project, "给 Harness 的指令（可编辑）：", "发送到 DeepSeek Harness",
                    "解释这段代码", null, null);
            if (instruction == null || instruction.trim().isEmpty()) {
                return; // 用户取消
            }
        }

        final String message = DshUtil.buildCodePrompt(
                instruction.trim(),
                context.filePath,
                context.startLine,
                context.endLine,
                context.code,
                context.language,
                null);
        final String summary = instruction.trim();

        ProgressManager.getInstance().run(new Task.Backgroundable(project, "发送代码到 DeepSeek Harness", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setText("确保 DeepSeek Harness 服务器运行…");
                DshServerManager manager = DshServerManager.getInstance(project);
                try {
                    if (!manager.isReachable()) {
                        notifyInfo(project, "正在发送代码到 DeepSeek Harness",
                                "服务器未运行，正在启动并提交指令「" + summary + "」…");
                        manager.startServer();
                    }
                    if (!awaitRunning(manager, DshStudioConstants.API_WAIT_SERVER_MS)) {
                        notifyError(project, "服务器未就绪",
                                "DeepSeek Harness 服务器在 " + (DshStudioConstants.API_WAIT_SERVER_MS / 1000)
                                        + " 秒内未启动完成。请打开工具窗口查看 Server Log，"
                                        + "确认 Node.js 18+ 已安装、端口未被占用。");
                        return;
                    }
                    indicator.setText("提交到 Harness 会话…");
                    DshApiClient api = DshApiClient.getInstance(project);
                    String sessionId = api.getOrCreateIdeSession();
                    api.prompt(sessionId, message);
                    ApplicationManager.getApplication().invokeLater(() -> {
                        notifyInfo(project, "已发送到 DeepSeek Harness",
                                "指令「" + summary + "」已提交，agent 正在工作。");
                        activateToolWindow(project);
                    });
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                            ? ex.getMessage() : ex.getClass().getSimpleName();
                    notifyError(project, "发送失败", msg);
                }
            }
        });
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static boolean awaitRunning(DshServerManager manager, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ServerState state = manager.getState();
            if (state == ServerState.RUNNING) {
                return true;
            }
            if (state == ServerState.FAILED) {
                return false;
            }
            // 主动探测一次，让状态机反映真实可达性（启动时看门狗也会探测，这里兜底）
            manager.probe();
            if (manager.isReachable()) {
                return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        manager.probe();
        return manager.isReachable();
    }

    @Nullable
    private static CodeContext collectCodeContext(Editor editor, @Nullable VirtualFile file) {
        Document document = editor.getDocument();
        String code;
        int startLine;
        int endLine;
        if (editor.getSelectionModel().hasSelection()) {
            int selectionStart = editor.getSelectionModel().getSelectionStart();
            int selectionEnd = editor.getSelectionModel().getSelectionEnd();
            TextRange range = new TextRange(selectionStart, selectionEnd);
            code = document.getText(range);
            startLine = document.getLineNumber(range.getStartOffset()) + 1;
            endLine = document.getLineNumber(range.getEndOffset()) + 1;
        } else {
            code = document.getText();
            startLine = 1;
            endLine = document.getLineCount();
        }
        if (code.trim().isEmpty()) {
            return null;
        }
        String language = null;
        if (file != null) {
            FileType fileType = file.getFileType();
            language = fileType.getName().toLowerCase();
        }
        return new CodeContext(file == null ? null : file.getPath(), startLine, endLine, code.trim(), language);
    }

    private static void activateToolWindow(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(DshStudioConstants.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null, true, true);
        }
    }

    private static void notifyInfo(Project project, String title, String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content, NotificationType.INFORMATION)
                .notify(project);
    }

    private static void notifyError(Project project, String title, String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content, NotificationType.ERROR)
                .notify(project);
    }

    private static final class CodeContext {
        @Nullable
        final String filePath;
        final int startLine;
        final int endLine;
        final String code;
        @Nullable
        final String language;

        CodeContext(@Nullable String filePath, int startLine, int endLine, String code, @Nullable String language) {
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.code = code;
            this.language = language;
        }
    }
}
