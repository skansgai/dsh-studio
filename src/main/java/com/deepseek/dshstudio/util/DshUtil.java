package com.deepseek.dshstudio.util;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.runtime.DshRuntimeManager;
import com.deepseek.dshstudio.runtime.DshRuntimeMode;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 平台无关的工具方法：健康探测、命令行构建、进程清理、打开浏览器等。
 */
public final class DshUtil {

    private DshUtil() {
    }

    // ── 平台判断 ──────────────────────────────────────────────────────────

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 当前平台的标识，形如 {@code win32-x64} / {@code darwin-arm64} / {@code linux-x64}。
     * <p>
     * 与内置运行时打包时用的命名保持一致（npm 的 {@code os}/{@code cpu} 字段取值），
     * 用来判断「内置运行时是否覆盖当前平台」。
     */
    public static String hostTarget() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName;
        if (os.contains("win")) {
            osName = "win32";
        } else if (os.contains("mac") || os.contains("darwin")) {
            osName = "darwin";
        } else {
            osName = "linux";
        }
        String cpuName;
        switch (arch) {
            case "amd64":
            case "x86_64":
                cpuName = "x64";
                break;
            case "aarch64":
            case "arm64":
                cpuName = "arm64";
                break;
            case "x86":
            case "i386":
            case "i486":
            case "i586":
            case "i686":
                cpuName = "ia32";
                break;
            default:
                cpuName = arch;
        }
        return osName + "-" + cpuName;
    }

    // ── 健康探测 ──────────────────────────────────────────────────────────

    /**
     * 探测服务器是否可达：只要 HTTP 有响应（任何 <500 的状态码）即视为在线。
     */
    public static boolean isReachable(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            int code = connection.getResponseCode();
            return code < 500;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ── 命令构建 ──────────────────────────────────────────────────────────

    /**
     * 解析工作目录：优先用户配置，其次当前项目目录，最后用户主目录。
     */
    public static String resolveWorkingDirectory(@NotNull DshSettingsState settings,
                                                 @Nullable Project project) {
        String configured = settings.workingDirectory == null ? "" : settings.workingDirectory.trim();
        if (!configured.isEmpty()) {
            return configured;
        }
        if (project != null && project.getBasePath() != null) {
            return project.getBasePath();
        }
        return System.getProperty("user.home", ".");
    }

    /**
     * 构建服务器启动命令。
     * <p>
     * 模板占位符：{host} {port} {workdir} {dshHome} 为纯文本替换；
     * {dsh} 展开为「用哪一份 dsh 启动」的完整前缀（内置运行时 / 热更新版本 / 系统 npx），
     * 由 {@link DshRuntimeManager} 按设置里的运行时来源决定。
     */
    public static List<String> resolveCommandLine(@NotNull DshSettingsState settings,
                                                  @Nullable Project project) {
        return resolveTemplate(settings.normalizedServerCommand(), settings, project);
    }

    /**
     * 展开一个命令模板为参数列表。
     * <p>
     * {@code {dsh}} 展开后可能包含带空格的路径（{@code C:\Program Files\nodejs\node.exe}），
     * 所以是按 token 拼接而不是字符串替换 —— 字符串替换后再分词会把路径拆断。
     */
    public static List<String> resolveTemplate(@NotNull String template,
                                               @NotNull DshSettingsState settings,
                                               @Nullable Project project) {
        // 模板里没有 {dsh} 时完全不碰运行时解析：自定义命令不应被内置运行时的可用性牵连
        if (!template.contains(DshStudioConstants.DSH_PLACEHOLDER)) {
            return resolveTemplate(template, settings, project, List.of());
        }
        return resolveTemplate(template, settings, project, resolveDshPrefix(settings));
    }

    /**
     * 展开模板，{@code {dsh}} 用调用方给定的前缀。
     * <p>
     * 单独把前缀抽出来是为了可测：单元测试里没有 IDE 应用环境，拿不到运行时服务。
     */
    public static List<String> resolveTemplate(@NotNull String template,
                                               @NotNull DshSettingsState settings,
                                               @Nullable Project project,
                                               @NotNull List<String> dshPrefix) {
        String host = hostOf(settings.normalizedServerUrl());
        String port = String.valueOf(settings.startPort);
        String workdir = resolveWorkingDirectory(settings, project);
        String dshHome = settings.dshHome == null ? "" : settings.dshHome.trim();

        String expanded = template
                .replace("{host}", host)
                .replace("{port}", port)
                .replace("{workdir}", workdir)
                .replace("{dshHome}", dshHome);

        List<String> tokens = new ArrayList<>();
        int from = 0;
        while (true) {
            int idx = expanded.indexOf(DshStudioConstants.DSH_PLACEHOLDER, from);
            if (idx < 0) {
                tokens.addAll(tokenize(expanded.substring(from)));
                break;
            }
            tokens.addAll(tokenize(expanded.substring(from, idx)));
            tokens.addAll(dshPrefix);
            from = idx + DshStudioConstants.DSH_PLACEHOLDER.length();
        }

        if (tokens.isEmpty()) {
            throw new IllegalStateException("启动命令为空");
        }
        tokens.set(0, resolveLauncher(tokens.get(0)));
        return tokens;
    }

    /** 按设置里的运行时来源解析 {@code {dsh}} 的展开结果。 */
    @NotNull
    public static List<String> resolveDshPrefix(@NotNull DshSettingsState settings) {
        return DshRuntimeManager.getInstance()
                .resolve(DshRuntimeMode.fromId(settings.runtimeMode)).prefix;
    }

    /**
     * 解析命令行的第一个 token：Windows 下将 npx/npm/dsh 解析为带 .cmd 的完整路径，
     * 以便 ProcessBuilder 直接执行；无法解析时原样返回（Java 在 Windows 上会经由
     * cmd.exe 执行 PATH 中的 .cmd/.bat）。
     */
    public static String resolveLauncher(String first) {
        if (!isWindows()) {
            return first;
        }
        String lower = first.toLowerCase(Locale.ROOT);
        String base;
        if (lower.equals("npx") || lower.equals("npx.cmd")) {
            base = "npx";
        } else if (lower.equals("npm") || lower.equals("npm.cmd")) {
            base = "npm";
        } else if (lower.equals("dsh") || lower.equals("dsh.cmd")) {
            base = "dsh";
        } else {
            return first;
        }
        String resolved = resolveOnPath(base + ".cmd");
        if (resolved != null && !resolved.trim().isEmpty()) {
            return resolved.trim();
        }
        // 兜底：保持 .cmd 形式，交给 PATH 搜索
        return base + ".cmd";
    }

    /**
     * 通过 Windows 的 where 命令在 PATH 中查找可执行文件的完整路径。
     */
    @Nullable
    public static String resolveOnPath(String exe) {
        try {
            Process p = new ProcessBuilder("where", exe).redirectErrorStream(true).start();
            String first = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                first = reader.readLine();
            }
            p.waitFor(3, TimeUnit.SECONDS);
            return (first == null || first.trim().isEmpty()) ? null : first.trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从 URL 中提取 host（用于 --host 参数）。
     */
    public static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return (host == null || host.isEmpty()) ? "127.0.0.1" : host;
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    /**
     * 简单的命令行分词：支持双引号/单引号包裹的空格与引号。
     */
    public static List<String> tokenize(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    // ── 进程清理 ──────────────────────────────────────────────────────────

    /**
     * 终止进程（含其子进程树）：Windows 使用 taskkill /T /F，其他平台 destroy + destroyForcibly。
     */
    public static void destroyProcessTree(Process process) {
        if (process == null) {
            return;
        }
        if (isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException ignored) {
                process.destroyForcibly();
            }
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        } else {
            process.destroy();
            try {
                process.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 查出占用某个 TCP 端口的进程 PID。
     * <p>
     * Windows 走 {@code netstat -ano}，其他平台优先 {@code lsof -ti}，退回到 {@code ss -ltnp}。
     * 只在需要提示用户清理残留实例时调用，属于低频操作。
     *
     * @param port 端口号
     * @return 监听该端口的 PID 列表（已去重）；查不到或命令失败时返回空列表
     */
    @NotNull
    public static List<Long> findPortOwnerPids(int port) {
        List<Long> pids = new ArrayList<>();
        if (port <= 0) {
            return pids;
        }
        try {
            List<String> lines = new ArrayList<>();
            if (isWindows()) {
                lines = runAndRead("netstat", "-ano", "-p", "tcp");
                String needle = ":" + port + " ";
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.toUpperCase(Locale.ROOT).contains("LISTENING")) {
                        continue;
                    }
                    // 形如：TCP  127.0.0.1:3080  0.0.0.0:0  LISTENING  14052
                    if (trimmed.indexOf(needle) < 0) {
                        continue;
                    }
                    String[] cols = trimmed.split("\\s+");
                    if (cols.length < 5) {
                        continue;
                    }
                    addPid(pids, cols[cols.length - 1]);
                }
            } else {
                lines = runAndRead("lsof", "-ti", "tcp:" + port);
                for (String line : lines) {
                    addPid(pids, line.trim());
                }
            }
        } catch (Exception ignored) {
            // 命令不存在 / 无权限：查不到就算了，不影响主流程
        }
        return pids;
    }

    /**
     * 强制结束指定 PID 的进程（Windows 连带子进程树）。
     *
     * @return 是否成功发起了结束命令
     */
    public static boolean killPid(long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            List<String> cmd = new ArrayList<>();
            if (isWindows()) {
                cmd.add("taskkill");
                cmd.add("/PID");
                cmd.add(String.valueOf(pid));
                cmd.add("/T");
                cmd.add("/F");
            } else {
                cmd.add("kill");
                cmd.add("-9");
                cmd.add(String.valueOf(pid));
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.waitFor(5, TimeUnit.SECONDS);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void addPid(@NotNull List<Long> pids, @NotNull String raw) {
        try {
            long pid = Long.parseLong(raw.trim());
            if (pid > 0 && !pids.contains(pid)) {
                pids.add(pid);
            }
        } catch (NumberFormatException ignored) {
            // 忽略非数字列
        }
    }

    /** 执行一条外部命令并读取其标准输出（合并 stderr），失败时返回空列表。 */
    @NotNull
    private static List<String> runAndRead(@NotNull String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        p.waitFor(10, TimeUnit.SECONDS);
        return lines;
    }

    // ── 浏览器 ────────────────────────────────────────────────────────────

    /**
     * 在系统默认浏览器中打开地址。
     */
    public static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through
        }
        if (isWindows()) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } catch (IOException ignored) {
                // nothing else we can do
            }
        }
    }

    /** 检查本机是否安装了 Node.js/npx（用于启动服务器的提示信息）。 */
    public static boolean isNpxAvailable() {
        if (!isWindows()) {
            return commandExists("npx");
        }
        return resolveOnPath("npx.cmd") != null || resolveOnPath("npx") != null;
    }

    /** 检查本机是否安装了 Node.js（内置运行时与 npx 启动都依赖它）。 */
    public static boolean isNodeAvailable() {
        if (!isWindows()) {
            return commandExists("node");
        }
        return resolveOnPath("node.exe") != null || resolveOnPath("node") != null;
    }

    private static boolean commandExists(String name) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + name)
                    .redirectErrorStream(true).start();
            p.waitFor(3, TimeUnit.SECONDS);
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 解析 node 可执行文件：Windows 上取 PATH 里的完整路径（避免落到 .cmd 转发），
     * 其余平台交给 ProcessBuilder 按 PATH 查找。
     */
    public static String resolveNodeExecutable() {
        if (isWindows()) {
            String p = resolveOnPath("node.exe");
            if (p == null || p.trim().isEmpty()) {
                p = resolveOnPath("node");
            }
            return (p == null || p.trim().isEmpty()) ? "node.exe" : p.trim();
        }
        return "node";
    }

    /** {@code node --version} 的输出形如 {@code v22.19.0}，也可能带前缀噪音。 */
    private static final Pattern NODE_VERSION_PATTERN =
            Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

    /**
     * 探测本机 Node.js 的版本，返回归一化后的版本号（如 {@code "22.19.0"}）。
     * <p>
     * 未安装、执行失败或输出无法解析时返回 {@code null}。**刻意不缓存**：用户可能在 IDE
     * 运行期间才装好 Node，设置页的「重新检测」需要立刻看到变化。
     */
    @Nullable
    public static String detectNodeVersion() {
        try {
            for (String line : runAndRead(resolveNodeExecutable(), "--version")) {
                String parsed = parseNodeVersion(line);
                if (parsed != null) {
                    return parsed;
                }
            }
            return null;
        } catch (Exception e) {
            // 没装 node 时 ProcessBuilder.start() 直接抛 IOException；探测失败一律当作"没有"
            return null;
        }
    }

    /**
     * 从 {@code node --version} 的输出里解析版本号（{@code "v22.19.0"} → {@code "22.19.0"}）。
     * <p>抽成包级可见的纯函数便于单测。
     */
    @Nullable
    static String parseNodeVersion(@Nullable String output) {
        if (output == null) {
            return null;
        }
        Matcher matcher = NODE_VERSION_PATTERN.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    /** 本机 Node.js 是否满足 {@link DshStudioConstants#MIN_NODE_VERSION}。 */
    public static boolean isNodeVersionSupported() {
        String version = detectNodeVersion();
        return version != null
                && compareVersion(version, DshStudioConstants.MIN_NODE_VERSION) >= 0;
    }

    /** 将任意目录字符串规范化为绝对路径（用于展示）。 */
    public static String absolutePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return new File(path.trim()).getAbsolutePath();
    }

    // ── 编辑器集成辅助 ─────────────────────────────────────────────────────

    /**
     * 组装发送给 Harness 的提示词：指令 + 文件定位 + 代码块 + 可选补充说明。
     */
    public static String buildCodePrompt(@NotNull String instruction,
                                         @Nullable String filePath,
                                         int startLine,
                                         int endLine,
                                         @NotNull String code,
                                         @Nullable String language,
                                         @Nullable String extraNote) {
        StringBuilder prompt = new StringBuilder(instruction).append("\n\n");
        if (filePath != null && !filePath.trim().isEmpty()) {
            prompt.append("文件: ").append(filePath.trim());
            if (startLine > 0 && endLine >= startLine) {
                prompt.append("（第 ").append(startLine).append("-").append(endLine).append(" 行）");
            }
            prompt.append("\n");
        }
        prompt.append("```");
        if (language != null && !language.trim().isEmpty()) {
            prompt.append(language.trim());
        }
        prompt.append("\n").append(code).append("\n```\n");
        if (extraNote != null && !extraNote.trim().isEmpty()) {
            prompt.append(extraNote.trim()).append("\n");
        }
        return prompt.toString();
    }

    /** 从 dsh 启动输出行中提取 launch token；无则返回 null。 */
    @Nullable
    public static String extractLaunchToken(String line) {
        if (line == null || !line.contains("token=")) {
            return null;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(DshStudioConstants.TOKEN_REGEX).matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 相对时间描述（用于会话列表），如 "3 分钟前"。 */
    public static String relativeTime(long epochMs) {
        if (epochMs <= 0) {
            return "未知时间";
        }
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 0) {
            return "刚刚";
        }
        long seconds = diff / 1000;
        if (seconds < 60) {
            return "刚刚";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        return (hours / 24) + " 天前";
    }

    // ── 版本查询（关于页 / 检查更新）─────────────────────────────────────

    /**
     * 已安装插件版本（取自 plugin.xml，运行时读取）。
     * 读取失败（极少数环境下 PluginManagerCore 不可用）回退为 "未知"。
     */
    @NotNull
    public static String getInstalledPluginVersion() {
        try {
            IdeaPluginDescriptor descriptor =
                    PluginManagerCore.getPlugin(PluginId.getId(DshStudioConstants.PLUGIN_ID));
            if (descriptor != null && descriptor.getVersion() != null && !descriptor.getVersion().isEmpty()) {
                return descriptor.getVersion();
            }
        } catch (Exception ignored) {
            // 极少数环境下 PluginManagerCore 不可用，回退到 unknown
        }
        return "未知";
    }

    /**
     * npm 上 {@code @deepseek-ai/dsh} 的最新版本（npx --yes 默认拉取的正是它）。
     * 网络不可达或解析失败返回 null。
     */
    @Nullable
    public static String fetchLatestDshVersion(int timeoutMs) {
        String body = httpGetText("https://registry.npmjs.org/@deepseek-ai/dsh/latest", timeoutMs);
        if (body == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonObject()) {
                JsonElement version = element.getAsJsonObject().get("version");
                if (version != null && version.isJsonPrimitive()) {
                    return version.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 响应
        }
        return null;
    }

    /**
     * JetBrains Marketplace 上本插件的最新版本（取 updates 列表首个条目）。
     * 网络不可达或解析失败返回 null。
     */
    @Nullable
    public static String fetchLatestPluginVersion(int timeoutMs) {
        String body = httpGetText("https://plugins.jetbrains.com/api/plugins/33569/updates", timeoutMs);
        if (body == null) {
            return null;
        }
        try {
            JsonElement element = JsonParser.parseString(body);
            if (element.isJsonArray() && element.getAsJsonArray().size() > 0) {
                JsonElement first = element.getAsJsonArray().get(0);
                if (first.isJsonObject()) {
                    JsonElement version = first.getAsJsonObject().get("version");
                    if (version != null && version.isJsonPrimitive()) {
                        return version.getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 响应
        }
        return null;
    }

    /**
     * 语义化版本比较：a&lt;b 返回负数，a==b 返回 0，a&gt;b 返回正数。
     * 预发布 / 元数据后缀（如 -rc.1、+build）按数值部分比较，缺失段视为 0。
     */
    public static int compareVersion(@NotNull String a, @NotNull String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = parseVersionPart(pa, i);
            int vb = parseVersionPart(pb, i);
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int parseVersionPart(@NotNull String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        String numeric = parts[index].replaceAll("[^0-9].*$", "");
        if (numeric.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(numeric);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** HTTP GET，返回响应体文本；非 2xx 或任何异常返回 null。 */
    @Nullable
    private static String httpGetText(String url, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "DshStudio-UpdateCheck");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream stream = connection.getInputStream()) {
                return readAll(stream);
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
}
