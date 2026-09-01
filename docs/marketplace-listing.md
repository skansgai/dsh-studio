# Marketplace 插件页文案（中英双语）

> 用途：粘贴到 [plugins.jetbrains.com](https://plugins.jetbrains.com) 插件管理页的
> **Getting Started** 与 **Description** 字段。
>
> - **Getting Started**：用「纯文本版」（市场该字段可能不渲染 Markdown）
> - **Description**：用「Markdown 版」（该字段支持 Markdown/HTML）
>
> 网页字段直接支持中文输入，无需转码。

---

## 1. Getting Started（纯文本版，推荐）

### English

```
Quick Start

1. Install: Settings -> Plugins -> Marketplace, search "DeepSeek Harness", install and restart the IDE.
2. Open: click the blue hexagon icon on the right tool-window bar, or go to Tools -> DeepSeek Harness.
3. Connect: it auto-connects if a dsh web server is already running (default http://127.0.0.1:3080). If not, click the Start (play) button — this requires Node.js 18+ and installs DeepSeek Harness automatically on first run.
4. Configure the model: inside the Harness UI open Settings -> Models and add your API key (e.g. a DeepSeek API key).
5. Go: create a session, pick a model, and start chatting / coding.

Everyday controls: Start / Stop server (toolbar), Refresh (page), Server Log tab (troubleshooting), Settings -> Tools -> DeepSeek Harness (IDE theme, port, working directory, DSH_HOME). Background image & opacity are set inside the Harness web UI (Settings -> 通用设置).

Requirements: Android Studio 2023.1+ / IntelliJ IDEA 2023.1+; Node.js 18+ for the automatic server start.
```

### 中文

```
快速上手

1. 安装：Settings -> Plugins -> Marketplace 搜索 "DeepSeek Harness"，安装后重启 IDE。
2. 打开：点击右侧工具窗口栏的蓝色六边形图标，或 Tools -> DeepSeek Harness。
3. 连接：若本机已运行 dsh web（默认 http://127.0.0.1:3080）会自动连接；否则点击 ▶ 启动（需 Node.js 18+，首次运行会自动安装 DeepSeek Harness）。
4. 配置模型：在 Harness 界面 Settings -> Models 填入你的 API Key（如 DeepSeek 官方 Key）。
5. 开用：新建会话、选择模型，开始对话/写代码。

常用功能：▶ 启动 / ⏹ 停止服务器（顶部工具栏）、↻ 刷新页面、Server Log 标签页查看日志、Settings -> Tools -> DeepSeek Harness 配置主题/端口/工作目录/DSH_HOME；背景图与透明度在 Harness 网页「通用设置」内设置。

环境要求：Android Studio 2023.1+ / IntelliJ IDEA 2023.1+；自动启动服务器需 Node.js 18+。
```

---

## 2. Description（Markdown 版，中英二选一或并列）

### English (Markdown)

```
### DeepSeek Harness integration

Open the **DeepSeek Harness** web UI right inside **Android Studio** / **IntelliJ IDEA**:

- Embedded browser tool window (JCEF) showing the Harness chat and tool calls
- One-click start / stop of the local `dsh web` server (Node.js / npx based, customizable command)
- Automatic server status detection with a colored status bar and live polling
- Server log panel for troubleshooting startup problems
- **Send code to Harness**: right-click in the editor to send the selection (or whole file) to a session
- **Status bar widget**: a colored server-state indicator; click to open the tool window
- **Recent sessions**: a search-style popup to jump to any Harness session
- **Headless tasks**: run a one-shot `dsh --profile headless` task from the IDE
- Open the same page in your system browser at any time
- Configurable URL / port / working directory (workspace root) / `DSH_HOME`

### Quick Start

1. **Install**: Settings → Plugins → Marketplace, search "DeepSeek Harness", install and restart the IDE.
2. **Open**: click the blue hexagon icon on the right tool-window bar, or go to **Tools → DeepSeek Harness**.
3. **Connect**: it auto-connects if a `dsh web` server is already running (default http://127.0.0.1:3080). If not, click ▶ to start one — this requires Node.js 18+ and installs DeepSeek Harness automatically on first run.
4. **Configure the model**: inside the Harness UI open **Settings → Models** and add your API key (e.g. a DeepSeek API key).
5. **Go**: create a session, pick a model, and start chatting / coding.

### Everyday controls

- ▶ Start / ⏹ Stop the server (toolbar on top)
- ↻ Refresh the page
- Server Log tab for troubleshooting
- Settings → Tools → DeepSeek Harness: theme, port, working directory, DSH_HOME

**Requirements**: Android Studio 2023.1+ / IntelliJ IDEA 2023.1+; Node.js 18+ is needed only for the automatic server start.

DeepSeek Harness is DeepSeek's open-source coding agent framework; this plugin is just an IDE entry point for it.
```

### 中文 (Markdown)

```
### DeepSeek Harness 集成

在 **Android Studio** / **IntelliJ IDEA** 中直接打开 **DeepSeek Harness** Web 界面：

- 内嵌浏览器工具窗口（JCEF），实时展示 Harness 对话与工具调用
- 一键启动 / 停止本地 `dsh web` 服务器（基于 Node.js / npx，可自定义命令）
- 服务器状态自动检测，状态栏彩色指示 + 定时刷新
- 服务器日志面板，方便排查问题
- **发送代码到 Harness**：编辑器右键把选中代码（或整个文件）发给会话
- **状态栏小部件**：彩色服务器状态指示，点击打开工具窗口
- **会话快速访问**：搜索式弹窗跳转到任意 Harness 会话
- **Headless 任务**：在 IDE 内运行一次性 `dsh --profile headless` 任务
- 可随时在系统浏览器中打开同一页面
- 可配置地址 / 端口 / 工作目录（workspace 根目录）/ `DSH_HOME`

### 快速上手

1. **安装**：Settings → Plugins → Marketplace 搜索 "DeepSeek Harness"，安装后重启 IDE。
2. **打开**：点击右侧工具窗口栏的蓝色六边形图标，或 **Tools → DeepSeek Harness**。
3. **连接**：若本机已运行 `dsh web`（默认 http://127.0.0.1:3080）会自动连接；否则点 ▶ 启动（需 Node.js 18+，首次运行自动安装 DeepSeek Harness）。
4. **配置模型**：在 Harness 界面 **Settings → Models** 填入你的 API Key（如 DeepSeek 官方 Key）。
5. **开用**：新建会话、选择模型，开始对话/写代码。

### 常用功能

- ▶ 启动 / ⏹ 停止服务器（顶部工具栏）
- ↻ 刷新页面
- Server Log 标签页查看日志
- Settings → Tools → DeepSeek Harness：主题、端口、工作目录、DSH_HOME

**环境要求**：Android Studio 2023.1+ / IntelliJ IDEA 2023.1+；仅自动启动服务器时需要 Node.js 18+。

DeepSeek Harness 是 DeepSeek 的开源编码智能体框架；本插件只是它的 IDE 入口。
```

---

## 3. Documentation URL（Contacts & Resources）

```
https://github.com/skansgai/dsh-studio
```

---

## 使用建议

| 字段 | 粘贴哪个 |
|---|---|
| Getting Started | 纯文本版（英文 或 中文，或两者都贴） |
| Description | Markdown 版（英文为主；想双语可先英文后中文） |
| Documentation URL | GitHub 仓库地址 |
