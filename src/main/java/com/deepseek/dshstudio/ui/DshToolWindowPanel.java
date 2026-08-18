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
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
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
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FlowLayout;

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

    private final Project project;
    private final DshServerManager manager;

    private final JBLabel statusLabel = new JBLabel();
    private final JTextArea logArea = new JTextArea();
    private final ImageBackdropPanel browserHolder = new ImageBackdropPanel(0.35f);
    private final JBTabbedPane tabs = new JBTabbedPane();

    private final Timer healthTimer;
    private final MessageBusConnection connection;

    @Nullable
    private final JBCefBrowser browser;
    private final boolean embeddedEnabled;

    private volatile String loadedUrl;
    private volatile boolean disposed;

    public DshToolWindowPanel(@NotNull Project project) {
        super(new BorderLayout());
        this.project = project;
        this.manager = DshServerManager.getInstance(project);

        DshSettingsState settings = DshSettingsState.getInstance();
        this.embeddedEnabled = settings.useEmbeddedBrowser && JBCefApp.isSupported();
        if (embeddedEnabled) {
            this.browser = new JBCefBrowser();
        } else {
            this.browser = createDisabledBrowserFallback();
        }

        buildUi();
        logArea.setText(manager.getLogText());
        applyThemeToChrome();

        this.connection = project.getMessageBus().connect(this);
        connection.subscribe(DshServerTopics.SERVER_TOPIC, new DshServerListener() {
            @Override
            public void onStateChanged(ServerState state) {
                updateStatus();
            }

            @Override
            public void onLog(String chunk) {
                if (!chunk.isEmpty()) {
                    logArea.append(chunk);
                    logArea.setCaretPosition(logArea.getDocument().getLength());
                }
            }
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
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
        statusLabel.setVerticalTextPosition(SwingConstants.CENTER);
        bar.add(statusLabel, BorderLayout.WEST);

        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new StartServerAction(project));
        group.add(new StopServerAction(project));
        group.add(Separator.getInstance());
        group.add(new OpenInBrowserAction(project));
        group.add(new RefreshAction(project, this::reloadPage));

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("DshStudio.Toolbar", group, true);
        toolbar.setTargetComponent(this);
        bar.add(toolbar.getComponent(), BorderLayout.EAST);
        return bar;
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
        DshSettingsState settings = DshSettingsState.getInstance();
        browserHolder.setBackgroundImage(settings.backgroundImagePath);
        addEmptyStateOverlay();
        if (embeddedEnabled) {
            browserHolder.add(browser.getComponent(), BorderLayout.CENTER);
        } else {
            browserHolder.add(buildFallbackPanel(), BorderLayout.CENTER);
        }
        return browserHolder;
    }

    /** 未连接/加载中时，在背景图上叠加一句提示（透明，不遮挡背景图）。 */
    private void addEmptyStateOverlay() {
        JBLabel empty = new JBLabel("等待连接到 DeepSeek Harness 服务器…", SwingConstants.CENTER);
        empty.setOpaque(false);
        empty.setForeground(JBColor.WHITE);
        empty.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 0));
        browserHolder.add(empty, BorderLayout.SOUTH);
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

    private JBCefBrowser createDisabledBrowserFallback() {
        // 占位：embeddedEnabled=false 时不会真正创建 JCEF 浏览器
        return null;
    }
    // ── 运行逻辑 ──────────────────────────────────────────────────────────

    private void tick() {
        if (disposed || project.isDisposed()) {
            healthTimer.stop();
            return;
        }
        manager.probeAsync();
        updateStatus();
        if (manager.getState() == ServerState.RUNNING) {
            loadUrlOnce();
        }
    }

    private void loadUrlOnce() {
        if (!embeddedEnabled || browser == null) {
            return;
        }
        String url = manager.getUrl();
        if (!url.equals(loadedUrl)) {
            loadedUrl = url;
            browser.loadURL(url);
            injectPageTheme();
        }
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
        browser.getCefBrowser().executeJavaScript(script, "", 0);
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
        }
    }

    private void reloadPage() {
        if (embeddedEnabled && browser != null) {
            browser.getCefBrowser().reload();
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
            Disposer.dispose(browser);
        }
    }

    public JComponent getComponent() {
        return this;
    }
}
