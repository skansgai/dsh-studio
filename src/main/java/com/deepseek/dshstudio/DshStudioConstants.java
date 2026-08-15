package com.deepseek.dshstudio;

/**
 * 插件级常量：ID、工具窗口、默认配置等。
 */
public final class DshStudioConstants {

    public static final String PLUGIN_ID = "com.deepseek.dshstudio";
    public static final String PLUGIN_NAME = "DeepSeek Harness Studio";

    /** Tool window 的唯一 id（同时作为标题显示）。 */
    public static final String TOOL_WINDOW_ID = "DeepSeek Harness";

    /** 通知分组 id（见 plugin.xml）。 */
    public static final String NOTIFICATION_GROUP_ID = "DshStudio.Notifications";

    /** DeepSeek Harness web 服务器默认地址（dsh --profile web 默认监听 127.0.0.1:3080）。 */
    public static final String DEFAULT_SERVER_URL = "http://127.0.0.1:3080";
    public static final int DEFAULT_PORT = 3080;

    /** 自动启动服务器的默认命令模板；支持占位符 {host} {port} {workdir} {dshHome}。 */
    public static final String DEFAULT_SERVER_COMMAND =
            "npx --yes @deepseek-ai/dsh web --host {host} --port {port}";

    /** 健康检查超时（毫秒）。 */
    public static final int HEALTH_TIMEOUT_MS = 1500;

    /** 默认健康检查轮询间隔（毫秒）。 */
    public static final int HEALTH_POLL_MS = 2000;

    /** 启动服务器后等待其可用的最大时长（毫秒）。 */
    public static final int START_WATCHDOG_MS = 90_000;

    /** 日志缓冲区上限（字符），超出后保留尾部。 */
    public static final int LOG_CAP_CHARS = 200_000;

    private DshStudioConstants() {
    }
}
