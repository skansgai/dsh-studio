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

    /** 自定义启动命令模板；留空使用默认模板。支持 {host} {port} {workdir} {dshHome}。 */
    public String serverCommand = "";

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

    /** 是否在网页上方盖一层半透明覆盖层（含背景图），实验性。 */
    public boolean pageOverlayEnabled = false;

    /** 覆盖层不透明度（0–100）。 */
    public int pageOverlayOpacity = 40;

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
        this.workingDirectory = state.workingDirectory;
        this.dshHome = state.dshHome;
        this.autoStartServer = state.autoStartServer;
        this.useEmbeddedBrowser = state.useEmbeddedBrowser;
        this.healthPollMs = state.healthPollMs;
        this.uiTheme = state.uiTheme == null ? "follow" : state.uiTheme;
        this.backgroundImagePath = state.backgroundImagePath == null ? "" : state.backgroundImagePath;
        this.pageOverlayEnabled = state.pageOverlayEnabled;
        this.pageOverlayOpacity = state.pageOverlayOpacity;
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
