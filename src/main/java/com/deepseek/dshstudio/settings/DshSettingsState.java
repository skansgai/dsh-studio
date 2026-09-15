package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 插件设置（应用级），持久化到 deepseek-harness.xml。
 */
@State(name = "DeepSeekHarnessSettings", storages = {@Storage("deepseek-harness.xml")})
public final class DshSettingsState implements PersistentStateComponent<DshSettingsState> {

    /** 连接 / 打开的 DeepSeek Harness 地址。 */
    public String serverUrl = DshStudioConstants.DEFAULT_SERVER_URL;

    /** 自动启动服务器时使用的端口（与默认命令模板中的 {port} 对应）。 */
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

    /** 服务器工作目录；留空则使用当前项目目录。 */
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
     */
    public String serverAuthToken = "";

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
