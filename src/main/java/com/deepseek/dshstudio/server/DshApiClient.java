package com.deepseek.dshstudio.server;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * dsh web Remote API 的轻量 HTTP 客户端（项目级服务）。
 * <p>
 * 协议要点（逆向自 {@code @deepseek-ai/dsh@0.1.1-rc.2} 的
 * {@code dsh-client-connection} 与 {@code dsh-host-apiproxy} 产物，非文档推测）：
 * <ul>
 *   <li><b>路由</b>：{@code POST /api/<method>}，method 是<b>点号</b>全名，如
 *       {@code /api/session.list}。不是 {@code /api/session/list}。</li>
 *   <li><b>请求体</b>：四元 RPC 报文
 *       {@code {"type":"client-request","rpcId":"<uuid>","method":"session.list","payload":{...}}}。
 *       业务参数直接放 {@code payload}，没有 args / request 外层包装。</li>
 *   <li><b>响应体</b>：{@code {"type":"server-response","rpcId":"<回显>","result":{...}}}，
 *       result 为 {@code {"ok":true,"value":{...}}} 或
 *       {@code {"ok":false,"error":{"code","message","details"}}}。</li>
 *   <li><b>鉴权</b>：<b>没有 token，也没有 Cookie</b>。服务端用 Host 头做信任围栏
 *       （防 DNS rebinding），127.0.0.1 / localhost 默认放行。因此本插件从本机调用
 *       无需任何凭证；只有 {@code host.*} {@code settings.*} {@code credentials.*}
 *       {@code agentPreset.*} {@code llm.discoverModels} 被锁死在 loopback，
 *       session.* 不受此限。</li>
 * </ul>
 * 所有方法都在调用线程同步执行网络 IO，请勿在 EDT 直接调用——统一配合
 * {@code ApplicationManager#getApplication()#executeOnPooledThread} 或
 * {@link #listSessionsAsync()} 使用。
 */
public final class DshApiClient {

    private final Project project;
    private final Gson gson = new Gson();

    /** 当前"发送代码"目标会话 id；null 表示尚未创建。 */
    @Nullable
    private volatile String currentSessionId;

    private DshApiClient(@NotNull Project project) {
        this.project = project;
    }

    public static DshApiClient getInstance(@NotNull Project project) {
        return project.getService(DshApiClient.class);
    }

    // ── 对外能力 ──────────────────────────────────────────────────────────

    /** 列出可见会话（{@code session.list} → {@code {items:[...]}}）。 */
    public List<DshSessionSummary> listSessions() throws DshApiException {
        JsonObject payload = new JsonObject();
        JsonObject value = call("list", payload);
        return parseSessionList(value);
    }

    /**
     * 创建（或幂等采用）一个会话，返回 sessionId。
     *
     * @param cwd 会话工作目录（dsh 将其作为 workspace 根）
     */
    public String createSession(@NotNull String cwd) throws DshApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("cwd", cwd);
        JsonObject value = call("create", payload);
        String sessionId = stringOf(value, "sessionId");
        if (sessionId == null || sessionId.isEmpty()) {
            throw new DshApiException("session.create 未返回 sessionId");
        }
        return sessionId;
    }

    /** 给会话追加强制标题（用于在 Web UI 侧边栏中辨识 IDE 会话）。 */
    public void renameSession(@NotNull String sessionId, @NotNull String title) throws DshApiException {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("title", title);
        call("rename", payload);
    }

    /**
     * 向会话提交一条提示词（agent 将异步开始工作，本方法只确认已受理）。
     * <p>
     * wire 上 {@code mode} 是必填枚举（{@code queue} 排队 / {@code steer} 插队），
     * 这里用 {@code queue}：不打断 agent 正在进行的回合。
     *
     * @return 本次调用的 rpcId（可用于日志关联）
     */
    public String prompt(@NotNull String sessionId, @NotNull String text) throws DshApiException {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text);
        JsonArray content = new JsonArray();
        content.add(part);

        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", sessionId);
        payload.addProperty("mode", "queue");
        payload.add("content", content);
        return callRaw("prompt", payload).rpcId;
    }

    /** 当前"发送代码"目标会话 id（可能尚未创建）。 */
    @Nullable
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public void setCurrentSessionId(@Nullable String sessionId) {
        this.currentSessionId = sessionId;
    }

    /**
     * 获取（或创建）"发送代码"目标会话：优先复用本项目的会话；
     * 不存在时创建一个以项目目录为工作区、以项目名命名的会话。
     */
    public String getOrCreateIdeSession() throws DshApiException {
        String existing = currentSessionId;
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String cwd = DshUtil.resolveWorkingDirectory(DshSettingsState.getInstance(), project);
        String sessionId = createSession(cwd);
        currentSessionId = sessionId;
        // 命名非关键路径，失败不打断
        String name = project.getName() != null ? project.getName() : "IDE";
        try {
            renameSession(sessionId, "IDE · " + name);
        } catch (DshApiException ignored) {
        }
        return sessionId;
    }

    // ── 协议实现 ──────────────────────────────────────────────────────────

    /**
     * 执行一次 unary RPC，返回业务 value 对象。
     *
     * @param method  不带命名空间前缀的方法短名（如 {@code list}）
     * @param payload 业务参数（直接作为报文的 payload 字段）
     */
    private JsonObject call(@NotNull String method, @NotNull JsonObject payload) throws DshApiException {
        return callRaw(method, payload).value;
    }

    /**
     * 执行一次 unary RPC。
     *
     * @param method  不带命名空间前缀的方法短名（如 {@code list}）
     * @param payload 业务参数（直接作为报文的 payload 字段）
     * @return 本次的 rpcId 与解析后的业务 value
     */
    private CallResult callRaw(@NotNull String method, @NotNull JsonObject payload) throws DshApiException {
        String fullMethod = DshStudioConstants.API_NAMESPACE_SESSION + "." + method;
        String rpcId = "dsh-studio-" + UUID.randomUUID();
        String url = baseUrl() + "/api/" + fullMethod;
        try {
            Response response = post(url, envelope(fullMethod, rpcId, payload));
            if (response.code == 403) {
                throw new DshApiException("服务器拒绝了本次调用（403）：请求未通过 Host 信任围栏。"
                        + "请确认服务器地址是 127.0.0.1 / localhost，或在设置中改用回环地址。");
            }
            if (response.code == 404) {
                throw new DshApiException("接口不存在（404）：" + url
                        + "。可能是 dsh 版本较旧，请升级 DeepSeek Harness。");
            }
            if (response.code < 200 || response.code >= 300) {
                throw new DshApiException("HTTP " + response.code + ": " + excerpt(response.body));
            }
            return new CallResult(rpcId, unwrapValue(response.body));
        } catch (IOException e) {
            throw new DshApiException("网络错误：" + e.getMessage(), e);
        }
    }

    /** 构造 client-request 报文。 */
    private String envelope(@NotNull String method, @NotNull String rpcId, @NotNull JsonObject payload) {
        JsonObject body = new JsonObject();
        body.addProperty("type", "client-request");
        body.addProperty("rpcId", rpcId);
        body.addProperty("method", method);
        body.add("payload", payload);
        return gson.toJson(body);
    }

    /**
     * 解析 server-response 信封，取出 {@code result.value}。
     * {@code ok:false} 时抛出携带 code/message 的异常。
     */
    private JsonObject unwrapValue(String body) throws DshApiException {
        if (body == null || body.trim().isEmpty()) {
            return new JsonObject();
        }
        JsonElement element;
        try {
            element = JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new DshApiException("响应不是合法 JSON：" + excerpt(body), e);
        }
        if (!element.isJsonObject()) {
            throw new DshApiException("意外的响应形态：" + excerpt(body));
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement result = object.get("result");
        if (result == null || !result.isJsonObject()) {
            throw new DshApiException("响应缺少 result 字段：" + excerpt(body));
        }
        JsonObject resultObject = result.getAsJsonObject();
        Boolean ok = boolOrNull(resultObject, "ok");
        if (Boolean.FALSE.equals(ok)) {
            JsonElement error = resultObject.get("error");
            String message = error != null && error.isJsonObject()
                    ? stringOf(error.getAsJsonObject(), "message")
                    : null;
            String code = error != null && error.isJsonObject()
                    ? stringOf(error.getAsJsonObject(), "code")
                    : null;
            throw new DshApiException("服务器返回错误"
                    + (code == null ? "" : "（" + code + "）")
                    + "：" + (message == null ? excerpt(body) : message));
        }
        JsonElement value = resultObject.get("value");
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private String baseUrl() {
        String url = DshServerManager.getInstance(project).getUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private Response post(String url, String jsonBody) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);
            return new Response(code, body);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(DshStudioConstants.API_TIMEOUT_MS);
        connection.setReadTimeout(DshStudioConstants.API_TIMEOUT_MS);
        return connection;
    }

    private static String readAll(@Nullable InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    private static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() > 200 ? compact.substring(0, 200) + "…" : compact;
    }

    @Nullable
    private static String stringOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static List<DshSessionSummary> parseSessionList(JsonObject value) {
        List<DshSessionSummary> sessions = new ArrayList<>();
        JsonElement items = value.get("items");
        if (items == null || !items.isJsonArray()) {
            return sessions;
        }
        for (JsonElement item : items.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String sessionId = stringOf(object, "sessionId");
            if (sessionId == null || sessionId.isEmpty()) {
                continue;
            }
            sessions.add(new DshSessionSummary(
                    sessionId,
                    firstNonNull(stringOf(object, "title"), titleFromProjections(object)),
                    longOf(object, "updatedAt"),
                    boolOf(object, "running"),
                    boolOf(object, "blank")));
        }
        return sessions;
    }

    /** 标题位于 projections.values.*.title（不同版本形态不同，两种都兼容）。 */
    @Nullable
    private static String titleFromProjections(JsonObject object) {
        JsonElement projections = object.get("projections");
        if (projections == null || !projections.isJsonObject()) {
            return null;
        }
        JsonElement values = projections.getAsJsonObject().get("values");
        if (values == null || !values.isJsonObject()) {
            return null;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : values.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value == null) {
                continue;
            }
            String title;
            if (value.isJsonPrimitive() && !value.getAsString().isEmpty()) {
                title = value.getAsString();
            } else if (value.isJsonObject()) {
                title = stringOf(value.getAsJsonObject(), "title");
            } else {
                title = null;
            }
            if (title != null && !title.isEmpty()) {
                return title;
            }
        }
        return null;
    }

    private static long longOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
    }

    private static boolean boolOf(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    @Nullable
    private static Boolean boolOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsBoolean() : null;
    }

    @Nullable
    private static String firstNonNull(@Nullable String a, @Nullable String b) {
        return a != null ? a : b;
    }

    // ── 类型 ──────────────────────────────────────────────────────────────

    /** API 调用失败（网络 / 协议 / 服务器返回错误）。 */
    public static final class DshApiException extends Exception {
        public DshApiException(String message) {
            super(message);
        }

        public DshApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** 一次 unary 调用的完整结果：回显用 rpcId + 业务 value。 */
    private static final class CallResult {
        final String rpcId;
        final JsonObject value;

        CallResult(String rpcId, JsonObject value) {
            this.rpcId = rpcId;
            this.value = value;
        }
    }

    /** 在后台线程执行调用，供 UI 层使用。 */
    public CompletableFuture<List<DshSessionSummary>> listSessionsAsync() {
        CompletableFuture<List<DshSessionSummary>> future = new CompletableFuture<>();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                future.complete(listSessions());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }
}
