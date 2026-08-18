import java.util.Properties

plugins {
    java
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.deepseek"
version = "0.1.2"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// ── IDE 选择 ────────────────────────────────────────────────────────────────
// 1) dsh.ide.localPath：直接使用本机安装的 IDE（Android Studio 的常见安装路径会被自动识别）
// 2) 否则使用 dsh.ide.type / dsh.ide.version 下载对应发行版（默认 IntelliJ Community）
val autoDetectedAs = File("C:\\Program Files\\Android\\Android Studio")
val ideLocalPath = providers.gradleProperty("dsh.ide.localPath").orNull
    ?.takeIf { it.isNotBlank() }
    ?: if (autoDetectedAs.isDirectory) autoDetectedAs.absolutePath else null

intellij {
    if (ideLocalPath != null) {
        localPath.set(ideLocalPath)
    } else {
        type.set(providers.gradleProperty("dsh.ide.type").orElse("IC"))
        version.set(providers.gradleProperty("dsh.ide.version").orElse("2024.2.3"))
    }
    plugins.set(emptyList())
    // 兼容范围写在 plugin.xml 的 <idea-version since-build="231"/> 中，不做 until-build 限制
    updateSinceUntilBuild.set(false)
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

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(17)
        options.encoding = "UTF-8"
    }
    // 本插件未使用 SearchableOptions，关闭该任务以加速构建
    buildSearchableOptions {
        enabled = false
    }
    patchPluginXml {
        version.set(project.version.toString())
        changeNotes.set("""
            <h3>0.1.2</h3>
            <ul>
              <li>新增「半透明覆盖层」：可在网页上叠加背景图（实验性，可调不透明度）</li>
            </ul>
            <h3>0.1.1</h3>
            <ul>
              <li>新增界面主题（跟随 IDE / 浅色 / 深色）与背景图片设置</li>
              <li>修复内嵌页面布局异常：移除了会破坏侧边栏/设置的 matchMedia 覆盖</li>
            </ul>
            <h3>0.1.0</h3>
            <ul>
              <li>内嵌浏览器工具窗口（JCEF），直接在 Android Studio / IDEA 中打开 DeepSeek Harness 界面</li>
              <li>一键启动 / 停止 <code>dsh web</code> 服务器（Node.js / npx，可自定义命令）</li>
              <li>服务器健康状态检测与实时日志面板</li>
              <li>系统浏览器打开、地址 / 端口 / 工作目录 / DSH_HOME 可配置</li>
            </ul>
        """.trimIndent())
    }
    signPlugin {
        dshProp("signPlugin.certificateChainFile")?.let { certificateChainFile.set(file(it)) }
        dshProp("signPlugin.privateKeyFile")?.let { privateKeyFile.set(file(it)) }
        dshProp("signPlugin.password")?.let { password.set(it) }
    }
    publishPlugin {
        dshProp("intellijPlatformPublishingToken")?.let { token.set(it) }
    }
}
