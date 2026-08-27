package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.server.DshServerManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.ui.IconManager;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口工具栏动作：启动 DeepSeek Harness 服务器。
 */
public final class StartServerAction extends AnAction {

    private final Project project;

    public StartServerAction(@NotNull Project project) {
        super("Start Server",
                "Start the DeepSeek Harness web server",
                IconManager.getInstance().getIcon("/icons/dsh-start.svg", StartServerAction.class));
        this.project = project;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        DshServerManager manager = DshServerManager.getInstance(project);
        e.getPresentation().setEnabled(!manager.isReachable() && !manager.isManagedProcessAlive());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            return;
        }
        DshServerManager.getInstance(project).startServer();
    }
}
