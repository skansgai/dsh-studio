package com.deepseek.dshstudio.actions;

import com.deepseek.dshstudio.DshStudioConstants;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/**
 * 菜单动作：打开 DeepSeek Harness 工具窗口（Tools 菜单）。
 */
public final class OpenDshToolWindowAction extends AnAction {

    public OpenDshToolWindowAction() {
        super("DeepSeek Harness",
                "Open the DeepSeek Harness tool window",
                IconLoader.getIcon("/icons/dsh-toolwindow.svg"));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(DshStudioConstants.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null, true, true);
        }
    }
}
