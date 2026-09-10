# DeepSeek Harness Studio 0.3.1 —— 发布说明（Marketplace 用）

> 上传位置：JetBrains Marketplace 后台 → 新版本 → **Update notes（更新说明）** 文本框。
> Marketplace 支持 HTML，直接粘「HTML 版」即可；若只要纯文本，用下面「纯文本版」。

---

## HTML 版（推荐，直接粘贴）

```html
<h3>0.3.1</h3>
<ul>
  <li><b>适配 dsh 0.1.2-rc.1 的启动令牌鉴权</b>：新版 dsh 的首页必须带 <code>?token=</code> 才能打开，
      直接访问会被拒绝（401 <code>dsh web authentication required</code>）。插件现在会从自己拉起的 dsh 进程输出里
      自动捕获 token 并拼到访问地址上；若复用的是外部已在运行的实例（拿不到 token），会明确提示，
      并引导你从启动它的终端复制带 token 的地址填进「设置 → 服务器地址」。</li>
  <li><b>修复市场/IDE 中插件图标不显示</b>：将 <code>pluginIcon.svg</code> 从 JAR 根目录移到规范的 <code>META-INF/</code>
      位置，并改为 40×40、无渐变（纯色）的扁平写法，规避市场 SVG 清洗器对 <code>&lt;defs&gt;</code>/渐变与
      <code>width/height=240</code> 的丢弃，确保图标正常渲染。</li>
  <li><b>设置页新增「关于」区块</b>：显示插件版本与 DeepSeek Harness (dsh) 的 npm 最新版本，并提供「检查更新」按钮，
      一键比对插件（Marketplace）与 dsh（npm）是否有新版本并给出升级指引。</li>
  <li><b>提示并一键清理残留的旧 dsh 实例</b>：<code>npx --yes</code> 会把 dsh 静默升级，升级前启动的进程却仍在跑，
      它会继续按旧结构产出 boot manifest 并从已升级的包里读取新 bundle，页面会报
      <code>client-modules: boot manifest batches must be an array</code>。现在启动若发现端口被非本插件的实例占用，
      会给出提示并提供「结束占用进程并重启」。</li>
</ul>
```

---

## 纯文本版

```
0.3.1

- 适配 dsh 0.1.2-rc.1 的启动令牌鉴权：新版 dsh 的首页必须带 ?token= 才能打开，直接访问会被拒绝
  （401 dsh web authentication required）。插件现在会从自己拉起的 dsh 进程输出里自动捕获 token 并拼到访问地址上；
  若复用的是外部已在运行的实例（拿不到 token），会明确提示，并引导你从启动它的终端复制带 token 的地址
  填进「设置 → 服务器地址」。
- 修复市场/IDE 中插件图标不显示：将 pluginIcon.svg 从 JAR 根目录移到规范的 META-INF/ 位置，并改为 40×40、
  无渐变（纯色）的扁平写法，确保图标正常渲染。
- 设置页新增「关于」区块：显示插件版本与 DeepSeek Harness (dsh) 的 npm 最新版本，并提供「检查更新」按钮，
  一键比对插件（Marketplace）与 dsh（npm）是否有新版本并给出升级指引。
- 提示并一键清理残留的旧 dsh 实例：npx --yes 会把 dsh 静默升级，升级前启动的进程却仍在跑，页面会报
  client-modules: boot manifest batches must be an array。现在启动若发现端口被非本插件的实例占用，
  会给出提示并提供「结束占用进程并重启」。
```

---

## 发布前检查清单

- [ ] 上传文件：`build/distributions/dsh-studio-0.3.1-signed.zip`（**带 -signed 的那个**，117,021 B）
- [ ] 版本号填 `0.3.1`（市场当前最新是 0.3.0，合法递增）
- [ ] 插件 logo 需在后台**单独手动上传**（`publishPlugin` 没有 token，logo 不会自动带）
- [ ] 兼容 IDE 版本范围沿用 `plugin.xml` 的 `<idea-version>`，不要临时改
- [ ] 发布后确认：市场 API 返回首个版本为 0.3.1
      `GET https://plugins.jetbrains.com/api/plugins/33569/updates`
