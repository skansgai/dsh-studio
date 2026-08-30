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

    /**
     * 自动启动服务器的默认命令模板；支持占位符 {host} {port} {workdir} {dshHome}。
     * <p>
     * {@code --no-open} 是必要的：dsh web 启动后默认会调起系统默认浏览器打开页面，
     * 而本插件把页面嵌在 JCEF 里，再弹一个系统浏览器窗口纯属打扰。
     */
    public static final String DEFAULT_SERVER_COMMAND =
            "npx --yes @deepseek-ai/dsh web --host {host} --port {port} --no-open";

    /** 健康检查超时（毫秒）。 */
    public static final int HEALTH_TIMEOUT_MS = 1500;

    /** 默认健康检查轮询间隔（毫秒）。 */
    public static final int HEALTH_POLL_MS = 2000;

    /** 启动服务器后等待其可用的最大时长（毫秒）。 */
    public static final int START_WATCHDOG_MS = 90_000;

    /** 日志缓冲区上限（字符），超出后保留尾部。 */
    public static final int LOG_CAP_CHARS = 200_000;

    // ── Remote API（dsh web 的 /api HTTP 桥）──
    // 实测（@deepseek-ai/dsh 0.1.1-rc.2）：POST /api/session.<method>，
    // body = {"type":"client-request","rpcId":"...","method":"session.<method>","payload":{...}}，
    // 响应 = {"type":"server-response","rpcId":"...","result":{"ok":true,"value":{...}}}。

    /** Remote API 命名空间（session-controller 注册为 namespace "session"）。 */
    public static final String API_NAMESPACE_SESSION = "session";

    /** API 调用超时（毫秒）。 */
    public static final int API_TIMEOUT_MS = 8000;

    /** 等待服务器就绪的最长时间（毫秒），用于"发送代码"前确保服务器可用。 */
    public static final int API_WAIT_SERVER_MS = 90_000;

    /** headless 一次性任务的默认命令模板；支持 {task} {workdir} {dshHome}。 */
    public static final String DEFAULT_HEADLESS_COMMAND =
            "npx --yes @deepseek-ai/dsh --profile headless {task}";

    /** 从服务器启动输出中提取 launch token 的正则（dsh-web-app 会打印带 ?token=... 的根 URL）。 */
    public static final String TOKEN_REGEX = "[?&]token=([A-Za-z0-9._~+\\-]+)";

    private DshStudioConstants() {
    }
}
