package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.server.DshApiClient;
import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.server.DshServerManager.ServerState;
import com.deepseek.dshstudio.server.DshSessionSummary;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.icons.AllIcons;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JList;
import java.util.List;

/**
 * 会话快速访问：搜索式弹窗列出 Harness 的历史会话（标题 + 相对时间 + 运行状态），
 * 选中后在内嵌浏览器中直达该会话。工具栏入口 + Tools 菜单入口。
 */
public final class DshSessionsPopupAction extends AnAction {

    public DshSessionsPopupAction() {
        super("Recent Harness Sessions…",
                "List and jump to a DeepSeek Harness session",
                AllIcons.Toolwindows.ToolWindowMessages);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
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
        DshServerManager manager = DshServerManager.getInstance(project);
        if (!manager.isReachable()) {
            notifyInfo(project, "服务器未运行",
                    "DeepSeek Harness 服务器尚未运行，已打开工具窗口（可在其中启动）。");
            activateToolWindow(project);
            return;
        }
        DshApiClient.getInstance(project).listSessionsAsync()
                .thenAccept(sessions -> ApplicationManager.getApplication().invokeLater(
                        () -> showPopup(project, sessions), project.getDisposed()))
                .exceptionally(ex -> {
                    ApplicationManager.getApplication().invokeLater(
                            () -> notifyError(project, "获取会话列表失败", String.valueOf(ex.getMessage())));
                    return null;
                });
    }

    // ── 弹窗 ──────────────────────────────────────────────────────────────

    private void showPopup(@NotNull Project project, @Nullable List<DshSessionSummary> sessions) {
        if (project.isDisposed()) {
            return;
        }
        if (sessions == null || sessions.isEmpty()) {
            notifyInfo(project, "暂无会话",
                    "DeepSeek Harness 中还没有可见会话；发送代码或在内嵌界面里新建一个吧。");
            activateToolWindow(project);
            return;
        }
        JBPopupFactory.getInstance()
                .createPopupChooserBuilder(sessions)
                .setTitle("DeepSeek Harness 会话（输入文字过滤）")
                .setRenderer(new ColoredListCellRenderer<>() {
                    @Override
                    protected void customizeCellRenderer(@NotNull JList<? extends DshSessionSummary> list,
                                                         DshSessionSummary value,
                                                         int index,
                                                         boolean selected,
                                                         boolean hasFocus) {
                        setIcon(value.running
                                ? AllIcons.Actions.Execute
                                : AllIcons.Actions.Preview);
                        append(value.displayTitle(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                        append("  " + DshUtil.relativeTime(value.updatedAt),
                                SimpleTextAttributes.GRAYED_ATTRIBUTES);
                        if (value.running) {
                            append("  ● 运行中", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                        }
                    }
                })
                .setNamerForFiltering(DshSessionSummary::displayTitle)
                .setItemChosenCallback(session -> openSession(project, session))
                .createPopup()
                .showInFocusCenter();
    }

    // ── 直达会话 ──────────────────────────────────────────────────────────

    /**
     * 选中会话后的动作。
     * <p>
     * <b>dsh 0.1.1-rc.2 不支持会话深链</b>：前端产物里 {@code searchParams} 与
     * {@code location.hash} 出现次数为 0，SPA 完全不读 URL 参数，且没有
     * "切换当前会话"的 host API（session.* 方法面里没有 focus/select）。
     * 所以这里不做无效的 URL 拼接，而是打开工具窗口 + 把 sessionId 放进剪贴板，
     * 让用户一键在侧边栏里定位。等上游支持深链后再升级为直达。
     */
    private void openSession(@NotNull Project project, @NotNull DshSessionSummary session) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(DshStudioConstants.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return;
        }
        DshServerManager manager = DshServerManager.getInstance(project);
        if (manager.getState() != ServerState.RUNNING) {
            manager.probeAsync();
        }
        copyToClipboard(session.sessionId);
        toolWindow.activate(null, true, true);
        notifyInfo(project, "会话 ID 已复制",
                "“" + session.displayTitle() + "” 的会话 ID 已在剪贴板中。"
                        + "dsh 目前不支持通过链接直达会话，请在侧边栏中切换到该会话。");
    }

    private static void copyToClipboard(@NotNull String text) {
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (RuntimeException ignored) {
            // 剪贴板不可用（无头环境 / 被占用）时不打断主流程
        }
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

    private static void notifyError(Project project, String title, @Nullable String content) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, content == null ? "" : content, NotificationType.ERROR)
                .notify(project);
    }
}
