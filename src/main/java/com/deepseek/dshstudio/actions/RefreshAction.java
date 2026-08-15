package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.server.DshServerManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口工具栏动作：重新探测服务器状态并刷新内嵌页面。
 */
public final class RefreshAction extends AnAction {

    private final Project project;
    private final Runnable reload;

    public RefreshAction(@NotNull Project project, @NotNull Runnable reload) {
        super("Refresh", "Re-check the server status and reload the page", AllIcons.Actions.Refresh);
        this.project = project;
        this.reload = reload;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setEnabled(true);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            return;
        }
        DshServerManager.getInstance(project).probeAsync();
        reload.run();
    }
}
