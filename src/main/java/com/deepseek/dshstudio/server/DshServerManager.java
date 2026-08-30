package com.deepseek.dshstudio.server;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 项目级服务：管理 DeepSeek Harness web 服务器进程的启动 / 停止 / 健康探测 / 日志。
 * <p>
 * 状态机：
 * <ul>
 *   <li>{@link ServerState#STOPPED} — 未启动（且外部无实例）</li>
 *   <li>{@link ServerState#STARTING} — 已拉起进程，等待端口就绪</li>
 *   <li>{@link ServerState#RUNNING} — 服务器可达（本插件启动的，或外部已运行的实例）</li>
 *   <li>{@link ServerState#FAILED} — 启动失败 / 启动超时 / 进程异常退出</li>
 * </ul>
 */
public final class DshServerManager {

    public enum ServerState {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED
    }

    private final Project project;

    private final Object lock = new Object();
    private final StringBuilder log = new StringBuilder();

    private volatile ServerState state = ServerState.STOPPED;
    private volatile boolean reachable;
    private volatile boolean startAttempted;
    @Nullable
    private volatile Process process;
    /** dsh 启动输出中捕获的浏览器鉴权 token（形如 ?token=xxx），供 DshApiClient 交换 Cookie。 */
    @Nullable
    private volatile String launchToken;

    private DshServerManager(@NotNull Project project) {
        this.project = project;
    }

    public static DshServerManager getInstance(@NotNull Project project) {
        return project.getService(DshServerManager.class);
    }

    // ── 状态查询 ──────────────────────────────────────────────────────────

    public ServerState getState() {
        return state;
    }

    /** 最近一次健康探测的结果。 */
    public boolean isReachable() {
        return reachable;
    }

    /** 是否有本插件拉起的、仍存活的进程。 */
    public boolean isManagedProcessAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    /** 当前连接地址。 */
    public String getUrl() {
        return DshSettingsState.getInstance().normalizedServerUrl();
    }

    /** 本插件启动的进程打印的 launch token（未捕获到时为 null）。 */
    @Nullable
    public String getLaunchToken() {
        return launchToken;
    }

    // ── 启动 / 停止 ────────────────────────────────────────────────────────

    /**
     * 启动服务器（异步）。若地址已可达则直接进入 RUNNING（视为外部实例，不再重复启动）。
     */
    public void startServer() {
        DshSettingsState settings = DshSettingsState.getInstance();
        synchronized (lock) {
            if (reachable) {
                startAttempted = false;
                setState(ServerState.RUNNING);
                return;
            }
            if (isManagedProcessAlive()) {
                setState(ServerState.STARTING);
                return;
            }
            String workdir = DshUtil.resolveWorkingDirectory(settings, project);
            List<String> command;
            try {
                command = DshUtil.resolveCommandLine(settings, project);
            } catch (Exception e) {
                appendLog("[dsh] 无法解析启动命令: " + e.getMessage() + "\n");
                startAttempted = true;
                setState(ServerState.FAILED);
                return;
            }
            // 前置检查：默认使用 npx 启动，但本机没有 Node.js → 直接友好提示，不再盲目拉起进程
            if (usesNpxLauncher(command) && !DshUtil.isNpxAvailable()) {
                appendLog("[dsh] 未检测到 Node.js / npx。请先安装 Node.js 18+（https://nodejs.org），"
                        + "或在 设置 → DeepSeek Harness 中自定义启动命令。\n");
                notifyBalloon("无法启动 DeepSeek Harness 服务器",
                        "未检测到 Node.js / npx。<br>" +
                                "请先安装 Node.js 18+（<a href=\"https://nodejs.org\">https://nodejs.org</a>），" +
                                "然后点击 ▶ 重试；<br>或在 设置 → DeepSeek Harness 中自定义启动命令。",
                        NotificationType.WARNING);
                startAttempted = true;
                setState(ServerState.FAILED);
                return;
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(new File(workdir));
                pb.redirectErrorStream(true);
                if (settings.dshHome != null && !settings.dshHome.trim().isEmpty()) {
                    pb.environment().put("DSH_HOME", settings.dshHome.trim());
                }
                appendLog("$ " + String.join(" ", command) + "   (cwd: " + workdir + ")\n");
                launchToken = null;
                Process p = pb.start();
                process = p;
                startAttempted = true;
                setState(ServerState.STARTING);

                // 输出流 → 日志（顺带捕获 launch token）
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            appendLog(line + "\n");
                            captureLaunchToken(line);
                        }
                    } catch (IOException ignored) {
                        // 进程结束
                    }
                });

                // 进程退出 → 更新状态
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    try {
                        int code = p.waitFor();
                        appendLog("\n[dsh] 进程已退出，退出码 " + code + "\n");
                        if (process == p) {
                            probe();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });

                // 看门狗：启动期间主动探测就绪（否则状态机会一直停在 STARTING，
                // 导致"发送代码"等需要等待就绪的功能永远等不到 RUNNING）
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    long deadline = System.currentTimeMillis() + DshStudioConstants.START_WATCHDOG_MS;
                    while (System.currentTimeMillis() < deadline
                            && state == ServerState.STARTING
                            && isManagedProcessAlive()) {
                        probe(); // 探测到可达会把 state 置为 RUNNING
                        if (state == ServerState.RUNNING) {
                            return;
                        }
                        try {
                            Thread.sleep(DshStudioConstants.HEALTH_POLL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (state == ServerState.STARTING && !reachable) {
                        appendLog("[dsh] 等待服务器就绪超时（" + (DshStudioConstants.START_WATCHDOG_MS / 1000) + "s），请查看上方日志。\n");
                        setState(ServerState.FAILED);
                    }
                });
            } catch (IOException e) {
                appendLog("[dsh] 启动失败: " + e.getMessage() + "\n");
                if (!DshUtil.isNpxAvailable()) {
                    appendLog("[dsh] 提示：未检测到 Node.js / npx。请安装 Node.js 18+，或在设置中自定义启动命令。\n");
                }
                notifyBalloon("启动 DeepSeek Harness 服务器失败",
                        "无法执行启动命令，详见工具窗口的 Server Log 面板。<br>" +
                                "常见原因：Node.js 未安装、端口被占用、或自定义命令有误。",
                        NotificationType.ERROR);
                startAttempted = true;
                setState(ServerState.FAILED);
            }
        }
    }

    /**
     * 停止由本插件启动的服务器进程（含子进程树）。
     */
    public void stopServer() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            if (reachable) {
                notifyBalloon("无法停止外部服务器",
                        "当前地址 " + getUrl() + " 上的服务器并非由本插件启动，请在启动它的终端中停止，或直接关闭对应进程。",
                        NotificationType.INFORMATION);
            } else {
                startAttempted = false;
                setState(ServerState.STOPPED);
            }
            return;
        }
        appendLog("[dsh] 正在停止服务器...\n");
        DshUtil.destroyProcessTree(p);
        process = null;
        startAttempted = false;
        setState(ServerState.STOPPED);
    }

    /**
     * 重启由本插件管理的 dsh web 服务器（先停后起）。
     * <p>
     * 用于安装 / 卸载 dsh 插件后让插件变更生效。{@link #stopServer()} 只停止本插件拉起的进程；
     * 若当前是<b>外部</b>实例（非本插件启动）或服务器根本未运行，则无法在此重启——
     * 外部实例会提示用户手动重启，未运行实例则无需重启（变更在下次启动时生效）。
     * <b>必须在后台线程调用</b>（内部含短暂等待，调用方请用
     * {@code ApplicationManager#getApplication()#executeOnPooledThread}）。
     */
    public void restartServer() {
        boolean managed = isManagedProcessAlive();
        if (!managed) {
            if (state == ServerState.RUNNING) {
                notifyBalloon("无法自动重启外部服务器",
                        "当前 DeepSeek Harness 服务器并非由本插件启动，无法自动重启以加载插件变更。"
                                + "请在启动它的终端中重启 dsh web。",
                        NotificationType.INFORMATION);
            }
            return;
        }
        stopServer();
        try {
            Thread.sleep(800);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        probe();
        startServer();
        notifyBalloon("DeepSeek Harness 正在重启",
                "已应用插件变更，服务器重启中（就绪后内嵌页面会自动刷新）。",
                NotificationType.INFORMATION);
    }

    /**
     * 立即执行一次健康探测并更新状态（阻塞，请勿在 EDT 直接调用；见 {@link #probeAsync()}）。
     */
    public void probe() {
        boolean up = DshUtil.isReachable(getUrl(), DshStudioConstants.HEALTH_TIMEOUT_MS);
        reachable = up;
        ServerState next;
        if (up) {
            next = ServerState.RUNNING;
            startAttempted = false;
        } else if (isManagedProcessAlive()) {
            next = ServerState.STARTING;
        } else if (startAttempted) {
            next = ServerState.FAILED;
        } else {
            next = ServerState.STOPPED;
        }
        setState(next);
    }

    /** 在后台线程执行一次健康探测，避免阻塞 EDT。 */
    public void probeAsync() {
        ApplicationManager.getApplication().executeOnPooledThread(this::probe);
    }

    // ── 日志 ──────────────────────────────────────────────────────────────

    public String getLogText() {
        synchronized (lock) {
            return log.toString();
        }
    }

    /** 供外部（如 headless 任务运行器）向日志面板追加内容并触发 UI 刷新。 */
    public void appendExternalLog(String chunk) {
        appendLog(chunk);
    }

    public void clearLog() {
        synchronized (lock) {
            log.setLength(0);
        }
        fireLog(""); // 触发 UI 刷新
    }

    private void appendLog(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        synchronized (lock) {
            log.append(chunk);
            if (log.length() > DshStudioConstants.LOG_CAP_CHARS) {
                log.delete(0, log.length() - DshStudioConstants.LOG_CAP_CHARS);
            }
        }
        fireLog(chunk);
    }

    /** 判断启动命令是否基于 npx（用于前置检测 Node.js 是否可用）。 */
    private static boolean usesNpxLauncher(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String name = new File(command.get(0)).getName().toLowerCase(Locale.ROOT);
        return name.equals("npx") || name.equals("npx.cmd");
    }

    /** 从一行启动输出中提取 launch token（首次捕获即记下）。 */
    private void captureLaunchToken(String line) {
        if (line == null || launchToken != null || !line.contains("token=")) {
            return;
        }
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile(DshStudioConstants.TOKEN_REGEX).matcher(line);
        if (matcher.find()) {
            launchToken = matcher.group(1);
        }
    }

    // ── 通知 ──────────────────────────────────────────────────────────────

    private void setState(ServerState next) {
        if (next == state) {
            return;
        }
        ServerState previous = state;
        state = next;
        // 启动后首次就绪：给一个不打扰的气球通知（工具窗口未打开时尤其有用）
        if (next == ServerState.RUNNING && previous == ServerState.STARTING && isManagedProcessAlive()) {
            notifyBalloon("DeepSeek Harness 服务器已就绪",
                    "地址：" + getUrl() + "<br>点击右侧工具窗口图标或状态栏图标开始使用。",
                    NotificationType.INFORMATION);
        }
        fireState(next);
    }

    private void fireState(ServerState next) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(DshServerTopics.SERVER_TOPIC).onStateChanged(next);
            }
        });
    }

    private void fireLog(String chunk) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                project.getMessageBus().syncPublisher(DshServerTopics.SERVER_TOPIC).onLog(chunk);
            }
        });
    }

    private void notifyBalloon(String title, String content, NotificationType type) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                NotificationGroupManager.getInstance()
                        .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                        .createNotification(title, content, type)
                        .notify(project);
            }
        });
    }
}
