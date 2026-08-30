import java.util.Properties

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.deepseek"
version = "0.3.0"

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
            <h3>0.3.0</h3>
            <ul>
              <li><b>背景图 + 透明度设置移至 dsh 网页内置「通用设置」</b>：在 dsh 界面内注入一个齿轮按钮 → 弹出
                  「通用设置」面板，可在此选择背景图片、调节浮层透明度（0–60%）；背景以 fixed + pointer-events:none 的
                  CSS 半透明浮层叠在 dsh 界面之上（不挡点击），设置保存在 dsh 网页本地，刷新后保留。</li>
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
