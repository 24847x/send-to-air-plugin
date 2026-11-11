# Send to Air

JetBrains plugin for all IDEs that have the JetBrains Air plugin installed (2026.2 or newer).

Select code → `⌥⇧A` → a file reference with line numbers is inserted into the Air chat input.

## What it does

1. Takes the current file and selected line range from the editor
2. Formats a reference: `@file:<name> [lines from X to Y]`
3. Finds the open Air session, or starts a new one if none is open
4. Inserts the reference into the input field (does NOT send — you can add your question first)

**Example output in chat:**
```
@file:UserService.kt [lines from 42 to 58]
```

In terminal-based sessions (e.g. Claude Code) the reference is pasted into the agent's terminal in its own format:
```
@src/service/UserService.kt#L42-58
```

## Build

**Requirements:** internet connection (to download Gradle, the JDK 21 toolchain, the IntelliJ IDEA SDK and the Air plugin)

```bash
# Build the plugin
./gradlew buildPlugin

# The plugin will be located at:
# build/distributions/send-to-air-plugin-1.0.0.zip
```

> The first build may take 5–10 minutes — it downloads the IntelliJ IDEA SDK (over 1 GB)

## Compatibility

Works with any JetBrains IDE, version 2026.2 or newer, that has the **JetBrains Air** plugin installed:

- IntelliJ IDEA (Community & Ultimate)
- WebStorm
- PyCharm (Community & Professional)
- PhpStorm
- GoLand
- RubyMine
- CLion
- Rider
- RustRover
- DataGrip
- Android Studio

## Installation in JetBrains IDEs

1. Open your JetBrains IDE → **Settings** → **Plugins**
2. Gear icon (⚙️) → **Install Plugin from Disk...**
3. Select `build/distributions/send-to-air-plugin-1.0.0.zip`
4. Restart the IDE

## Usage

- Select code → right-click → **Send to Air**
- Or use the hotkey: `⌥⇧A` (macOS) / `Alt+Shift+A` (Windows/Linux)
- Files and folders: select them in the **Project** view (`⌘1` / `Alt+1`) → right-click → **Send to Air** (or the same hotkey).
  Each one is inserted as `@file:src/Main.kt` or `@folder:src/checkout/` (paths relative to the project).

## Changing the hotkey

**Settings** → **Keymap** → search for `Send to Air` → assign your own.

## Troubleshooting

**"Air is not available" or "Could not find an Air session" notification?**
Make sure the JetBrains Air plugin is installed and enabled in your IDE (Settings → Plugins → Marketplace → search "Air").
