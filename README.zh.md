# DeepSeek Harness Studio

[English](README.md) | 中文

在 **Android Studio** / **IntelliJ IDEA** 中直接打开 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) Web 界面的 IntelliJ Platform 插件。

DeepSeek Harness 是 DeepSeek 的开源编码智能体框架；`dsh web` 会在本机启动一个 Web 界面（默认 `http://127.0.0.1:3080`）。本插件把该界面嵌进 IDE 的右侧工具窗口，并负责服务器的启动 / 停止 / 状态检测，让你不必离开 IDE 即可使用 Harness。

[![在 JetBrains Marketplace 安装](https://img.shields.io/badge/在%20JetBrains%20Marketplace-安装-blue?style=flat-square)](https://plugins.jetbrains.com/plugin/33569-deepseek-harness-studio)

## 功能

- **内嵌浏览器工具窗口**（基于 JCEF）：工具窗口内直接显示 Harness Web 界面，实时查看对话、工具调用与产物。
- **一键启动 / 停止服务器**：工具栏 ▶ / ⏹ 按钮，通过 `npx` 拉起 `dsh web`（可自定义命令模板），停止时自动结束整个进程树。
- **状态检测**：定时健康探测，状态栏以颜色指示（绿=已连接 / 橙=启动中 / 红=失败 / 灰=未启动）；已存在外部运行的实例时会自动识别并直接连接（如：Harness 已在你终端里运行时）。
- **服务器日志面板**：实时展示 `dsh web` 的 stdout/stderr，方便排查启动失败、端口占用等问题。
- **系统浏览器打开**：工具栏按钮可在默认浏览器中打开同一地址。
- **发送代码到 Harness**：编辑器选中代码 → 右键 → `Send Code to Harness…`，把选区（或整个文件）连同可编辑的指令一并发给 Harness 会话；服务器未运行会自动拉起，发送后自动打开工具窗口。
- **状态栏小部件**：窗口底部状态栏显示彩色 Harness 服务器状态（绿=已连接 / 橙=启动中 / 红=失败 / 灰=未启动），点击即打开工具窗口。
- **会话快速访问**：`Tools → DeepSeek Harness → Recent Harness Sessions…` 弹出搜索式会话列表，选中一项即把 sessionId 复制到剪贴板并打开工具窗口。
- **Headless 任务**：`Tools → DeepSeek Harness → Run Headless Task…` 在 IDE 内直接跑一次性 `dsh --profile headless` 任务，输出进入 Server Log。
- **可配置**：地址、端口、启动命令、工作目录（workspace 根目录）、`DSH_HOME`、是否自动启动、是否使用内嵌浏览器。
- **换肤**：设置里可选「跟随 IDE / 浅色 / 深色」，工具窗口顶部状态条会随主题着色。
- **背景图浮层（在 dsh 网页「通用设置」内设置）**：打开 dsh 网页的「设置 → 通用设置」，面板内会自动出现「背景图片 / 浮层透明度」控制项，可选本地图片并调节半透明程度（0–60%，默认 15%）；图片以半透明覆盖层叠在 dsh 界面之上（CSS 注入、不挡点击），设置由插件持久化，刷新或重启 IDE 后保留。

## 兼容性

- Android Studio 2023.1+（platform 231+）或 IntelliJ IDEA 2023.1+；不设上限，兼容后续版本。
- 自动启动服务器需要本机安装 **Node.js 18+**（使用 `npx`）。
- 内嵌浏览器需要 IDE 启用 JCEF（现代版本默认开启；若不可用，插件会自动回退为“在系统浏览器打开”）。

## 安装

**方式一：从 Marketplace 安装（推荐，已发布）**
`File → Settings → Plugins → Marketplace` → 搜索 **DeepSeek Harness** → Install → 重启 IDE。

**方式二：本地安装**
1. 构建插件（见下），或直接使用 `build/distributions/` 中产出的 zip。
2. Android Studio 中：`File → Settings → Plugins → ⚙ → Install Plugin from Disk…`，选择 zip 文件，重启 IDE。
3. 菜单 `Tools → DeepSeek Harness`，或右侧工具窗口栏点击 “DeepSeek Harness” 图标。

## 使用

### 快速开始（三步）

1. **打开**：`Tools → DeepSeek Harness`，或点击右侧工具窗口栏的蓝色六边形图标。
2. **连接/启动**：若本机已运行 `dsh web`（默认 `http://127.0.0.1:3080`）会自动连接；否则点工具栏 **▶** 自动启动（需 Node.js 18+，首次运行会自动安装 DeepSeek Harness）。
3. **配置模型并开用**：在 Harness 界面 `Settings → Models` 填入 API Key（如 DeepSeek 官方 Key），新建会话、选模型，开始对话/写代码。

| 操作 | 说明 |
|---|---|
| 打开工具窗口 | `Tools → DeepSeek Harness`，或右侧工具窗口栏图标 |
| 连接服务器 | 打开工具窗口即自动探测 `服务器地址`（默认 `http://127.0.0.1:3080`）；若已配置“自动启动”，未运行时自动拉起 `dsh web` |
| 手动启动 | 工具栏 ▶ 按钮（灰色表示当前无需启动：已在运行或正在启动） |
| 停止服务器 | 工具栏 ⏹ 按钮（仅对本插件拉起的进程生效；外部实例请在其终端停止） |
| 刷新 | 工具栏 ↻ 按钮：重新探测状态并刷新页面 |
| 系统浏览器打开 | 工具栏 🌐 按钮 |
| 查看日志 | 工具窗口底部 “Server Log” 标签页 |

### 0.2.0 新功能使用方式

- **发送代码到 Harness**：在编辑器里选中一段代码（不选中则发送整个文件），右键 → `DeepSeek Harness → Send Code to Harness…`。弹窗里可编辑附带给 agent 的指令，点 OK 后插件会确保服务器在运行、把代码作为 prompt 提交到 Harness 会话，并自动打开工具窗口。首次使用会触发 `npx --yes @deepseek-ai/dsh` 下载（约 284MB / 14 分钟），期间状态栏显示“启动中”属正常。

### 0.3.0 新功能使用方式

- **背景图浮层（在 dsh 网页「通用设置」内）**：打开 DeepSeek Harness 工具窗口、连上服务器后，进入 dsh 网页的
  「设置 → 通用设置」，面板内会自动出现「DeepSeek Harness Studio」卡片，包含「背景图片」选择（选本地图 / 「移除背景」清除）
  与「浮层透明度」滑块（0–60%，默认 15%）。图片会作为半透明覆盖层叠在 dsh 网页界面之上（鼠标点击不受影响）。
  设置由插件端持久化，刷新或重启 IDE 后保留。详见下方「如何设置背景图」。
- **换肤**：`Settings → Tools → DeepSeek Harness → 界面主题` 选「跟随 IDE / 浅色 / 深色」，工具窗口顶部状态条随主题着色。
- **状态栏小部件**：窗口底部状态栏右侧有一个 DSH 状态控件，初态“未连接”。点击它可打开 Harness 工具窗口；服务器就绪后变为“运行中”（绿色）。
- **会话快速访问**：`Tools → DeepSeek Harness → Recent Harness Sessions…` 弹出当前会话列表，选中一项会把该会话的 sessionId 复制到剪贴板并打开工具窗口（注：dsh Web 前端暂不支持会话深链，因此以“复制 ID + 打开工具窗口”方式跳转）。
- **Headless 任务**：`Tools → DeepSeek Harness → Run Headless Task…` 输入任务描述，插件以 `dsh --profile headless "任务"` 方式在后台运行，输出实时写入 Server Log。

### 如何设置背景图

背景图在 **dsh 网页自身**的「通用设置」里设置（不是 IntelliJ 的插件设置页）：

1. 打开 DeepSeek Harness 工具窗口并连上服务器（见上方「快速开始」）。
2. 在 dsh 网页里点右上角「设置」（齿轮 ⚙）打开设置弹窗。
3. 在左侧菜单点 **通用设置**。
4. 在面板里找到「DeepSeek Harness Studio」卡片，包含两项：
   - **背景图片**：点「选择图片」从本机选一张图片；点「移除背景」可清除。
   - **浮层透明度**：拖动滑块调节半透明程度（0–60%，默认 15%；数值越大图越淡、界面文字越清晰）。
5. 选好后，背景图会立即以半透明覆盖层显示在 dsh 界面之上，鼠标点击不受影响。

**持久化说明**：背景图与透明度由插件端保存（跟随 IDE 配置），所以：

- 点工具栏 **↻ 刷新** 或重启 IDE 后，背景图依然在，无需重新设置；
- 想关闭背景图，进入同一张卡片点「移除背景」即可。

> 提示：这些控制项是本插件注入进 dsh 网页「通用设置」面板的。若 dsh 升级后面板结构变化导致不显示，请更新本插件版本。

### 设置（Settings → Tools → DeepSeek Harness）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 服务器地址 | `http://127.0.0.1:3080` | 连接 / 打开的 Harness 地址 |
| 自动启动端口 | `3080` | 自动启动时传给 `--port` 的端口 |
| 启动命令模板 | 见下 | 留空使用默认模板；支持占位符 `{host} {port} {workdir} {dshHome}` |
| 工作目录 | 当前项目目录 | 作为 Harness 的 workspace 根目录（`dsh web` 的运行目录） |
| DSH_HOME | `~/.dsh` | 留空则用默认（也可用环境变量覆盖） |
| 自动启动服务器 | 开 | 打开工具窗口时若服务器未运行则自动启动 |
| 使用内嵌浏览器 | 开 | 关闭后仅提供系统浏览器打开（JCEF 不可用时自动回退） |

默认启动命令模板：

```text
npx --yes @deepseek-ai/dsh web --host {host} --port {port}
```

首次启动时 `dsh` 会自动初始化 `web` profile（数据存放在 `~/.dsh`），之后启动即秒连。

## 构建

环境要求：JDK 17+（本机可用 JDK 22，构建脚本以 `--release 17` 编译）、Gradle（仓库自带 wrapper，首次构建会自动下载依赖与 IDE 发行版）。

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

产物：`build/distributions/dsh-studio-0.2.0.zip`（未签名）/ `dsh-studio-0.2.0-signed.zip`（已签名，上传市场用这个）。

### 构建目标选择

插件针对 **IntelliJ Community**（IC）构建即可在 Android Studio 中运行（AS 与 IDEA 共用 IntelliJ Platform，本插件只使用平台 API）。在 `gradle.properties` 中可切换：

```properties
# 默认：下载 IntelliJ Community 2024.2.3 用于构建（体积最小）
dsh.ide.type=IC
dsh.ide.version=2024.2.3

# 方式一：直接针对 Android Studio 构建（下载 Android Studio 发行版，体积较大）
# dsh.ide.type=android-studio
# dsh.ide.version=2024.2.1.12   # 换成你本机 AS 的版本号（Help → About）

# 方式二（推荐，零下载）：使用本机已安装的 IDE
# dsh.ide.localPath=C\:/Program Files/Android/Android Studio
```

构建脚本还会自动识别 `C:\Program Files\Android\Android Studio`（已安装时自动使用，无需配置）。

### 在 IDE 中调试插件

用 IntelliJ IDEA 打开本项目（`build.gradle.kts`），等待 Gradle 同步后运行 `runIde` 任务即可启动一个带本插件的沙箱 IDE。在 Android Studio 中打开项目时，请先在 `gradle.properties` 里配置 `dsh.ide.localPath` 指向你本机的 Android Studio，再运行 `runIde`。

### 校验与测试

```bash
gradlew.bat verifyPlugin   # 插件描述符校验
gradlew.bat test           # 单元测试（DshUtil 纯逻辑）
```

### 关于 IntelliJ Gradle Plugin 版本

本项目使用 **IntelliJ Platform Gradle Plugin 2.18.1**（官方 2.x 线），构建目标为 IntelliJ Community 2024.2.3，兼容 231+ 平台（含 Android Studio 2023.1+ 与 IDEA 2023.1+）。2.x 要求 Gradle 9.0.0+，仓库自带 wrapper 已升级到 Gradle 9.7.1（国内可改用腾讯云镜像加速下载）。

插件描述符 `plugin.xml` 已直接声明 `since-build="231"`，不依赖 Gradle 注入；如需面向更高平台，修改 `build.gradle.kts` 里的版本与 `pluginConfiguration.ideaVersion.sinceBuild` 即可。

## JCEF 说明

内嵌浏览器基于 IDE 自带的 JCEF（JetBrains Chromium Embedded Framework）。若工具窗口提示 “内嵌浏览器不可用”，可按以下方式启用：

1. `Help → Edit Custom VM Options…`，添加 `-Dide.browser.jcef.enabled=true`，重启 IDE；
2. 或直接在设置中关闭“使用内嵌浏览器”，改用系统浏览器打开。

## 常见问题

- **启动失败 / 状态红色**：打开 “Server Log” 标签页查看 `dsh web` 输出；插件也会弹出通知提示。常见原因：Node.js 未安装（点击 ▶ 时会直接弹窗提示并给出下载地址）、端口被占用（可改“自动启动端口”）、`npx` 不在 PATH、网络原因导致首次下载 `@deepseek-ai/dsh` 失败。
- **提示“无法停止外部服务器”**：当前地址上的实例不是你通过本插件启动的（比如 Harness 已在终端里运行）。直接在对应终端停止即可。
- **多个项目同时自动启动**：同一端口只允许一个实例；建议只在一个项目里启用“自动启动”，或为不同项目配置不同端口。
- **连接局域网 / 远程实例**：把“服务器地址”改为目标地址即可连接；若 Harness 服务端未把该来源加入受信列表（`--trusted-host`），界面中的 `/api` 调用可能被拒绝，需在启动 Harness 时配置。

## 目录结构

```text
src/main/java/com/deepseek/dshstudio/
├── DshStudioConstants.java          # 常量
├── actions/                         # 菜单与工具栏动作
├── server/                          # 服务器进程管理、状态机、日志、消息总线
├── settings/                        # 设置存储（PersistentStateComponent）与设置页
├── ui/                              # 工具窗口（JCEF 内嵌浏览器 + 状态栏 + 日志）
└── util/                            # 平台无关工具（健康探测、命令解析、进程清理）
src/main/resources/
├── META-INF/plugin.xml              # 插件描述符
└── icons/                           # 图标（SVG）
```

## 更新日志

完整版本变更（新增功能、修复、构建变更）见 [CHANGELOG.md](CHANGELOG.md)。

## 发布到插件市场

想把插件发布到 JetBrains 插件市场（plugins.jetbrains.com）？完整流程（账号注册、证书生成、签名、上传、审核）见 **[PUBLISHING.md](PUBLISHING.md)（英文）** / **[PUBLISHING.zh.md](PUBLISHING.zh.md)（中文）**。

## License

[MIT](LICENSE)
