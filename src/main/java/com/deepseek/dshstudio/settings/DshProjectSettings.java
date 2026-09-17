package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 项目级设置：把「一个 dsh 实例」相关的状态（地址 / 端口 / 工作目录 / token）从应用级下沉到项目级。
 * <p>
 * 这样同一个 IDE 里同时打开 A、B 两个项目时，各自跑各自的 dsh 进程、各自连各自的地址、
 * 各自用自己的工作目录，互不覆盖也互不串会话。
 * <p>
 * <b>存放位置</b>：项目自己的 {@code workspace.xml}（IntelliJ 约定的「项目内、但只对本机有意义」的存储），
 * 不往 {@code .idea} 里新建插件专属文件，免得被提交进版本库 —— 端口这种东西换台机器本来就该重新分配。
 * <p>
 * <b>两种模式</b>（由 {@link #serverUrl} 是否为空决定）：
 * <ul>
 *   <li><b>自动模式</b>（默认，地址留空）：本插件为本项目拉起 dsh。端口优先用
 *       {@link #allocatedPort}（上次实际分配到的，保证地址在重启后稳定），其次用
 *       {@link #startPort}（用户期望端口），两者都被占用时自动挑一个系统空闲端口。
 *       被占用时<b>绝不复用</b>那个已有实例 —— 多项目并行时那多半是另一个项目的 dsh，
 *       复用会让两个项目的会话串在一起。</li>
 *   <li><b>手动模式</b>（地址非空）：直接连这个地址（例如终端里已经起好的实例，或远程机器），
 *       插件不自己拉进程、也不改端口。</li>
 * </ul>
 */
@State(name = "DshStudioProjectSettings", storages = {@Storage(StoragePathMacros.WORKSPACE_FILE)})
public final class DshProjectSettings implements PersistentStateComponent<DshProjectSettings> {

    /** 服务器地址；留空表示「自动管理」（由本插件为本项目拉起 dsh）。 */
    public String serverUrl = "";

    /** 自动模式下的期望端口（默认 3080）。被占用时插件会自动换端口，不会启动失败。 */
    public int startPort = DshStudioConstants.DEFAULT_PORT;

    /**
     * 自动模式下上次实际分配到的端口（0 = 尚未分配）。
     * <p>
     * 持久化是为了让同一个项目的地址在 IDE 重启后保持不变 —— 否则每次开项目端口都可能变，
     * 书签、外部工具里的地址就全失效了。
     */
    public int allocatedPort = 0;

    /** 工作目录（Harness 的 workspace 根）；留空则用本项目根目录 {@code project.basePath}。 */
    public String workingDirectory = "";

    /**
     * 外部服务器鉴权 token（可选）。
     * <p>
     * 只在「手动模式且那个实例不是本插件拉起的」时才有意义：本插件拉起的进程能自己从启动输出里
     * 捕获 token，不需要手填。
     */
    public String serverAuthToken = "";

    /**
     * 是否已经做过「从应用级旧配置继承」这一次性迁移。
     * <p>
     * 0.3.2 及更早版本把服务器地址 / 端口 / 工作目录 / token 存在应用级设置里（全局共享），
     * 升级后需要把这些值搬到项目级，否则老用户的自定义地址会丢。标记位保证只搬一次。
     */
    public boolean migrated = false;

    @Nullable
    private final Project project;

    /**
     * 平台按项目实例化（{@code projectService} 要求存在接收 {@link Project} 的构造函数）。
     *
     * @param project 所属项目；单元测试可直接传 {@code null}（不涉及项目路径的方法仍可用）
     */
    public DshProjectSettings(@Nullable Project project) {
        this.project = project;
    }

    public static DshProjectSettings getInstance(@NotNull Project project) {
        return project.getService(DshProjectSettings.class);
    }

    // ── 模式判定 ──────────────────────────────────────────────────────────

    /** 自动模式：地址留空，由本插件为本项目拉起 dsh 进程。 */
    public boolean isAutoManaged() {
        return serverUrl == null || serverUrl.trim().isEmpty();
    }

    /**
     * 本项目当前使用的端口。
     * <p>
     * 手动模式优先取地址里写的端口（地址是权威）；自动模式取已分配端口，没有则取期望端口。
     */
    public int effectivePort() {
        if (!isAutoManaged()) {
            int fromUrl = portOf(serverUrl);
            if (fromUrl > 0) {
                return fromUrl;
            }
        }
        if (allocatedPort > 0) {
            return allocatedPort;
        }
        return startPort > 0 ? startPort : DshStudioConstants.DEFAULT_PORT;
    }

    /** 本项目当前要连接的地址。自动模式由端口推导，手动模式原样返回用户填的地址。 */
    public String effectiveServerUrl() {
        if (!isAutoManaged()) {
            return serverUrl.trim();
        }
        return "http://127.0.0.1:" + effectivePort();
    }

    /** 当前地址里的主机名（解析不出来时退回回环地址）。 */
    public String effectiveHost() {
        String url = effectiveServerUrl();
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null || host.isEmpty() ? "127.0.0.1" : host;
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    /**
     * 本项目的工作目录（Harness 的 workspace 根）：
     * 用户显式配置优先，否则用项目根目录，最后才退回用户主目录。
     * <p>
     * 注意这里是<b>项目级</b>的 —— 手动改工作目录只影响当前项目，不会把别的项目也带偏；
     * 项目 B 是项目 A 的子目录时，各自解析到各自的 {@code basePath}。
     */
    public String resolvedWorkingDirectory() {
        String configured = workingDirectory == null ? "" : workingDirectory.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        String base = project == null ? null : project.getBasePath();
        return base != null ? base : System.getProperty("user.home", ".");
    }

    /** 规范化后的鉴权 token（可能为空串）。 */
    public String normalizedServerAuthToken() {
        return serverAuthToken == null ? "" : serverAuthToken.trim();
    }

    // ── 持久化 ────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public DshProjectSettings getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DshProjectSettings state) {
        this.serverUrl = state.serverUrl == null ? "" : state.serverUrl;
        this.startPort = state.startPort > 0 ? state.startPort : DshStudioConstants.DEFAULT_PORT;
        this.allocatedPort = Math.max(0, state.allocatedPort);
        this.workingDirectory = state.workingDirectory == null ? "" : state.workingDirectory;
        this.serverAuthToken = state.serverAuthToken == null ? "" : state.serverAuthToken;
        this.migrated = state.migrated;
        if (!migrated) {
            inheritLegacyGlobalValues();
            migrated = true;
        }
    }

    /**
     * 一次性迁移：把 0.3.2 及更早版本存在<b>应用级</b>设置里的服务器配置继承到本项目。
     * <p>
     * 只继承「被用户改过的非默认值」，否则会把每个项目都钉死在默认端口 3080 上，
     * 反而丢掉自动分配空闲端口的能力（多项目并行时这就是串会话的根因）。
     * <p>
     * 迁移全局只做一次（由 {@link DshSettingsState#legacyServerSettingsMigrated} 把关），
     * 落到升级后第一个打开的项目上 —— 用户当初改的就是「唯一那个」实例，语义最接近。
     */
    private void inheritLegacyGlobalValues() {
        DshSettingsState legacy;
        try {
            legacy = DshSettingsState.getInstance();
        } catch (Exception e) {
            return; // 没有应用环境（单测）时跳过
        }
        if (legacy == null || legacy.legacyServerSettingsMigrated) {
            return;
        }
        String legacyUrl = legacy.normalizedServerUrl();
        if (this.serverUrl.isEmpty() && !DshStudioConstants.DEFAULT_SERVER_URL.equals(legacyUrl)) {
            this.serverUrl = legacyUrl;
        }
        if (this.startPort == DshStudioConstants.DEFAULT_PORT
                && legacy.startPort > 0
                && legacy.startPort != DshStudioConstants.DEFAULT_PORT) {
            this.startPort = legacy.startPort;
        }
        if (this.workingDirectory.isEmpty() && legacy.workingDirectory != null) {
            this.workingDirectory = legacy.workingDirectory.trim();
        }
        if (this.serverAuthToken.isEmpty() && legacy.serverAuthToken != null) {
            this.serverAuthToken = legacy.serverAuthToken.trim();
        }
        legacy.legacyServerSettingsMigrated = true;
    }

    /** 从 URL 里取端口；取不到（或没写）返回 -1。 */
    private static int portOf(@Nullable String url) {
        if (url == null) {
            return -1;
        }
        try {
            int port = java.net.URI.create(url.trim()).getPort();
            return port > 0 ? port : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }
}
