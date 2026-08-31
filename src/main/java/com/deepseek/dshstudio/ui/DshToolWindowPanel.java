package com.deepseek.dshstudio.ui;

import com.deepseek.dshstudio.actions.OpenInBrowserAction;
import com.deepseek.dshstudio.actions.RefreshAction;
import com.deepseek.dshstudio.actions.StartServerAction;
import com.deepseek.dshstudio.actions.StopServerAction;
import com.deepseek.dshstudio.settings.DshSettingsState;
import com.deepseek.dshstudio.settings.DshUiTheme;
import com.deepseek.dshstudio.server.DshServerListener;
import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.server.DshServerManager.ServerState;
import com.deepseek.dshstudio.server.DshServerTopics;
import com.deepseek.dshstudio.settings.DshSettingsTopics;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

/**
 * 工具窗口主面板：
 * <ul>
 *   <li>顶部状态栏：连接状态 + 动作工具栏（启动 / 停止 / 刷新 / 系统浏览器打开）</li>
 *   <li>中间标签页：内嵌浏览器（JCEF） / 服务器日志</li>
 * </ul>
 */
public final class DshToolWindowPanel extends JBPanel<DshToolWindowPanel> implements Disposable {

    private static final JBColor COLOR_OK = new JBColor(0x1E8E3E, 0x81C995);
    private static final JBColor COLOR_WARN = new JBColor(0xB26B00, 0xE8B26A);
    private static final JBColor COLOR_ERROR = new JBColor(0xC5221F, 0xF28B82);
    private static final JBColor COLOR_IDLE = new JBColor(new Color(0x808080), new Color(0xA0A0A0));

    /** 注入到 dsh 网页的「通用设置 + 背景浮层」脚本（来自 classpath 资源 /dsh/overlay.js）。 */
    private static final String OVERLAY_SCRIPT;
    static {
        String loaded = "";
        try (InputStream in = DshToolWindowPanel.class.getResourceAsStream("/dsh/overlay.js")) {
            if (in != null) {
                loaded = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 资源缺失：浮层不可用，不影响主功能
        }
        OVERLAY_SCRIPT = loaded;
    }

    private final Project project;
    private final DshServerManager manager;

    private final JBLabel statusLabel = new JBLabel();
    private final JTextArea logArea = new JTextArea();
    private final JPanel browserHolder = new JPanel(new CardLayout());
    private final ImageBackdropPanel emptyStatePanel = new ImageBackdropPanel(0.35f);
    private final ImageBackdropPanel statusBarPanel = new ImageBackdropPanel(0.45f);
    private final JBTabbedPane tabs = new JBTabbedPane();

    private final Timer healthTimer;
    private final MessageBusConnection connection;

    /** JCEF 浏览器实例（反射创建，类型为 Object，避免字节码直接引用 JCEF 类）。 */
    @Nullable
    private final Object browser;
    /** 浏览器实例对应的 Swing 组件；当 JCEF 不可用时为 null，此时使用 buildFallbackPanel()。 */
    @Nullable
    private final JComponent browserComponent;
    private final boolean embeddedEnabled;

    private volatile String loadedUrl;
    private volatile boolean disposed;

    public DshToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.manager = DshServerManager.getInstance(project);

        DshSettingsState settings = DshSettingsState.getInstance();
        this.embeddedEnabled = settings.useEmbeddedBrowser && DshJcefSupport.isSupported();
        if (embeddedEnabled) {
            this.browser = DshJcefSupport.createBrowser();
            this.browserComponent = this.browser != null ? DshJcefSupport.getComponent(this.browser) : null;
            if (this.browser != null) {
                DshJcefSupport.installConsoleSync(this.browser, this::onPageSync);
            }
        } else {
            this.browser = null;
            this.browserComponent = null;
        }

        buildUi();
        logArea.setText(manager.getLogText());
        applyThemeToChrome();
        updateViewCard();

        this.connection = project.getMessageBus().connect(this);
        connection.subscribe(DshServerTopics.SERVER_TOPIC, new DshServerListener() {
            @Override
            public void onStateChanged(ServerState state) {
                updateStatus();
                updateViewCard();
                if (state == ServerState.RUNNING) {
                    loadUrlOnce();
                }
            }

            @Override
            public void onLog(String chunk) {
                if (!chunk.isEmpty()) {
                    logArea.append(chunk);
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }
        });

        // 设置变化（设置页广播）：重新应用自身界面 + 背景图 + 页面桥接 + 背景浮层
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(DshSettingsTopics.SETTINGS_TOPIC, () -> {
                    applyThemeToChrome();
                    applyBackgroundImages();
                    injectPageTheme();
                    injectBackgroundOverlay();
                });

        this.healthTimer = new Timer(Math.max(500, settings.healthPollMs), e -> tick());
        this.healthTimer.start();

        manager.probeAsync();
        if (settings.autoStartServer) {
            manager.startServer();
        }
        updateStatus();
    }

    // ── UI 构建 ──────────────────────────────────────────────────────────

    private void buildUi() {
        add(buildStatusBar(), BorderLayout.NORTH);
        add(buildTabs(), BorderLayout.CENTER);
    }

    private JComponent buildStatusBar() {
        statusBarPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        statusLabel.setVerticalTextPosition(SwingConstants.CENTER);
        statusBarPanel.add(statusLabel, BorderLayout.WEST);

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new StartServerAction(project));
        group.add(new StopServerAction(project));
        group.add(Separator.getInstance());
        group.add(new com.deepseek.dshstudio.actions.DshSessionsPopupAction());
        group.add(new OpenInBrowserAction(project));
        group.add(new RefreshAction(project, this::reloadPage));

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("DshStudio.Toolbar", group, true);
        toolbar.setTargetComponent(this);
        statusBarPanel.add(toolbar.getComponent(), BorderLayout.EAST);
        return statusBarPanel;
    }

    private JComponent buildTabs() {
        tabs.addTab("Harness", buildBrowserTab());
        JScrollPane logScroll = new JScrollPane(logArea);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        tabs.addTab("Server Log", logScroll);
        return tabs;
    }

    private Component buildBrowserTab() {
        applyBackgroundImages();
        // 卡片一：空白/未连接状态（显示背景图 + 提示）
        emptyStatePanel.setLayout(new BorderLayout());
        JBLabel empty = new JBLabel("等待连接到 DeepSeek Harness 服务器…", SwingConstants.CENTER);
        empty.setOpaque(false);
        empty.setForeground(JBColor.WHITE);
        empty.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 0));
        emptyStatePanel.add(empty, BorderLayout.SOUTH);
        browserHolder.add(emptyStatePanel, "empty");
        if (embeddedEnabled && browserComponent != null) {
            browserHolder.add(browserComponent, "browser");
        } else {
            browserHolder.add(buildFallbackPanel(), "browser");
        }
        return browserHolder;
    }

    /** 把设置里的背景图应用到状态栏条与空白页（支持本地路径与 data: URI）。 */
    private void applyBackgroundImages() {
        String path = DshSettingsState.getInstance().backgroundImagePath;
        String file = toLocalImageFile(path);
        statusBarPanel.setBackgroundImage(file);
        emptyStatePanel.setBackgroundImage(file);
    }

    /**
     * 把 data:image URI 解码成临时 png 文件（ImageBackdropPanel 只吃本地路径）；
     * 本地路径或非法值时原样返回 / 返回空。临时文件落在系统临时目录，覆盖写，无需清理。
     */
    private static String toLocalImageFile(@Nullable String path) {
        if (path == null || !path.startsWith("data:image")) {
            return path;
        }
        try {
            int comma = path.indexOf(',');
            if (comma < 0) return "";
            byte[] bytes = Base64.getDecoder().decode(path.substring(comma + 1));
            Path tmp = Paths.get(System.getProperty("java.io.tmpdir"), "dshstudio-bg.png");
            Files.write(tmp, bytes);
            return tmp.toString();
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    /** 已连接且已加载页面时切到浏览器卡片，否则显示背景图空白卡片；内嵌不可用时显示说明。 */
    private void updateViewCard() {
        CardLayout cl = (CardLayout) browserHolder.getLayout();
        if (!embeddedEnabled || browser == null) {
            cl.show(browserHolder, "browser"); // 该卡片在 !embeddedEnabled 时是说明面板
        } else if (manager.getState() == ServerState.RUNNING) {
            cl.show(browserHolder, "browser");
        } else {
            cl.show(browserHolder, "empty");
        }
    }

    private JComponent buildFallbackPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        JBLabel hint = new JBLabel(
                "<html><center>内嵌浏览器不可用或已禁用（JCEF）。<br>" +
                        "请在 Android Studio 的 Help &gt; Edit Custom VM Options 中添加：<br>" +
                        "<code>-Dide.browser.jcef.enabled=true</code>，然后重启 IDE；<br>" +
                        "或使用上方工具栏的按钮在系统浏览器中打开。</center></html>");
        hint.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(hint, BorderLayout.CENTER);

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton openButton = new JButton("在系统浏览器中打开");
        openButton.addActionListener(e -> DshUtil.openInBrowser(manager.getUrl()));
        buttonRow.add(openButton);
        panel.add(buttonRow, BorderLayout.SOUTH);
        return panel;
    }

    // ── 运行逻辑 ──────────────────────────────────────────────────────────

    private void tick() {
        if (disposed || project.isDisposed()) {
            healthTimer.stop();
            return;
        }
        manager.probeAsync();
        updateStatus();
        updateViewCard();
        if (manager.getState() == ServerState.RUNNING) {
            loadUrlOnce();
        }
    }

    private void loadUrlOnce() {
        if (!embeddedEnabled || browser == null) {
            return;
        }
        String url = browserUrl();
        if (!url.equals(loadedUrl)) {
            loadedUrl = url;
            DshJcefSupport.loadURL(browser, url);
            injectPageTheme();
            injectBackgroundOverlay();
        }
    }

    /**
     * 在工具窗口的内嵌浏览器中打开一个外部 URL。
     * 内嵌不可用时退回系统浏览器。
     */
    public void loadUrlInBrowser(@NotNull String url) {
        if (!embeddedEnabled || browser == null) {
            DshUtil.openInBrowser(url);
            return;
        }
        loadedUrl = url;
        DshJcefSupport.loadURL(browser, url);
    }

    /**
     * 内嵌浏览器要加载的地址。
     * <p>
     * dsh 0.1.1-rc.2 的访问控制是 Host 头信任围栏（防 DNS rebinding），
     * 既没有 token 也没有 Cookie：从 127.0.0.1 / localhost 发起的请求直接放行，
     * 所以这里不需要拼任何凭证。
     */
    public String browserUrl() {
        return manager.getUrl();
    }

    /**
     * 页面深浅色桥接（最小、安全版）：
     * 只设置 documentElement 的 CSS color-scheme —— 这是标准 CSS 属性，只影响原生控件/滚动条，
     * 不会改动页面布局。不再覆盖 window.matchMedia（那会导致 DSH 侧边栏/设置等布局失效）。
     * DSH 页面自身的完整主题请用其界面内 Settings → General 的 Light/Dark/System 切换。
     */
    private void injectPageTheme() {
        if (browser == null) {
            return;
        }
        Boolean dark = DshUiTheme.fromId(DshSettingsState.getInstance().uiTheme).isDarkOverride();
        if (dark == null) {
            return; // 跟随模式：交给 DSH 自动（System）
        }
        String scheme = dark ? "dark" : "light";
        String script =
                "if(document&&document.documentElement){"
                + "document.documentElement.style.colorScheme='" + scheme + "';"
                + "}";
        DshJcefSupport.executeJavaScript(browser, script);
    }

    /**
     * 把背景图作为半透明浮层注入到 dsh 网页之上（fixed + pointer-events:none）；
     * 同时把「背景图 / 浮层透明度」控制项直接注入到 dsh 网页自带的「通用设置」面板内容区，
     * 跟随 dsh 设置弹窗出现，不再使用右下角悬浮按钮。
     * JCEF 原生窗口上 Swing 盖不住，故用往页面 DOM 注入的方式实现；浮层不拦截鼠标事件。
     *
     * <p>刷新不丢的关键：dsh 网页里选的图/透明度会通过 console 回传并持久化到插件设置
     * （{@link #onPageSync}），这里把插件端的权威值以 {@code window.__dshRestore} 注入，
     * 页面每次（含刷新后）加载都用它恢复，不再依赖 JCEF 的 localStorage 跨 reload 持久化。</p>
     */
    private void injectBackgroundOverlay() {
        if (browser == null || OVERLAY_SCRIPT.isEmpty()) {
            return;
        }
        DshSettingsState s = DshSettingsState.getInstance();
        // 插件端权威值：本地路径转 data URI 注入；已是 data:image 则原样用。
        String bg = s.backgroundImagePath;
        if (bg != null && !bg.isEmpty() && !bg.startsWith("data:image")) {
            File f = new File(bg.trim());
            if (f.isFile() && f.length() < 3L * 1024 * 1024) {
                try {
                    byte[] bytes = Files.readAllBytes(f.toPath());
                    bg = "data:" + mimeOf(bg) + ";base64," + Base64.getEncoder().encodeToString(bytes);
                } catch (IOException ignored) {
                    bg = "";
                }
            } else {
                bg = "";
            }
        }
        int opacity = (int) Math.round(Math.max(0.0, Math.min(1.0, s.backgroundImageOpacity)) * 100);
        String restoreLine = "window.__dshRestore={bg:" + (bg.isEmpty() ? "null" : ("\"" + bg + "\""))
                + ",opacity:" + opacity + "};";
        String script = OVERLAY_SCRIPT.replace("/*SEED*/", restoreLine);
        DshJcefSupport.executeJavaScript(browser, script);
    }

    /**
     * 接收 dsh 网页回传的背景图 / 透明度，持久化到插件设置（刷新后由 {@link #injectBackgroundOverlay} 重新注入）。
     * 回传格式：{@code {"bg":"data:image/...;base64,..","op":15}}。
     */
    private void onPageSync(@NotNull String json) {
        String bg = "";
        double op = -1;
        int bi = json.indexOf("\"bg\":");
        if (bi >= 0) {
            int q1 = json.indexOf('"', bi + 5);
            int q2 = json.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1) bg = json.substring(q1 + 1, q2);
        }
        int oi = json.indexOf("\"op\":");
        if (oi >= 0) {
            String num = json.substring(oi + 5).replaceAll("[,}\\s].*", "");
            try {
                op = Double.parseDouble(num);
            } catch (NumberFormatException ignore) {
                op = -1;
            }
        }
        if (bg.isEmpty() && op < 0) {
            return;
        }
        final String fBg = bg;
        final double fOp = op;
        ApplicationManager.getApplication().invokeLater(() -> {
            DshSettingsState s = DshSettingsState.getInstance();
            if (!fBg.isEmpty()) s.backgroundImagePath = fBg;
            if (fOp >= 0) s.backgroundImageOpacity = Math.max(0.0, Math.min(1.0, fOp / 100.0));
            applyBackgroundImages(); // 同步 IDE 状态栏 / 空白页背景
        });
    }

    private static String mimeOf(@NotNull String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".bmp")) return "image/bmp";
        return "image/png";
    }

    /** 将选中的主题应用到工具窗口自身界面（状态栏文字、日志区）。 */
    private void applyThemeToChrome() {
        Boolean dark = DshUiTheme.fromId(DshSettingsState.getInstance().uiTheme).isDarkOverride();
        Color bg;
        Color fg;
        if (Boolean.TRUE.equals(dark)) {
            bg = new Color(0x1E1E1E);
            fg = new Color(0xC0C0C0);
        } else if (Boolean.FALSE.equals(dark)) {
            bg = new Color(0xF5F5F5);
            fg = new Color(0x333333);
        } else {
            bg = null; // 跟随 IDE
            fg = null;
        }
        if (bg != null && fg != null) {
            logArea.setBackground(bg);
            logArea.setForeground(fg);
            logArea.setCaretColor(fg);
            statusBarPanel.setBackground(bg);
            statusBarPanel.setForeground(fg);
        } else {
            // 跟随 IDE：交还给系统外观，不强制覆盖
            statusBarPanel.setBackground(null);
            statusBarPanel.setForeground(null);
        }
    }

    private void reloadPage() {
        if (embeddedEnabled && browser != null) {
            DshJcefSupport.reload(browser);
            injectPageTheme();
            injectBackgroundOverlay();
        }
        loadUrlOnce();
    }

    private void updateStatus() {
        ServerState state = manager.getState();
        String url = manager.getUrl();
        String text;
        JBColor color;
        switch (state) {
            case RUNNING -> {
                boolean external = !manager.isManagedProcessAlive();
                text = external
                        ? "● Connected（外部实例） — " + url
                        : "● Connected — " + url;
                color = COLOR_OK;
            }
            case STARTING -> {
                text = "● Starting server…";
                color = COLOR_WARN;
            }
            case FAILED -> {
                text = "✕ Failed — see Server Log";
                color = COLOR_ERROR;
            }
            default -> {
                text = "○ Server stopped — click ▶ to start";
                color = COLOR_IDLE;
            }
        }
        statusLabel.setForeground(color);
        statusLabel.setText(text);
        statusLabel.setToolTipText(url);
    }

    // ── Disposable ────────────────────────────────────────────────────────

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        healthTimer.stop();
        connection.disconnect();
        if (embeddedEnabled && browser != null) {
            DshJcefSupport.dispose(browser);
        }
    }

    public JComponent getComponent() {
        return this;
    }
}
