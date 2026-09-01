# DeepSeek Harness Studio · 插件市场规划

> 状态：规划稿（v1，2026-09-02）
> 用途：把插件从「已上架」推进到「有人用、持续更新、有口碑」。
> 配套文档：`docs/marketplace-listing.md`（上架文案已备）、`docs/DESIGN-0.3.0.md`（功能调研底稿）。

---

## 一、现状盘点

| 项 | 状态 | 缺口 |
|---|---|---|
| 0.2.0 上架 | 已上传 Marketplace（plugin id 33569） | 已被 0.3.0 取代 |
| 0.3.0 上架 | ✅ 已上传 Marketplace（plugin id 33569），正式版可装 | 审核/搜索可见性确认中 |
| 0.3.0 特性 | 背景图/透明度注入 dsh 网页「通用设置」+ 刷新持久化，已真机验证并上架 | — |
| 上架文案 | `docs/marketplace-listing.md` 中英文已备 | 待回填市场字段 |
| 截图 | ✅ 已备 4 张（`docs/screenshots/`） | — |
| GitHub | ✅ 已 push（merge commit `be1b1b9`） | — |
| 正式签名证书 | ✅ 已用正式证书签名并上传 | — |

**结论**：工程侧 0.3.0 已闭环并上架，发布链路已通，GitHub 已 push（`be1b1b9`），4 张截图已入 `docs/screenshots/`。当前重心转到「回填市场列表字段 + 增长推广 + 后续功能路线」。

---

## 二、市场定位与差异化

**这是什么**：DeepSeek Harness（`dsh`，DeepSeek 开源编码智能体）在 Android Studio / IntelliJ IDEA 里的原生入口。

**目标用户**：已在终端/浏览器里用 `dsh web` 的开发者；想「不离开 IDE 就能用 Harness」的 Android/后端工程师。

**一句话价值**：把 Harness 的工作流留在 IDE 内——不用切浏览器、不用记命令、状态一眼可见。

**差异化（相对官方纯网页）**：
- 原生内嵌 JCEF，IDE 主题/快捷键体系一致；
- 一键启停 `dsh web` + 外部实例自动识别连接；
- 状态栏彩色指示 + Server Log，排查启动问题；
- 编辑器右键「发送代码到 Harness」；
- 会话快速访问（复制 sessionId 跳转）；
- Headless 一次性任务；
- 背景图个性化（0.3.0）。

**空白市场**：JetBrains 市场上「DeepSeek / dsh 专用 IDE 集成」类插件极少，先发优势明显，应趁早占关键词与评分类目。

---

## 三、列表页优化（立刻可做，零代码）

按 JetBrains [Marketplace 最佳实践](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)：

1. **标题/副标题关键词**：`DeepSeek Harness Studio` 已含核心词；Description 首段必须出现 `DeepSeek`、`Harness`、`coding agent`、`Android Studio`、`IntelliJ`。
2. **截图 4 张**（✅ 已备，见 `docs/screenshots/`）：
   - `01-main-view-dark.png` — 深色主题 + 半透明背景图；
   - `02-main-view-light.png` — 浅色主题 + 半透明背景图；
   - `03-background-settings.png` — Harness 设置 → 通用设置里的「背景图 / 浮层透明度」控制卡片（0.3.0 核心卖点）；
   - `04-main-view-dark-alt.png` — 深色主题主界面备选图。
   - 建议上传顺序：01 → 02 → 03 → 04，第 3 张可作首图。
3. **分类**：`Tools Integration`。
4. **标签（Tags）**：`deepseek`、`harness`、`ai`、`coding-agent`、`android-studio`、`intellij`。
5. **Description**：直接回填 `docs/marketplace-listing.md` 的 Markdown 版，并补一段 0.3.0 背景图特性。
6. **Documentation URL**：先 push GitHub 仓库再填，避免 404（当前写的是 `skansgai/dsh-studio`，需与真实仓库一致）。
7. **多语言**：英文为主、中文并列（市场以英文检索为主）。

---

## 四、功能路线（驱动 adoption）

原则：**小步快跑、高频发版**，用版本号更新维持市场「最近更新」权重；优先做「零外部依赖、用户高感知」的特性。

| 优先级 | 功能 | 价值 | 风险/依赖 | 建议版本 |
|---|---|---|---|---|
| P0 | ~~打通发布链路~~ ✅ **已完成**：正式证书 + 上传 0.3.0（0.3.0 已上架 plugin 33569） | 0.3.0 可装 | — | 完成 |
| P1 | **错误自愈**：端口被占用时自动换端口并提示 | 降低「启动失败」差评 | 低 | 0.3.1 |
| P1 | **多会话侧边栏**：在工具窗口内并列/切换多个 dsh 会话 | 高感知效率提升 | 中（SPA 路由） | 0.3.x |
| P2 | **换肤联动 dsh 主题**：插件主题选项同时写 `settings.update` 改 dsh 自身主题（API 已验证可行） | 视觉统一 | 低（已 curl 验证） | 0.3.x |
| P2 | **dsh 插件市场轻量入口**：工具窗口内「打开 dsh-plugin.org」+ 一个快速安装框（npm/github/file） | 生态黏性 | 中（dsh 自带 Plugins 页，需论证增量价值） | 评估后 |
| P3 | 内嵌 dsh 官方文档 / 快捷键面板 | 新手友好 | 低 | 后续 |

> 注：`DESIGN-0.3.0.md` 里的「完整 Plugins Tab（列表+启停+移除）」已被你砍掉——理由充分（dsh 自带 Settings→Plugins 页）。若未来做，只做「轻量入口 + 快速安装框」即可，不做完整管理。

---

## 五、推广渠道

- **GitHub**：README 置顶截图 + 特性清单 + 使用动图（GIF），是市场外的第二流量入口。
- **中文社区**：掘金 / 知乎 / CSDN 发「在 Android Studio 里用 DeepSeek Harness 写代码」短文。
- **海外**：Reddit `r/AndroidDev`、`r/IntelliJ`、`r/DeepSeek`；Hacker News 若有时机。
- **DeepSeek 生态**：官方 Discord / 论坛提一句插件存在。
- **Marketplace 自身**：靠关键词（§三）自然搜索，保持更新频率。

---

## 六、衡量指标

- Marketplace 后台：安装量、卸载率、近 30 天活跃。
- 评分与评论（早期每条评论都珍贵，需主动邀请满意用户评）。
- GitHub stars / issues（反馈渠道健康度）。
- 兼容性反馈：旧平台（2023.1–2025.2）是否有人报 NoClassDefFoundError 类问题。

---

## 七、下一步行动清单（按依赖排序）

- [x] **1. 正式签名证书**：✅ 已用自有证书签名并上传 0.3.0。
- [x] **2. Git push**：✅ 已 push（merge commit `be1b1b9`），`github.com/skansgai/dsh-studio` 链接有效。
- [x] **3. 上传 0.3.0**：✅ 已上架 plugin id 33569。
- [x] **4. 补 4 张截图**：✅ 已入 `docs/screenshots/`（01 深色主界面 / 02 浅色主界面 / 03 背景设置卡片 / 04 深色备选）。
- [ ] **5. 回填列表字段**：用 `docs/marketplace-listing.md` 文案填 Getting Started + Description，补 0.3.0 背景图段。
- [ ] **6. 发一版推广帖**：中文社区 + Reddit 各一篇，附 GitHub 与 Marketplace 链接。
- [ ] **7. 排期 0.3.1**：先做「端口占用自愈」这类低风险高感知修复，维持更新节奏。

---

## 八、风险与待确认

1. ~~测试证书不能上传市场~~ ✅ 已用正式证书解决。
2. ~~GitHub 仓库归属 / push~~ ✅ 已确认 `skansgai/dsh-studio` 为真实仓库，代码已 push（`be1b1b9`）。
3. ~~0.3.0 是否已在市场~~ ✅ 已确认上架。
4. **dsh 版本耦合**：背景图/透明度靠注入 dsh 网页 DOM，dsh 大版本改版可能失效 → 发版前在真机验证一次，并在 CHANGELOG 标注「适配 dsh @x.y.z」。
