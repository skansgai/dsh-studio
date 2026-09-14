package com.deepseek.dshstudio.runtime;

import org.jetbrains.annotations.NotNull;

/**
 * 内置运行时的解包位置。
 * <p>
 * 做成可配是因为企业安全软件（DLP，如 E-SafeNet）会按**路径范围**做透明加密：
 * 在它的范围内写一个小文件要 ~85 ms，实测只有 11 个/秒；而系统临时目录被排除在外，
 * 实测 2500 个/秒。内置运行时有 1.8 万个文件，两者相差 **27 分钟 vs 6 秒**。
 * 见 {@code docs/design-bundled-runtime.md} 的实测表。
 */
public enum DshRuntimeLocation {

    /**
     * 自动（默认）：Windows 用系统临时目录，其余平台用用户目录。
     * <p>
     * 只在 Windows 上偏向临时目录，是因为上述 DLP 是 Windows 上的企业软件；
     * 而 macOS / Linux 的 {@code /tmp} 常见 {@code noexec} 挂载与 tmpfs（占内存），
     * 反而会让终端 / ripgrep 这类原生可执行文件跑不起来。
     */
    AUTO("auto", "自动（推荐）"),

    /** 系统临时目录：解包最快，但可能被系统清理（清理后重新解包即可）。 */
    TEMP("temp", "系统临时目录（解包最快）"),

    /** 用户目录 {@code ~/.dshstudio/runtime}：更持久，不受临时目录清理影响。 */
    HOME("home", "用户目录（更持久）");

    public final String id;
    public final String label;

    DshRuntimeLocation(String id, String label) {
        this.id = id;
        this.label = label;
    }

    @NotNull
    public static DshRuntimeLocation fromId(String id) {
        if (id != null) {
            for (DshRuntimeLocation location : values()) {
                if (location.id.equals(id.trim())) {
                    return location;
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
