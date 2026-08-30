# 更新日志 / Changelog

本文件记录 DeepSeek Harness Studio 的版本变更。英文条目对应中文说明，保持双语一致。

## 0.3.0（2026-08-31）

### 新增功能 / New features
- **背景图半透明浮层**：设置中可指定一张本地图片，作为半透明覆盖层叠在 DeepSeek Harness 网页界面之上
  （通过往页面注入 `fixed` + `pointer-events:none` 的 CSS 浮层实现，鼠标点击不受影响）；
  浮层透明度可调（0–60%）。顶部状态条与“等待连接”空白页保留原有背景图。

### 内部实现 / Implementation
- 新增 `DshToolWindowPanel.injectBackgroundOverlay()`：把图片 base64 编码后以 data URI 注入页面 DOM。
- 新增 `DshSettingsTopics`（设置变化广播），替换原先的 `DshThemeTopics`。
- `DshSettingsState` 新增 `backgroundImageOpacity`；移除 `driveDshTheme` / `autoRestartAfterPluginChange`。

### 说明 / Notes
- 相比 0.3.0 初版，本版移除了“驱动 dsh 自身主题”与“插件市场（Plugins 标签页）”两项，设置更简洁。
- dsh 网页上的浮层属于 CSS 注入，不影响 dsh 自身功能；SPA 内部整页跳转时浮层会在加载后自动重新注入。

---

## 0.2.3（2026-08-30）

### 修复 / Fixes
- **修复 2023.1 的 IconManager 兼容性**：`IconManager.getIcon(String, ClassLoader)` 在
  IntelliJ IDEA 2023.1 (IU-231) 上不存在，导致 3 个 `Method not found` compatibility problems。
  改回 `IconManager.getIcon(String, Class<?>)`——该重载在 2023.1–2026.x 全版本存在；
  在新版平台上属于 deprecated API，仅为警告，不影响上架。
- 延续 0.2.2：JCEF 类访问全部通过 `DshJcefSupport` 反射完成，主插件字节码无
  `com.intellij.ui.jcef` 直接引用。

### 说明 / Notes
- 版本号升到 0.2.3 是因为 **0.2.2 已上传市场并被扫描出上述 Critical 问题**，
  Marketplace 拒绝重复上传相同版本号。

---

## 0.2.2（2026-08-30）

### 修复 / Fixes
- **修复 2023.1 市场的 Critical 兼容性**：把 `DshToolWindowPanel` 里对 `JBCefApp` / `JBCefBrowser`
  等 JCEF 类的**直接引用**全部抽到新类 `DshJcefSupport`，改为 `Class.forName(...)` + 反射调用。
  这样主插件 jar 内没有任何类的字节码包含 `com.intellij.ui.jcef` 引用，从而消除
  IntelliJ IDEA 2023.1.7 (IU-231.9423.9) 上因 optional 模块解析不到而产生的
  **3 个 compatibility problems**。
- 保留 optional 依赖 `<depends optional="true" config-file="dsh-jcef.xml">com.intellij.modules.jcef</depends>`：
  在 2026.x 平台该模块存在，JCEF 类可见；在 2023.1–2025.2 平台模块不存在，
  `DshJcefSupport.isSupported()` 通过反射判断后回退为说明面板。

### 说明 / Notes
- 版本号升到 0.2.2 是因为 **0.2.1 已上传市场并被扫描出 Critical 问题**，
  Marketplace 拒绝重复上传相同版本号。

---

## 0.2.1（2026-08-30）

兼容性修复版本，目标是让 JetBrains Marketplace 的自动验证全部通过。

### 修复 / Fixes
- **JCEF 依赖改为可选**：`<depends optional="true" config-file="dsh-jcef.xml">com.intellij.modules.jcef</depends>`。
  `com.intellij.modules.jcef` 这个模块只在 2026.x 平台存在，声明成强制依赖会让
  **2023.1–2025.2 全部报 `missing mandatory dependency` 而无法安装**。改为可选后：
  新平台正常加载 JCEF，旧平台跳过该依赖；不可用时工具窗口回退为说明面板。
- **消除 1 处 scheduled-for-removal API**：`settings` 里选背景图的 `FileTypeDescriptor`
  改为 `FileChooserDescriptorFactory.createSingleFileDescriptor().withFileFilter(Condition)`。
- **消除 3 处 deprecated API**：`StartServerAction` / `StopServerAction` / `OpenDshToolWindowAction`
  的 `IconManager.getIcon(String, Class)` 改为 `getIcon(String, ClassLoader)`。
- **消除 1 处配置缺陷**：可选依赖补 `config-file="dsh-jcef.xml"`（新增
  `src/main/resources/META-INF/dsh-jcef.xml`），否则市场扫描报
  `OptionalDependencyConfigFileNotSpecified`。

### 验证结果 / Verification
本地 pluginVerifier（IntelliJ IDEA 2026.2.1 / IU-262.9437.185）修复前报告：
`Compatible. 1 usage of scheduled for removal API and 3 usages of deprecated API. 1 plugin configuration defect`
—— 上述四类问题全部对应修复。

### 说明 / Notes
- 版本号升到 0.2.1 是因为 **JetBrains Marketplace 拒绝重复上传相同版本号**，
  0.2.0 已在审核队列中，只能以新版本号提交修复。

---

## 0.2.0（2026-08-30）

### 新增功能 / New features
- **发送代码到 Harness（Send Code to Harness）**：编辑器右键 `Send Code to Harness…`，把选中代码（或整个文件）连同可编辑指令发给 Harness 会话；服务器未运行会自动拉起，发送后自动打开工具窗口。
- **状态栏小部件（Status bar widget）**：窗口底部状态栏显示彩色 Harness 服务器状态（绿=已连接 / 橙=启动中 / 红=失败 / 灰=未启动），点击即打开工具窗口。
- **会话快速访问（Recent sessions）**：`Tools → DeepSeek Harness → Recent Harness Sessions…` 弹出搜索式会话列表，选中一项即复制 sessionId 并打开工具窗口。
- **Headless 任务（Headless task）**：`Tools → DeepSeek Harness → Run Headless Task…` 在 IDE 内直接运行一次性 `dsh --profile headless` 任务，输出进入 Server Log。

### 修复 / Fixes
- **JCEF 模块依赖缺失**：`plugin.xml` 补 `<depends>com.intellij.modules.jcef</depends>`，修复真实 IDE 中打开工具窗口时抛 `NoClassDefFoundError: com/intellij/ui/jcef/JBCefBrowser`。
- **发送代码“点了没反应”**：`DshServerManager` 启动看门狗改为定时 `probe()`，服务器就绪即置 `RUNNING`；`DshSendCodeAction` 异常捕获放宽到 `catch (Exception)` 并增加即时通知，消除静默失败。

### 构建变更 / Build changes
- 升级构建到 **IntelliJ Platform Gradle Plugin 2.18.1** + Gradle 9.7.1（原为 1.17.4 / 8.13）。
- 默认启动命令增加 `--no-open`，避免内嵌 JCEF 时又弹出系统浏览器。
- 兼容性 `since-build="231"` 不变，仍兼容 Android Studio 2023.1+ / IntelliJ IDEA 2023.1+。

### 已知限制 / Known limitations
- dsh Web 前端暂不支持会话深链，会话快速访问以“复制 ID + 打开工具窗口”方式跳转。
- 通过 API 创建的会话默认落在 dsh 的“未分组”（不绑定 workspace），功能不受影响。

---

## 0.1.x

早期版本提供：内嵌浏览器工具窗口（JCEF）、一键启停 `dsh web` 服务器、服务器状态自动检测、服务器日志面板、系统浏览器打开、基础配置项。详见 [README](README.zh.md)。
