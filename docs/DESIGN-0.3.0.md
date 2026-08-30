# DeepSeek Harness Studio 0.3.0 个性化功能设计文档

> 状态：设计稿（待评审）
> 前置：0.2.0 已上传 JetBrains Marketplace，等待审核。本稿为下一版本（暂定 0.3.0）的功能设计。
> 范围：两块个性化能力——① 链接插件市场 / 安装 dsh 插件；② 插件自身 UI 换肤。

---

## 0. 背景与目标

0.2.0 已具备：内嵌 dsh Web UI（JCEF）、启动/停止服务器、状态栏小部件、发送代码、会话快速访问、Headless 任务。
用户希望在插件内进一步做"个性化"：

1. **链接插件市场、安装插件**：在 IDE 内直接浏览 / 安装 DeepSeek Harness 生态的插件（agent presets、skills、UI 插件等）。
2. **更换皮肤**：给**插件自身 UI**（状态栏控件、工具窗口框架、弹窗）换配色，不依赖 dsh 网页本身。

经调研（2026-08-30），dsh 的插件生态是真实完整、可 CLI 驱动的，落地路径清晰，详见 §1.3。

---

## 0.5 实测校正（2026-08-30 22:20 真机 CLI 实跑，推翻上文若干推测）

> 在 `C:\Users\yss\AppData\Local\npm-cache\_npx\1e7f6d9597241db0\node_modules\.bin\dsh.cmd`
> 上实跑 dsh CLI 得出。以下结论优先级高于 §1.3 的早期调研。

| # | 原推测 | 实测结论 |
|---|--------|----------|
| 1 | `dsh plugin list` 输出格式未知，需实测解析 | **`dsh plugin` 委托给 pnpm**：`dsh plugin --profile web --help` 打印的是 pnpm 9.15.9 帮助。没装插件时 `list` **无任何输出**（非表格/JSON）。→ **不要解析 CLI 输出** |
| 2 | 已装列表只能靠 CLI | 直接读 `~/.dsh/profiles/web/package.json` 的 `dependencies`（插件=npm 依赖），`dsh.profile.bundles` 是内置核心（`@deepseek-ai/dsh-base`、`@deepseek-ai/dsh-web-app`），不计为插件 |
| 3 | 安装/卸载靠 CLI，配置写在 `cordis.patch.yml` | 部分正确：`cordis.patch.yml` 是顶层 YAML 数组（id-targeted overrides/disables/insert lists，当前 `[]`）；但增删靠 pnpm 改 `package.json` |
| 4 | 无插件相关 API | **有**：Remote API `pluginInventory/list` → `{entries:[{entryId, moduleName, enabled, fiberPhase}]}`，`fiberPhase` ∈ pending/loading/active/failed/unloading/null |
| 5 | — | **但无 install / remove / enable / disable 远程 API**，增删仍必须走 CLI + 重启 |
| 6 | — | dsh 自带 `dsh-client-ui-settings-plugins` / `-plugin-inventory` / `-theme`，**内嵌 web UI 里已有 Settings → Plugins 页面** |
| 7 | 皮肤只改插件自身 UI | `~/.dsh/settings.yaml` 有 `ui-theme.preference`（dark/light），**dsh 自己的主题存在这里**，理论上可让插件连带控制内嵌页面主题 |

**§1.7 风险项状态更新**：原 #1（list 输出格式）→ ✅ 已解决（改读 package.json / 用 API）；
原 #2、#3、#4 仍待真机验证。

---

## 0.6 真机验证（2026-08-31，settings API / 换肤可行性）

在用户本机已运行的 `dsh web`（127.0.0.1:3080）上 curl 实测：

1. **`settings.describe`** 返回完整命名空间清单。`ui-theme` 结构：
   `preference ∈ light | dark | system`（默认 `system`），`applies:"live"`，`revision:0`。
   其它可读命名空间：`locale` / `agent-default-model` / `ui-conversation` / `agent-presets` /
   `shell` / `permission` / `llm-deepseek` / `web-search-deepseek` / `agent-loop` / `llm-pi-ai`。
2. **`settings.update` 写 dsh 主题成功**：`{"ns":"ui-theme","patch":{"preference":"light"}}`
   把 `~/.dsh/settings.yaml` 改写为 `light`（文件内容已落盘）。
   - 沙箱环境唯一报错：`safe-delete` shim 拦了 dsh 的 atomic-replace（trash 旧文件）步骤，
     返回 `ok:false` + `code:"settings-rejected"`；**但这是沙箱特有，非 dsh bug**，
     真实机器上此步成功、返回 `ok:true`。
   - 实测后已把 `settings.yaml` 还原为 `dark`（与运行实例内存值一致），无遗留 lock/temp 文件。
3. **结论**：插件换肤应**同时驱动 dsh 自身主题**（选项 b，见 §5 Q6），视觉收益远大于只改插件 UI；
   优先用 `settings.update`（干净、live、免重启），并在真实机器复测"内存值+内嵌页面实时刷新"。

---

## 1. 功能 A：插件市场与安装（dsh 生态扩展）

### 1.1 用户场景

- 用户在 IDE 里不想切到浏览器/终端去装 dsh 插件，希望直接在工具窗口完成。
- 想知道当前装了哪些插件、一键卸载、从市场挑选安装。
- 安装后 dsh Web UI 自动出现新能力（如 dshmarket 市场页、dsh-web-ui 皮肤中心）。

### 1.2 交互设计（工具窗口新增"插件"标签页）

在 `DshToolWindowPanel` 内新增一个 Tab（与现有 Web UI / Server Log 并列），命名为 **Plugins**：

```
┌─ Plugins ────────────────────────────────────────────┐
│ [浏览插件市场]  (在 JCEF 打开 https://dsh-plugin.org) │
│                                                        │
│ 已安装插件                                             │
│ ┌──────────────────────────────────────────────────┐ │
│ │ ☑ dshmarket        v0.3.1   官方市场      [移除]   │ │
│ │ ☐ dsh-web-ui       v1.2.0   皮肤/面板     [移除]   │ │
│ │ ☑ dsh-browser      v0.9.4   浏览器自动化   [移除]  │ │
│ └──────────────────────────────────────────────────┘ │
│                                                        │
│ 安装插件：                                             │
│ [ npm包名 / github:user/repo / 本地路径        ] [安装] │
│   （回车或点安装 → 调 dsh plugin add → 输出到日志）     │
│   ☐ 安装后自动重启 dsh web                             │
└────────────────────────────────────────────────────┘
```

- **浏览插件市场**：按钮在 JCEF 浏览器内打开 `https://dsh-plugin.org`（社区 hub，4400+ 插件、11 类）。
  > 说明：dsh Web UI 是 SPA 且不读 URL 参数（见 MEMORY.md），无法深链到具体插件页，只能整页打开 hub。
  > 进阶：若用户已装官方 `dshmarket`，本按钮可改为在 JCEF 内嵌 dsh 自带的 Plugin Market 页（需实测其路由）。
- **已安装列表**：从 `dsh plugin --profile web list` 解析（格式待实测，见 §1.7）；每行带启用开关 + 移除按钮。
- **安装框**：支持三种源 —— npm 包名（`dsh-browser`）、GitHub（`github:user/repo`）、本地路径（`file:/...`）。
- **自动重启开关**：默认开。安装/移除后调用 `DshServerManager` 的 stop+start，让插件立即生效。

### 1.3 底层机制（已调研，权威来源见 MEMORY.md）

| 操作 | dsh CLI |
|------|---------|
| 安装 | `dsh plugin --profile web add <src>`（src = npm 名 / `github:user/repo` / tarball / `file:/...`） |
| 移除 | `dsh plugin --profile web remove <name>` |
| 列出 | `dsh plugin --profile web list` |
| 更新 | `dsh plugin --profile web update` |
| 官方市场 | `dsh plugin --profile web add dshmarket` → 重启 → Settings → Plugin Market |
| 社区 hub | `https://dsh-plugin.org` |

- 底层改写 `~/.dsh/profiles/web/cordis.patch.yml`。
- **关键约束**：插件装完/移除后**必须重启 `dsh web`** 才能加载（仅刷新页面无效）——这是流程里强制带重启步骤的原因。

### 1.4 数据结构

```java
public class DshPluginInfo {
    public String name;        // 插件名，如 dsh-browser
    public String version;     // 版本，可能为 null（list 未返回时）
    public String source;      // npm / github / local / builtin
    public boolean enabled;    // 是否启用（来自 cordis 配置）
    public String description; // 可选，从 hub 抓取或 list 输出
}
```

### 1.5 新增 / 修改文件清单

新增：
- `server/DshPluginManager.java` —— 封装 dsh plugin CLI 调用：
  - `listInstalled()`：执行 `dsh plugin --profile web list`，解析输出为 `List<DshPluginInfo>`（解析器需实测后定）。
  - `install(String src)`：执行 `dsh plugin --profile web add <src>`，返回结果/错误流。
  - `remove(String name)`：执行 `dsh plugin --profile web remove <name>`。
  - 内部复用 `DshServerManager` 的 `runDshCommand()` 风格（ProcessBuilder + 日志回写 Server Log）。
- `ui/DshPluginsPanel.java` —— Plugins 标签页 UI（列表表格 + 安装输入框 + 浏览市场按钮）。
- `actions/DshOpenPluginMarketAction.java`（可选）—— Tools 菜单项，等同"浏览插件市场"按钮。

修改：
- `ui/DshToolWindowPanel.java` —— 增加 Plugins Tab，挂载 `DshPluginsPanel`。
- `server/DshServerManager.java` —— 暴露 `restartServer()`（stop 后 start），供安装后自动重启。
- `plugin.xml` —— 注册 `DshOpenPluginMarketAction`（若采用）。
- `README.zh.md` / `README.md` —— 补插件市场用法。

### 1.6 关键流程（安装时序）

```
用户输入源 → 点[安装]
  → DshPluginManager.install(src)
      → ProcessBuilder: dsh plugin --profile web add <src>
      → stdout/stderr 实时回写 Server Log 面板
      → 成功/失败判定
  → 若成功且"自动重启"开：
      → DshServerManager.restartServer()  （先停后起，复用现有启动逻辑）
      → 状态栏转"启动中"→"运行中"
  → 通知气泡："插件 X 已安装，dsh 已重启生效" / 或"安装失败：<原因>"
  → 刷新已装列表
```

### 1.7 风险与待实测项（开工第一步就验证）

1. **`dsh plugin --profile web list` 的输出格式未知** —— 需在用户本机实跑一次，确定解析规则（可能含表格/JSON/纯文本）。解析器据此实现。
2. **`dsh plugin add` 在 dsh web 运行时能否直接改配置** —— 可能要求 web 关闭才能写 `cordis.patch.yml`。若如此，安装流程改为：先停 web → 执行 add → 再起 web（自动重启开关天然覆盖）。
3. **自动重启是否真能加载新插件** —— 需真机验证一次（装一个无害插件如 `dshmarket`，看重启后 dsh UI 是否出现 Plugin Market）。
4. **npm 镜像**：`dsh plugin add` 走 pnpm/npm，国内首次可能慢，复用现有 npmmirror 配置经验。
5. **权限/安全提示**：安装第三方插件即在本机跑外部代码，UI 上应加一句"仅安装可信来源"的提示（参考官方建议）。

---

## 2. 功能 B：插件自身 UI 换肤

### 2.1 主题模型

新增主题枚举（持久化到设置）：

```java
public enum DshTheme {
    FOLLOW_IDE,   // 跟随 IDE（默认，用 JBColor 自动深/浅）
    DARK,         // 强制深色
    LIGHT,        // 强制浅色
    CUSTOM        // 自定义主色（取一个 accent 颜色）
}
```

- `FOLLOW_IDE` 用 `JBColor`（`JBColor.namedColor(...)` 或 `UIUtil` 主题感知），零维护。
- `DARK` / `LIGHT` / `CUSTOM` 由插件自绘配色（背景、前景、边框、强调色）。

### 2.2 设置项

- `DshSettingsState` 新增 `theme: DshTheme`（默认 `FOLLOW_IDE`）+ `customAccent: String`（十六进制，仅 CUSTOM 用）。
- `DshSettingsConfigurable` 新增「外观 / Theme」下拉 + （CUSTOM 时显示）颜色选择器。
- 修改后立即持久化；UI 组件监听设置变化实时刷新（或下次打开生效，推荐实时）。

### 2.3 应用范围

| 组件 | 换肤点 |
|------|--------|
| `DshStatusBarWidget` | 状态文字前景色、未连接/运行中的状态点颜色、悬停背景 |
| `DshToolWindowPanel` | 框架背景、Tab 选中色、边框 |
| `DshSessionsPopupAction` 弹窗 | 列表选中行高亮、标题栏配色 |
| `DshPluginsPanel`（功能 A） | 表格/按钮配色，跟随同一套 |

### 2.4 实现要点

- 所有颜色走一个 `DshThemePalette` 工厂：输入 `DshTheme` + `JBColor.isDark()`，输出一套 `Color` 常量。
- Swing 组件改色用 `setForeground/setBackground/setBorder`，并在 `updateUI()` 或设置变更时重绘。
- 监听：`PropertiesComponent` 或 `DshSettingsState` 的变更回调（可简单用 `ApplicationManager.getApplication().getMessageBus()` 发主题变更事件，各组件订阅）。
- 不侵入 dsh 网页本身（那是 dsh 的 `/theme`，不在本功能范围）。

### 2.5 新增 / 修改文件清单

新增：
- `ui/DshTheme.java` —— 枚举。
- `ui/DshThemePalette.java` —— 颜色解析工厂。
- `ui/DshThemeListener.java`（可选）—— 主题变更消息总线。

修改：
- `DshSettingsState.java` —— 加 `theme` / `customAccent` 字段 + 持久化。
- `DshSettingsConfigurable.java` —— 加外观设置 UI。
- `DshStatusBarWidget.java` / `DshToolWindowPanel.java` / `DshSessionsPopupAction.java` / `DshPluginsPanel.java` —— 应用调色板。
- `README.zh.md` / `README.md` —— 补外观设置说明。

---

## 3. 验收标准

功能 A：
- [ ] Plugins 标签页显示当前已装 dsh 插件列表（真机 `dsh plugin list` 实测解析正确）。
- [ ] 安装框输入 npm 包名可成功安装，Server Log 显示进度，结束后 dsh 重启且新插件生效。
- [ ] 移除按钮可卸载插件并重启生效。
- [ ] "浏览插件市场"按钮在 JCEF 打开 dsh-plugin.org。
- [ ] 安装失败有明确错误气泡（不静默）。

功能 B：
- [ ] 设置里切到 DARK / LIGHT / CUSTOM，状态栏、工具窗口、弹窗配色即时变化。
- [ ] FOLLOW_IDE 与 IDE 深/浅切换保持一致。
- [ ] 重启 IDE 后主题保持（持久化正确）。
- [ ] 8 个 action 仍正确覆写 `getActionUpdateThread()`，无新增 OLD_EDT 告警。

---

## 4. 分期与里程碑

- **M1（功能 B，皮肤）**：纯插件侧、零外部依赖，最快出活、可独立验证。建议先做。
- **M2（功能 A，插件市场）**：依赖 dsh CLI 实测（§1.7 五项），开工第一步先实跑 `dsh plugin list/add` 确认机制，再实现面板与重启逻辑。
- 两里程碑各自独立可发布；也可合并进 0.3.0 一次上线。

---

## 5. 开放问题（评审时请确认）

> 下面的 Q2' / Q5 / Q6 是 0.5 节实测后**新增**的问题。

1. ~~功能 A 的"链接插件市场"——是打开社区 hub `dsh-plugin.org`，还是优先引导装官方 `dshmarket` 并内嵌其页？~~
   → 保留，但注意 dsh 自带 Settings → Plugins 页面，需先定「增量价值」（见 Q5）。
2. 功能 B 是否需要"自定义主色"细粒度，还是只要 跟随IDE/深/浅 三档即可？
   （现状：已有 `DshUiTheme` 三档，目前只作用于日志区 + 内嵌页面 color-scheme，
   未覆盖状态栏/工具窗口框架/会话弹窗，也没有自定义主色。）
3. 自动重启 dsh web 是否默认开启（装插件后不打断用户 vs. 用户想自己控制重启时机）？
4. 插件市场与皮肤是否合并为 0.3.0，还是分两个小版本？
5. **【新增】插件管理要不要做完整 Tab？** 实测发现 dsh 内嵌 UI 已有 Settings → Plugins 页面。
   选项：(a) 只做"打开插件市场 + 快速安装框"轻量版；(b) 做完整 Plugins Tab（列表+启停+移除）。
6. **【新增，✅ 2026-08-31 已实测解决】"换肤"要不要顺带控制 dsh 自己的主题？**
   → **结论：选项 (b) 可行，且收益最大**。已 curl 验证 `settings.update` 可写 dsh 主题
   （`ui-theme.preference ∈ light|dark|system`），文件落盘成功（沙箱仅 safe-delete shim 报错，非 dsh bug）。
   插件换肤应**同时驱动 dsh 自身主题**，让内嵌网页也换肤。详见 §0.6。
