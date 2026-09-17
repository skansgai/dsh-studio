package com.deepseek.dshstudio.settings;

import com.deepseek.dshstudio.DshStudioConstants;
import com.deepseek.dshstudio.runtime.DshNodeChecker;
import com.deepseek.dshstudio.runtime.DshRuntimeUpdater;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.deepseek.dshstudio.runtime.DshRuntimeLocation;
import com.deepseek.dshstudio.runtime.DshRuntimeManager;
import com.deepseek.dshstudio.runtime.DshRuntimeMode;
import com.deepseek.dshstudio.util.DshUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
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
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

/**
 * 设置页：Settings → Tools → DeepSeek Harness。
 * <p>
 * <b>做成项目级</b>（{@code projectConfigurable}）是有意的：本页里的「服务器地址 / 端口 /
 * 工作目录 / Token」都是「一个 dsh 实例」的概念，同一个 IDE 里同时开 A、B 两个项目时必须各配各的。
 * 共享一份全局配置的话，B 一改就会把 A 覆盖掉，B 还会连到 A 的服务器上去。
 * <p>
 * 其余区块（运行时 / 主题 / 背景图 / 启动选项）仍然是全局的，存在应用级设置里，
 * 在哪个项目的设置页改都一样。界面上通过区块标题里的「（全局）」/「（仅本项目）」区分。
 */
public final class DshSettingsConfigurable implements Configurable {

    /** 所属项目：项目级字段（服务器地址 / 端口 / 工作目录 / token）都从它取。 */
    private final Project project;

    private final JBTextField serverUrlField = new JBTextField();
    private final JSpinner startPortSpinner = new JSpinner(new SpinnerNumberModel(3080, 0, 65535, 1));
    private final JBTextField serverCommandField = new JBTextField();
    private final JBTextField workingDirectoryField = new JBTextField();
    private final JBTextField dshHomeField = new JBTextField();
    private final JBTextField serverTokenField = new JBTextField();
    private final JCheckBox autoStartCheckBox = new JCheckBox("打开工具窗口时自动启动服务器（若尚未运行）");
    private final JCheckBox embeddedCheckBox = new JCheckBox("使用内嵌浏览器（JCEF）显示界面");
    private final JCheckBox keepProcessCheckBox =
            new JCheckBox("项目关闭后保留 dsh 服务器进程（默认关闭：随项目结束，顺带释放端口）");
    private final JComboBox<DshUiTheme> themeCombo = new JComboBox<>(DshUiTheme.values());
    private final JBLabel testResultLabel = new JBLabel();
    /** 只读展示「本项目实际会连的地址」，避免用户以为填了端口就一定用那个端口。 */
    private final JBLabel effectiveAddressLabel = new JBLabel();

    // ── 运行时 ────────────────────────────────────────────────────
    private final JComboBox<DshRuntimeMode> runtimeModeCombo = new JComboBox<>(DshRuntimeMode.values());
    private final JComboBox<DshRuntimeLocation> runtimeLocationCombo =
            new JComboBox<>(DshRuntimeLocation.values());
    private final JBLabel runtimeEffectiveLabel = new JBLabel();
    private final JBLabel runtimePathLabel = new JBLabel();
    private final JBLabel runtimeNodeLabel = new JBLabel();
    private final JBLabel runtimeHotLabel = new JBLabel();
    private final JButton recheckNodeButton = new JButton("重新检测");
    private final JButton checkRuntimeUpdateButton = new JButton("检查更新");
    private final JButton removeHotRuntimeButton = new JButton("删除热更新");
    private final JButton unpackButton = new JButton("下载运行时");
    private final JButton clearRuntimeButton = new JButton("清理运行时");

    /**
     * Node 探测结果缓存。
     * <p>
     * 探测要起一个 {@code node --version} 子进程，放在每次 {@code refreshRuntimeInfo()} 里
     * 会在 EDT 上反复阻塞；所以只在首次和用户点「重新检测」时刷新。
     */
    @Nullable
    private DshNodeChecker.Report nodeReport;

    // ── 关于（版本 + 检查更新）────
    private final JBLabel pluginVersionLabel = new JBLabel();
    private final JBLabel dshVersionLabel = new JBLabel();
    private final JButton checkUpdateButton = new JButton("检查更新");

    private JPanel root;

    /**
     * 平台按项目实例化（{@code projectConfigurable} 要求存在接收 {@link Project} 的构造函数）。
     *
     * @param project 当前项目，用于读写项目级设置
     */
    public DshSettingsConfigurable(@NotNull Project project) {
        this.project = project;
    }

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
            JPanel server = sectionPanel("服务器（仅本项目）");
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
            server.add(new JBLabel("期望端口:"), c);
            c.gridx = 1;
            c.weightx = 1;
            server.add(startPortSpinner, c);

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            server.add(effectiveAddressLabel, c);
            c.gridwidth = 1;

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            server.add(new JBLabel("启动命令模板（全局）:"), c);
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

        // ── 运行时 ────────────────────────────────────────────────────
        {
            JPanel runtime = sectionPanel("运行时");
            GridBagConstraints c = gridBag();
            int r = 0;

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("运行时来源:"), c);
            c.gridx = 1;
            c.weightx = 1;
            runtimeModeCombo.addActionListener(e -> refreshRuntimeInfo());
            runtime.add(runtimeModeCombo, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("运行时位置:"), c);
            c.gridx = 1;
            c.weightx = 1;
            runtimeLocationCombo.addActionListener(e -> refreshRuntimeInfo());
            runtime.add(runtimeLocationCombo, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("当前生效:"), c);
            c.gridx = 1;
            c.weightx = 1;
            runtime.add(runtimeEffectiveLabel, c);


            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("运行时目录:"), c);
            c.gridx = 1;
            c.weightx = 1;
            runtime.add(runtimePathLabel, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("Node.js:"), c);
            c.gridx = 1;
            c.weightx = 1;
            recheckNodeButton.addActionListener(e -> refreshNodeInfo(true));
            JPanel nodeRow = new JPanel(new BorderLayout(8, 0));
            nodeRow.add(runtimeNodeLabel, BorderLayout.CENTER);
            nodeRow.add(recheckNodeButton, BorderLayout.EAST);
            runtime.add(nodeRow, c);

            c.gridy = r++;
            c.gridx = 0;
            c.weightx = 0;
            runtime.add(new JBLabel("热更新版本:"), c);
            c.gridx = 1;
            c.weightx = 1;
            checkRuntimeUpdateButton.addActionListener(e -> checkRuntimeUpdate());
            removeHotRuntimeButton.addActionListener(e -> removeHotRuntime());
            JPanel hotRow = new JPanel(new BorderLayout(8, 0));
            hotRow.add(runtimeHotLabel, BorderLayout.CENTER);
            JPanel hotButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            hotButtons.add(checkRuntimeUpdateButton);
            hotButtons.add(removeHotRuntimeButton);
            hotRow.add(hotButtons, BorderLayout.EAST);
            runtime.add(hotRow, c);

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            JPanel runtimeButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            unpackButton.addActionListener(e -> unpackNow());
            clearRuntimeButton.addActionListener(e -> clearRuntime());
            runtimeButtons.add(unpackButton);
            runtimeButtons.add(clearRuntimeButton);
            runtime.add(runtimeButtons, c);
            c.gridwidth = 1;

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            runtime.add(buildRuntimeHint(), c);
            c.gridwidth = 1;

            addSection(box, runtime);
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

            c.gridy = r++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            startup.add(keepProcessCheckBox, c);
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
            about.add(new JBLabel("dsh 最新版（npm）:"), ac);
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

        // 「当前地址」这一行随地址框 / 端口框实时变化，让用户立刻看到实际会用哪个地址
        serverUrlField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refreshEffectiveAddress();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refreshEffectiveAddress();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refreshEffectiveAddress();
            }
        });
        startPortSpinner.addChangeListener(e -> refreshEffectiveAddress());

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
                        "<b>哪些是项目级</b>：服务器地址 / 期望端口 / 工作目录 / Token <b>只对本项目生效</b>，" +
                        "存在项目自己的 workspace 里；运行时、主题、背景图、启动选项是所有项目共享的全局设置。<br>" +
                        "<b>服务器地址</b>：<b>留空＝自动管理</b> —— 本插件为本项目拉起一个 dsh 进程，" +
                        "端口优先用上次分配到的、其次用「期望端口」，被占用时自动改用空闲端口；" +
                        "它<b>不会</b>去复用别的项目已经在跑的那个实例（复用会让两个项目串会话）。" +
                        "填了地址＝手动模式，直接连它（例如终端里已经起好的实例），插件不再自己拉进程。<br>" +
                        "<b>启动命令</b>：留空使用默认 <code>{dsh} web --host {host} --port {port} --no-open</code>；" +
                        "支持占位符 <code>{dsh} {host} {port} {workdir} {dshHome}</code>。" +
                        "<code>{dsh}</code> 按上面的「运行时来源」展开为已下载运行时或系统 <code>npx</code>；" +
                        "模板里不含它时命令原样执行。其中 <code>{host} {port} {workdir}</code> 取自本项目。<br>" +
                        "<b>工作目录</b>：留空则使用本项目根目录，作为 Harness 的 workspace 根。" +
                        "dsh 的会话按 workspace 隔离，所以同时打开的两个项目会话互不可见。<br>" +
                        "<b>DSH_HOME</b>：留空则使用运行时目录下的 <code>.dsh</code>（可通过环境变量覆盖）。<br>" +
                        "<b>服务器 Token</b>：连接非本插件启动的服务器时，从其启动输出里的 <code>?token=…</code> " +
                        "复制 token 到此处，即可使用「发送代码」「会话列表」等 IDE 内操作；本插件自己启动的服务器无需填写。" +
                        "</div></html>");
    }

    /**
     * 刷新「当前地址」那一行。
     * <p>
     * 按输入框里的<b>当前值</b>推算（而不是已保存的值），这样用户一边改一边就能看到结果；
     * 端口被占用时插件会自动换端口，这一行会如实说明换了哪个。
     */
    private void refreshEffectiveAddress() {
        if (effectiveAddressLabel == null) {
            return;
        }
        String typed = serverUrlField.getText() == null ? "" : serverUrlField.getText().trim();
        int typedPort = (Integer) startPortSpinner.getValue();
        DshProjectSettings ps = DshProjectSettings.getInstance(project);
        String url;
        String mode;
        if (!typed.isEmpty()) {
            url = typed;
            mode = "手动模式：直接连这个地址，插件不自己拉进程";
        } else {
            int actual = ps.allocatedPort > 0 ? ps.allocatedPort : typedPort;
            url = "http://127.0.0.1:" + actual;
            if (ps.allocatedPort > 0 && ps.allocatedPort != typedPort) {
                mode = "自动模式：期望端口 " + typedPort + " 已被占用，本项目实际用 " + ps.allocatedPort;
            } else {
                mode = "自动模式：本插件为本项目拉起 dsh";
            }
        }
        effectiveAddressLabel.setText("<html>本项目当前地址：<b>" + url + "</b>　·　" + mode + "</html>");
        effectiveAddressLabel.setForeground(JBColor.GRAY);
        effectiveAddressLabel.setToolTipText(
                "工作目录：" + ps.resolvedWorkingDirectory()
                        + "　（Harness 的 workspace 根，会话按它隔离）");
    }

    /** 运行时区块的说明文字。 */
    private static JBLabel buildRuntimeHint() {
        return new JBLabel(
                "<html><div style='width:520px'>" +
                        "插件包不再内置 dsh（保持约 115KB）。<b>首次启动会自动下载</b>当前平台的运行时包（约 41MB），校验后解包安装，无需预装 npx 大包。" +
                        "下载与解包只在首次发生（约几十秒，之后自动复用）。<br>" +
                        "<b>运行时来源</b> —— " +
                        "<b>自动</b>：优先用已下载（热更新）的运行时，下载不到（无网络 / 发布渠道无本平台包）时回退系统 dsh；" +
                        "<b>仅系统 dsh</b>：保持旧行为，用 <code>npx --yes @deepseek-ai/dsh</code> 启动。<br>" +
                        "<b>运行时位置</b> —— 企业安全软件（DLP）会按路径范围做透明加密：" +
                        "在用户目录里写一个小文件要 ~85 毫秒，而系统临时目录被排除在外。" +
                        "1.8 万个文件因此相差约 <b>27 分钟 vs 6 秒</b>，所以 Windows 默认放临时目录；" +
                        "若担心临时目录被系统清理，或所在环境禁止从临时目录执行程序，可改为用户目录。<br>" +
                        "dsh 运行时与 npx 都依赖本机安装的 <b>Node.js "
                        + DshStudioConstants.MIN_NODE_VERSION
                        + " 或更高</b>（该下限来自依赖包的 engines 声明，低于它只提示、不拦截）。" +
                        "可执行位（终端、ripgrep）会在解包时自动补上。" +
                        "</div></html>");
    }

    /** 刷新运行时区块：当前生效来源、已下载版本、目录、按钮可用性。 */
    private void refreshRuntimeInfo() {
        DshRuntimeManager rt = DshRuntimeManager.getInstance();

        DshRuntimeMode mode = (DshRuntimeMode) runtimeModeCombo.getSelectedItem();
        try {
            DshRuntimeManager.Launch launch = rt.resolve(mode == null ? DshRuntimeMode.AUTO : mode);
            String version = launch.version == null ? "" : " · dsh " + launch.version;
            runtimeEffectiveLabel.setText(launch.source.label + version);
            runtimeEffectiveLabel.setForeground(JBColor.foreground());
        } catch (Exception e) {
            runtimeEffectiveLabel.setText(String.valueOf(e.getMessage()));
            runtimeEffectiveLabel.setForeground(new JBColor(0xC5221F, 0xF28B82));
        }

        // 「下载运行时」按钮：本地还没下载过任何运行时时可用（点它会走首次下载流程）；
        // 已经下载过则禁用，避免重复下载（需要更新请点「检查更新」）。
        unpackButton.setEnabled(rt.hotUpdateDir() == null);

        Path root = rt.runtimeRootFor(
                (DshRuntimeLocation) runtimeLocationCombo.getSelectedItem());
        runtimePathLabel.setText(shortenHome(root));
        runtimePathLabel.setToolTipText(root.toString());
        clearRuntimeButton.setEnabled(Files.isDirectory(root));

        refreshNodeInfo(false);
        refreshHotInfo();
    }

    /** 刷新「热更新版本」行：列出已安装的热更新版本，并决定按钮是否可用。 */
    private void refreshHotInfo() {
        List<String> versions = DshRuntimeUpdater.getInstance().installedVersions();
        if (versions.isEmpty()) {
            runtimeHotLabel.setText("无（当前用的是系统 dsh，或尚未下载运行时）");
            runtimeHotLabel.setForeground(JBColor.GRAY);
        } else {
            runtimeHotLabel.setText(String.join("、", versions)
                    + "（共 " + versions.size() + " 份，自动模式优先使用）");
            runtimeHotLabel.setForeground(JBColor.foreground());
        }
        removeHotRuntimeButton.setEnabled(!versions.isEmpty());
        runtimeHotLabel.setToolTipText("已下载的运行时版本解包在运行时目录里，自动模式下优先使用。"
                + "想回滚到系统 dsh，把「运行时来源」改成「仅系统 dsh」即可，不必删除。");
    }

    /** 手动检查运行时更新（后台查询，发现新版本再问用户要不要下载）。 */
    private void checkRuntimeUpdate() {
        checkRuntimeUpdateButton.setEnabled(false);
        checkRuntimeUpdateButton.setText("检查中…");
        ProgressManager.getInstance().run(
                new Task.Backgroundable(null, "检查 dsh 运行时更新", true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(true);
                        DshRuntimeUpdater.UpdateInfo info =
                                DshRuntimeUpdater.getInstance().checkForUpdate();
                        ApplicationManager.getApplication().invokeLater(() -> {
                            checkRuntimeUpdateButton.setEnabled(true);
                            checkRuntimeUpdateButton.setText("检查更新");
                            if (info == null) {
                                Messages.showInfoMessage(
                                        "当前没有可用的新版本（也可能是网络不通，"
                                                + "或发布渠道上还没有本平台的包）。",
                                        "检查 dsh 运行时更新");
                                return;
                            }
                            String message = "发现新版本 dsh " + info.dshVersion + "。\n\n"
                                    + "当前版本：" + (info.currentVersion == null ? "未知" : info.currentVersion) + "\n"
                                    + "下载体积：" + info.humanSize()
                                    + "（只含 " + DshUtil.hostTarget() + " 平台）\n\n"
                                    + "下载后会解包到运行时目录，下次启动服务器时生效。";
                            if (Messages.showYesNoDialog(message,
                                    "发现新版本 dsh " + info.dshVersion,
                                    Messages.getQuestionIcon()) == Messages.YES) {
                                downloadRuntimeUpdate(info);
                            }
                        });
                    }
                });
    }

    /** 带进度地下载并解包一个运行时版本。 */
    private void downloadRuntimeUpdate(DshRuntimeUpdater.UpdateInfo info) {
        ProgressManager.getInstance().run(
                new Task.Backgroundable(null, "下载 dsh 运行时 " + info.dshVersion, true) {
                    @Override
                    public void run(@NotNull ProgressIndicator indicator) {
                        indicator.setIndeterminate(false);
                        try {
                            DshRuntimeUpdater.getInstance().downloadAndInstall(info, indicator);
                            ApplicationManager.getApplication().invokeLater(() -> {
                                refreshRuntimeInfo();
                                Messages.showInfoMessage(
                                        "dsh " + info.dshVersion + " 已就绪，下次启动服务器时生效。\n\n"
                                                + "想只用系统 dsh：把「运行时来源」改成「仅系统 dsh」。",
                                        "dsh 运行时已更新");
                            });
                        } catch (ProcessCanceledException canceled) {
                            // 用户取消，静默收场（临时文件已由 downloadAndInstall 清掉）
                        } catch (Exception e) {
                            notifyError("下载 dsh 运行时失败", String.valueOf(e.getMessage()));
                        }
                    }
                });
    }

    /** 删除所有已下载的运行时版本（回退到系统 dsh）。 */
    private void removeHotRuntime() {
        DshRuntimeUpdater updater = DshRuntimeUpdater.getInstance();
        List<String> versions = updater.installedVersions();
        if (versions.isEmpty()) {
            return;
        }
        if (Messages.showYesNoDialog(
                "确定要删除以下热更新版本吗？\n\n" + String.join("\n", versions) + "\n\n"
                        + "删除后会改用系统 dsh 启动（若已下载其它版本则优先用最新的）。",
                "删除热更新版本", Messages.getQuestionIcon()) != Messages.YES) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (String version : versions) {
            try {
                updater.removeVersion(version);
            } catch (Exception e) {
                failed.add(version + "（" + e.getMessage() + "）");
            }
        }
        refreshRuntimeInfo();
        if (!failed.isEmpty()) {
            notifyError("部分热更新版本删除失败", String.join("\n", failed));
        }
    }

    /**
     * 刷新 Node.js 状态行。
     *
     * @param force {@code true} 表示重新起子进程探测（「重新检测」按钮）；
     *              {@code false} 表示复用缓存，避免在 EDT 上反复阻塞
     */
    private void refreshNodeInfo(boolean force) {
        if (force || nodeReport == null) {
            nodeReport = DshNodeChecker.check();
        }
        runtimeNodeLabel.setText(nodeReport.describe());
        runtimeNodeLabel.setForeground(nodeReport.isOk()
                ? JBColor.foreground()
                : new JBColor(0xC5221F, 0xF28B82));
        runtimeNodeLabel.setToolTipText("已下载运行时与系统 dsh 都是 Node 程序。"
                + "最低版本 " + DshStudioConstants.MIN_NODE_VERSION
                + " 来自依赖包的 engines 声明，低于它只会提示、不会拦截启动。");
    }

    /** 首次下载并解包 dsh 运行时（EDT 上会弹出带进度的模态框）。 */
    private void unpackNow() {
        unpackButton.setEnabled(false);
        unpackButton.setText("解包中…");
        try {
            DshRuntimeManager.getInstance().prepare(null, DshRuntimeMode.AUTO);
        } catch (Exception e) {
            notifyError("下载并解包 dsh 运行时失败", String.valueOf(e.getMessage()));
        } finally {
            unpackButton.setText("下载运行时");
            refreshRuntimeInfo();
        }
    }

    /** 删除整个运行时目录（下次启动会按需重新下载）。 */
    private void clearRuntime() {
        DshRuntimeManager rt = DshRuntimeManager.getInstance();
        Path root = rt.runtimeRootFor((DshRuntimeLocation) runtimeLocationCombo.getSelectedItem());
        int answer = Messages.showYesNoDialog(
                "确定要删除运行时目录吗？\n\n" + root + "\n\n"
                        + "已下载的运行时会在下次启动时按需重新下载（首次约几十秒），已下载的版本会被一并删除。",
                "清理 dsh 运行时", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        try {
            rt.clear(root);
        } catch (Exception e) {
            notifyError("清理运行时失败", String.valueOf(e.getMessage()));
        }
        refreshRuntimeInfo();
    }

    private static void notifyError(String title, String message) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup(DshStudioConstants.NOTIFICATION_GROUP_ID)
                .createNotification(title, StringUtil.escapeXmlEntities(message), NotificationType.ERROR)
                .notify(null);
    }

    /** 把用户主目录前缀缩写成 {@code ~}，避免设置页被长路径撑宽。 */
    private static String shortenHome(Path path) {
        String home = System.getProperty("user.home", "");
        String s = path.toString();
        if (!home.isEmpty() && s.startsWith(home)) {
            return "~" + s.substring(home.length());
        }
        return s;
    }

    private void testConnection() {
        testResultLabel.setText("检测中…");
        testResultLabel.setForeground(JBColor.GRAY);
        String url = serverUrlField.getText().trim();
        // 地址留空 = 自动模式：测的就是本项目实际会连的那个地址（含自动分配到的端口）
        String target = url.isEmpty()
                ? DshProjectSettings.getInstance(project).effectiveServerUrl()
                : url;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            boolean up = DshUtil.isReachable(target, DshStudioConstants.HEALTH_TIMEOUT_MS);
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
            sb.append("• <b>DeepSeek Harness (dsh)</b>：npm 上最新 <b>v").append(dshLatest)
                    .append("</b>。当前实际使用的版本见上方「运行时」区块。");
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
        DshProjectSettings ps = DshProjectSettings.getInstance(project);
        return !serverUrlField.getText().trim().equals(ps.serverUrl == null ? "" : ps.serverUrl)
                || (Integer) startPortSpinner.getValue() != ps.startPort
                || !serverCommandField.getText().equals(state.serverCommand)
                || !((DshRuntimeMode) runtimeModeCombo.getSelectedItem()).id.equals(state.runtimeMode)
                || !((DshRuntimeLocation) runtimeLocationCombo.getSelectedItem()).id.equals(state.runtimeLocation)
                || !workingDirectoryField.getText().equals(ps.workingDirectory == null ? "" : ps.workingDirectory)
                || !dshHomeField.getText().equals(state.dshHome)
                || !serverTokenField.getText().trim().equals(ps.normalizedServerAuthToken())
                || autoStartCheckBox.isSelected() != state.autoStartServer
                || embeddedCheckBox.isSelected() != state.useEmbeddedBrowser
                || keepProcessCheckBox.isSelected() != state.keepDshRunningAfterProjectClose
                || !((DshUiTheme) themeCombo.getSelectedItem()).id.equals(state.uiTheme);
    }

    @Override
    public void apply() {
        DshSettingsState state = DshSettingsState.getInstance();
        DshProjectSettings ps = DshProjectSettings.getInstance(project);

        // ── 项目级：只影响当前项目 ──
        String typedUrl = serverUrlField.getText().trim();
        if (!typedUrl.equals(ps.serverUrl == null ? "" : ps.serverUrl)) {
            // 地址变了（自动↔手动切换）：旧的「已分配端口」不再适用，清掉让它重新挑
            ps.allocatedPort = 0;
        }
        ps.serverUrl = typedUrl;
        int typedPort = (Integer) startPortSpinner.getValue();
        if (typedPort != ps.startPort) {
            // 期望端口改了：下次启动重新挑（否则会一直粘着上次分配到的端口，改了也不生效）
            ps.allocatedPort = 0;
        }
        ps.startPort = typedPort;
        ps.workingDirectory = workingDirectoryField.getText().trim();
        ps.serverAuthToken = serverTokenField.getText().trim();

        // ── 应用级：所有项目共享 ──
        state.serverCommand = serverCommandField.getText().trim();
        state.runtimeMode = ((DshRuntimeMode) runtimeModeCombo.getSelectedItem()).id;
        state.runtimeLocation = ((DshRuntimeLocation) runtimeLocationCombo.getSelectedItem()).id;
        state.dshHome = dshHomeField.getText().trim();
        state.autoStartServer = autoStartCheckBox.isSelected();
        state.useEmbeddedBrowser = embeddedCheckBox.isSelected();
        state.keepDshRunningAfterProjectClose = keepProcessCheckBox.isSelected();
        state.uiTheme = ((DshUiTheme) themeCombo.getSelectedItem()).id;

        refreshEffectiveAddress();
        // 广播设置变化，让已打开的工具窗口重新应用（主题 / 背景浮层）
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(DshSettingsTopics.SETTINGS_TOPIC).onChanged();
    }

    @Override
    public void reset() {
        DshSettingsState state = DshSettingsState.getInstance();
        DshProjectSettings ps = DshProjectSettings.getInstance(project);
        serverUrlField.setText(ps.serverUrl == null ? "" : ps.serverUrl);
        startPortSpinner.setValue(ps.startPort);
        serverCommandField.setText(state.serverCommand);
        runtimeModeCombo.setSelectedItem(DshRuntimeMode.fromId(state.runtimeMode));
        runtimeLocationCombo.setSelectedItem(DshRuntimeLocation.fromId(state.runtimeLocation));
        workingDirectoryField.setText(ps.workingDirectory == null ? "" : ps.workingDirectory);
        dshHomeField.setText(state.dshHome);
        serverTokenField.setText(ps.normalizedServerAuthToken());
        autoStartCheckBox.setSelected(state.autoStartServer);
        embeddedCheckBox.setSelected(state.useEmbeddedBrowser);
        keepProcessCheckBox.setSelected(state.keepDshRunningAfterProjectClose);
        themeCombo.setSelectedItem(DshUiTheme.fromId(state.uiTheme));
        testResultLabel.setText("");
        testResultLabel.setHorizontalAlignment(SwingConstants.LEFT);

        pluginVersionLabel.setText(DshUtil.getInstalledPluginVersion());
        dshVersionLabel.setText("查询中…");
        dshVersionLabel.setForeground(JBColor.GRAY);
        // 设置页每次打开都重新探测 Node：用户可能刚在 IDE 运行期间装好
        nodeReport = null;
        refreshEffectiveAddress();
        refreshRuntimeInfo();
        refreshDshVersion();
    }

    @Override
    public void disposeUIResources() {
        root = null;
    }
}
