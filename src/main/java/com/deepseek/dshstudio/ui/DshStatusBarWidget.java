package com.deepseek.dshstudio.ui;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.server.DshServerListener;
import com.deepseek.dshstudio.server.DshServerManager;
import com.deepseek.dshstudio.server.DshServerManager.ServerState;
import com.deepseek.dshstudio.server.DshServerTopics;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.CustomStatusBarWidget;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.JBColor;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBusConnection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 状态栏小部件：常驻显示 DeepSeek Harness 服务器状态（绿 ● 已连接 / 橙 ◐ 启动中 /
 * 红 ✕ 失败 / 灰 ○ 未运行），点击打开工具窗口。
 * <p>
 * 工具窗口未打开时由本组件的 Alarm 周期探测保持状态新鲜。
 */
public final class DshStatusBarWidget implements CustomStatusBarWidget {

    private static final JBColor COLOR_OK = new JBColor(0x1E8E3E, 0x81C995);
    private static final JBColor COLOR_WARN = new JBColor(0xB26B00, 0xE8B26A);
    private static final JBColor COLOR_ERROR = new JBColor(0xC5221F, 0xF28B82);
    private static final JBColor COLOR_IDLE = JBColor.GRAY;

    private static final int POLL_MS = 4000;

    private final Project project;
    private final JPanel component = new JPanel(new BorderLayout());
    private final JLabel label = new JLabel();
    private final Alarm alarm;
    private final MessageBusConnection connection;
    @Nullable
    private StatusBar statusBar;

    public DshStatusBarWidget(@NotNull Project project) {
        this.project = project;
        this.alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        label.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        label.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openToolWindow();
            }
        });
        component.add(label, BorderLayout.CENTER);
        component.setToolTipText("DeepSeek Harness");

        this.connection = project.getMessageBus().connect(this);
        connection.subscribe(DshServerTopics.SERVER_TOPIC, new DshServerListener() {
            @Override
            public void onStateChanged(ServerState state) {
                updateNow();
            }
        });
        schedulePolling();
        updateNow();
    }

    private void schedulePolling() {
        if (project.isDisposed()) {
            return;
        }
        alarm.addRequest(() -> {
            if (project.isDisposed()) {
                return;
            }
            // 工具窗口面板打开时它自带轮询；这里保证关闭时状态依然更新
            DshServerManager.getInstance(project).probeAsync();
            schedulePolling();
        }, POLL_MS);
    }

    /**
     * 请求刷新外观。可能来自后台轮询线程或 MessageBus，因此统一切回 EDT 再碰 Swing 组件。
     */
    private void updateNow() {
        if (project.isDisposed()) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(this::applyState, project.getDisposed());
    }

    /** 真正更新标签；必须在 EDT 上执行。 */
    private void applyState() {
        if (project.isDisposed()) {
            return;
        }
        DshServerManager manager = DshServerManager.getInstance(project);
        ServerState state = manager.getState();
        String url = manager.getUrl();
        String text;
        String tooltip;
        JBColor color;
        switch (state) {
            case RUNNING -> {
                text = "● Harness";
                tooltip = "DeepSeek Harness 已连接 — " + url + "（点击打开）";
                color = COLOR_OK;
            }
            case STARTING -> {
                text = "◐ Harness";
                tooltip = "DeepSeek Harness 启动中…（点击打开）";
                color = COLOR_WARN;
            }
            case FAILED -> {
                text = "✕ Harness";
                tooltip = "DeepSeek Harness 启动失败，查看工具窗口日志（点击打开）";
                color = COLOR_ERROR;
            }
            default -> {
                text = "○ Harness";
                tooltip = "DeepSeek Harness 未运行（点击打开工具窗口启动）";
                color = COLOR_IDLE;
            }
        }
        label.setText(text);
        label.setForeground(color);
        component.setToolTipText(tooltip);
    }

    private void openToolWindow() {
        if (project.isDisposed()) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow(DshStudioConstants.TOOL_WINDOW_ID);
        if (toolWindow != null) {
            toolWindow.activate(null, true, true);
        }
    }

    // ── StatusBarWidget / CustomStatusBarWidget 契约 ─────────────────────

    @Override
    public @NotNull String ID() {
        return "DshStudio.StatusBarWidget";
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        this.statusBar = statusBar;
        updateNow();
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        // CustomStatusBarWidget 直接提供 JComponent（见 getComponent）
        return null;
    }

    @Override
    public JComponent getComponent() {
        return component;
    }

    @Override
    public void dispose() {
        alarm.cancelAllRequests();
        connection.disconnect();
    }
}
