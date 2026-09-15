package com.deepseek.dshstudio.server;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.runtime.DshNodeChecker;
import com.deepseek.dshstudio.runtime.DshRuntimeManager;
import com.deepseek.dshstudio.runtime.DshRuntimeMode;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
    /** 本次"复用外部实例"是否已经提示过（避免每次探测都弹一次）。 */
    private volatile boolean staleWarned;

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

    /**
     * 供浏览器（内嵌 / 系统）打开的地址：在服务器地址上拼上 launch token。
     * <p>
     * dsh 从 0.1.2-rc.1 起增加了 launch token 鉴权 —— 不带 <code>?token=</code> 直接访问首页会被拒绝，
     * 只返回 <code>dsh web authentication required; reopen the URL printed by dsh web.</code>。
     * 带上 token 访问一次后服务端会下发 Cookie，之后同域请求自动放行。
     * <p>
     * 只有本插件拉起的进程才捕获得到 token（从它的启动输出里）。复用外部实例时 token 为 null，
     * 此时原样返回地址，调用方需要提示用户去原终端复制带 token 的地址。
     *
     * @return 带 token 的地址；无可用 token、或地址里已经带了 token 时原样返回
     */
    @NotNull
    public String browsableUrl() {
        String url = getUrl();
        String token = launchToken;
        if (token == null || token.isEmpty() || url.contains("token=")) {
            return url;
        }
        return url + (url.indexOf('?') >= 0 ? '&' : '?') + "token=" + token;
    }

    // ── 启动 / 停止 ────────────────────────────────────────────────────────

    /**
     * 启动服务器（异步）。若地址已可达则直接进入 RUNNING（视为外部实例，不再重复启动）。
     */
    public void startServer() {
        DshSettingsState settings = DshSettingsState.getInstance();

        // 运行时准备刻意放在锁外：首次使用需要解包内置运行时（80 MB / 1.8 万文件，
        // 可能几十秒并弹出进度框），期间不该占着 lock 让其它线程干等。
        try {
            DshRuntimeManager.getInstance()
                    .prepare(project, DshRuntimeMode.fromId(settings.runtimeMode));
        } catch (Exception e) {
            String reason = String.valueOf(e.getMessage());
            appendLog("[dsh] 运行时准备失败: " + reason + "\n");
            notifyBalloon("无法启动 DeepSeek Harness 服务器",
                    StringUtil.escapeXmlEntities(reason), NotificationType.WARNING);
            setState(ServerState.FAILED);
            return;
        }

        // 启动命令解析与 Node 前置检查也放在锁外：引导对话框是模态的，
        // 占着 lock 会让状态栏、动作等其它线程干等。命令解析是纯函数，没有副作用。
        List<String> command;
        try {
            command = DshUtil.resolveCommandLine(settings, project);
        } catch (Exception e) {
            appendLog("[dsh] 无法解析启动命令: " + e.getMessage() + "\n");
            startAttempted = true;
            setState(ServerState.FAILED);
            return;
        }
        // 解析时若因 DLP 等原因从已下载运行时回退到系统 dsh，把原因告诉用户（不是失败，是降级）
        String resolveNote = DshRuntimeManager.getInstance().consumeLastResolveNote();
        if (resolveNote != null && !resolveNote.isEmpty()) {
            appendLog("[dsh] " + resolveNote + "\n");
            notifyBalloon("已改用系统 dsh 启动",
                    StringUtil.escapeXmlEntities(resolveNote), NotificationType.WARNING);
        }
        // 内置运行时与系统 npx 都是 Node 程序。缺 Node 或版本偏低时弹一次引导
        // （只警告不拦截，用户选「继续尝试」就往下走）。已经能连上外部实例时不需要 Node。
        if (!reachable && needsNode(command) && !DshNodeChecker.guideIfNeeded(project)) {
            appendLog("[dsh] 已取消启动：Node.js 未就绪。\n");
            startAttempted = true;
            setState(ServerState.FAILED);
            return;
        }

        synchronized (lock) {
            if (reachable) {
                startAttempted = false;
                setState(ServerState.RUNNING);
                // 端口上已有服务但进程不由本插件管理：很可能是上次会话 / 升级前残留的旧实例，
                // 直接复用会让用户拿到一个坏掉的实例，所以提示并给出一键清理。
                if (!isManagedProcessAlive()) {
                    warnStaleInstance();
                }
                return;
            }
            if (isManagedProcessAlive()) {
                setState(ServerState.STARTING);
                return;
            }
            String workdir = DshUtil.resolveWorkingDirectory(settings, project);
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(new File(workdir));
                pb.redirectErrorStream(true);
                pb.environment().put("DSH_HOME",
                        DshRuntimeManager.getInstance().resolveDshHome(settings));
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
                if (!DshUtil.isNodeAvailable()) {
                    appendLog("[dsh] 提示：未检测到 Node.js。请安装 Node.js 18+，或在设置中自定义启动命令。\n");
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
     * 当前连接地址里的端口号（用于定位占用端口的进程）；解析不出时返回 -1。
     */
    private int currentPort() {
        try {
            int port = URI.create(getUrl()).getPort();
            return port > 0 ? port : 80;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 端口上已有服务，但进程不是本插件拉起的 —— 提示可能复用了残留实例，并提供一键清理。
     * <p>
     * 典型场景：{@code npx --yes} 会把 dsh 静默升级到最新版，而在此之前启动的 dsh 进程仍在跑。
     * 旧进程继续按旧结构产出 boot manifest，却从磁盘上已升级的包里读取新的 client bundle，
     * 页面就会报 {@code client-modules: boot manifest batches must be an array} 之类的错误。
     * 只有结束那个旧进程、重新拉起才能恢复。
     */
    private void warnStaleInstance() {
        if (staleWarned) {
            return;
        }
        int port = currentPort();
        if (port <= 0) {
            return;
        }
        List<Long> pids = DshUtil.findPortOwnerPids(port);
        if (pids.isEmpty()) {
            return; // 拿不到 PID 就没有可执行的清理动作，别打扰用户
        }
        staleWarned = true;

        String who = "端口 " + port + "（PID " + pids + "）";
        appendLog("[dsh] 检测到 " + who + " 上已有一个 dsh 服务在运行，但它不是本插件启动的，本次将直接复用。\n"
                + "[dsh] 如果页面报 client-modules 相关错误，它多半是升级前的残留实例：结束它之后重新启动即可。\n");
        if (launchToken == null) {
            appendLog("[dsh] 注意：这个实例不是本插件启动的，拿不到它的 launch token。\n"
                    + "[dsh] dsh 0.1.2-rc.1 起首页需要 ?token= 鉴权，直接打开会提示 "
                    + "\"dsh web authentication required\"。\n"
                    + "[dsh] 解决办法二选一：① 结束它、让本插件重新启动；"
                    + "② 在启动它的终端里复制 dsh 打印的带 token 的地址，填到 设置 → 服务器地址。\n");
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            Notification notification = NotificationGroupManager.getInstance()
                    .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                    .createNotification("正在复用已有的 dsh 服务",
                            who + " 上已有一个 dsh 服务在运行，但<b>不是本插件启动的</b>。<br>"
                                    + "如果页面报错（例如 <code>client-modules</code> 相关错误），它很可能是 "
                                    + "<b>npx 升级前残留的旧实例</b>——结束它后重新启动即可恢复正常。"
                                    + (launchToken == null
                                    ? "<br>另外它不在本插件管理下，拿不到 launch token，"
                                    + "页面会提示 <code>dsh web authentication required</code>；"
                                    + "请在启动它的终端复制带 <code>?token=</code> 的地址，"
                                    + "或结束它让本插件重新启动。"
                                    : ""),
                            NotificationType.WARNING);
            notification.addAction(new NotificationAction("结束占用进程并重启") {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e, @NotNull Notification n) {
                    n.expire();
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        for (Long pid : pids) {
                            DshUtil.killPid(pid);
                        }
                        appendLog("[dsh] 已结束占用进程 " + pids + "\n");
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        staleWarned = false;
                        probe();
                        startServer();
                    });
                }
            });
            notification.notify(project);
        });
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
        if (!up) {
            staleWarned = false; // 服务没了，下次再复用外部实例时重新提示
        }
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

    /**
     * 判断启动命令是否需要本机安装 Node.js。
     * <p>实现已挪到 {@link DshNodeChecker#commandNeedsNode}（headless 动作也要用同一套判断）。
     */
    private static boolean needsNode(List<String> command) {
        return DshNodeChecker.commandNeedsNode(command);
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
