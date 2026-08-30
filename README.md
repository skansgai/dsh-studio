# DeepSeek Harness Studio

English | [中文](README.zh.md)

An IntelliJ Platform plugin that opens the [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) web UI right inside **Android Studio** / **IntelliJ IDEA**.

DeepSeek Harness is DeepSeek's open-source coding-agent framework; `dsh web` serves its web UI locally (default `http://127.0.0.1:3080`). This plugin embeds that UI into a right-hand IDE tool window and manages the server (start / stop / status), so you can use Harness without leaving the IDE.

[![Install on JetBrains Marketplace](https://img.shields.io/badge/Install%20on-JetBrains%20Marketplace-blue?style=flat-square)](https://plugins.jetbrains.com/plugin/33569-deepseek-harness-studio)

## Features

- **Embedded browser tool window** (JCEF): shows the Harness web UI directly, with live chat, tool calls and artifacts.
- **One-click server start / stop**: ▶ / ⏹ toolbar buttons that launch `dsh web` via `npx` (customizable command template); stopping kills the whole process tree.
- **Status detection**: periodic health probes with a color-coded status bar (green=connected / orange=starting / red=failed / gray=stopped). An externally running instance (e.g. Harness started in your terminal) is detected automatically and connected directly.
- **Server log panel**: live stdout/stderr from `dsh web` for troubleshooting startup failures, port conflicts, etc.
- **Open in system browser**: opens the same URL in your default browser.
- **Send code to Harness**: right-click in the editor and `Send Code to Harness…` to send the selection (or the whole file) along with an editable instruction to a Harness session; auto-starts the server if needed and opens the tool window.
- **Status bar widget**: a colored Harness server indicator at the bottom status bar (green=connected / orange=starting / red=failed / gray=stopped); click to open the tool window.
- **Recent sessions**: `Tools → DeepSeek Harness → Recent Harness Sessions…` opens a search-style session list; picking one copies its sessionId and opens the tool window.
- **Headless tasks**: `Tools → DeepSeek Harness → Run Headless Task…` runs a one-shot `dsh --profile headless` task inside the IDE, output goes to the Server Log.
- **Configurable**: URL, port, start command, working directory (workspace root), `DSH_HOME`, auto-start, embedded browser toggle.
- **Theme**: pick Follow IDE / Light / Dark in settings; the tool-window top bar is tinted to match.
- **Image overlay**: set a local image in settings to overlay the DeepSeek Harness web UI as a semi-transparent layer (CSS-injected, click-through); overlay opacity is adjustable (0–60%).

## Compatibility

- Android Studio 2023.1+ (platform 231+) or IntelliJ IDEA 2023.1+; no upper bound, future versions supported.
- Automatic server start requires **Node.js 18+** (uses `npx`).
- The embedded browser requires JCEF (enabled by default in modern versions; if unavailable the plugin falls back to opening in the system browser).

## Installation

**Option 1: From the Marketplace (recommended, published)**
`File → Settings → Plugins → Marketplace` → search **DeepSeek Harness** → Install → restart the IDE.

**Option 2: Local install**
1. Build the plugin (see below), or use the zip from `build/distributions/`.
2. In Android Studio: `File → Settings → Plugins → ⚙ → Install Plugin from Disk…`, pick the zip, restart the IDE.
3. Open via `Tools → DeepSeek Harness`, or the tool-window icon on the right edge.

## Usage

### Quick start (three steps)

1. **Open**: `Tools → DeepSeek Harness`, or click the blue hexagon icon on the right tool-window bar.
2. **Connect / start**: it auto-connects if `dsh web` is already running (default `http://127.0.0.1:3080`); otherwise click **▶** to start it (requires Node.js 18+; DeepSeek Harness is installed automatically on first run).
3. **Configure the model and go**: add your API key in the Harness UI at `Settings → Models` (e.g. a DeepSeek API key), create a session, pick a model, and start chatting / coding.

| Action | Description |
|---|---|
| Open tool window | `Tools → DeepSeek Harness`, or the right tool-window icon |
| Connect | auto-probes the `server URL` (default `http://127.0.0.1:3080`) on open; auto-starts `dsh web` if configured |
| Manual start | ▶ toolbar button (gray = not needed: already running or starting) |
| Stop server | ⏹ toolbar button (only stops processes started by this plugin; stop external instances in their own terminal) |
| Refresh | ↻ toolbar button: re-probe status and reload the page |
| Open in browser | 🌐 toolbar button |
| View logs | the "Server Log" tab at the bottom of the tool window |

### New features in 0.2.0

- **Send code to Harness**: select code in the editor (or nothing to send the whole file), right-click → `DeepSeek Harness → Send Code to Harness…`. In the dialog you can edit the instruction sent to the agent; on OK the plugin ensures the server is running, submits the code as a prompt to a Harness session, and opens the tool window. First use triggers `npx --yes @deepseek-ai/dsh` (≈284MB / 14 min download); a "starting" status in the status bar during that time is normal.
- **Status bar widget**: a DSH status control on the bottom-right status bar, initially "not connected". Click it to open the Harness tool window; it turns green ("running") once the server is ready.
- **Recent sessions**: `Tools → DeepSeek Harness → Recent Harness Sessions…` opens the session list; picking one copies its sessionId to the clipboard and opens the tool window (note: the dsh web UI does not support session deep links yet, so we jump via "copy ID + open tool window").
- **Headless tasks**: `Tools → DeepSeek Harness → Run Headless Task…` runs `dsh --profile headless "task"` in the background, output streamed to the Server Log.

### Settings (Settings → Tools → DeepSeek Harness)

| Setting | Default | Description |
|---|---|---|
| Server URL | `http://127.0.0.1:3080` | Harness URL to connect / open |
| Auto-start port | `3080` | Port passed to `--port` on auto-start |
| Start command template | see below | Blank = default template; supports placeholders `{host} {port} {workdir} {dshHome}` |
| Working directory | current project dir | Used as the Harness workspace root (`dsh web` run dir) |
| DSH_HOME | `~/.dsh` | Blank = default (can also be overridden via env var) |
| Auto-start server | on | Auto-start the server if not running when the tool window opens |
| Use embedded browser | on | Off = system browser only (falls back automatically when JCEF is unavailable) |

Default start command template:

```text
npx --yes @deepseek-ai/dsh web --host {host} --port {port}
```

On first start, `dsh` auto-initializes the `web` profile (data in `~/.dsh`); later starts are instant.

## Building

Requirements: JDK 17+ (JDK 22 works; the build compiles with `--release 17`), Gradle (a wrapper is included; the first build downloads dependencies and an IDE distribution).

```bash
# Windows
gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

Output: `build/distributions/dsh-studio-0.2.0.zip` (unsigned) / `dsh-studio-0.2.0-signed.zip` (signed; upload this one to the Marketplace).

### Choosing the build target

Building against **IntelliJ Community** (IC) is enough to run in Android Studio (AS and IDEA share the IntelliJ Platform; this plugin uses platform APIs only). Switch in `gradle.properties`:

```properties
# Default: download IntelliJ Community 2024.2.3 for building (smallest)
dsh.ide.type=IC
dsh.ide.version=2024.2.3

# Option A: build directly against Android Studio (downloads the AS distribution, larger)
# dsh.ide.type=android-studio
# dsh.ide.version=2024.2.1.12   # use your local AS version (Help → About)

# Option B (recommended, no download): use a locally installed IDE
# dsh.ide.localPath=C\:/Program Files/Android/Android Studio
```

The build script also auto-detects `C:\Program Files\Android\Android Studio` (used automatically when installed).

### Debugging the plugin in an IDE

Open this project in IntelliJ IDEA (`build.gradle.kts`), let Gradle sync, then run the `runIde` task to launch a sandbox IDE with the plugin. If opening in Android Studio, set `dsh.ide.localPath` in `gradle.properties` to your Android Studio first, then run `runIde`.

### Verification and tests

```bash
gradlew.bat verifyPlugin   # plugin descriptor check
gradlew.bat test           # unit tests (DshUtil pure logic)
```

### About the IntelliJ Gradle Plugin version

This project uses **IntelliJ Platform Gradle Plugin 2.18.1** (the official 2.x line), building against IntelliJ Community 2024.2.3 and targeting 231+ platforms (Android Studio 2023.1+ and IDEA 2023.1+). The 2.x line requires Gradle 9.0.0+; the bundled wrapper has been upgraded to Gradle 9.7.1 (in China you can switch to the Tencent Cloud mirror to speed up the download).

The plugin descriptor `plugin.xml` already declares `since-build="231"` directly and does not rely on Gradle injection; to target a newer platform, change the version and `pluginConfiguration.ideaVersion.sinceBuild` in `build.gradle.kts`.

## JCEF notes

The embedded browser uses the IDE's built-in JCEF (JetBrains Chromium Embedded Framework). If the tool window reports "embedded browser unavailable":

1. `Help → Edit Custom VM Options…`, add `-Dide.browser.jcef.enabled=true`, restart the IDE;
2. or disable "Use embedded browser" in the settings and use the system browser instead.

## FAQ

- **Start failed / red status**: open the "Server Log" tab to see `dsh web` output; the plugin also shows a notification. Common causes: Node.js not installed (a balloon suggests installing it with a link), port occupied (change the auto-start port), `npx` not on PATH, or network issues while downloading `@deepseek-ai/dsh` on first run.
- **"Cannot stop external server"**: the instance at the URL was not started by this plugin (e.g. Harness is running in a terminal). Stop it in that terminal.
- **Multiple projects auto-starting**: only one instance can bind a port; enable auto-start in one project, or use different ports per project.
- **LAN / remote instances**: point the server URL at the target address; if the Harness server does not trust that origin (`--trusted-host`), `/api` calls may be rejected — configure it when starting Harness.

## Project layout

```text
src/main/java/com/deepseek/dshstudio/
├── DshStudioConstants.java          # constants
├── actions/                         # menu & toolbar actions
├── server/                          # server process management, state machine, log, message bus
├── settings/                        # settings storage (PersistentStateComponent) & settings page
├── ui/                              # tool window (JCEF embedded browser + status bar + log)
└── util/                            # platform-agnostic helpers (health probe, command parsing, process cleanup)
src/main/resources/
├── META-INF/plugin.xml              # plugin descriptor
└── icons/                           # icons (SVG)
```

## Changelog

Full version history (features, fixes, build changes) is in [CHANGELOG.md](CHANGELOG.md).

## Publishing

Want to publish to the JetBrains Marketplace (plugins.jetbrains.com)? Full guide (account, certificate, signing, upload, review): **[PUBLISHING.md](PUBLISHING.md)** / **[中文版](PUBLISHING.zh.md)**.

## License

[MIT](LICENSE)
