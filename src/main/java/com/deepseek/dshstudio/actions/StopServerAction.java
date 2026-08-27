package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.server.DshServerManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.ui.IconManager;
import org.jetbrains.annotations.NotNull;

/**
 * 工具窗口工具栏动作：停止本插件启动的 DeepSeek Harness 服务器。
 */
public final class StopServerAction extends AnAction {

    private final Project project;

    public StopServerAction(@NotNull Project project) {
        super("Stop Server",
                "Stop the DeepSeek Harness web server started by this plugin",
                IconManager.getInstance().getIcon("/icons/dsh-stop.svg", StopServerAction.class));
        this.project = project;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }
        e.getPresentation().setEnabled(DshServerManager.getInstance(project).isManagedProcessAlive());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        if (project.isDisposed()) {
            return;
        }
        DshServerManager.getInstance(project).stopServer();
    }
}
