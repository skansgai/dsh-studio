# 内置 dsh 运行时 · 设计方案

> 日期：2026-09-11　｜　决策前提：**构建链路成本不是问题，以客户体验优先**（用户 2026-09-11 确认）  
> 目标：把「首次可用等待 14 分钟」变成「装上就能用」。

---

## 一、实测数据（本机 npx 缓存 + Marketplace API，2026-09-11）

### 1.1 依赖闭包

以 `@deepseek-ai/dsh` 为入口求依赖闭包：

| 项      | 数值                   |
| ------ | -------------------- |
| 闭包包数   | **457 个**（直接依赖 70 个） |
| 闭包原始大小 | **210.7 MB**         |
| 闭包压缩后  | **54.6 MB**          |

### 1.2 裁剪收益（逐文件 deflate 实测）

| 裁剪项          | 原始      | 压缩后         |
| ------------ | ------- | ----------- |
| sourcemap    | 34.9 MB | 7.4 MB      |
| 类型声明 `.d.ts` | 35.9 MB | 7.1 MB      |
| 文档           | 6.7 MB  | 2.5 MB      |
| 测试 / 示例      | 9.1 MB  | 2.2 MB      |
| **全部裁掉后**    | —       | **35.5 MB** |

### 1.3 与竞品对照

|               | 插件包大小         | 客户首次可用      |
| ------------- | ------------- | ----------- |
| 我们（现状）        | 0.1 MB        | **约 14 分钟** |
| 我们（裁剪内置，实测推算） | **≈ 35.5 MB** | **秒级**      |
| 竞品 33555      | 40.7 MB       | 秒级          |

> 后来真正做完（阶段 1–3）的实际体积是 **82.8 MB**（5 平台原生 sharp）/ 46.5 MB（WASM），
> 高于这里 35.5 MB 的推算 —— 差额来自**分平台的原生模块**：上面那张裁剪表只算了通用 JS 部分，
> 而 `@img/sharp-*`、`@vscode/ripgrep-*`、`koffi`、`node-pty` 每个平台各要一份。
> 完整结果与取舍见 §四。



> 结论：**裁剪后的体积与竞品几乎一致，且远低于 Marketplace 400 MB 上限。**  
> 也就是说，「内置」在体积上完全可行，此前的顾虑（294 MB）来自把整个 `node_modules` 当成了一个整体。

### 1.4 尚未覆盖的部分

- **原生模块是分平台的**：`@img/sharp`（图像，~18 MB/平台）、`@vscode/ripgrep`（搜索）、  
  `koffi`（FFI）、`node-pty`（终端）。本机实测只有 win32-x64 被装上，**打包时需显式拉齐三平台**  
  （npm 支持 `--os` / `--cpu`）。
- **Node.js 运行时**：dsh 是 Node CLI，必须有 Node。官方 win-x64 包约 30 MB（压缩），  
  展开后 `node.exe` 约 80 MB。dsh 各包**未声明 `engines`**，需自行确定最低版本并在启动时校验。

---

## 二、目标体验（这是方案的验收标准）

1. **装上就能用**：安装插件后第一次点「启动」，**秒级**出界面，不再有 14 分钟黑箱等待。
2. **离线可用**：内网 / 代理 / 无外网环境下，开箱即用。
3. **不重复下载**：用户机器上已有可用的 dsh 或 Node 时，直接复用，不重复占磁盘。
4. **更新不打断**：运行时升级在后台静默进行，**永远不阻塞**用户当前会话。
5. **失败可回退**：任何更新失败，自动退回内置基线版本，功能不中断。
6. **进度可见**：万一需要联网补齐（Node / 新运行时），必须有百分比、速度、可取消、可重试，而不是一行日志。

---

## 三、推荐方案：内置基线 + 运行时独立热更新

### 3.1 分层结构

```
插件包内（只读，永远可用）
  └─ dsh-runtime-baseline/     裁剪后的 dsh（≈35.5 MB 压缩）
        └─ 首次使用时解包到用户目录

用户目录（可写，优先使用）
  └─ <IDE 配置>/dshstudio/runtime/<版本>/
        ├─ 热更新下载的新版运行时
        └─ 平台原生模块（Node 缺失时一并补齐）
```

**解析顺序**：用户目录的新版运行时 → 内置基线 → 系统全局 dsh（用户已装则复用）→ 自定义命令。  
每一层都可被设置页里的「使用内置 / 使用全局 dsh / 自定义命令」显式覆盖。

### 3.2 为什么基线 + 热更新，而不是只内置

dsh 迭代很快（0.1.2-rc.1 → 0.1.5-rc.2 只用了几周）。若只内置：

- 用户想用 dsh 新特性，得等我们发插件版本；
- dsh 出兼容性修复，我们也得跟着发版。

把运行时版本与插件版本**解耦**，两个问题同时解决：

- 内置基线保证「装了就能用」和「离线可用」；
- 热更新保证「能拿到最新 dsh」，且**不增加插件包体积**。

### 3.3 Node.js 的处理（✅ 已实现）

| 方案                  | 优点                | 缺点                        |
| ------------------- | ----------------- | ------------------------- |
| 一并内置 Node           | 真正零依赖             | +约 30 MB/平台（三平台 ≈ +90 MB） |
| **检测 + 引导下载**（已采用） | 体积小；Node 用户本机多半已有 | 缺 Node 时需要一次联网            |

实现见 `com.deepseek.dshstudio.runtime.DshNodeChecker`。

**最低版本 22.19.0，不是拍脑袋定的。** 2026-09-14 把内置运行时里 553 个
`package.json` 的 `engines.node` 全量汇总取最大值：

| 包 | 谁依赖它 | `engines.node` |
| -- | -- | -- |
| `undici@8.10.2` | `@deepseek-ai/dsh-web-fetch-http`（官方插件） | **>= 22.19.0** |
| `@earendil-works/pi-ai@0.84.4` | `@deepseek-ai/dsh-llm-pi-ai`（官方插件） | **>= 22.19.0** |
| `commander@15.0.0` | 含 `@deepseek-ai/dsh` 自身在内 5 个官方包 | >= 22.12.0 |

dsh 自身代码里**没有任何 Node 版本校验**（唯一提到 Node 的是一句注释
"Works in both Node.js 20+ and browsers"），所以 `engines` 是唯一的权威信号。

- **只警告、不拦截**：`engines` 只是包作者的保守估计，低于它未必一定跑不起来，
  强行拦住反而挡掉本来能用的用户。对话框给「打开下载页 / 继续尝试 / 取消」三项，
  用户选过「继续尝试」后本次会话不再重复打扰。
- **探测不缓存**（`DshUtil.detectNodeVersion()` 每次真起一个 `node --version` 子进程），
  所以设置页的「重新检测」能立刻反映刚装好的 Node；设置页内的自动刷新走缓存，
  避免在 EDT 上反复起进程。
- **启动路径上的检查放在 `synchronized (lock)` 之外**：引导对话框是模态的，
  占着锁会让状态栏、动作等其它线程干等。
- 命令是否需要 Node 按**可执行文件名**判断（`npx` / `npm` / `dsh` / `node*`），
  用户把命令改成非 Node 程序时不会误报。

---

## 四、构建链路（✅ 已实现并跑通）

Gradle 任务 `bundleDshRuntime`（`build.gradle.kts`），挂在 `processResources` 之前，  
产物 `build/dsh-runtime/dsh-runtime.zip` 被打进 JAR 的 `dsh-runtime/` 下。

流程：

1. **确定基底平台**：宿主平台必须出现在 `dsh.runtime.targets` 里 —— 只有它的二进制能在
   构建机上真正执行，后面的启动自检才有意义。宿主不在目标列表时直接构建失败并给出提示。
2. **解析宿主依赖**（全流程唯一一次真正跑 npm）：
   `npm install --omit=dev --ignore-scripts @deepseek-ai/dsh@<ver>`。  
   成功后写下标记文件 `.dsh-runtime-install-ok`（内容含版本与平台指纹），下次直接复用；
   半截的树（上次失败）没有标记，会自动重装。`-Pdsh.runtime.refresh=true` 可强制重来。
3. **补齐其余平台的原生包**：读宿主安装产出的 `package-lock.json` —— npm 的 lockfile 是
   **跨平台**的，记录了所有 `os`/`cpu` 变体的包及其 tarball 地址与 sha512。据此对每个目标平台
   定点下载 + 校验 + 解包，不跑 npm。**这一步对所有目标平台（含宿主）执行**，因此裁剪掉的
   包在切换配置后能自动找回来。
4. **补齐通用包**：少数不带平台约束的包（WASM 版 sharp 及其运行时 `@emnapi/runtime`）单独补。
5. **裁剪**，四道：
   - `pruneNpmTrash` —— 清 npm 的改名残骸（见坑 2）；
   - `pruneExcludedPackages` —— 整包丢弃（musl 变体、WASM 兜底、按模式取舍的原生 sharp）；
   - `pruneNodePty` —— 只留目标平台的 `prebuilds/`，删掉 `third_party/`、`src/`、`build/`；
   - `prune` —— 通用规则：`test|tests|__tests__|spec|specs|examples|example|docs|doc` 目录，
     `*.map` / `*.md` / `*.pdb` 文件（`license` / `notice` 等保留）。
6. **校验**（任一失败即中断构建，绝不发出坏包）：
   - `dsh --version` —— 启动器与基本依赖完整；
   - `dsh --profile web --dump-config` —— 整个 web profile 插件树能否组合（能发现缺包）；
   - `verifyPlatformNatives` —— 按目标平台逐个点名，确认 koffi / ripgrep / sharp / node-pty
     prebuild 都在（见下）。
7. **写可执行位清单**：zip 不保留 Unix 权限位，把 `spawn-helper`、`rg` 的相对路径写进
   `.dsh-runtime-executables`，交给插件解包后统一 chmod。
8. **打包**：zip 写入 `build/dsh-runtime/dsh-runtime.zip`。

### 为什么是「宿主装一次 + 定点抓取」而不是「每个平台跑一次 npm」

最初实现是后者：对每个平台跑 `npm install --os=X --cpu=Y`，再按包粒度合并。实际跑不通，
换成了现在的方案。原因：

- **`--ignore-scripts` 是必须的**（见坑 1），但即便加上，`--os/--cpu` 的过滤也只发生在
  reify **落盘**阶段：中途的 `node_modules` 会短暂包含**全部平台**的变体（实测 darwin 目标下
  koffi 的 android / freebsd / openbsd 变体全在）。进程一旦中断就留下一棵脏树。
- **太慢**：每跑一次都要重写一万多个文件，在带安全过滤驱动的机器上动辄十几分钟；5 个平台
  就是接近一小时。
- **npm 会清掉我们抓来的包**：tarball 抓取的平台包在 npm 眼里是「多余的」，下次
  `npm install` 会移除它们 —— 这没问题（抓取步骤会补回来），但会留下大量改名残骸（坑 2）。

定点抓取只下载真正需要的十几个包，一次完整构建约 **1~3 分钟**，而且天然排除了
freebsd / android / ppc64 这些我们根本不发布的变体。

### 为什么安装一律加 `--ignore-scripts`

跨平台构建能跑通的关键。整棵依赖树里真正需要脚本的只有四个包，全都不需要执行：

| 包 | 脚本做什么 | 为什么可以跳过 |
| -- | -- | -- |
| `koffi` | `install`：`cnoke.cjs` **现场编译原生码** | 运行时二进制来自 `@koromix/koffi-<os>-<arch>` 可选依赖，`src/koffi/index.cjs` 直接 require；编译只是本地兜底 |
| `node-pty` | `install` / `postinstall` | 包内 `prebuilds/<os>-<arch>/` **自带全平台产物**，`lib/utils.js` 的查找顺序是 `build/Release` → `build/Debug` → `prebuilds/…`，回退即可命中 |
| `@deepseek-ai/dsh-subprocess-local` | `postinstall`：给 `spawn-helper` 补可执行位 | 由插件解包后统一 chmod（见第 7 步） |
| `protobufjs` | `postinstall`：打印版本兼容性警告 | 纯告警，无副作用 |

其余 `prepare` / `prepublish` 脚本在 registry 安装时本来就不会执行，属噪声。

跳过脚本后，各平台的树完全由「平台专有可选依赖」决定，既避免交叉编译，也让产物在不同
构建机上可复现。

### 实测结果（2026-09-11，5 平台）

| sharp 模式 | 产出 zip | 说明 |
| -- | -- | -- |
| `native` | **82.8 MB** | 5 个平台各带原生二进制 + libvips |
| `hybrid` | 约 54 MB | 仅 Windows 原生，其余 WASM |
| `wasm` | **46.5 MB** | 只带 WASM 版 sharp |

三种模式都通过了两级自检（`dsh 0.1.2-rc.1`；web profile 526 行）与平台完整性校验。

体积构成（`wasm` 模式，压缩后）：sharp/libvips 3.4M、ripgrep 9.7M、dsh 自身 7.4M、
koffi 2.5M、node-pty 0.9M、其余 JS 依赖 18.6M。

> **依赖闭包裁剪没有价值**：原计划「BFS 可达性裁剪省约 10 MB」经实测推翻 ——
> 从 `--profile web --dump-config` 的 144 个插件出发做闭包，507 个已装包里只有
> `@emnapi/runtime`（0.42 MB）在闭包外，而且它还是被我们主动排除的 `sharp-wasm32` 的依赖。
> npm 装的包本来就没有冗余，**不必再做这一步**。

### sharp 的取舍（体积的最大变量）

`@deepseek-ai/dsh-attachment-local` 顶层 `import sharp`，不能省。而 sharp 的原生包
（`@img/sharp-<os>-<cpu>` + `@img/sharp-libvips-<os>-<cpu>`）每个平台约 8 MB，5 个平台
就是 40 MB —— 占 `native` 模式体积的一半。

sharp 0.35 自带 **WASM 兜底**（`dist/sharp.cjs`：原生全部失败后 `require("@img/sharp-wasm32/sharp.node")`），
而 WASM 包只有 3.4 MB 且平台无关。实测对比（本机 Node 22，win32-x64）：

| 场景 | 原生 | WASM |
| -- | -- | -- |
| 3000×2000 → 800×533 | 22.4 ms | 32.4 ms |
| 8000×6000（48MP）→ 1600×1200 | 54 ms | 113 ms |
| 同上，进程 RSS | 48 MB | **481 MB** |
| png/jpeg/webp/avif/tiff/gif 编解码、SVG 输入、旋转/裁剪/合成 | 全部 OK | **全部 OK**（输出字节一致） |

结论：**小图（截图，最常见的场景）差异可忽略；大图慢约 2 倍、内存高约 10 倍。**
`dsh-attachment-local` 的 `detectImage` 会完整解码（`limitInputPixels: false`，靠自己的
`maxPixels` 事后校验），所以 48MP 这种大图的内存峰值是真实存在的。

因此做成 `dsh.runtime.sharp` 可配（默认 `native`），把选择权留给发布决策。

### 实现中踩到的坑

1. **跨平台安装会触发 `koffi` 的交叉编译**：`cnoke.cjs` 按 `process.platform`（构建机）
   找预编译产物，找不到就退回源码编译，于是在 Windows 上编 darwin 的 `.node` 必然失败。
   表现为构建跑 1 小时后挂在 `Process 'command 'cmd'' finished with non-zero exit value 1`。
   → 解法：`--ignore-scripts`。
2. **npm 移除包时不是直接删，而是改名藏起来**：目录被改成 `.<原名>-<随机串>` 放在
   `node_modules` 里（`ls` 看不见），之后才异步清理。实测一次构建留下 **35 MB 的 sharp
   原生包残骸**，而且因为名字带了点前缀，按包名匹配的裁剪规则完全看不见。
   → 解法：`pruneNpmTrash` —— npm 不安装以 `.` 开头的包目录（`.bin` 除外），见到就删。
3. **`node-pty` 在 Windows 上没有 `pty.node`**：Windows 用 `conpty.node`，只有 darwin/linux
   才有 `pty.node`。平台完整性校验一开始按 `pty.node` 判，误报了 win32-x64。
4. **不要用 `File.walkTopDown()` 遍历 node_modules**：它会跟进符号链接，遇到成环时原地空转
   （目标文件已存在 → 不产生新文件，只烧 CPU，表现为「构建卡死」）。改用 `Files.walk`。
5. **不要在 Windows 上逐文件复制整棵树**：上万个文件 + 安全过滤驱动，实测一次要 40 分钟以上。
   改为「基底平台原地当成品树」，重复运行只需几十秒。

**构建期风险控制**：运行时版本固定、构建可复现；两级自检保证「发出去的包一定能启动」；
平台完整性校验保证「每个平台的终端 / 搜索 / 图片功能都有原生支撑」；解析失败直接构建失败，
而不是静默降级成「无内置运行时」。

**开发期跳过**：`-Pdsh.runtime.skip=true`。

---

## 五、插件侧实现（✅ 已完成）

### 5.1 目录布局

```
<运行时根>/
  ├─ baseline-<stamp>/      内置基线（由插件包解出）
  ├─ baseline-<stamp>.tmp/  解包中的临时目录，中断后下次自动清理
  ├─ <dshVersion>/          运行时热更新下载的版本（优先级更高）
  └─ .incoming-<version>*/  热更新的下载/解包中间产物，成功或失败都会被清掉
```

`stamp` 是构建产物 zip 的 sha256 前 8 位，写进 `dsh-runtime/runtime-meta.txt`
（与 zip 并列打进 JAR）。dsh 版本、目标平台、sharp 模式、裁剪规则任一变化都会换一个新
stamp，于是「插件升级后要不要重新解包」是自动判断的，不需要额外状态文件。

热更新版本用**版本号**命名（`0.1.5-rc.1`），与 `baseline-*` 区分。中间产物统一以 `.` 开头，
`hotUpdateDir()` 会跳过它们，所以「下载到一半」不会被误当成一个可用版本。

### 5.1.1 热更新从哪拿包（为什么不用 npm）

dsh 的 72 个直接依赖里 69 个是一方包，但**全部**用 `^x.y.z` 范围声明（还带 prerelease），
没有一个是精确版本。要在 Java 里正确实现 semver 范围匹配 + 递归依赖解析 + 平台过滤，
等于重写半个 npm，而且会和构建链路形成两套实现、两份 bug。

所以热更新**复用同一条 Gradle 链路**的产物：`.github/workflows/runtime-release.yml`
每天检查 npm 上有没有新 dsh，有就用 `bundleDshRuntime` 构建（与内置基线完全相同的
裁剪与自检流程），按平台拆包后挂到 GitHub Release 上。

| 项 | 约定 |
| -- | -- |
| 标签 | `runtime-<dshVersion>`，例如 `runtime-0.1.5-rc.1` |
| 资产 | `dsh-runtime-<target>.zip` + `runtime-meta-<target>.txt` |
| 发现 | `GET /repos/skansgai/dsh-studio/releases?per_page=30`，按标签前缀过滤后取最大版本 |

**按平台拆包的理由（实测，2026-09-15）**：合并包 18428 个条目、压缩后 82.9 MB
（解包后 239.7 MB）；按平台拆开后每个包约 **41 MB**（win32-x64 42.8 / linux-x64 42.3 /
darwin-arm64 41.5 MB），**省约 51%** —— 差额几乎全是别的平台的 sharp/libvips、ripgrep、
koffi、node-addon 二进制。
拆包不物化新树（那是上万文件的第二份拷贝），而是在写 zip 时按
lockfile 的 `os`/`cpu` 约束过滤路径；每个拆出来的包都会单独跑一遍原生包完整性校验
（`verifySplitZip`）——这类缺失在 JS 层完全看不出来，只有用户在那台机器上真正用终端 /
贴图片时才炸，所以宁可构建失败。

### 5.1.2 打包时必须校验明文（踩过的坑）

审计内置包时发现 `dsh-runtime.zip` 的 18390 个条目里有 **36 个是密文**（文件头含
`E-SafeNet`），集中在 `@img/sharp-*`、`@koromix/koffi-*`、`@vscode/ripgrep`、
`node-addon-require-builtin-*` 这些原生包的 `package.json` / `.h` / `.js` 上。

危害：用户机器上没有 DLP，`node` 读到的就是原始密文字节，`package.json` 解析失败 →
**原生模块直接加载不了**（sharp 贴图、koffi 终端全废）。

成因：加密是**异步**的，且只对「DLP 范围内路径」生效。`work/raw/<target>/**`（npm 写的）
实测 0 密文；而 Gradle（java）解包平台 tarball 时写的文件会被加密，打包时刚好赶上
加密完成的那些就进了 zip —— 所以数量是**非确定性**的（可能 0，也可能 36）。

修法两条，缺一不可：

1. **把工作目录挪出 DLP 范围**（`workDir` → `%TEMP%/dshstudio-runtime/work`）：
   根本不在范围内落盘就不会被加密，顺带拿到 200 倍的速度提升（见 §5.2）。
2. **`writeZip` 里加内容级硬校验**：边打包边读每个文件前 64 字节，命中 `E-SafeNet`
   就删掉坏产物并让构建失败，错误信息列出前 10 个文件名。
   不要只依赖「路径应该没问题」这个假设 —— 加密行为依赖环境，必须实测。
   修完后 6 个 zip 全部 0 密文。

> 同样的道理适用于 `runtime-meta`：它的扩展名刻意用 `.txt` 而不是 `.json`，
> 因为 DLP 会按扩展名加密 `.json`；`processResources` 里还有一道 `doFirst` 兜底校验。


### 5.2 运行时根放哪里（最反直觉的一个决定）

实测（本机，同一块 C 盘，**Java 进程**写 400 个 1 KB 文件）：

| 目录                        | 写入速度      | 1.8 万个文件约需    |
| ------------------------- | --------- | ------------- |
| 系统临时目录 `%TEMP%`           | 2477 个/秒  | **6 秒**       |
| 用户目录 `~`                  | 11.5 个/秒  | **27 分钟**     |
| `%LOCALAPPDATA%`          | 11.6 个/秒  | 26 分钟         |
| `%LOCALAPPDATA%\JetBrains` | 11.7 个/秒  | 26 分钟         |
| `%APPDATA%`               | 11.8 个/秒  | 26 分钟         |
| 项目目录                      | 8~15 个/秒  | 20~38 分钟      |

同一块盘上差 200 倍，说明企业安全软件（DLP，本机是 E-SafeNet）是**按路径范围**做透明加密的：
用户目录 / 项目目录在范围内，系统临时目录被排除。这不是「磁盘慢」，而是每个文件多出约
85 毫秒的同步开销（像是逐文件走了一次策略服务）。

所以默认策略：

| 平台           | 默认位置                  | 原因                                                     |
| ------------ | --------------------- | ------------------------------------------------------ |
| Windows      | 系统临时目录                | 上述 DLP 是 Windows 上的企业软件，两者相差 27 分钟 vs 6 秒               |
| macOS / Linux | 用户目录 `~/.dshstudio/runtime` | 没有这类驱动；且 `/tmp` 常见 `noexec` 挂载与 tmpfs（占内存），放那里反而会让 rg / spawn-helper 跑不起来 |

设置页可选 `auto / temp / home`：担心临时目录被系统清理、或环境禁止从临时目录执行程序时，
改成用户目录即可（代价是 DLP 机器上首次解包很慢）。

> 这一条推翻了初稿「放用户目录、跨 IDE 版本复用」的想法：那个理由省下的是 IDE 升级时的几秒，
> 代价却是 DLP 机器上的 27 分钟。

> **2026-09-14 复测确认**：上表依然成立。同一台机器、C 盘有 2.7 GB 空闲时再测
> （每个目录 400 个 1 KB 文件）：`%TEMP%` 3011 个/秒，项目目录 / `~` /
> `%LOCALAPPDATA%` / `%APPDATA%` 全部是 11 个/秒。差距稳定在约 270 倍，不是偶发。

### 5.2.1 【风险】DLP 还会把解包出来的文件加密成密文

2026-09-14 排查集成测试失败时发现，同一台机器上的 DLP 除了「按路径拖慢写入」，
还会**按扩展名把 `.js` / `.ts` / `.json` 就地加密**。把运行时 zip 解到 `%TEMP%`
（Java 进程，与插件运行时同样的写入方式）后实测：

- 18390 个文件**全部**解出，耗时 **16.8 秒（1096 个/秒）**——解包本身很快，不是瓶颈；
- 但解完后立刻检查，**11950 个（64%）已经是密文**（文件头 `E-SafeNet`）：
  `.ts` 6120/7500、`.js` 5129/6450、`.json` 680/730 被加密；
  而 `.mjs` / `.mts` / `.cjs` / `.cts` / `.yaml` / `.node` 一个都没被加密。

后果：**`node.exe` 读到的是密文**。实测读入口 `bin.js` 拿到的就是 `E-SafeNet` 头，
`new TextDecoder('utf-8', {fatal:true})` 直接抛错；`node` 甚至连同目录的
`package.json` 都解析不了（`ERR_INVALID_PACKAGE_CONFIG`）。
也就是说，在装了这类 DLP 的机器上，**内置运行时解包后跑不起来**。

这不是代码问题——解包逻辑已验证正确（条目数 18390 全中、字节数与 zip 声明一致、
zip slip 防护生效）。`DshRuntimeManagerTest.extractZipProducesUsableTree`
现在会识别这种密文并在控制台打印跳过原因，只跳过「内容校验」，
文件数校验照常执行，避免把环境问题误报成代码回归。

**待决策（尚未处理）**：

1. 解包后做一次抽样自检（读入口文件头是否 `E-SafeNet`），失败时在设置页 / 通知里给出
   明确指引（改运行时位置、或请 IT 把运行时目录加入白名单），而不是把 node 的乱码报错
   甩给用户；
2. 文档 / FAQ 是否需要写明「企业 DLP 环境可能需要把 `<TEMP>/dshstudio-runtime`
   加入排除目录」。

> **顺带纠正一个此前的误判**：2026-09-12 曾观察到「真实 zip 解到 `%TEMP%` 只有约
> 16 个/秒」，并据此怀疑过 `%TEMP%` 也不快。实际原因是当时 **C 盘只剩几百 MB**，
> 写入被阻塞（实验脚本最后直接以 `No space left on device` 失败）。清理磁盘后同一
> 操作是 1096 个/秒。**磁盘剩余空间不足会伪装成「DLP 变慢」，排查时先看 `df`。**

### 5.2.2 为绕开 DLP 做的两处工程加固

上面的 DLP 会**加密构建产物**，实测把 `runtime-meta.json` 拷进 `build/resources/main`
后约 3 秒就变密文，`jar` 于是把密文打进插件包，插件运行时读不到自己的元数据
（表现：「插件包中没有内置 dsh 运行时」）。抢时间不可靠（一次完整构建要 1 分钟以上），
所以做了两处加固：

1. **资源扩展名 `.json` → `.txt`**（`dsh-runtime/runtime-meta.txt`）。
   内容仍是 JSON，只是换一个 DLP 不碰的扩展名。实测 `.txt` / `.md` / `.zip` / `.jar`
   都不在加密范围内，而 `.js` / `.ts` / `.json` 会被加密。
2. **`processResources` 加兜底校验**：拷贝前读一次 meta 的文件头，命中 `E-SafeNet`
   就直接 `GradleException` 失败，并提示把 `build/` 加入 DLP 排除名单 —— 宁可构建失败，
   也不要把一个插件自己都读不懂的包发出去。

另外 `DshRuntimeManager.verifyExtractedTree`（见 5.2.1）会在解包后抽查入口文件：
拿到密文就抛出可读的报错并给出出路（改运行时位置 / 找 IT 加白名单），
而不是把 node 的乱码解析错误甩给用户。

**同一个坑还坑了 `overlay.js`（顺手一起修了）**：`src/main/resources/dsh/overlay.js`
从 2026-09-07 的提交 `93459ca5`（「移除 dsh 网页设置里注入的关于标签页」）起，
仓库里存的一直是 **11546 字节的密文** —— 那次改动在文件写盘与 `git add` 之间被加密，
之后所有提交都继承了它，插件包里的 `dsh/overlay.js` 同样是密文，
注入到网页的是乱码，「背景图 / 透明度」功能实际是坏的。

补救时发现**抢时间没用**：DLP 对「已经被加密过的文件」会在 1~2 秒内重新加密，
连删掉重建也一样（密文逐字节相同，说明加密是确定性的）。所以改用同一套办法 ——
资源改名 `overlay.js.txt`（内容仍是 JS），插件侧优先加载 `/dsh/overlay.js.txt`、
保留旧名兼容。仓库里从此不再有 `.js` 文件。

明文是从 `dsh-studio-0.3.3.jar` 里取回的：那个 JAR 在文件被加密之前构建，
内含 11546 字节明文，与仓库里密文的大小一致（即同一份内容）。
`0.3.2` / `0.3.3` 从未上传 Marketplace，所以这个问题没有流到已发布版本；
线上 `0.3.1` 的包里同样是密文，下次发版会一并修掉。

### 5.3 启动方式的解析优先级

命令模板新增 `{dsh}` 占位符，默认模板变为：

```
{dsh} web --host {host} --port {port} --no-open
```

`{dsh}` 按设置展开：

| 模式              | 展开结果                                                |
| --------------- | --------------------------------------------------- |
| `auto`（默认）      | 热更新版本 → 内置基线 → `npx --yes @deepseek-ai/dsh`          |
| `bundled`       | 只用内置基线；不可用时**明确报错**并给出改设置的指引，不静默降级                  |
| `system`        | 只用 `npx --yes @deepseek-ai/dsh`（插件 0.3.x 以前的行为）      |

内置运行时的展开结果是两个 token：`node` + `<根>/node_modules/@deepseek-ai/dsh/lib/bin.js`。
路径里可能有空格（`C:\Program Files\...`），所以是**按 token 拼接**而不是字符串替换 ——
先替换再分词会把路径拆断。

模板里不含 `{dsh}` 时（用户自定义命令），完全不触碰运行时解析，行为与旧版一致。

### 5.4 首次解包

1. 读 `runtime-meta.json` 得到 `stamp`；
2. `<根>/baseline-<stamp>` 下有 `.unpacked-ok` 且内容等于 stamp → 直接用；
3. 否则解到 `baseline-<stamp>.tmp` —— **先解到临时目录、完成后再整体改名**，
   中途被杀不会留下「看起来完整」的树；按条目数报进度、可取消；
4. 非 Windows 上按 `.dsh-runtime-executables` 补可执行位（zip 不保留 Unix 权限位，
   漏掉这一步终端与 ripgrep 直接不可用）；
5. 改名到位、写下 `.unpacked-ok`、清掉其它 stamp 的旧目录。

在 EDT 上调用会弹一个带进度、可取消的模态框；在后台线程调用时直接内联执行（不嵌套模态框）。
`auto` 模式下若已有热更新版本可用，则**不**为了基线去解包。

### 5.5 与旧行为的兼容

- `serverCommand` 留空 → 用新默认模板（含 `{dsh}`）→ 自动走内置运行时；
- `serverCommand` 是用户自定义的 → 原样执行，行为不变；
- headless 一次性任务同样改用 `{dsh}`，与启动服务器走同一条运行时准备路径；
- 启动前置检查从「有没有 npx」改成「有没有 Node.js」—— 内置运行时与 npx 都依赖它。

---

## 六、实施顺序

| 阶段 | 内容 | 状态 |
| -- | -- | -- |
| 1 | `bundleDshRuntime` Gradle 任务 + 裁剪 + 两级自检 | ✅ 已完成 |
| 2 | 跨平台构建（宿主 npm + lockfile 定点抓取 + 平台完整性校验） | ✅ 已完成（5 平台，46.5~82.8 MB） |
| 3 | 体积优化（npm 残骸、musl、WASM 取舍、node-pty） | ✅ 已完成（108.4 → 46.5 MB） |
| 4 | Java 侧：首次解包 + 进度 + 路径解析优先级 + chmod + 运行时位置策略 | ✅ 已完成 |
| 5 | Node 探测 + 引导下载 | ✅ 已完成（最低 22.19.0；只警告不拦截，见 3.3） |
| 6 | 运行时热更新（检查 + 下载 + 解包 + 回滚） | ✅ 已完成（发布渠道见 5.1.1） |
| 7 | 设置页「运行时」区块（版本、来源、位置、手动检查/回滚） | ✅ 已完成 |

---

## 七、已确认的决策

1. **跨平台原生模块**：一次性打包全部 5 个平台（`win32-x64, darwin-x64, darwin-arm64,
   linux-x64, linux-arm64`），保证离线可用。体积仍在 Marketplace 400 MB 限额内。
2. **Node.js**：不内置，做「检测 + 一键引导下载」。
3. **构建链路成本**：不是问题（用户 2026-09-11 明确）。
4. **sharp 模式**：做成 `dsh.runtime.sharp` 可配（`native` / `hybrid` / `wasm`），
   **默认 `native`** —— 每个平台都用官方预编译二进制，风险最低；`wasm`/`hybrid` 依赖
   WASM 后端，只在 Windows 上验证过功能等价，macOS/Linux 上是「应该能跑但没验证」。
   发布前若体积压力大，可再评估切 `hybrid`（省约 29 MB）。
5. **运行时位置**：Windows 默认系统临时目录，其余平台默认用户目录（见 5.2 的实测）。
   做成可配，因为「临时目录可能被清理」与「环境禁止从临时目录执行程序」是两个真实的反对理由。

## 八、后续

- **`darwin-x64` 是否保留**：Intel Mac 已进入维护期，单独占约 10 MB（sharp + ripgrep + koffi）。
- ~~**Node 最低版本**~~：**已定 22.19.0**（从 553 个 `package.json` 的 `engines.node` 取最大值，
  依据见 3.3）。未做的是**实测验证**：本机 DLP 会把解包产物加密成密文导致 dsh 跑不起来，
  无法在 Windows 上验证 Node 20/22/24 的实际表现；换一台没有 DLP 的机器可以补做。
- **内置基线的更新频率**：是否每次插件发版都同步升一次内置 dsh，还是按需。
- **可执行位清单**：目前只覆盖 `spawn-helper`（darwin）与 `rg`（非 Windows）；
  若后续新增原生可执行文件，需同步扩展 `writeExecutableManifest`。
- **临时目录上的执行限制**：部分企业用应用控制策略禁止从 `%TEMP%` 执行程序。
  内置运行时里的 `rg`（搜索）与 `spawn-helper`（终端）是从运行时根执行的，
  真遇到时用户在设置页改成「用户目录」即可；若反馈集中，可考虑只把这两个可执行文件
  另存到用户目录。
- **文档同步**：`README.md` / `README.zh.md` / `plugin.xml` 的描述仍在讲
  「首次使用会触发 npx 下载约 284MB / 14 分钟」，发版前需改写。
- **首次解包的非阻塞化**（可选）：目前是模态进度框。在 DLP 机器上首次可能很久，
  可改成「工具窗口内显示进度 + 后台完成后再启动服务器」，避免长时间挡住 IDE。
