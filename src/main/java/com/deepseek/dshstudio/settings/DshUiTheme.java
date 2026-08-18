package com.deepseek.dshstudio.settings;

import org.jetbrains.annotations.NotNull;

/**
 * 插件主题（作用于工具窗口自身界面，并对内嵌页面做浅/深色桥接）。
 */
public enum DshUiTheme {

    /** 跟随 IDE 的明暗主题。 */
    FOLLOW("跟随 IDE", "follow"),
    /** 强制浅色。 */
    LIGHT("浅色", "light"),
    /** 强制深色。 */
    DARK("深色", "dark");

    public final String label;
    public final String id;

    DshUiTheme(String label, String id) {
        this.label = label;
        this.id = id;
    }

    /** 是否强制深色（true）/ 强制浅色（false）/ 跟随（null）。 */
    public Boolean isDarkOverride() {
        return switch (this) {
            case DARK -> Boolean.TRUE;
            case LIGHT -> Boolean.FALSE;
            default -> null;
        };
    }

    public static DshUiTheme fromId(@NotNull String id) {
        for (DshUiTheme t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return FOLLOW;
    }
}
