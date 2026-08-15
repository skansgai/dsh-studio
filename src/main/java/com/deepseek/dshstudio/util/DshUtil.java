package com.deepseek.dshstudio.util;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

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
     * 模板占位符：{host} {port} {workdir} {dshHome}。
     * Windows 下自动把首个 npx/npm 解析为 npx.cmd 的完整路径（通过 where 查找）。
     */
    public static List<String> resolveCommandLine(@NotNull DshSettingsState settings,
                                                  @Nullable Project project) {
        String template = settings.normalizedServerCommand();
        String host = hostOf(settings.normalizedServerUrl());
        String port = String.valueOf(settings.startPort);
        String workdir = resolveWorkingDirectory(settings, project);
        String dshHome = settings.dshHome == null ? "" : settings.dshHome.trim();

        template = template
                .replace("{host}", host)
                .replace("{port}", port)
                .replace("{workdir}", workdir)
                .replace("{dshHome}", dshHome);

        List<String> tokens = tokenize(template);
        if (tokens.isEmpty()) {
            throw new IllegalStateException("启动命令为空");
        }
        tokens.set(0, resolveLauncher(tokens.get(0)));
        return tokens;
    }

    /**
     * 解析命令行的第一个 token：Windows 下将 npx/npm 解析为带 .cmd 的完整路径，
     * 以便 ProcessBuilder 直接执行；无法解析时原样返回（Java 在 Windows 上会经由
     * cmd.exe 执行 PATH 中的 .cmd/.bat）。
     */
    private static String resolveLauncher(String first) {
        if (!isWindows()) {
            return first;
        }
        String lower = first.toLowerCase(Locale.ROOT);
        String base;
        if (lower.equals("npx") || lower.equals("npx.cmd")) {
            base = "npx";
        } else if (lower.equals("npm") || lower.equals("npm.cmd")) {
            base = "npm";
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
            try {
                Process p = new ProcessBuilder("sh", "-c", "command -v npx").redirectErrorStream(true).start();
                p.waitFor(3, TimeUnit.SECONDS);
                return p.exitValue() == 0;
            } catch (Exception e) {
                return false;
            }
        }
        return resolveOnPath("npx.cmd") != null || resolveOnPath("npx") != null;
    }

    /** 将任意目录字符串规范化为绝对路径（用于展示）。 */
    public static String absolutePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "";
        }
        return new File(path.trim()).getAbsolutePath();
    }
}
