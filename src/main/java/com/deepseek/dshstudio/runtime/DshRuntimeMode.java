package com.deepseek.dshstudio.runtime;

import org.jetbrains.annotations.NotNull;

/**
 * dsh 运行时的来源选择。
 * <p>
 * 对应设置页「运行时」区块的下拉框，持久化为 {@code runtimeMode} 字符串。
 */
public enum DshRuntimeMode {

    /**
     * 自动（默认）：用户目录里的热更新版本 → 插件内置基线 → 系统 dsh（npx）。
     * <p>
     * 既保证「装上就能用」，又能在后台热更新到更新的 dsh 后自动用上新版。
     */
    AUTO("auto", "自动（优先内置运行时）"),

    /**
     * 仅内置运行时：忽略用户目录里的热更新版本。
     * <p>
     * 热更新把版本弄坏时的逃生出口（等价于一次「回滚到内置基线」）。
     */
    BUNDLED("bundled", "仅内置运行时"),

    /** 仅系统 dsh：保持插件 0.3.x 以前的行为，用 {@code npx --yes @deepseek-ai/dsh} 启动。 */
    SYSTEM("system", "仅系统 dsh（npx）");

    public final String id;
    public final String label;

    DshRuntimeMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    @NotNull
    public static DshRuntimeMode fromId(String id) {
        if (id != null) {
            for (DshRuntimeMode mode : values()) {
                if (mode.id.equals(id.trim())) {
                    return mode;
                }
            }
        }
        return AUTO;
    }

    @Override
    public String toString() {
        return label;
    }
}
