package com.deepseek.dshstudio.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

/**
 * 状态栏小部件工厂：把 {@link DshStatusBarWidget} 注册到 IDE 状态栏。
 * <p>
 * 用户可在 Settings → Appearance &amp; Behavior → Appearance 处隐藏/恢复该小部件。
 */
public final class DshStatusBarWidgetFactory implements StatusBarWidgetFactory {

    public static final String FACTORY_ID = "DshStudio.StatusBarWidget";

    @Override
    public @NotNull @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "DeepSeek Harness";
    }

    @Override
    public @NotNull String getId() {
        return FACTORY_ID;
    }

    @Override
    public boolean isAvailable(@NotNull Project project) {
        return !project.isDefault();
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new DshStatusBarWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        widget.dispose();
    }

    @Override
    public boolean canBeEnabledOn(@NotNull StatusBar statusBar) {
        return true;
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
