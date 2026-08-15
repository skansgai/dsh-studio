package com.deepseek.dshstudio.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * DeepSeek Harness 工具窗口工厂：创建内嵌浏览器面板。
 */
public final class DshToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DshToolWindowPanel panel = new DshToolWindowPanel(project);
        Content content = ContentFactory.getInstance()
                .createContent(panel.getComponent(), "", false);
        toolWindow.getContentManager().addContent(content);
        Disposer.register(toolWindow.getDisposable(), panel);
    }
}
