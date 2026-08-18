package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileTypeDescriptor;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.io.File;

/**
 * 设置页：Settings → Tools → DeepSeek Harness。
 */
public final class DshSettingsConfigurable implements Configurable {

    private final JBTextField serverUrlField = new JBTextField();
    private final JSpinner startPortSpinner = new JSpinner(new SpinnerNumberModel(3080, 0, 65535, 1));
    private final JBTextField serverCommandField = new JBTextField();
    private final JBTextField workingDirectoryField = new JBTextField();
    private final JBTextField dshHomeField = new JBTextField();
    private final JCheckBox autoStartCheckBox = new JCheckBox("打开工具窗口时自动启动服务器（若尚未运行）");
    private final JCheckBox embeddedCheckBox = new JCheckBox("使用内嵌浏览器（JCEF）显示界面");
    private final JComboBox<DshUiTheme> themeCombo = new JComboBox<>(DshUiTheme.values());
    private final JBTextField backgroundImageField = new JBTextField();
    private final JCheckBox overlayCheckBox = new JCheckBox("在网页上叠加半透明背景层（实验性，可能受原生渲染限制）");
    private final JSpinner overlayOpacitySpinner = new JSpinner(new SpinnerNumberModel(40, 0, 100, 5));
    private final JBLabel testResultLabel = new JBLabel();

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

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;

        int row = 0;
        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("服务器地址:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(serverUrlField, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("自动启动端口:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(startPortSpinner, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("启动命令模板:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(serverCommandField, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("工作目录:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(workingDirectoryField, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("DSH_HOME:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(dshHomeField, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("界面主题:"), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(themeCombo, c);

        c.gridy = row++;
        c.gridx = 0;
        c.weightx = 0;
        form.add(new JBLabel("背景图片:"), c);
        c.gridx = 1;
        c.weightx = 1;
        JPanel bgRow = new JPanel(new BorderLayout(6, 0));
        bgRow.add(backgroundImageField, BorderLayout.CENTER);
        JButton browseButton = new JButton("浏览…");
        browseButton.addActionListener(e -> chooseBackgroundImage());
        bgRow.add(browseButton, BorderLayout.EAST);
        form.add(bgRow, c);

        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        JPanel overlayRow = new JPanel(new BorderLayout(8, 0));
        overlayRow.add(overlayCheckBox, BorderLayout.WEST);
        JBLabel opLabel = new JBLabel("不透明度(%):");
        JPanel op = new JPanel(new BorderLayout(4, 0));
        op.add(opLabel, BorderLayout.WEST);
        op.add(overlayOpacitySpinner, BorderLayout.CENTER);
        overlayRow.add(op, BorderLayout.EAST);
        form.add(overlayRow, c);
        c.gridwidth = 1;

        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 1;
        form.add(autoStartCheckBox, c);
        c.gridwidth = 1;

        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 2;
        form.add(embeddedCheckBox, c);
        c.gridwidth = 1;

        // 提示与测试按钮
        JBLabel hints = new JBLabel(
                "<html><div style='width:520px'>" +
                        "<b>启动命令</b>：留空使用默认 <code>npx --yes @deepseek-ai/dsh web --host {host} --port {port}</code>；" +
                        "支持占位符 <code>{host} {port} {workdir} {dshHome}</code>。<br>" +
                        "<b>工作目录</b>：留空则使用当前项目目录（作为 Harness 的 workspace 根目录）。<br>" +
                        "<b>DSH_HOME</b>：留空则使用 <code>~/.dsh</code>（可通过环境变量覆盖）。" +
                        "</div></html>");
        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 2;
        form.add(hints, c);
        c.gridwidth = 1;

        JPanel testRow = new JPanel(new BorderLayout(8, 0));
        JButton testButton = new JButton("测试连接");
        testButton.addActionListener(e -> testConnection());
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testResultLabel, BorderLayout.CENTER);
        c.gridy = row++;
        c.gridx = 0;
        c.gridwidth = 2;
        form.add(testRow, c);
        c.gridwidth = 1;

        root.add(form, BorderLayout.NORTH);
        reset();
        return root;
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

    private void chooseBackgroundImage() {
        FileTypeDescriptor descriptor = new FileTypeDescriptor("背景图片", "png", "jpg", "jpeg", "svg", "webp", "gif", "bmp");
        com.intellij.openapi.vfs.VirtualFile file = FileChooser.chooseFile(
                descriptor, null, null);
        if (file != null && file.getPath() != null) {
            backgroundImageField.setText(file.getPath());
        }
    }

    @Override
    public boolean isModified() {
        DshSettingsState state = DshSettingsState.getInstance();
        return !serverUrlField.getText().trim().equals(state.serverUrl)
                || (Integer) startPortSpinner.getValue() != state.startPort
                || !serverCommandField.getText().equals(state.serverCommand)
                || !workingDirectoryField.getText().equals(state.workingDirectory)
                || !dshHomeField.getText().equals(state.dshHome)
                || autoStartCheckBox.isSelected() != state.autoStartServer
                || embeddedCheckBox.isSelected() != state.useEmbeddedBrowser
                || !((DshUiTheme) themeCombo.getSelectedItem()).id.equals(state.uiTheme)
                || !backgroundImageField.getText().equals(state.backgroundImagePath)
                || overlayCheckBox.isSelected() != state.pageOverlayEnabled
                || (Integer) overlayOpacitySpinner.getValue() != state.pageOverlayOpacity;
    }

    @Override
    public void apply() {
        DshSettingsState state = DshSettingsState.getInstance();
        state.serverUrl = serverUrlField.getText().trim();
        state.startPort = (Integer) startPortSpinner.getValue();
        state.serverCommand = serverCommandField.getText().trim();
        state.workingDirectory = workingDirectoryField.getText().trim();
        state.dshHome = dshHomeField.getText().trim();
        state.autoStartServer = autoStartCheckBox.isSelected();
        state.useEmbeddedBrowser = embeddedCheckBox.isSelected();
        state.uiTheme = ((DshUiTheme) themeCombo.getSelectedItem()).id;
        state.backgroundImagePath = backgroundImageField.getText().trim();
        state.pageOverlayEnabled = overlayCheckBox.isSelected();
        state.pageOverlayOpacity = (Integer) overlayOpacitySpinner.getValue();
    }

    @Override
    public void reset() {
        DshSettingsState state = DshSettingsState.getInstance();
        serverUrlField.setText(state.serverUrl);
        startPortSpinner.setValue(state.startPort);
        serverCommandField.setText(state.serverCommand);
        workingDirectoryField.setText(state.workingDirectory);
        dshHomeField.setText(state.dshHome);
        autoStartCheckBox.setSelected(state.autoStartServer);
        embeddedCheckBox.setSelected(state.useEmbeddedBrowser);
        themeCombo.setSelectedItem(DshUiTheme.fromId(state.uiTheme));
        backgroundImageField.setText(state.backgroundImagePath);
        overlayCheckBox.setSelected(state.pageOverlayEnabled);
        overlayOpacitySpinner.setValue(state.pageOverlayOpacity);
        testResultLabel.setText("");
        testResultLabel.setHorizontalAlignment(SwingConstants.LEFT);
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
