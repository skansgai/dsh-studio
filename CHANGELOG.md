# 更新日志 / Changelog

本文件记录 DeepSeek Harness Studio 的版本变更。英文条目对应中文说明，保持双语一致。

## 0.4.3（2026-09-15）

### 修复 / Fixes
- **背景图设置第一次打开不显示 / Background setting missing on first open**：注入到 dsh 网页「通用设置」面板里的背景图卡片，此前用「页面里同时出现『外观』和『语言』两行」来判断是否停在通用设置页。这两行由两个独立懒加载的客户端插件提供（`dsh-client-ui-theme` 提供「外观」、`dsh-client-locale` 提供「语言」），首次打开设置时它们还没注册，判定因此为假、卡片不注入 —— 切一次语言触发整页重渲染后才出现。现在改为判断设置导航里高亮的是不是「通用设置」（导航由设置外壳自己渲染，不依赖插件加载）。
- 新增 `MutationObserver`：设置面板一渲染出来就注入卡片，不再依赖最多 2 秒一次的轮询；轮询仍保留作兜底。
- 功能行尚未加载时，卡片退到设置内容区末尾显示，而不是完全不出现；并避免 React 重绘后产生重复卡片。
- 设置弹窗识别改为「可见 + 带设置内容标记」，不再盲取页面上第一个 `role="dialog"`，避免被其它浮层 / 提示节点干扰。
- 单次注入失败不再可能中断轮询（此前若首轮 `ensure()` 抛异常，`setInterval` 注册不上，浮层会一直不出现）。
- 卡片注入失败时会在工具窗口日志里给出原因（`no-settings-dialog` / `settings-page-mismatch` / `no-anchor` 等），便于反馈定位。

---

## 0.4.2（2026-09-15）

### 变更 / Changes
- **不再内置运行时，插件包回到约 115KB**：0.4.0 起把一份裁剪过的 dsh（含全部 Node 依赖）打进插件包，换来「装上即用、不用等 npx 下载十几分钟」。但这带来几个持续代价：每次小版本发布都要全量重下 80+MB、5 个平台里 4 份对用户是浪费、还把第三方原生二进制带进了插件包的供应链 / 安全审查面。0.4.2 起插件包不再包含任何运行时。
- **首次启动改为下载当前平台包**：复用已有的热更新链路——检测到本地没有 dsh 时，从本仓库的 GitHub Release 拉取当前平台约 41MB 的包，sha256 校验通过后再原子解包安装并启动。原始问题（284MB / 十几分钟的 npx 拉取）照样解决，首次体验只是「等一次 41MB 下载」；插件包体积、分发与每次小版本发布的下载成本都不受影响。
- **「运行时来源」去掉「仅内置运行时」选项**：保留「自动（优先已下载运行时，回退系统 dsh）」与「仅系统 dsh（npx）」。
- 访问不了 GitHub Release 的极少数环境，可在 Release 页手动下载「全平台离线包」导入，不必让所有人都背 80MB。

### 说明 / Notes
- 内置 dsh 运行时的设计文档（docs/design-bundled-runtime.md）已标记过时，仅供历史参考。
- 运行时热更新仍走本仓库的 GitHub Release，由 `.github/workflows/runtime-release.yml` 定时构建；该工作流现额外产出一份「全平台离线包」作为可选资产。

---

## 0.4.0（2026-09-15）

### 新增功能 / New features
- **内置 dsh 运行时，开箱即用**：插件自带裁剪过的 dsh（含全部 Node 依赖），首次使用时在本地解包即可运行，
  不再需要 `npx --yes @deepseek-ai/dsh` 现下 284MB、等十几分钟。一次打包覆盖
  Windows x64 / macOS（Intel、Apple Silicon）/ Linux（x64、arm64）五个平台。
  解包位置默认在系统临时目录（用户目录与项目目录在本机被 DLP 透明加密，写 1.8 万个小文件要慢约 200 倍），
  可在「设置 → 工具 → DeepSeek Harness → 运行时」里改。
- **运行时热更新**：设置页可检查 dsh 新版本，发现后**先问再下**（约 41MB，只含本机平台），
  sha256 校验通过后才原子安装，下次启动服务器生效。想回滚把「运行时来源」改成「仅内置运行时」即可，
  不需要额外机制。安装中途断网或被打断不会留下半个版本目录骗过下一次启动。
- **Node.js 探测与引导**：启动前检测 Node.js，低于依赖包 `engines` 声明的最低版本时给出提示与安装指引
  （只提示、不拦截）。

### 构建 / Build
- **打包时校验明文**：`bundleDshRuntime` 会逐条检查运行时包内的文件是否被本机 DLP 透明加密
  （密文文件头含 `E-SafeNet`），命中就删掉产物并让构建失败 —— 这类包发到用户机器上 node 读到的是密文，
  原生模块加载不了。修复前实测有 36 个密文条目。
- **构建工作目录移出加密范围**：改到系统临时目录（可用 `-Pdsh.runtime.work=` 覆盖）。
  同一个 1.8 万文件的打包任务，从 90+ 分钟降到 **3 分 58 秒**。
- `overlay.js` 改名为 `overlay.js.txt`，避免被透明加密后误提交成密文；并加入 pre-commit 钩子拦截密文提交。
  （该文件内容未变，仅改名。）

### 说明 / Notes
- **插件体积从 115KB 变为约 83MB**（含 5 平台内置运行时）。JetBrains Marketplace 的单包上限是 400MB。
- 运行时热更新走本仓库的 GitHub Release，由 `.github/workflows/runtime-release.yml` 定时构建。

---

## 0.3.1（2026-09-10）

### 修复 / Fixes
- **适配 dsh 0.1.2-rc.1 的启动令牌鉴权**：新版 dsh 的首页必须带 `?token=` 才能打开，直接访问会被拒绝
  （401 `dsh web authentication required`）。插件现在会从自己拉起的 dsh 进程输出里自动捕获 token 并拼到访问地址上；
  若复用的是外部已在运行的实例（拿不到 token），会明确提示，并引导你从启动它的终端复制带 token 的地址
  填进「设置 → 服务器地址」。
- **市场/IDE 中插件图标不显示**：将 `pluginIcon.svg` 从 JAR 根目录移到规范的 `META-INF/` 位置，并改为 40×40、
  无渐变（纯色）的扁平写法，规避市场 SVG 清洗器对 `<defs>`/渐变与 `width/height=240` 的丢弃。
- **残留的旧 dsh 实例导致页面报错**：`npx --yes @deepseek-ai/dsh` 会静默升级，升级前启动的进程却仍在跑，
  它继续按旧结构产出 boot manifest、却从已升级的包里读新 bundle，页面报
  `client-modules: boot manifest batches must be an array`。现在启动若发现端口被非本插件的实例占用，
  会给出提示并提供「结束占用进程并重启」。

### 新增功能 / New features
- **设置页新增「关于」区块**：显示插件版本与 DeepSeek Harness (dsh) 的 npm 最新版本，并提供「检查更新」按钮，
  一键比对插件（JetBrains Marketplace）与 dsh（npm）是否有新版本并给出升级指引。
  （注：版本信息仅在 IDE 设置页 Settings → Tools → DeepSeek Harness 中提供。）

### 说明 / Notes
- 0.3.2 / 0.3.3 只存在于本地 git，从未发布；发布版本号直接沿用 0.3.1。

---

## 0.3.0（2026-08-31）

### 新增功能 / New features
- **背景图 + 透明度控制项注入进 dsh 网页「通用设置」面板**：在 dsh 网页「设置 → 通用设置」面板内自动插入
  「DeepSeek Harness Studio」卡片（背景图片选择 / 浮层透明度滑块，0–60% 默认 15%），不再是右下角悬浮齿轮按钮；
  背景以 `fixed` + `pointer-events:none` 的 CSS 半透明浮层叠在 dsh 界面之上（鼠标点击不受影响）。
- IntelliJ 设置页（Settings → Tools → DeepSeek Harness）不再包含背景图/透明度项，仅保留「通用设置 / 服务器 / 启动选项」分区与界面主题。

### 修复 / Fixes
- **刷新后背景图丢失**：根因为 `reload()` 异步而注入脚本同步打在了正在卸载的旧页面上；改为挂 `CefLoadHandler`
  在页面每次加载完成时自动重注入，并加 500/1500/3000ms 延迟兜底。背景图改由插件端持久化（落盘 `<config>/dshstudio/background.*`，
  设置只存路径），刷新或重启 IDE 后保留。
- **「移除背景」无效**：原逻辑忽略清空回传，刷新后旧图复活；改为区分「字段不存在」与「空串=清空」，空串真正清除。
- 图片经 `CefDisplayHandler` 回传桥（拦截 `DSHSTUDIO_SYNC:` 前缀的 console.log）回传插件并持久化；JCEF 不可用时静默降级。

### 内部实现 / Implementation
- 新增资源脚本 `src/main/resources/dsh/overlay.js`：注入「通用设置」浮层 + 背景浮层；`DshToolWindowPanel` 通过类加载器读取并执行。
- 新增 `DshSettingsTopics`（设置变化广播），替换原先的 `DshThemeTopics`。
- `DshSettingsState` 保留 `backgroundImagePath` / `backgroundImageOpacity` 作为首次种子（旧 IntelliJ 设置值可带过）。

### 说明 / Notes
- 相比 0.3.0 初版，本版移除了“驱动 dsh 自身主题”与“插件市场（Plugins 标签页）”两项，设置更简洁。
- dsh 网页上的浮层属于 CSS 注入，不影响 dsh 自身功能；SPA 内部整页跳转时浮层与面板会定时自动重新注入。

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
