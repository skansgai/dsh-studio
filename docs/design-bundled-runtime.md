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

### 3.3 Node.js 的处理

| 方案                  | 优点                | 缺点                        |
| ------------------- | ----------------- | ------------------------- |
| 一并内置 Node           | 真正零依赖             | +约 30 MB/平台（三平台 ≈ +90 MB） |
| **检测 + 一键引导下载**（推荐） | 体积小；Node 用户本机多半已有 | 缺 Node 时需要一次联网            |

推荐**后者**：启动时探测 `node --version`，满足最低版本就直接用；不满足或缺失时，  
弹出一次带进度的引导下载（明确写清版本、体积、耗时，可取消）。绝大多数目标用户（Node 生态开发者）  
本机已有 Node，实际很少触发。

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

## 五、实施顺序

| 阶段 | 内容 | 状态 |
| -- | -- | -- |
| 1 | `bundleDshRuntime` Gradle 任务 + 裁剪 + 两级自检 | ✅ 已完成 |
| 2 | 跨平台构建（宿主 npm + lockfile 定点抓取 + 平台完整性校验） | ✅ 已完成（5 平台，46.5~82.8 MB） |
| 3 | 体积优化（npm 残骸、musl、WASM 取舍、node-pty） | ✅ 已完成（108.4 → 46.5 MB） |
| 4 | Java 侧：首次解包 + 进度 + 路径解析优先级 + chmod | ⬜ 待做 |
| 5 | Node 探测 + 一键引导下载 | ⬜ 待做 |
| 6 | 运行时热更新（后台检查 + 静默下载 + 回退） | ⬜ 待做 |
| 7 | 设置页「运行时」区块（版本、来源、手动检查/回滚） | ⬜ 待做 |

---

## 六、已确认的决策

1. **跨平台原生模块**：一次性打包全部 5 个平台（`win32-x64, darwin-x64, darwin-arm64,
   linux-x64, linux-arm64`），保证离线可用。体积仍在 Marketplace 400 MB 限额内。
2. **Node.js**：不内置，做「检测 + 一键引导下载」。
3. **构建链路成本**：不是问题（用户 2026-09-11 明确）。
4. **sharp 模式**：做成 `dsh.runtime.sharp` 可配（`native` / `hybrid` / `wasm`），
   默认 `native`；具体默认值待发布决策。

## 七、后续

- **确定 `dsh.runtime.sharp` 的默认值**：需要在「下载体积」与「大图处理的内存/耗时」之间定夺。
- **`darwin-x64` 是否保留**：Intel Mac 已进入维护期，单独占约 10 MB（sharp + ripgrep + koffi）。
- **Node 最低版本**：dsh 各包均未声明 `engines`，需实测确定（建议从 Node 20 LTS 起验证）。
- **内置基线的更新频率**：是否每次插件发版都同步升一次内置 dsh，还是按需。
- **可执行位清单**：目前只覆盖 `spawn-helper`（darwin）与 `rg`（非 Windows）；
  若后续新增原生可执行文件，需同步扩展 `writeExecutableManifest`。
