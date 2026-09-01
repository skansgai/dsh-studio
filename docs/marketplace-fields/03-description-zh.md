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

### 个性化（0.3.0）

- 在 Harness 网页 **设置 → 通用设置** 内直接设置半透明背景图与透明度，重启/刷新后保留。
- 顶部菜单栏自动半透明，背景图透出，与 IDE 视觉统一。

### 快速上手

1. **安装**：Settings → Plugins → Marketplace 搜索 "DeepSeek Harness"，安装后重启 IDE。
2. **打开**：点击右侧工具窗口栏的蓝色六边形图标，或 **Tools → DeepSeek Harness**。
3. **连接**：若本机已运行 `dsh web`（默认 http://127.0.0.1:3080）会自动连接；否则点 ▶ 启动（需 Node.js 18+，首次运行自动安装 DeepSeek Harness）。
4. **配置模型**：在 Harness 界面 **Settings → Models** 填入你的 API Key（如 DeepSeek 官方 Key）。
5. **开用**：新建会话、选择模型，开始对话/写代码。

### 设置

- **Tools → DeepSeek Harness**：IDE 主题、端口、工作目录、`DSH_HOME`。
- **Harness 网页 → 通用设置**：背景图与浮层透明度（0.3.0）。

**环境要求**：Android Studio 2023.1+ / IntelliJ IDEA 2023.1+；仅自动启动服务器时需要 Node.js 18+。

DeepSeek Harness 是 DeepSeek 的开源编码智能体框架；本插件只是它的 IDE 入口。
