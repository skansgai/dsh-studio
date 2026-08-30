package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口工具栏动作：在系统默认浏览器中打开 DeepSeek Harness。
 */
public final class OpenInBrowserAction extends AnAction {

    private final Project project;

    public OpenInBrowserAction(@NotNull Project project) {
        super("Open in Browser",
                "Open the DeepSeek Harness web UI in the system browser",
                AllIcons.General.Web);
        this.project = project;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // update() 里会调用 isReachable() 等网络探测，必须在后台线程执行
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setEnabled(DshServerManager.getInstance(project).isReachable());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            return;
        }
        DshServerManager manager = DshServerManager.getInstance(project);
        DshUtil.openInBrowser(manager.getUrl());
    }
}
