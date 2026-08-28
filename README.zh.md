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
- **可配置**：地址、端口、启动命令、工作目录（workspace 根目录）、`DSH_HOME`、是否自动启动、是否使用内嵌浏览器。

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

产物：`build/distributions/dsh-studio-0.1.0.zip`。

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

本项目使用 IntelliJ Gradle Plugin **1.17.4**（稳定、已验证）。构建时它会提示 “1.x does not support 242+”——这是提示性警告，1.17.4 针对 2024.2+ 平台（含 Android Studio 2024.2）仍可正常构建运行。

如果你的 Android Studio 更新（如 2025.x，platform 251+）且构建报错，可升级到官方的 **IntelliJ Platform Gradle Plugin 2.x**，把 `build.gradle.kts` 迁移为：

```kotlin
plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 二选一：
        // androidStudio("2024.2.1.12")   // 直接针对 Android Studio
        intellijIdeaCommunity("2024.2.3") // 或 IntelliJ Community（体积小）
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        ideaVersion { sinceBuild = "231" }
    }
    buildSearchableOptions = false
}
```

其余源码、`plugin.xml` 与图标无需改动（`plugin.xml` 已直接声明 `since-build="231"`，不依赖 Gradle 注入）。

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

## 发布到插件市场

想把插件发布到 JetBrains 插件市场（plugins.jetbrains.com）？完整流程（账号注册、证书生成、签名、上传、审核）见 **[PUBLISHING.md](PUBLISHING.md)（英文）** / **[PUBLISHING.zh.md](PUBLISHING.zh.md)（中文）**。

## License

[MIT](LICENSE)
