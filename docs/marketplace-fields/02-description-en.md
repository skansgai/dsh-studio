### DeepSeek Harness integration

Open the **DeepSeek Harness** web UI right inside **Android Studio** / **IntelliJ IDEA**:

- Embedded browser tool window (JCEF) showing the Harness chat and tool calls
- One-click start / stop of the local `dsh web` server (Node.js / npx based, customizable command)
- Automatic server status detection with a colored status bar and live polling
- Server log panel for troubleshooting startup problems
- **Send code to Harness**: right-click in the editor to send the selection (or whole file) to a session
- **Status bar widget**: a colored server-state indicator; click to open the tool window
- **Recent sessions**: a search-style popup to jump to any Harness session
- **Headless tasks**: run a one-shot `dsh --profile headless` task from the IDE
- Open the same page in your system browser at any time
- Configurable URL / port / working directory (workspace root) / `DSH_HOME`

### Personalize (0.3.0)

- Set a semi-transparent background image and opacity directly inside the Harness web UI (**Settings → 通用设置**); the choice persists across restarts and refreshes.
- The Harness top bar turns translucent so the background shows through, matching the IDE look.

### Quick Start

1. **Install**: Settings → Plugins → Marketplace, search "DeepSeek Harness", install and restart the IDE.
2. **Open**: click the blue hexagon icon on the right tool-window bar, or go to **Tools → DeepSeek Harness**.
3. **Connect**: it auto-connects if a `dsh web` server is already running (default http://127.0.0.1:3080). If not, click ▶ to start one — this requires Node.js 18+ and installs DeepSeek Harness automatically on first run.
4. **Configure the model**: inside the Harness UI open **Settings → Models** and add your API key (e.g. a DeepSeek API key).
5. **Go**: create a session, pick a model, and start chatting / coding.

### Settings

- **Tools → DeepSeek Harness**: IDE theme, port, working directory, `DSH_HOME`.
- **Harness web UI → 通用设置**: background image & overlay opacity (0.3.0).

**Requirements**: Android Studio 2023.1+ / IntelliJ IDEA 2023.1+; Node.js 18+ is needed only for the automatic server start.

DeepSeek Harness is DeepSeek's open-source coding agent framework; this plugin is just an IDE entry point for it.
