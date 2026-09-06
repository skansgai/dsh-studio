package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;

/**
 * 设置页：Settings → Tools → DeepSeek Harness。
 */
public final class DshSettingsConfigurable implements Configurable {

    private final JBTextField serverUrlField = new JBTextField();
    private final JSpinner startPortSpinner = new JSpinner(new SpinnerNumberModel(3080, 0, 65535, 1));
    private final JBTextField serverCommandField = new JBTextField();
    private final JBTextField workingDirectoryField = new JBTextField();
    private final JBTextField dshHomeField = new JBTextField();
    private final JBTextField serverTokenField = new JBTextField();
    private final JCheckBox autoStartCheckBox = new JCheckBox("打开工具窗口时自动启动服务器（若尚未运行）");
    private final JCheckBox embeddedCheckBox = new JCheckBox("使用内嵌浏览器（JCEF）显示界面");
    private final JComboBox<DshUiTheme> themeCombo = new JComboBox<>(DshUiTheme.values());
    private final JBLabel testResultLabel = new JBLabel();

    // ── 关于（版本 + 检查更新）────
    private final JBLabel pluginVersionLabel = new JBLabel();
    private final JBLabel dshVersionLabel = new JBLabel();
    private final JButton checkUpdateButton = new JButton("检查更新");

    private JPanel root;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "DeepSeek Harness";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        root = new JPanel(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        javax.swing.Box box = javax.swing.Box.createVerticalBox();

        // ── 通用设置 ──────────────────────────────────────────────────
        {
            JPanel general = sectionPanel("通用设置");
            GridBagConstraints c = gridBag();
            int r = 0;

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            general.add(new JBLabel("界面主题:"), c);
            c.gridx = 1;
            c.weightx = 1;
            general.add(themeCombo, c);

            addSection(box, general);
        }

        // ── 服务器 ────────────────────────────────────────────────────
        {
            JPanel server = sectionPanel("服务器");
            GridBagConstraints c = gridBag();
            int r = 0;

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("服务器地址:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(serverUrlField, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("自动启动端口:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(startPortSpinner, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("启动命令模板:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(serverCommandField, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("工作目录:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(workingDirectoryField, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("DSH_HOME:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(dshHomeField, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("服务器 Token（可选）:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(serverTokenField, c);

            addSection(box, server);
        }

        // ── 启动选项 ──────────────────────────────────────────────────
        {
            JPanel startup = sectionPanel("启动选项");
            GridBagConstraints c = gridBag();
            int r = 0;

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            startup.add(autoStartCheckBox, c);
            c.gridwidth = 1;

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            startup.add(embeddedCheckBox, c);
            c.gridwidth = 1;

            addSection(box, startup);
        }

        // ── 提示与测试 ─────────────────────────────────────────────────
        JPanel misc = new JPanel(new GridBagLayout());
        GridBagConstraints c = gridBag();
        int r = 0;
        c.gridy = r++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        misc.add(buildHints(), c);
        c.gridwidth = 1;

        JPanel testRow = new JPanel(new BorderLayout(8, 0));
        JButton testButton = new JButton("测试连接");
        testButton.addActionListener(e -> testConnection());
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testResultLabel, BorderLayout.CENTER);
        c.gridy = r++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        misc.add(testRow, c);
        c.gridwidth = 1;

        addSection(box, misc);

        // ── 关于（版本 + 检查更新）─────────────────────────────────────────
        {
            JPanel about = sectionPanel("关于");
            GridBagConstraints ac = gridBag();
            int ar = 0;

            ac.gridy = ar++;
            ac.gridx = 0;
            ac.weightx = 0;
            about.add(new JBLabel("插件版本:"), ac);
            ac.gridx = 1;
            ac.weightx = 1;
            about.add(pluginVersionLabel, ac);

            ac.gridy = ar++;
            ac.gridx = 0;
            ac.weightx = 0;
            about.add(new JBLabel("DeepSeek Harness (dsh):"), ac);
            ac.gridx = 1;
            ac.weightx = 1;
            about.add(dshVersionLabel, ac);

            ac.gridy = ar++;
            ac.gridx = 0;
            ac.gridwidth = 2;
            ac.weightx = 1;
            JPanel aboutBtnRow = new JPanel(new BorderLayout(8, 0));
            checkUpdateButton.addActionListener(e -> checkForUpdates());
            aboutBtnRow.add(checkUpdateButton, BorderLayout.WEST);
            about.add(aboutBtnRow, ac);
            ac.gridwidth = 1;

            addSection(box, about);
        }

        root.add(new JScrollPane(box), BorderLayout.CENTER);
        reset();
        return root;
    }

    private static JPanel sectionPanel(@NotNull String title) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        return p;
    }

    private static GridBagConstraints gridBag() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        return c;
    }

    private static void addSection(javax.swing.Box box, JPanel section) {
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        section.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, section.getPreferredSize().height));
        box.add(section);
    }

    private static JBLabel buildHints() {
        return new JBLabel(
                "<html><div style='width:520px'>" +
                        "<b>启动命令</b>：留空使用默认 <code>npx --yes @deepseek-ai/dsh web --host {host} --port {port}</code>；" +
                        "支持占位符 <code>{host} {port} {workdir} {dshHome}</code>。<br>" +
                        "<b>工作目录</b>：留空则使用当前项目目录（作为 Harness 的 workspace 根目录）。<br>" +
                        "<b>DSH_HOME</b>：留空则使用 <code>~/.dsh</code>（可通过环境变量覆盖）。<br>" +
                        "<b>服务器 Token</b>：连接非本插件启动的服务器时，从其启动输出里的 <code>?token=…</code> " +
                        "复制 token 到此处，即可使用「发送代码」「会话列表」等 IDE 内操作；本插件自己启动的服务器无需填写。" +
                        "</div></html>");
    }

    private void testConnection() {
        testResultLabel.setText("检测中…");
        testResultLabel.setForeground(JBColor.GRAY);
        String url = serverUrlField.getText().trim();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean up = DshUtil.isReachable(
                    url.isEmpty() ? DshStudioConstants.DEFAULT_SERVER_URL : url,
                    DshStudioConstants.HEALTH_TIMEOUT_MS);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (up) {
                    testResultLabel.setForeground(new JBColor(0x1E8E3E, 0x81C995));
                    testResultLabel.setText("连接成功：服务器可达。");
                } else {
                    testResultLabel.setForeground(new JBColor(0xC5221F, 0xF28B82));
                    testResultLabel.setText("无法连接：请确认服务器已启动（可先保存设置并在工具窗口中点击 ▶ 启动）。");
                }
            });
        });
    }

    /** 异步拉取 npm 上 dsh 最新版本并刷新标签（设置页打开时自动调用）。 */
    private void refreshDshVersion() {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String v = DshUtil.fetchLatestDshVersion(DshStudioConstants.API_TIMEOUT_MS);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (v == null) {
                    dshVersionLabel.setText("（无法获取，请检查网络）");
                    dshVersionLabel.setForeground(JBColor.GRAY);
                } else {
                    dshVersionLabel.setText(v + "（npm 最新版）");
                    dshVersionLabel.setForeground(JBColor.foreground());
                }
            });
        });
    }

    /** 「检查更新」：比对插件（Marketplace）与 dsh（npm）的最新版本，弹通知给出指引。 */
    private void checkForUpdates() {
        checkUpdateButton.setEnabled(false);
        checkUpdateButton.setText("检查中…");
        String installed = pluginVersionLabel.getText();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String pluginLatest = DshUtil.fetchLatestPluginVersion(DshStudioConstants.API_TIMEOUT_MS);
            String dshLatest = DshUtil.fetchLatestDshVersion(DshStudioConstants.API_TIMEOUT_MS);
            ApplicationManager.getApplication().invokeLater(() -> {
                checkUpdateButton.setEnabled(true);
                checkUpdateButton.setText("检查更新");
                if (dshLatest != null) {
                    dshVersionLabel.setText(dshLatest + "（npm 最新版）");
                    dshVersionLabel.setForeground(JBColor.foreground());
                }
                showUpdateResult(installed, pluginLatest, dshLatest);
            });
        });
    }

    /** 把检查结果汇总成一条通知：有插件更新用 WARNING，否则 INFORMATION。 */
    private void showUpdateResult(String installed, String pluginLatest, String dshLatest) {
        boolean hasUpdate = false;
        StringBuilder sb = new StringBuilder("<html>");

        // 插件
        if (pluginLatest == null) {
            sb.append("• <b>插件</b>：无法连接 JetBrains Marketplace 检查更新，请稍后重试。<br>");
        } else {
            int cmp = DshUtil.compareVersion(installed, pluginLatest);
            if (cmp < 0) {
                hasUpdate = true;
                sb.append("• <b>插件</b>：有新版 <b>v").append(pluginLatest).append("</b>（当前 v")
                        .append(installed).append("）。前往 <b>Settings → Plugins → Marketplace</b> 搜索 ")
                        .append("“DeepSeek Harness” 更新，或访问插件页 ")
                        .append("<a href=\"https://plugins.jetbrains.com/plugin/33569-deepseek-harness-studio\">33569</a>。<br>");
            } else {
                sb.append("• <b>插件</b>：已是最新（v").append(installed).append("）。<br>");
            }
        }

        // dsh
        if (dshLatest == null) {
            sb.append("• <b>DeepSeek Harness (dsh)</b>：无法连接 npm 检查更新，请稍后重试。");
        } else {
            sb.append("• <b>DeepSeek Harness (dsh)</b>：最新 <b>v").append(dshLatest)
                    .append("</b>。本插件用 <code>npx --yes</code> 启动服务器，下次启动会自动使用最新版。");
        }
        sb.append("</html>");

        String title = hasUpdate ? "DeepSeek Harness Studio：有可用更新" : "DeepSeek Harness Studio：已是最新";
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, sb.toString(),
                        hasUpdate ? NotificationType.WARNING : NotificationType.INFORMATION)
                .notify(null);
    }

    @Override
    public boolean isModified() {
        DshSettingsState state = DshSettingsState.getInstance();
        return !serverUrlField.getText().trim().equals(state.serverUrl)
                || (Integer) startPortSpinner.getValue() != state.startPort
                || !serverCommandField.getText().equals(state.serverCommand)
                || !workingDirectoryField.getText().equals(state.workingDirectory)
                || !dshHomeField.getText().equals(state.dshHome)
                || !serverTokenField.getText().trim().equals(state.serverAuthToken)
                || autoStartCheckBox.isSelected() != state.autoStartServer
                || embeddedCheckBox.isSelected() != state.useEmbeddedBrowser
                || !((DshUiTheme) themeCombo.getSelectedItem()).id.equals(state.uiTheme);
    }

    @Override
    public void apply() {
        DshSettingsState state = DshSettingsState.getInstance();
        state.serverUrl = serverUrlField.getText().trim();
        state.startPort = (Integer) startPortSpinner.getValue();
        state.serverCommand = serverCommandField.getText().trim();
        state.workingDirectory = workingDirectoryField.getText().trim();
        state.dshHome = dshHomeField.getText().trim();
        state.serverAuthToken = serverTokenField.getText().trim();
        state.autoStartServer = autoStartCheckBox.isSelected();
        state.useEmbeddedBrowser = embeddedCheckBox.isSelected();
        state.uiTheme = ((DshUiTheme) themeCombo.getSelectedItem()).id;
        // 广播设置变化，让已打开的工具窗口重新应用（主题 / 背景浮层）
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(DshSettingsTopics.SETTINGS_TOPIC).onChanged();
    }

    @Override
    public void reset() {
        DshSettingsState state = DshSettingsState.getInstance();
        serverUrlField.setText(state.serverUrl);
        startPortSpinner.setValue(state.startPort);
        serverCommandField.setText(state.serverCommand);
        workingDirectoryField.setText(state.workingDirectory);
        dshHomeField.setText(state.dshHome);
        serverTokenField.setText(state.serverAuthToken);
        autoStartCheckBox.setSelected(state.autoStartServer);
        embeddedCheckBox.setSelected(state.useEmbeddedBrowser);
        themeCombo.setSelectedItem(DshUiTheme.fromId(state.uiTheme));
        testResultLabel.setText("");
        testResultLabel.setHorizontalAlignment(SwingConstants.LEFT);

        pluginVersionLabel.setText(DshUtil.getInstalledPluginVersion());
        dshVersionLabel.setText("查询中…");
        dshVersionLabel.setForeground(JBColor.GRAY);
        refreshDshVersion();
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
