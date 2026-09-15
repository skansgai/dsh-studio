import groovy.json.JsonSlurper
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.deepseek"
version = "0.4.3"

repositories {
    mavenCentral()
    // IntelliJ Platform Gradle Plugin 2.x 需要这行： IDE 发行版不在 Maven Central
    intellijPlatform {
        defaultRepositories()
    }
}

// 显式钉死 Java 17 工具链。
// 不设的话 IJPGP 2.x 会按目标 IDE（2024.2 内部用 JDK 21）要求 languageVersion=21，
// 而本机只有 JDK 17。插件字节码目标同样是 17（见下方 options.release），
// 在 IDE 自带的 JDK 21 上正常运行。
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// ── IDE 选择 ────────────────────────────────────────────────────────────────
// 优先级：
//   1) dsh.ide.localPath   — 显式指定本机已安装/已解压的 IDE
//   2) 自动识别 Android Studio 默认安装路径
//   3) 自动复用 Gradle 缓存中已解压的 IntelliJ Community（1.x 时代下载过的发行版，免重复下载）
//   4) 以上都没有：按 dsh.ide.type / dsh.ide.version 下载（默认 IC）
// 必须声明在 dependencies 之前：Gradle 脚本自上而下求值。
val ideVersion: String = providers.gradleProperty("dsh.ide.version").orElse("2024.2.3").get()

/**
 * 在 Gradle 缓存里找已解压的 IntelliJ Community 发行版。
 * 只认含 build.txt 的目录，避免命中未解压的 zip 或元数据文件。
 */
fun findCachedIde(version: String): String? {
    val root = File(
        gradle.gradleUserHomeDir,
        "caches/modules-2/files-2.1/com.jetbrains.intellij.idea/ideaIC/$version"
    )
    return root.takeIf { it.isDirectory }
        ?.listFiles()
        ?.asSequence()
        ?.mapNotNull { hashDir ->
            File(hashDir, "ideaIC-$version")
                .takeIf { File(it, "build.txt").isFile }
        }
        ?.firstOrNull()
        ?.absolutePath
}

val autoDetectedAs = File("C:\\Program Files\\Android\\Android Studio")
val ideLocalPath: String? = providers.gradleProperty("dsh.ide.localPath").orNull
    ?.takeIf { it.isNotBlank() }
    ?: if (autoDetectedAs.isDirectory) autoDetectedAs.absolutePath
    else findCachedIde(ideVersion)

dependencies {
    intellijPlatform {
        if (ideLocalPath != null) {
            // 本机已有 IDE：零下载，构建最快
            local(ideLocalPath)
        } else {
            create(
                providers.gradleProperty("dsh.ide.type").orElse("IC"),
                providers.gradleProperty("dsh.ide.version").orElse("2024.2.3")
            )
        }
        // Marketplace 的 ZIP 签名器，signPlugin 依赖它。**必须显式声明**：
        // 不声明时 signPlugin 会直接失败（"No Marketplace ZIP Signer executable found"），
        // 而 buildPlugin 照常成功 —— 于是很容易以为「构建没问题」，
        // 到上传时才发现在市场上传不了签名包。
        zipSigner()
    }
    testImplementation("junit:junit:4.13.2")
}

// ── 发布 / 签名配置（JetBrains Marketplace）────────────────────────────────
// 敏感信息一律不写进仓库：
//   1) 命令行 -P 参数（如 -PsignPlugin.privateKeyFile=...）
//   2) 环境变量（ORG_GRADLE_PROJECT_signPlugin.privateKeyFile=...）
//   3) 本地文件 signing.local.properties（已被 .gitignore 忽略）
val dshLocalProps = Properties().apply {
    rootProject.file("signing.local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun dshProp(name: String): String? =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: dshLocalProps.getProperty(name)?.takeIf { it.isNotBlank() }

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
        // description / changeNotes 的其余部分沿用 plugin.xml 中的内容
        changeNotes = """
            <h3>0.4.3</h3>
            <ul>
              <li><b>修复「背景图设置第一次打开不出现」</b>：注入到 dsh 网页「通用设置」里的背景卡片，此前靠「页面上同时出现外观 + 语言两行」来判断是否停在通用设置页，而这两行由独立懒加载的客户端插件（dsh-client-ui-theme / dsh-client-locale）提供，首次打开时还没注册，于是判定为假、卡片不注入 —— 切一次语言让整页重渲染后才出现。现在改为看设置导航里高亮的是不是「通用设置」，并新增 MutationObserver，在设置面板渲染出来的那一刻就注入，不再等最多 2 秒的轮询。</li>
              <li>功能行还没加载出来时，背景卡片退到内容区末尾显示，而不是完全不出现；同时避免 React 重绘后出现重复卡片。</li>
              <li>设置弹窗的识别改为「可见 + 带设置内容标记」，不再盲取页面上第一个 role="dialog"，避免被其它浮层或提示节点干扰。</li>
              <li>卡片注入失败时会在工具窗口日志里给出原因（no-settings-dialog / settings-page-mismatch 等），便于反馈定位。</li>
            </ul>
            <h3>0.4.2</h3>
            <ul>
              <li><b>不再内置运行时，回到轻量插件包（约 115KB）</b>：内置五平台运行时会让每次小版本发布都全量重下 80+MB，且 5 个平台里 4 份对用户是浪费、还把第三方原生二进制带进了插件包的供应链/安全审查面。0.4.2 起插件包不再包含任何运行时。</li>
              <li><b>首次启动改为下载当前平台包</b>：复用已有的热更新链路——检测到本地没有 dsh 时，从本仓库的 GitHub Release 拉取当前平台约 41MB 的包，sha256 校验通过后再原子解包安装并启动。首次体验只是「等一次 41MB 下载」；插件包体积、分发与每次小版本发布的下载成本都不受影响。访问不了 GitHub Release 的环境，可在 Release 页手动下载「全平台离线包」导入。</li>
              <li>「运行时来源」去掉了「仅内置运行时」选项，保留「自动（优先已下载运行时，回退系统 dsh）」与「仅系统 dsh（npx）」。</li>
            </ul>
            <h3>0.4.1</h3>
            <ul>
              <li><b>修复企业透明加密（DLP）下的启动失败</b>：部分环境会按读进程白名单把解包出的运行时文件加密，导致 node 加载 package.json 时报 ERR_INVALID_PACKAGE_CONFIG。现在改由 node 侧自检运行时是否可读：读不到就自动回退到系统 dsh（AUTO 模式）或在设置页给出操作指引（BUNDLED 模式），并在启动日志与通知栏提示。</li>
            </ul>
            <h3>0.4.0</h3>
            <ul>
              <li><b>内置 dsh 运行时，开箱即用</b>：插件自带裁剪过的 dsh（含全部 Node 依赖），首次使用时在本地解包即可运行，
                  不再需要 <code>npx --yes @deepseek-ai/dsh</code> 现下 284MB、等十几分钟。一次打包覆盖
                  Windows x64 / macOS（Intel、Apple Silicon）/ Linux（x64、arm64）五个平台。
                  解包位置默认在系统临时目录（用户目录与项目目录在本机被透明加密，写 1.8 万个小文件要慢约 200 倍），
                  可在「设置 → 工具 → DeepSeek Harness → 运行时」里改。</li>
              <li><b>运行时热更新</b>：设置页可检查 dsh 新版本，发现后<b>先问再下</b>（约 41MB，只含本机平台），
                  sha256 校验通过后才原子安装，下次启动服务器生效。想回滚把「运行时来源」改成「仅内置运行时」即可，
                  不需要额外机制。安装中途断网或被打断不会留下半个版本目录骗过下一次启动。</li>
              <li><b>Node.js 探测与引导</b>：启动前检测 Node.js，低于依赖包 <code>engines</code> 声明的最低版本时
                  给出提示与安装指引（只提示、不拦截）。</li>
              <li><b>打包加固</b>：构建时逐条校验运行时包内文件的明文（本机透明加密会在打包时混进密文，
                  那种包发到用户机器上 node 读到的是密文、原生模块加载不了），命中直接让构建失败；
                  构建工作目录也移出了加密范围，同一个打包任务从 90+ 分钟降到约 4 分钟。</li>
              <li><b>修复</b>：<code>overlay.js</code> 改名为 <code>overlay.js.txt</code>，避免被透明加密后误提交成密文。</li>
            </ul>
            <h3>0.3.1</h3>
            <ul>
              <li><b>适配 dsh 0.1.2-rc.1 的启动令牌鉴权</b>：新版 dsh 的首页必须带 <code>?token=</code> 才能打开，
                  直接访问会被拒绝（401 <code>dsh web authentication required</code>）。插件现在会从自己拉起的 dsh 进程输出里
                  自动捕获 token 并拼到访问地址上；若复用的是外部已在运行的实例（拿不到 token），会明确提示，
                  并引导你从启动它的终端复制带 token 的地址填进「设置 → 服务器地址」。</li>
              <li><b>修复市场/IDE 中插件图标不显示</b>：将 <code>pluginIcon.svg</code> 从 JAR 根目录移到规范的 <code>META-INF/</code> 位置，并改为 40×40、无渐变（纯色）的扁平写法，规避市场 SVG 清洗器对 <code>&lt;defs&gt;</code>/渐变与 <code>width/height=240</code> 的丢弃，确保图标正常渲染。</li>
              <li><b>设置页新增「关于」区块</b>：显示插件版本与 DeepSeek Harness (dsh) 的 npm 最新版本，并提供「检查更新」按钮，一键比对插件（Marketplace）与 dsh（npm）是否有新版本并给出升级指引。</li>
              <li><b>提示并一键清理残留的旧 dsh 实例</b>：<code>npx --yes</code> 会把 dsh 静默升级，升级前启动的进程却仍在跑，
                  它会继续按旧结构产出 boot manifest 并从已升级的包里读取新 bundle，页面会报
                  <code>client-modules: boot manifest batches must be an array</code>。现在启动若发现端口被非本插件的实例占用，
                  会给出提示并提供「结束占用进程并重启」。</li>
            </ul>
            <h3>0.3.0</h3>
            <ul>
              <li><b>背景图 + 透明度控制项注入进 dsh 网页「通用设置」面板</b>：在 dsh 网页「设置 → 通用设置」面板内自动插入
                  「DeepSeek Harness Studio」卡片（选背景图 / 调浮层透明度 0–60%），不再是右下角悬浮齿轮；背景以 fixed +
                  pointer-events:none 的 CSS 半透明浮层叠在 dsh 界面之上（不挡点击）。</li>
              <li><b>修复刷新后背景图丢失</b>：改为页面加载完成自动重注入，背景图由插件端持久化，刷新 / 重启 IDE 后保留；并修复「移除背景」无效。</li>
              <li>IntelliJ 设置页（Settings → Tools → DeepSeek Harness）不再包含背景图/透明度项，仅保留「通用设置 / 服务器 / 启动选项」分区与界面主题。</li>
              <li>顶部状态条与“等待连接”空白页保留原有背景图。</li>
              <li>精简设置项：移除了上一版的“驱动 dsh 自身主题”与“插件市场”两项。</li>
            </ul>
            <h3>0.2.3</h3>
            <ul>
              <li><b>修复 2023.1 的 IconManager 兼容性</b>：<code>IconManager.getIcon(String, ClassLoader)</code>
                  在 IntelliJ IDEA 2023.1 (IU-231) 上不存在，导致 3 个 compatibility problems。
                  改回 <code>IconManager.getIcon(String, Class&lt;?&gt;)</code>，该重载在 2023.1–2026.x
                  全版本存在；在新版平台上属于 deprecated API，仅为警告，不影响上架。</li>
              <li>延续 0.2.2：JCEF 类访问全部通过 <code>DshJcefSupport</code> 反射完成，
                  主插件字节码无 <code>com.intellij.ui.jcef</code> 直接引用。</li>
            </ul>
            <h3>0.2.2</h3>
            <ul>
              <li><b>修复 2023.1 市场的 Critical 兼容性</b>：把 <code>JBCefApp</code> / <code>JBCefBrowser</code>
                  等 JCEF 类的直接引用全部改为反射调用，主插件字节码不再包含 <code>com.intellij.ui.jcef</code>
                  引用，从而消除 IU-231 等旧平台上的 3 个 compatibility problems</li>
              <li>保留可选依赖 <code>com.intellij.modules.jcef</code>：2026.x 平台加载该模块后
                  JCEF 类可见；旧平台模块不存在时，工具窗口自动回退为说明面板</li>
            </ul>
            <h3>0.2.1</h3>
            <ul>
              <li><b>修复兼容性</b>：JCEF 模块依赖改为可选（<code>optional="true"</code>），解决 2023.1–2025.2 平台
                  因缺失 <code>com.intellij.modules.jcef</code> 而报 “missing mandatory dependency” 导致无法安装的问题</li>
              <li><b>消除市场验证警告</b>：替换 1 处 scheduled-for-removal API（<code>FileTypeDescriptor</code>）
                  与 3 处 deprecated API（<code>IconManager.getIcon(String, Class)</code>）</li>
              <li>为可选依赖补充 <code>config-file</code> 声明，消除 “plugin configuration defect”</li>
              <li>说明：JCEF 不可用时工具窗口自动回退为说明面板（<code>JBCefApp.isSupported()</code> 运行时判断）</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li><b>发送代码到 Harness</b>：编辑器右键即可把选中代码（或整个文件）连同文件定位提交到会话，agent 在工具窗口中执行</li>
              <li><b>状态栏小部件</b>：四色实时显示服务器状态（已连接 / 启动中 / 失败 / 未运行），点击直达工具窗口</li>
              <li><b>会话快速访问</b>：搜索式弹窗列出历史会话（标题 / 相对时间 / 运行状态），选中后打开工具窗口并复制会话 ID</li>
              <li><b>Headless 快捷任务</b>：Tools 菜单运行一次性 <code>dsh --profile headless</code> 任务，输出实时进入 Server Log</li>
              <li>内置 dsh Remote API 客户端</li>
              <li>修正：服务器启动命令默认追加 <code>--no-open</code>，不再额外弹出系统浏览器窗口</li>
              <li>构建升级到 IntelliJ Platform Gradle Plugin 2.x（支持 2024.2+ 目标平台）</li>
            </ul>
            <h3>0.1.7</h3>
            <ul>
              <li>图标加载改用 IconManager（消除市场验证的 deprecated / scheduled-for-removal 警告）</li>
            </ul>
            <h3>0.1.6</h3>
            <ul>
              <li>改用官方推荐的单参数 IconLoader.getIcon(path)（避免计划移除的 API）</li>
            </ul>
            <h3>0.1.5</h3>
            <ul>
              <li>消除市场验证警告：替换计划移除的 IconLoader.getIcon(String, Class) API</li>
            </ul>
            <h3>0.1.4</h3>
            <ul>
              <li>修复插件图标：移至 JAR 根目录 pluginIcon.svg（市场将显示自定义图标而非默认图标）</li>
            </ul>
            <h3>0.1.3</h3>
            <ul>
              <li>修复：状态变为已连接时立即切换并加载网页（不再卡在背景图空白页）</li>
              <li>修复：内嵌浏览器不可用时显示说明面板</li>
            </ul>
            <h3>0.1.2</h3>
            <ul>
              <li>移除不起作用的实验性覆盖层（Swing 无法盖住原生浏览器窗口）；保留主题与背景图（顶部条/空白页）</li>
            </ul>
            <h3>0.1.1</h3>
            <ul>
              <li>新增界面主题（跟随 IDE / 浅色 / 深色）与背景图片设置</li>
              <li>修复内嵌页面布局异常：移除了会破坏侧边栏/设置的 matchMedia 覆盖</li>
            </ul>
            <h3>0.2.0</h3>
            <ul>
              <li>新增：发送代码到 Harness（编辑器右键，把选区或整个文件连同指令发给会话，自动起服务器并打开工具窗口）</li>
              <li>新增：状态栏小部件（彩色服务器状态指示，点击打开工具窗口）</li>
              <li>新增：会话快速访问（Tools 菜单搜索式弹窗，复制 sessionId 并打开工具窗口）</li>
              <li>新增：Headless 任务（在 IDE 内运行一次性 <code>dsh --profile headless</code> 任务）</li>
              <li>修复：plugin.xml 补 JCEF 模块依赖，修复真实 IDE 中打开工具窗口的 NoClassDefFoundError</li>
              <li>修复：发送代码状态机（启动看门狗定时探测、异常完整捕获并提示），消除“点了没反应”</li>
              <li>构建升级到 IntelliJ Platform Gradle Plugin 2.18.1 + Gradle 9.7.1</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>内嵌浏览器工具窗口（JCEF），直接在 Android Studio / IDEA 中打开 DeepSeek Harness 界面</li>
              <li>一键启动 / 停止 <code>dsh web</code> 服务器（Node.js / npx，可自定义命令）</li>
              <li>服务器健康状态检测与实时日志面板</li>
              <li>系统浏览器打开、地址 / 端口 / 工作目录 / DSH_HOME 可配置</li>
            </ul>
        """.trimIndent()
        ideaVersion {
            // 兼容范围下限保持 231（Android Studio 2023.1+ / IDEA 2023.1+），不设上限
            sinceBuild = "231"
        }
    }

    // 本插件未使用 SearchableOptions，关闭该任务以加速构建
    buildSearchableOptions = false

    pluginVerification {
        // 只用本机已安装的 IDE 做验证（零下载，避免 1GB+ 的远程 IDE 下载卡住构建）：
        //   Android Studio 2023.1 (AI-232) —— 市场扫描报 missing mandatory dependency 的最老版本
        //   IntelliJ IDEA 2026.2.1         —— 用户真机版本
        // 需要覆盖其它版本时，取消下面的注释（会触发 1GB+ 的远程 IDE 下载，本机网络很慢）：
        //   create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7.1")
        //   create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
        ides {
            // 注：插件的 since-build 仍是 231（支持 2023.1+），但 pluginVerifier 1.410 自身
            // 最低只支持 233，因此本机 Android Studio 2023.1 (AI-232) 不能作为验证目标。
            // 默认用本机 IDE（ideLocalPath，通常是 ideaIC 2024.2.3）验证，零下载；
            // 更老/更新版本的兼容性由 JetBrains 市场扫描覆盖。
            val localIde: String? = ideLocalPath
            if (localIde != null) {
                local(localIde)
            }
        }
    }

    signing {
        dshProp("signPlugin.certificateChainFile")?.let { certificateChainFile = file(it) }
        dshProp("signPlugin.privateKeyFile")?.let { privateKeyFile = file(it) }
        dshProp("signPlugin.password")?.let { password = it }
    }

    publishing {
        dshProp("intellijPlatformPublishingToken")?.let { token = it }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    // verifyPluginSignature 读的是 signPlugin 的产物。Gradle 9 对隐式依赖是硬错误，
    // 不声明的话一条命令同时跑两个任务会直接失败。
    named("verifyPluginSignature") {
        dependsOn("signPlugin")
    }
}

// ════════════════════════════════════════════════════════════════════════════
// dsh 运行时打包（bundleDshRuntime：产物供 Release 离线包与 CI，不再打进 JAR）
// ════════════════════════════════════════════════════════════════════════════
//
// 为什么：现在首次启动靠 `npx --yes @deepseek-ai/dsh` 现拉，实测要下约 284 MB、
// 耗时十几分钟，试用转化基本被吃掉。竞品 33555 内置运行时（40.7 MB）后体验是「秒开」。
//
// 实测（2026-09-11）：dsh 依赖闭包 457 个包 / 210.7 MB，压缩 54.6 MB；
// 裁掉 sourcemap、`.d.ts`、文档、测试后压到 35.5 MB —— 与竞品基本持平，
// 远低于 Marketplace 400 MB 上限。详见 docs/design-bundled-runtime.md。
//
// 产物：build/dsh-runtime/dsh-runtime.zip，被打进 JAR 的 dsh-runtime/ 下，
//       插件首次运行时解包到用户目录使用（Java 侧在后续里程碑实现）。
//
// 可选配置（gradle.properties 或 -P）：
//   dsh.runtime.version   内置的 dsh 版本，默认见下方（与插件版本解耦）
//   dsh.runtime.targets   目标平台，逗号分隔，默认覆盖 5 个主流平台
//   dsh.runtime.node      node 可执行文件，默认 PATH 里的 node
//   dsh.runtime.npm       npm 可执行文件，默认 PATH 里的 npm
//   dsh.runtime.skip      true 则跳过打包（产物里不带内置运行时）

/**
 * 把 dsh 运行时按平台解析、合并、裁剪、校验后打成一个 zip。
 *
 * 流程分三步：
 *  1. 在构建机上跑**一次** `npm install`（宿主平台），拿到纯 JS 依赖树 + lockfile；
 *  2. 从 lockfile 里读出其余目标平台的「平台专有包」，按 tarball 地址定点抓取解包，
 *     拼成一棵含全部目标平台原生模块的树，保证一个包在 Windows/macOS/Linux 都能用；
 *  3. 裁剪、两级自检、平台完整性校验后打包。
 *
 * 安装一律带 `--ignore-scripts`，原因见 [resolveTarget] 的注释。
 */
abstract class BundleDshRuntimeTask : DefaultTask() {

    @get:Input
    abstract val dshVersion: Property<String>

    /** 目标平台，形如 `win32-x64`。 */
    @get:Input
    abstract val targets: ListProperty<String>

    /** npm 启动命令（可能含参数，如 Windows 上的 `cmd /c npm`）。 */
    @get:Input
    abstract val npmCommand: ListProperty<String>

    @get:Input
    abstract val nodeExecutable: Property<String>

    /** true 时忽略已有的解析结果，强制重新解析依赖。 */
    @get:Input
    abstract val forceRefresh: Property<Boolean>

    /**
     * sharp（图片处理）的打包方式：
     *  - `native`：各平台原生二进制 + libvips，最快最省内存，但 5 个平台合计约 40 MB；
     *  - `wasm`  ：只带 WASM 版（约 3.4 MB），体积小一个量级，代价是慢 1.5~2 倍、大图内存高；
     *  - `hybrid`：仅 Windows 带原生版，其余平台走 WASM。
     */
    @get:Input
    abstract val sharpMode: Property<String>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputFile
    abstract val outputZip: RegularFileProperty

    /**
     * 运行时元数据（与 zip 并列打进 JAR）。
     *
     * 插件侧只读这一个小文件就能知道「内置的是哪个 dsh、要不要重新解包」，
     * 不必去解 80 MB 的 zip。`stamp` 是 zip 内容的 sha256 前缀，内容一变就变。
     */
    @get:OutputFile
    abstract val outputMeta: RegularFileProperty

    /**
     * 按平台拆分的运行时包目录（`dsh-runtime-<target>.zip` + 各自的 meta）。
     *
     * 热更新用它们：整包 79 MB，而用户只需要自己那一个平台的 37 MB（实测省 53%，
     * 差额几乎全是别的平台的 sharp/libvips 与 ripgrep 二进制）。插件包内仍然用合并包，
     * 这样一个插件包就能服务所有平台。
     */
    @get:OutputDirectory
    abstract val outputSplitDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    /** 运行时用不到的目录（实测裁掉可省约 19 MB 压缩体积）。 */
    private val prunableDirs = setOf(
        "test", "tests", "__tests__", "spec", "specs",
        "examples", "example", "docs", "doc"
    )

    /** 运行时用不到的文件后缀（`pdb` 是调试符号，单个文件能有好几 MB）。 */
    private val prunableExtensions = setOf("map", "md", "markdown", "pdb")

    /** 这些文件虽然后缀在裁剪列表里，但要保留（合规/说明）。 */
    private val keepFileNames = setOf("license", "licence", "notice", "copying")

    /**
     * 整个包都不打包的清单（按 `@scope/name` 匹配，`*` 为通配），随 [sharpMode] 变化。
     *
     *  - **musl 变体**（约 15.5 MB）：JetBrains IDE 本身就不支持 musl / Alpine，
     *    用户机器上永远用不到 `linuxmusl-*`；
     *  - **WASM 兜底**（约 3.4 MB）：只有在该平台没有原生二进制时 sharp 才会退回 WASM；
     *  - **原生 sharp + libvips**（5 个平台约 40 MB）：`sharpMode=wasm` 时全部换成
     *    单个 `@img/sharp-wasm32`，体积小一个量级。
     */
    private val excludedPackages: List<String>
        get() = buildList {
            add("@img/sharp-freebsd-*")         // 不发布的平台
            add("@img/sharp-webcontainers-*")   // WebContainer 专用，跟 IDE 无关
            when (sharpMode.get().lowercase()) {
                "wasm" -> {
                    add("@img/sharp-libvips-*")
                    add("@img/sharp-darwin-*")
                    add("@img/sharp-linux*")    // 同时覆盖 linuxmusl-*
                    add("@img/sharp-win32-*")
                }
                "hybrid" -> {
                    // Windows 用原生，其余平台交给 WASM
                    add("@img/sharp-libvips-*")
                    add("@img/sharp-darwin-*")
                    add("@img/sharp-linux*")
                }
                else -> {
                    // native：5 个平台都有原生包，WASM 兜底轮不到；musl 也不支持
                    add("@img/sharp-wasm32")
                    add("@img/sharp-libvips-linuxmusl-*")
                    add("@img/sharp-linuxmusl-*")
                }
            }
        }

    /**
     * 与平台无关、但必须存在的包，随 [sharpMode] 变化。
     *
     * 裁剪是就地删文件，所以从 `native` 切到 `wasm` 后需要把 WASM 版 sharp 找回来。
     * 这类包不带 os/cpu 约束，[fetchPlatformPackages] 覆盖不到，由 [ensurePackages] 单独补。
     */
    private val extraPackages: List<String>
        get() = when (sharpMode.get().lowercase()) {
            "wasm", "hybrid" -> listOf("@img/sharp-wasm32", "@emnapi/runtime")
            else -> emptyList()
        }

    /** 每个目标平台必须齐全的原生包；内层多个候选表示「满足其一即可」。 */
    private fun requiredNatives(osName: String): List<List<String>> = buildList {
        add(listOf("@koromix/koffi-%s-%s"))          // FFI，dsh-subprocess-local 依赖
        add(listOf("@vscode/ripgrep-%s-%s"))         // 内置 ripgrep
        when (sharpMode.get().lowercase()) {
            "wasm" -> add(listOf("@img/sharp-wasm32"))
            "hybrid" -> if (osName == "win32") {
                add(listOf("@img/sharp-%s-%s"))
            } else {
                add(listOf("@img/sharp-wasm32"))
            }
            else -> {
                add(listOf("@img/sharp-%s-%s"))      // 图片处理，dsh-attachment-local 依赖
                // libvips 只有 darwin/linux 拆成独立包，且分 glibc / musl 两种变体
                if (osName != "win32") {
                    add(listOf("@img/sharp-libvips-%s-%s", "@img/sharp-libvips-%smusl-%s"))
                }
            }
        }
    }

    companion object {
        /** 某个平台解析成功后写下的标记；内容含版本与参数指纹，参数一变即失效。 */
        private const val MARKER_FILE = ".dsh-runtime-install-ok"

        /** 记录哪些文件需要可执行位；解包后由插件统一 chmod。 */
        private const val EXEC_MANIFEST = ".dsh-runtime-executables"

        /**
         * 本机 DLP 加密文件的文件头魔数。命中就说明这个文件在别人的机器上读出来是乱码。
         *
         * **必须放在 companion object 里，不能提到脚本顶层。** Kotlin 脚本里，顶层类一旦引用
         * 脚本级声明，编译器就会把它生成为**非静态内部类**，Gradle 随后会报
         * `Class Build_gradle.BundleDshRuntimeTask is a non-static inner class` 并且无法实例化任务。
         * 常量放在 companion 里没有这个问题（`writeZip` 会用到它）。
         */
        const val DLP_MAGIC = "E-SafeNet"

        /**
         * 构建机自身的平台标识，形如 `win32-x64`。
         *
         * 只有这个平台的二进制能在构建机上真正跑起来，所以它必须作为基底并被自检覆盖。
         */
        fun hostTarget(): String {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val osName = when {
                os.contains("win") -> "win32"
                os.contains("mac") || os.contains("darwin") -> "darwin"
                else -> "linux"
            }
            val cpuName = when (arch) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i486", "i586", "i686" -> "ia32"
                else -> arch
            }
            return "$osName-$cpuName"
        }
    }

    @TaskAction
    fun bundle() {
        val work = workDir.get().asFile
        work.mkdirs()

        val version = dshVersion.get()
        val platformTargets = targets.get()
        check(platformTargets.isNotEmpty()) { "dsh.runtime.targets 不能为空" }

        // 基底必须是构建机自己的平台：只有它的二进制能在这里真正执行，后面的自检才有意义。
        val host = hostTarget()
        check(host in platformTargets) {
            "当前构建机平台是 $host，但它不在 dsh.runtime.targets（$platformTargets）里。" +
                "自检需要在构建机上真实运行一次 dsh，请把 $host 加进目标列表，" +
                "或换一台属于目标平台的机器来构建。"
        }

        // ── 1. 只解析宿主平台依赖（全流程唯一一次真正跑 npm）──────────────
        resolveTarget(work, host, version)

        // ── 2. 补齐平台专有包：按 lockfile 定点抓取 ──────────────────────
        // 不再对每个平台各跑一次 npm：那样既慢（每次都要重写上万文件），又会在中途
        // 留下含全部平台变体的脏树。见 fetchPlatformPackages 的说明。
        //
        // 这一步对**所有**目标平台（含宿主）执行，而不只是非宿主平台：因为裁剪是就地删
        // 文件，切换 sharp 模式后宿主自己的原生包也需要能自动找回来，不必重跑 npm。
        val merged = File(work, "raw/$host")
        val mergedModules = File(merged, "node_modules")
        check(mergedModules.isDirectory) { "基底平台的 node_modules 不存在：$mergedModules" }
        val lockFile = File(merged, "package-lock.json")
        check(lockFile.isFile) { "缺少 package-lock.json，无法确定各平台的专有依赖：$lockFile" }
        val fetched = fetchPlatformPackages(lockFile, merged, platformTargets)
        val ensured = ensurePackages(lockFile, merged, extraPackages)
        logger.lifecycle("[dsh-runtime] 补入平台专有包 $fetched 个、通用包 $ensured 个")

        // ── 3. 裁剪 ──────────────────────────────────────────────────────
        val trash = pruneNpmTrash(merged)
        val droppedPackages = pruneExcludedPackages(merged)
        val droppedNodePty = pruneNodePty(merged, platformTargets)
        val pruned = prune(merged)
        logger.lifecycle(
            "[dsh-runtime] 裁剪：npm 残骸 $trash 个文件、整包 $droppedPackages 个文件、" +
                "node-pty $droppedNodePty 个文件、通用规则 $pruned 个文件"
        )

        // ── 4. 校验：必须真能跑起来，否则宁可让构建失败 ──────────────────
        // 裁剪是「删文件」，一旦误删就会在用户机器上才暴露。这里做两级自检：
        //   a) `dsh --version`            —— 启动器与基本依赖是否完整
        //   b) `dsh --profile web --dump-config` —— 整个 web profile 的插件树能否组合出来，
        //      能发现缺包 / 补丁解析失败（比只看 --version 强得多）
        val dshBin = File(mergedModules, "@deepseek-ai/dsh/lib/bin.js")
        check(dshBin.isFile) { "未找到 dsh 入口：$dshBin（依赖解析失败？）" }

        val versionOut = ByteArrayOutputStream()
        execOps.exec {
            commandLine(nodeExecutable.get(), dshBin.absolutePath, "--version")
            standardOutput = versionOut
            errorOutput = versionOut
            isIgnoreExitValue = true
            timeout.set(Duration.ofMinutes(3))
        }
        val printed = versionOut.toString(Charsets.UTF_8.name()).trim()
        check(printed.contains(version)) {
            "内置运行时自检失败：期望版本 $version，实际输出「$printed」"
        }

        val dumpOut = ByteArrayOutputStream()
        execOps.exec {
            commandLine(
                nodeExecutable.get(), dshBin.absolutePath,
                "--profile", "web", "--dump-config"
            )
            standardOutput = dumpOut
            errorOutput = dumpOut
            isIgnoreExitValue = true
            timeout.set(Duration.ofMinutes(5))
        }
        val dump = dumpOut.toString(Charsets.UTF_8.name())
        val dumpLines = dump.lines().size
        check(dumpLines > 100 && dump.contains("@deepseek-ai/dsh-base")) {
            "内置运行时自检失败：web profile 组合异常（输出 $dumpLines 行）\n" +
                dump.take(2000)
        }
        logger.lifecycle(
            "[dsh-runtime] 自检通过：dsh $printed；web profile 组合正常（$dumpLines 行）"
        )

        // ── 4.5 平台完整性校验 ────────────────────────────────────────────
        // 上面的自检只能验证「构建机这一种平台」（别的平台的二进制根本没法在这里执行）。
        // 所以再按目标平台逐个点名，确认各自的原生包都在。这类包缺失时 JS 层完全看不出来，
        // 只有用户在那台机器上真正打开终端 / 贴图片时才会炸，必须在这里拦下。
        verifyPlatformNatives(merged, platformTargets)

        // ── 4.6 可执行位清单 ──────────────────────────────────────────────
        val executables = writeExecutableManifest(merged, platformTargets)
        logger.lifecycle("[dsh-runtime] 记录 $executables 个需要可执行位的文件")

        // ── 5. 打包 ──────────────────────────────────────────────────────
        // 合并包（含全部目标平台）—— 打进插件 JAR，一个插件包服务所有平台。
        val zip = outputZip.get().asFile
        val (entryCount, unpackedBytes) = writeZip(merged, zip)

        // ── 5.5 按平台拆包 ───────────────────────────────────────────────
        // 热更新用：用户只需要自己那一个平台的运行时。实测整包压缩后 79 MB，
        // 拆开每个约 37 MB（省 53%），差额几乎全是别的平台的 sharp/libvips 与 ripgrep 二进制。
        // 不物化拆分后的树（那是上万文件，本机带安全过滤驱动时得几十分钟），
        // 而是在写 zip 时按路径过滤掉别的平台的原生包。
        val splitDir = outputSplitDir.get().asFile
        splitDir.mkdirs()
        platformTargets.forEach { target ->
            val excludes = platformExclusions(lockFile, target, platformTargets)
            val targetZip = File(splitDir, "dsh-runtime-$target.zip")
            val (n, bytes) = writeZip(merged, targetZip) { rel ->
                excludes.none { rel == it || rel.startsWith("$it/") }
            }
            // 拆包靠的是「按 lockfile 的 os/cpu 约束剔路径」，规则写错就会悄悄少带东西 ——
            // 这类缺失在 JS 层完全看不出来，只有用户在那台机器上真正用终端/贴图片时才炸，
            // 所以每个拆出来的包都单独校验一遍原生包是否齐全。
            verifySplitZip(targetZip, target)
            writeMeta(File(splitDir, "runtime-meta-$target.txt"), targetZip, listOf(target), n, bytes)
            logger.lifecycle(
                "[dsh-runtime] 拆包 $target：%.1f MB（%d 个文件，解包后 %.1f MB）".format(
                    targetZip.length() / 1024.0 / 1024.0, n, bytes / 1024.0 / 1024.0
                )
            )
        }

        // ── 6. 元数据 ────────────────────────────────────────────────────
        // 插件侧解包前先读它：stamp 与本地已解包目录一致就跳过，避免每次启动都碰 80 MB 的 zip。
        // stamp 取 zip 内容的 sha256 前缀 —— 版本、目标平台、sharp 模式、裁剪规则任一变化都会
        // 产生新 stamp，从而解包到新目录，旧目录可安全清理。
        val metaFile = outputMeta.get().asFile
        val stamp = writeMeta(metaFile, zip, platformTargets, entryCount, unpackedBytes)

        logger.lifecycle(
            // 注意括号：`.format()` 只作用于紧邻的字符串字面量，用 `+` 拼出来的串必须整体括起来，
            // 否则前面那段里的 %.1f 不会被替换（会原样打进日志，看着像构建坏了）。
            (
                "[dsh-runtime] 完成：${zip.absolutePath}（%.1f MB，$entryCount 个文件，" +
                    "解包后 %.1f MB，stamp $stamp）"
                ).format(zip.length() / 1024.0 / 1024.0, unpackedBytes / 1024.0 / 1024.0)
        )
    }

    /**
     * 把 [root] 打成一个 zip。
     *
     * @param include 按相对路径（正斜杠）判断是否收录；按平台拆包时用来剔除别的平台的原生包
     * @return 条目数与解包后总字节数
     */
    private fun writeZip(
        root: File,
        zip: File,
        include: (String) -> Boolean = { true }
    ): Pair<Int, Long> {
        zip.parentFile.mkdirs()
        zip.delete()
        val rootPath = root.toPath()
        var entryCount = 0
        var unpackedBytes = 0L
        // 边打包边校验明文：本机 DLP 会按路径/内容把文件异步加密成密文，而 java 进程
        // 读到的就是密文字节，会原样进 zip。用户那边没有 DLP，看到的就是乱码 ——
        // 与其发一个自己都跑不起来的包，不如构建就失败。
        val encrypted = mutableListOf<String>()
        ZipOutputStream(zip.outputStream().buffered()).use { zos ->
            Files.walk(rootPath).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    // 安装标记是构建期用的，不进产物
                    .filter { it.fileName?.toString() != MARKER_FILE }
                    .map { it to rootPath.relativize(it).joinToString("/") { n -> n.toString() } }
                    .filter { (_, name) -> include(name) }
                    .sorted(compareBy { it.second })
                    .forEach { (path, name) ->
                        zos.putNextEntry(ZipEntry(name))
                        Files.newInputStream(path).use { input ->
                            val head = input.readNBytes(64)
                            if (String(head, Charsets.ISO_8859_1).contains(DLP_MAGIC)) {
                                encrypted += name
                            }
                            zos.write(head)
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                        entryCount++
                        unpackedBytes += Files.size(path)
                    }
            }
        }
        if (encrypted.isNotEmpty()) {
            // 先把坏产物删掉，免得被后续步骤当成正常产物用
            zip.delete()
            throw GradleException(
                buildString {
                    append("打包时发现 ${encrypted.size} 个文件已被本机透明加密软件加密（文件头 $DLP_MAGIC）。\n")
                    append("这些文件原样打进 zip 后，用户机器上 node 读出来是乱码，会直接跑不起来：\n")
                    encrypted.take(10).forEach { append("  - ").append(it).append('\n') }
                    if (encrypted.size > 10) append("  ...（其余 ${encrypted.size - 10} 个略）\n")
                    append("构建工作目录应位于系统临时目录（见 dshRuntimeScratchDir()），")
                    append("若仍出现请把该目录加入 DLP 排除名单后重试。")
                }
            )
        }
        return entryCount to unpackedBytes
    }

    /** zip 内容的 sha256 前 8 位。版本/平台/sharp/裁剪规则任一变化都会产生新 stamp。 */
    private fun sha256Prefix(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(8)
    }

    /** 写运行时元数据；插件侧靠它判断「内置/已下载的是哪个 dsh、要不要重新解包」。返回 stamp。 */
    private fun writeMeta(
        metaFile: File,
        zip: File,
        targets: List<String>,
        entryCount: Int,
        unpackedBytes: Long
    ): String {
        val stamp = sha256Prefix(zip)
        val meta = buildString {
            append("{\n")
            append("  \"dshVersion\": \"${dshVersion.get()}\",\n")
            append("  \"stamp\": \"$stamp\",\n")
            append("  \"sharp\": \"${sharpMode.get()}\",\n")
            append("  \"targets\": [${targets.joinToString(", ") { "\"$it\"" }}],\n")
            append("  \"entries\": $entryCount,\n")
            append("  \"unpackedBytes\": $unpackedBytes,\n")
            append("  \"builtAt\": \"${Instant.now()}\"\n")
            append("}\n")
        }
        metaFile.parentFile.mkdirs()
        metaFile.writeText(meta)
        return stamp
    }

    /**
     * 校验按平台拆出来的包：必须仍含该平台的全部原生包。
     *
     * 这是拆包唯一的风险点 —— 过滤规则一旦写错就会少带原生包，而 JS 层完全看不出来，
     * 只有用户在那台机器上真正打开终端 / 贴图片时才炸。所以宁可构建失败。
     */
    private fun verifySplitZip(zip: File, target: String) {
        val names = mutableListOf<String>()
        ZipFile(zip).use { zf ->
            val e = zf.entries()
            while (e.hasMoreElements()) names += e.nextElement().name
        }
        val (osName, cpuName) = target.split("-", limit = 2)
        val problems = mutableListOf<String>()
        requiredNatives(osName).forEach { candidates ->
            val hit = candidates.any { pkg ->
                val prefix = "node_modules/${pkg.format(osName, cpuName)}/"
                names.any { it.startsWith(prefix) }
            }
            if (!hit) problems += "缺少 ${candidates.first().format(osName, cpuName)}"
        }
        // node-pty 的原生绑定是「一个包带全平台 prebuilds」，按平台子目录单独确认。
        // 注意 Windows 上是 conpty.node，只有 Unix 才有 pty.node。
        val ptyModule = if (osName == "win32") "conpty.node" else "pty.node"
        val pty = "node_modules/node-pty/prebuilds/$target/$ptyModule"
        if (pty !in names) problems += "缺少 $pty"

        check(problems.isEmpty()) {
            "按平台拆包校验失败（$target）：\n" + problems.joinToString("\n") +
                "\n多半是 platformExclusions 的过滤规则与 lockfile 里的 os/cpu 约束不一致。"
        }
    }

    /**
     * 按平台拆包时要剔除的路径前缀。
     *
     * 规则：lockfile 里带 os/cpu 约束、且不匹配 [target] 的包整包剔除；
     * node-pty 的 prebuilds 按平台子目录摆放，也一并只留自己那份。
     */
    private fun platformExclusions(
        lockFile: File,
        target: String,
        allTargets: List<String>
    ): List<String> {
        val (osName, cpuName) = target.split("-", limit = 2)
        val excludes = mutableListOf<String>()
        platformPackages(lockFile).forEach { pkg ->
            if (matches(pkg.os, osName) && matches(pkg.cpu, cpuName)) return@forEach
            excludes += pkg.path
        }
        allTargets.filter { it != target }
            .forEach { excludes += "node_modules/node-pty/prebuilds/$it" }
        return excludes
    }

    /**
     * 解析某个平台的依赖；已有完整结果（标记匹配）且未要求强制刷新时直接复用。
     *
     * 安装一律加 `--ignore-scripts`，这是跨平台构建能跑通的关键：
     *
     *  - `koffi` 的 install 脚本会**现场编译原生码**。跨平台构建时它按 `process.platform`
     *    （即构建机）去找预编译产物，找不到就退回源码编译，于是在 Windows 上编 darwin 的
     *    `.node` 必然失败（实测跑 1 小时后挂在 `cnoke.cjs`）。而它的运行时二进制其实来自
     *    `@koromix/koffi-<os>-<arch>` 可选依赖，`src/koffi/index.cjs` 直接 require 它，
     *    根本不需要编译。
     *  - `node-pty` 的 postinstall 只在 Windows 上把 `third_party/conpty` 拷进
     *    `build/Release`，非 Windows 是空操作；而 `lib/utils.js` 的查找顺序是
     *    `build/Release` → `build/Debug` → `prebuilds/<platform>-<arch>`，包内
     *    `prebuilds/` 已自带**全平台**产物，回退即可命中。
     *  - `@deepseek-ai/dsh-subprocess-local` 的 postinstall 只给 node-pty 的
     *    `spawn-helper` 补可执行位（tarball 会丢掉这个位），交给插件解包后统一 chmod。
     *  - 其余 `prepare` / `prepublish` 脚本在 registry 安装时本来就不会执行，属噪声。
     *
     * 跳过脚本后各平台的树完全由「平台专有可选依赖」决定，既避免交叉编译，也让
     * 产物在不同构建机上可复现。
     */
    private fun resolveTarget(work: File, target: String, version: String) {
        val parts = target.split("-", limit = 2)
        require(parts.size == 2) { "目标平台格式应为 <os>-<cpu>，实际为：$target" }
        val (osName, cpuName) = parts

        val dir = File(work, "raw/$target")
        val marker = File(dir, MARKER_FILE)
        val fingerprint = "dsh=$version os=$osName cpu=$cpuName scripts=skip"
        if (!forceRefresh.get() &&
            marker.isFile &&
            marker.readText().trim() == fingerprint &&
            File(dir, "node_modules/@deepseek-ai/dsh").isDirectory
        ) {
            logger.lifecycle("[dsh-runtime] 复用已有解析结果：$target")
            return
        }

        // 不删旧目录：npm install 本身是「对账」式的，会把树收敛到目标状态；
        // 而递归删掉上万个文件在带安全过滤驱动的机器上非常慢，能省则省。
        dir.mkdirs()
        logger.lifecycle("[dsh-runtime] 解析宿主平台 $target 的依赖（跳过安装脚本）…")
        execOps.exec {
            workingDir = dir
            commandLine(
                npmCommand.get() + listOf(
                    "install",
                    "--no-audit", "--no-fund", "--loglevel=error",
                    "--omit=dev", "--ignore-scripts",
                    "@deepseek-ai/dsh@$version"
                )
            )
        }
        // 只有整条 install 成功（非零退出会抛异常）才落标记，半截的树下轮会重装
        marker.writeText(fingerprint)
    }

    /**
     * 按目标平台逐个点名，确认各自的原生包都在（见 [requiredNatives]）。
     *
     * 这类包一旦缺失，JS 层毫无察觉，只有用户在那台机器上真正用到终端 / ripgrep /
     * 图片处理时才会报错，所以宁可让构建失败也不要发出一个「在别人电脑上缺胳膊少腿」的包。
     */
    private fun verifyPlatformNatives(root: File, platformTargets: List<String>) {
        val modules = File(root, "node_modules")
        val problems = mutableListOf<String>()

        platformTargets.forEach { target ->
            val (osName, cpuName) = target.split("-", limit = 2)
            requiredNatives(osName).forEach { candidates ->
                val hit = candidates.any { File(modules, it.format(osName, cpuName)).isDirectory }
                if (!hit) problems += "$target 缺少 ${candidates.first().format(osName, cpuName)}"
            }
            // node-pty 的原生绑定是「一个包带全平台 prebuilds」，按平台子目录单独确认。
            // 注意 Windows 上是 conpty.node，只有 Unix 才有 pty.node。
            val ptyModule = if (osName == "win32") "conpty.node" else "pty.node"
            val pty = "node-pty/prebuilds/$target/$ptyModule"
            if (!File(modules, pty).isFile) problems += "$target 缺少 $pty"
        }

        check(problems.isEmpty()) {
            "内置运行时平台完整性校验失败（共 ${problems.size} 项）：\n" +
                problems.joinToString("\n") +
                "\n多半是某个平台的依赖没装全；用 -Pdsh.runtime.refresh=true 重跑可强制重装。"
        }
        logger.lifecycle(
            "[dsh-runtime] 平台完整性校验通过：${platformTargets.size} 个目标平台的原生包齐全"
        )
    }

    /**
     * 按 lockfile 把其余平台的「平台专有包」定点抓下来，解包进基底树。
     *
     * 为什么不直接对每个平台各跑一次 `npm install --os=X --cpu=Y`：
     *  - npm 的 `--os/--cpu` 过滤发生在 reify 落盘阶段，中途的 node_modules 会短暂
     *    包含**全部平台**的变体（实测 darwin 目标下 koffi 的 android/freebsd/openbsd
     *    变体全在），进程一旦中断就留下一棵脏树；
     *  - 每跑一次都要重写一万多个文件，在带安全过滤驱动的机器上动辄十几分钟。
     *
     * 而 npm 的 lockfile 本身是**跨平台**的：它记录了所有 os/cpu 变体的包，连同 tarball
     * 地址与 sha512。于是「按平台只取所需」变成一次精确的定点下载 —— 快、可控、可复现，
     * 而且天然排除了 freebsd / android / ppc64 这些我们根本不发布的变体。
     *
     * @return 实际解包出来的包个数
     */
    private fun fetchPlatformPackages(
        lockFile: File,
        root: File,
        targets: List<String>
    ): Int {
        val platformPackages = platformPackages(lockFile)

        var fetched = 0
        targets.forEach { target ->
            val (osName, cpuName) = target.split("-", limit = 2)
            var count = 0
            platformPackages.forEach { pkg ->
                if (!matches(pkg.os, osName) || !matches(pkg.cpu, cpuName)) return@forEach
                // path 形如 node_modules/@scope/name
                val name = pkg.path.removePrefix("node_modules/")
                if (excludedPackages.any { globMatches(it, name) }) return@forEach
                // 基底平台自己的包已存在，跳过
                val dest = File(root, pkg.path)
                if (dest.isDirectory) return@forEach
                downloadAndExtract(pkg.resolved, pkg.integrity, dest)
                count++
            }
            fetched += count
            logger.lifecycle("[dsh-runtime] $target：补入 $count 个专有包")
        }
        return fetched
    }

    /** lockfile 里一条带平台约束的包。 */
    private data class PlatformPackage(
        val path: String,
        val resolved: String,
        val integrity: String?,
        val os: List<String>,
        val cpu: List<String>
    )

    /**
     * 从 lockfile 里摘出所有带 `os`/`cpu` 约束的包。
     *
     * 同一份结果有两处用途：构建时按平台**定点抓取**（[fetchPlatformPackages]），
     * 以及按平台**拆包**时剔除别的平台（[platformExclusions]）。抽出来共用，避免两处规则漂移。
     */
    private fun platformPackages(lockFile: File): List<PlatformPackage> {
        @Suppress("UNCHECKED_CAST")
        val lock = JsonSlurper().parse(lockFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val packages = lock["packages"] as? Map<String, Any?> ?: emptyMap()

        return packages.mapNotNull { (path, raw) ->
            if (!path.startsWith("node_modules/")) return@mapNotNull null
            val meta = raw as? Map<*, *> ?: return@mapNotNull null
            val osList = (meta["os"] as? List<*>)?.map { it.toString() } ?: emptyList()
            val cpuList = (meta["cpu"] as? List<*>)?.map { it.toString() } ?: emptyList()
            if (osList.isEmpty() && cpuList.isEmpty()) return@mapNotNull null
            val resolved = meta["resolved"] as? String ?: return@mapNotNull null
            PlatformPackage(path, resolved, meta["integrity"] as? String, osList, cpuList)
        }
    }

    /** 平台约束匹配：约束为空（不限制）或含 `any` 即视为匹配。 */
    private fun matches(constraint: List<String>, value: String): Boolean =
        constraint.isEmpty() || constraint.any { it.equals(value, ignoreCase = true) || it == "any" }

    /**
     * 补齐 [extraPackages] 里点名、但树上已经不存在的包。
     *
     * 地址同样取自 lockfile，因此和平台专有包走完全相同的下载与校验路径。
     */
    private fun ensurePackages(lockFile: File, root: File, names: List<String>): Int {
        if (names.isEmpty()) return 0
        @Suppress("UNCHECKED_CAST")
        val lock = JsonSlurper().parse(lockFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val packages = lock["packages"] as? Map<String, Any?> ?: emptyMap()

        var fetched = 0
        names.forEach { name ->
            val path = "node_modules/$name"
            if (File(root, path).isDirectory) return@forEach
            val meta = packages[path] as? Map<*, *>
            val resolved = meta?.get("resolved") as? String
            check(resolved != null) { "lockfile 里找不到 $name 的下载地址，无法补齐" }
            downloadAndExtract(resolved, meta["integrity"] as? String, File(root, path))
            fetched++
        }
        return fetched
    }

    /** 极简通配匹配，只支持 `*`。 */
    private fun globMatches(pattern: String, name: String): Boolean =
        Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) }).matches(name)

    /** 下载一个 tarball、校验 sha512 后解包到 [dest]。 */
    private fun downloadAndExtract(url: String, integrity: String?, dest: File) {
        val connection = URI(url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 300_000
        }
        val payload = ByteArrayOutputStream().also { out ->
            connection.getInputStream().use { it.copyTo(out) }
        }.toByteArray()

        // lockfile 里的 sha512 与 npm 用的是同一套算法，顺手校验一下，
        // 避免半截下载 / 镜像不一致悄悄变成用户机器上的崩溃
        if (integrity != null && integrity.startsWith("sha512-")) {
            val actual = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-512").digest(payload))
            check(actual == integrity.removePrefix("sha512-")) {
                "内置运行时下载校验失败（sha512 不匹配）：$url"
            }
        }

        dest.mkdirs()
        extractTarGz(ByteArrayInputStream(payload), dest)
    }

    /**
     * 极简 tar 解包：只处理 npm 包会出现的条目。
     *
     * npm 的 tarball 一律以 `package/` 开头，解包时剥掉这一层；不在该目录下的条目
     * 直接丢弃。另外需要处理两种「改名字段」：
     *  - GNU 长文件名（typeflag `L`），内容就是下一条目的完整路径；
     *  - pax 扩展头（typeflag `x`），路径藏在 `path=` 记录里。
     */
    private fun extractTarGz(input: InputStream, dest: File) {
        val header = ByteArray(512)
        GZIPInputStream(BufferedInputStream(input)).use { gz ->
            var pendingName: String? = null
            while (true) {
                if (!readFully(gz, header)) break
                if (header.all { it == 0.toByte() }) break // 空块 = 归档结束

                val typeFlag = header[156].toInt().toChar()
                val size = readOctal(header, 124, 12)
                var name = readString(header, 0, 100)
                val prefix = readString(header, 345, 155)

                if (typeFlag == 'L') {
                    pendingName = String(readBlock(gz, size), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                if (typeFlag == 'x' || typeFlag == 'g') {
                    pendingName = parsePaxPath(readBlock(gz, size))
                    continue
                }

                if (pendingName != null) {
                    name = pendingName!!
                    pendingName = null
                } else if (prefix.isNotEmpty()) {
                    name = "$prefix/$name"
                }

                val relative = name.removePrefix("package/").takeIf { name.startsWith("package/") }
                if (relative.isNullOrEmpty()) {
                    // 归档里不该有 package/ 之外的条目；有也直接丢弃
                    readBlock(gz, size)
                    continue
                }

                val out = File(dest, relative)
                val isDir = typeFlag == '5' || relative.endsWith("/")
                if (isDir) {
                    out.mkdirs()
                    readBlock(gz, size)
                    continue
                }
                if (typeFlag != '0' && typeFlag != '\u0000' && typeFlag != '7') {
                    readBlock(gz, size) // 符号链接/硬链接等，npm 包里不会出现
                    continue
                }
                val content = readBlock(gz, size)
                out.parentFile?.mkdirs()
                out.writeBytes(content)
            }
        }
    }

    /** 读满一个 512 字节的 tar 块；读到文件尾返回 false。 */
    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    /** 读 [size] 字节的数据，并跳过 tar 的 512 字节对齐填充。 */
    private fun readBlock(input: InputStream, size: Int): ByteArray {
        val data = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(data, read, size - read)
            check(n >= 0) { "tar 数据不完整" }
            read += n
        }
        val padding = (512 - size % 512) % 512
        if (padding > 0) {
            val pad = ByteArray(padding)
            var skipped = 0
            while (skipped < padding) {
                val n = input.read(pad, skipped, padding - skipped)
                check(n >= 0) { "tar 数据不完整（填充区）" }
                skipped += n
            }
        }
        return data
    }

    private fun readString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { buffer[it] == 0.toByte() }
            ?: (offset + length)
        return String(buffer, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(buffer: ByteArray, offset: Int, length: Int): Int {
        val text = String(buffer, offset, length, Charsets.US_ASCII)
            .trim { it <= ' ' || it == '\u0000' }
        return text.toIntOrNull(8) ?: 0
    }

    /** 从 pax 扩展头里取 `path=`；记录格式是「长度 键=值\n」循环。 */
    private fun parsePaxPath(data: ByteArray): String? {
        val text = String(data, Charsets.UTF_8)
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            if (length <= 0 || index + length > text.length) break
            val record = text.substring(space + 1, index + length).trimEnd('\n')
            if (record.startsWith("path=")) return record.removePrefix("path=")
            index += length
        }
        return null
    }

    /**
     * 记录哪些文件需要可执行位。
     *
     * zip 格式不保留 Unix 权限位，而 node-pty 的 `spawn-helper`（macOS 上 fork 子进程用）
     * 与 ripgrep 的 `rg` 必须是可执行的。把清单写进产物，交给插件解包后统一 chmod；
     * Windows 上本来就没有可执行位一说，忽略即可。
     */
    private fun writeExecutableManifest(root: File, platformTargets: List<String>): Int {
        val relativePaths = platformTargets.flatMap { target ->
            val osName = target.split("-", limit = 2).first()
            buildList {
                if (osName != "win32") add("@vscode/ripgrep-$target/bin/rg")
                // spawn-helper 只有 macOS 的 prebuild 里才有
                if (osName == "darwin") add("node-pty/prebuilds/$target/spawn-helper")
            }
        }.filter { File(root, "node_modules/$it").isFile }

        File(root, EXEC_MANIFEST).writeText(relativePaths.joinToString("\n", postfix = "\n"))
        return relativePaths.size
    }

    /** 删掉运行时用不到的文件，返回删除的文件数。 */
    private fun prune(root: File): Int {
        val rootPath = root.toPath()
        val dirs = mutableListOf<Path>()
        val files = mutableListOf<Path>()
        // 一次遍历同时收集目录与文件
        Files.walk(rootPath).use { stream ->
            stream.forEach { p ->
                if (Files.isDirectory(p)) {
                    if (p.fileName?.toString()?.lowercase() in prunableDirs) dirs.add(p)
                } else if (Files.isRegularFile(p)) {
                    files.add(p)
                }
            }
        }

        var removed = 0
        // 目录裁剪：从浅到深，父目录删掉后子目录自然消失
        dirs.sortedBy { it.nameCount }.forEach { p ->
            val dir = p.toFile()
            if (!dir.exists()) return@forEach
            removed += countFiles(p)
            dir.deleteRecursively()
        }
        // 文件裁剪
        files.forEach { p ->
            val name = p.fileName.toString().lowercase()
            val ext = name.substringAfterLast('.', "")
            if (keepFileNames.none { name.startsWith(it) } && ext in prunableExtensions) {
                if (p.toFile().delete()) removed++
            }
        }
        return removed
    }

    private fun countFiles(dir: Path): Int {
        var n = 0
        Files.walk(dir).use { stream -> stream.forEach { if (Files.isRegularFile(it)) n++ } }
        return n
    }

    /** 列出 node_modules 下的包目录（`@scope` 展开一层），返回 `@scope/name` → 目录。 */
    private fun packageDirs(modules: File): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        modules.listFiles()?.forEach { entry ->
            if (!entry.isDirectory) return@forEach
            // .bin 里是 npm 建的转发脚本/符号链接，运行时用不到
            if (entry.name == ".bin") return@forEach
            if (entry.name.startsWith("@")) {
                entry.listFiles()?.forEach { sub ->
                    if (sub.isDirectory) out += "${entry.name}/${sub.name}" to sub
                }
            } else {
                out += entry.name to entry
            }
        }
        return out
    }

    /** 删掉 [excludedPackages] 里点名的包，返回删除的文件数。 */
    private fun pruneExcludedPackages(root: File): Int {
        var removed = 0
        packageDirs(File(root, "node_modules")).forEach { (name, dir) ->
            if (excludedPackages.none { globMatches(it, name) }) return@forEach
            removed += countFiles(dir.toPath())
            dir.deleteRecursively()
        }
        return removed
    }

    /**
     * 清掉 npm 留下的「垃圾目录」，返回删除的文件数。
     *
     * npm 移除包时不是直接删，而是先把目录改名成 `.<原名>-<随机串>` 藏进 node_modules
     * （普通 `ls` 看不见），之后再异步清理。一旦安装被中断、或者后续没有触发清理，
     * 这些目录就会一直留在树上 —— 实测一次构建就留下了 **35 MB 的 sharp 原生包残骸**，
     * 而且因为名字带了点前缀，按包名匹配的裁剪规则完全看不见它们。
     *
     * npm 不会安装以 `.` 开头的包目录（node_modules 根下的 `.bin` 除外），所以见到就删。
     */
    private fun pruneNpmTrash(root: File): Int {
        val modules = File(root, "node_modules")
        val candidates = mutableListOf<File>()
        modules.listFiles()?.forEach { entry ->
            if (!entry.isDirectory || entry.name == ".bin") return@forEach
            if (entry.name.startsWith(".")) {
                candidates += entry
            } else if (entry.name.startsWith("@")) {
                entry.listFiles()?.forEach { sub ->
                    if (sub.isDirectory && sub.name.startsWith(".")) candidates += sub
                }
            }
        }
        var removed = 0
        candidates.forEach { dir ->
            removed += countFiles(dir.toPath())
            dir.deleteRecursively()
        }
        return removed
    }

    /**
     * node-pty 里跟运行时无关的部分。
     *
     *  - `prebuilds/<平台>`：只留目标平台。光是 win32-arm64 一个目录就有 11 MB，
     *    其中 10.6 MB 还是 `.pdb` 调试符号；
     *  - `third_party/`（conpty 原始文件）、`src/`（C++ 源码）、`build/`（postinstall
     *    的编译/拷贝产物）：都是安装期才用得上的原料。运行时 `lib/utils.js` 的查找顺序是
     *    `build/Release` → `build/Debug` → `prebuilds/<platform>-<arch>`，删掉前三者
     *    会自然回退到 prebuilds，功能不受影响。
     */
    private fun pruneNodePty(root: File, platformTargets: List<String>): Int {
        val pkg = File(root, "node_modules/node-pty")
        if (!pkg.isDirectory) return 0
        var removed = 0

        File(pkg, "prebuilds").listFiles()?.forEach { dir ->
            if (dir.name in platformTargets) return@forEach
            removed += countFiles(dir.toPath())
            dir.deleteRecursively()
        }
        listOf("third_party", "src", "build").forEach { name ->
            val dir = File(pkg, name)
            if (!dir.exists()) return@forEach
            removed += countFiles(dir.toPath())
            dir.deleteRecursively()
        }
        return removed
    }
}

val dshRuntimeVersion: String =
    providers.gradleProperty("dsh.runtime.version").orElse("0.1.2-rc.1").get()

val dshRuntimeTargets: List<String> =
    providers.gradleProperty("dsh.runtime.targets")
        .orElse("win32-x64,darwin-x64,darwin-arm64,linux-x64,linux-arm64")
        .get()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val dshRuntimeZip = layout.buildDirectory.file("dsh-runtime/dsh-runtime.zip")
// 注意扩展名是 .txt 而不是 .json：本机企业 DLP（E-SafeNet）会按扩展名把 .json
// 异步加密成密文，构建产物 runtime-meta.json 拷进 build/resources 后会被加密，
// jar 于是把密文打进插件包，插件运行时读不到自己的元数据。
// 内容仍然是 JSON，只是换一个 DLP 不碰的扩展名；下面的 doFirst 还会再兜底校验一次。
val dshRuntimeMeta = layout.buildDirectory.file("dsh-runtime/runtime-meta.txt")

/** 读前 64 字节判断文件是否已被透明加密。 */
fun isDlpEncrypted(file: File): Boolean = file.inputStream().use {
    String(it.readNBytes(64), Charsets.ISO_8859_1).contains(BundleDshRuntimeTask.DLP_MAGIC)
}

/**
 * 构建期的工作目录（几万个 node_modules 小文件）。
 *
 * **必须放在系统临时目录，不能放在项目目录里。** 本机企业 DLP 会对范围内的路径做
 * 「逐文件策略查询」，实测 java 写 1 KB 小文件：
 *
 * | 位置 | 速率 | 18390 个文件耗时 |
 * | -- | -- | -- |
 * | 系统临时目录 `%TEMP%` | 1209~1611 files/s | 约 12~15 秒 |
 * | 项目目录 `D:\Develop\...` | 6.0 files/s | 约 51 分钟 |
 * | `D:\` 根目录（同样在范围内） | 7.7 files/s | 约 40 分钟 |
 *
 * 相差 200 倍以上，而且**范围内的路径还会被异步加密**：产物 zip 里因此混进过 36 个密文条目
 * （全是 `@img/sharp-*`、`@koromix/koffi-*`、`@vscode/ripgrep` 这些原生包的 `package.json`），
 * 发到用户机器上 node 读出来就是乱码 —— 用户那边没有 DLP，看到的就是原始密文字节。
 * Java 进程无权解密，唯一可靠的办法就是根本不在范围内落盘。`writeZip` 里有硬校验兜底。
 *
 * 可用 `-Pdsh.runtime.work=<目录>` 覆盖（例如临时目录空间不足时）。
 */
fun dshRuntimeScratchDir(): File {
    val override = providers.gradleProperty("dsh.runtime.work").orNull?.takeIf { it.isNotBlank() }
    if (override != null) return File(override)
    val base = System.getenv("TEMP")?.takeIf { it.isNotBlank() }
        ?: System.getenv("TMP")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("java.io.tmpdir")
    return File(base, "dshstudio-runtime/work")
}

/**
 * npm 的启动命令。
 *
 * Windows 上 npm 是 `npm.cmd`，Java 的进程启动器不能直接执行批处理，
 * 必须经 `cmd /c` 转发；Unix 上直接调 `npm` 即可。
 * 可用 `-Pdsh.runtime.npm="<完整命令>"` 覆盖（按空格拆分）。
 */
val dshRuntimeNpmCommand: List<String> =
    providers.gradleProperty("dsh.runtime.npm").orNull
        ?.takeIf { it.isNotBlank() }
        ?.split(" ")
        ?.filter { it.isNotBlank() }
        ?: if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("cmd", "/c", "npm")
        } else {
            listOf("npm")
        }

val bundleDshRuntime = tasks.register<BundleDshRuntimeTask>("bundleDshRuntime") {
    group = "build"
    description = "打包 dsh 运行时（产物供 GitHub Release 离线包与 CI 使用，不再打进插件 JAR）"
    // 只在内置未关闭时参与构建
    onlyIf { providers.gradleProperty("dsh.runtime.skip").orNull != "true" }
    dshVersion.set(dshRuntimeVersion)
    targets.set(dshRuntimeTargets)
    npmCommand.set(dshRuntimeNpmCommand)
    nodeExecutable.set(providers.gradleProperty("dsh.runtime.node").orElse("node"))
    forceRefresh.set(providers.gradleProperty("dsh.runtime.refresh").map { it == "true" }.orElse(false))
    sharpMode.set(providers.gradleProperty("dsh.runtime.sharp").orElse("native"))
    // 放在系统临时目录，不放 build/：见 dshRuntimeScratchDir() 的说明（268 倍速差 + 避免被加密）
    workDir.set(layout.dir(providers.provider { dshRuntimeScratchDir() }))
    outputZip.set(dshRuntimeZip)
    outputMeta.set(dshRuntimeMeta)
    outputSplitDir.set(layout.buildDirectory.dir("dsh-runtime/split"))
}

