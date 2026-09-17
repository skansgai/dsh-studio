package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件设置（应用级），持久化到 IDE 配置目录的 deepseek-harness.xml。
 * <p>
 * 这里只放<b>真正跨项目共享</b>的东西：运行时来源 / 解包位置 / 启动命令模板 / DSH_HOME /
 * 自动启动 / 内嵌浏览器 / 主题 / 背景图。
 * <p>
 * 服务器地址、端口、工作目录、鉴权 token 这些「一个 dsh 实例」的概念已下沉到
 * {@link DshProjectSettings}（项目级）—— 同一个 IDE 里 A、B 两个项目要各跑各的 dsh，
 * 这些值共享就会互相覆盖。下面那四个 {@code @Deprecated} 字段只用于升级迁移，设置页不再编辑。
 */
@State(name = "DeepSeekHarnessSettings", storages = {@Storage("deepseek-harness.xml")})
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {

    /**
     * 连接 / 打开的 DeepSeek Harness 地址。
     *
     * @deprecated 地址是<b>项目级</b>概念，0.3.3 起由 {@link DshProjectSettings#serverUrl} 承载。
     *             这里保留读取只为升级时一次性迁移，设置页不再编辑。
     */
    @Deprecated
    public String serverUrl = DshStudioConstants.DEFAULT_SERVER_URL;

    /**
     * 自动启动服务器时使用的端口（与默认命令模板中的 {port} 对应）。
     *
     * @deprecated 端口是<b>项目级</b>概念，0.3.3 起由 {@link DshProjectSettings#startPort} 承载，
     *             且被占用时会自动改用空闲端口。这里保留读取只为升级迁移。
     */
    @Deprecated
    public int startPort = DshStudioConstants.DEFAULT_PORT;

    /** 自定义启动命令模板；留空使用默认模板。支持 {dsh} {host} {port} {workdir} {dshHome}。 */
    public String serverCommand = "";

    /**
     * 运行时来源：auto（优先已下载运行时，首次启动自动下载当前平台包）/ system（仅系统 dsh）。
     * <p>
     * 仅当启动命令模板里含 {dsh} 占位符时才有意义（默认模板含）。
     */
    public String runtimeMode = "auto";

    /**
     * 运行时（下载后）的解包位置：auto（Windows 用系统临时目录，其余用用户目录）/ temp / home。
     * <p>
     * 企业安全软件会按路径范围做透明加密，临时目录通常被排除在外，解包快两个数量级。
     * 详见 {@code DshRuntimeLocation}。
     */
    public String runtimeLocation = "auto";

    /**
     * 服务器工作目录；留空则使用当前项目目录。
     *
     * @deprecated 工作目录是<b>项目级</b>概念，0.3.3 起由
     *             {@link DshProjectSettings#workingDirectory} 承载。这里保留读取只为升级迁移。
     */
    @Deprecated
    public String workingDirectory = "";

    /** DSH_HOME 环境变量覆盖；留空则使用 ~/.dsh。 */
    public String dshHome = "";

    /** 打开工具窗口时若服务器未运行则自动启动。 */
    public boolean autoStartServer = true;

    /** 使用内嵌浏览器（JCEF）；关闭时仅提供系统浏览器打开。 */
    public boolean useEmbeddedBrowser = true;

    /** 健康检查轮询间隔（毫秒）。 */
    public int healthPollMs = DshStudioConstants.HEALTH_POLL_MS;

    /** 插件主题：follow / light / dark（作用于工具窗口界面并对内嵌页面做桥接）。 */
    public String uiTheme = "follow";

    /** 工具窗口背景图片（绝对路径，本地图片文件）；留空则不显示背景图。 */
    public String backgroundImagePath = "";

    /** 背景图浮层透明度（0.0–1.0），作用于内嵌 dsh 网页之上的半透明覆盖层。默认 0.15。 */
    public double backgroundImageOpacity = 0.15;

    /**
     * 外部服务器鉴权 token（可选）：连接非本插件启动的 dsh 实例时，
     * 从其启动输出中的 ?token=... 复制到这里以启用 IDE 内的会话操作。
     *
     * @deprecated token 属于「某个具体实例」，是<b>项目级</b>概念，0.3.3 起由
     *             {@link DshProjectSettings#serverAuthToken} 承载。这里保留读取只为升级迁移。
     */
    @Deprecated
    public String serverAuthToken = "";

    /**
     * 项目关闭后是否保留该项目拉起的 dsh 服务器进程。
     * <p>
     * 默认 {@code false}：项目一关就把进程结束掉，端口随之释放、也不会留下孤儿进程继续写共享的
     * DSH_HOME。想跨项目关开继续用同一个实例（比如后台还在跑长任务）时再打开它。
     * <p>
     * 注意只影响<b>本插件拉起的</b>进程；手动模式连的外部实例本来就不归插件管，不会被结束。
     */
    public boolean keepDshRunningAfterProjectClose = false;

    /**
     * 旧版（0.3.2 及更早）把服务器配置存在应用级，升级后需要搬到项目级一次。
     * 本标记保证只搬一次，落到升级后第一个打开的项目上。
     */
    public boolean legacyServerSettingsMigrated = false;

    public static DshSettingsState getInstance() {
        return ApplicationManager.getApplication().getService(DshSettingsState.class);
    }

    @Nullable
    @Override
    public DshSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(@NotNull DshSettingsState state) {
        this.serverUrl = state.serverUrl;
        this.startPort = state.startPort;
        this.serverCommand = state.serverCommand;
        this.runtimeMode = state.runtimeMode == null ? "auto" : state.runtimeMode;
        this.runtimeLocation = state.runtimeLocation == null ? "auto" : state.runtimeLocation;
        this.workingDirectory = state.workingDirectory;
        this.dshHome = state.dshHome;
        this.autoStartServer = state.autoStartServer;
        this.useEmbeddedBrowser = state.useEmbeddedBrowser;
        this.healthPollMs = state.healthPollMs;
        this.uiTheme = state.uiTheme == null ? "follow" : state.uiTheme;
        this.backgroundImagePath = state.backgroundImagePath == null ? "" : state.backgroundImagePath;
        this.backgroundImageOpacity = state.backgroundImageOpacity;
        this.serverAuthToken = state.serverAuthToken == null ? "" : state.serverAuthToken;
        this.keepDshRunningAfterProjectClose = state.keepDshRunningAfterProjectClose;
        this.legacyServerSettingsMigrated = state.legacyServerSettingsMigrated;
    }

    /** 规范化后的服务器地址。 */
    public String normalizedServerUrl() {
        String url = serverUrl == null ? "" : serverUrl.trim();
        return url.isEmpty() ? DshStudioConstants.DEFAULT_SERVER_URL : url;
    }

    public String normalizedServerCommand() {
        String cmd = serverCommand == null ? "" : serverCommand.trim();
        return cmd.isEmpty() ? DshStudioConstants.DEFAULT_SERVER_COMMAND : cmd;
    }
}
