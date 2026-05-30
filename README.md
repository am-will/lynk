# Lynk

Lynk turns an Android phone into a persistent chat and voice endpoint for AI agents running on your computer. The Android app is the bubble, notification, voice, and optional phone-control surface. The PC bridge is the local companion that pairs the phone, routes chat to host agents, starts realtime voice sessions, and exposes Android tools when a selected agent needs to touch the phone.

This is not just an OpenClaw remote. Host mode can route to **OpenClaw**, **Hermes**, or **Codex** from the same Android model picker. Local phone mode can run an imported **LiteRT-LM** model on-device and use Android/local app-private tools without a PC agent for every request.

## How It Works

1. The Android app connects to the PC bridge over `/phone`.
2. The bridge registers the phone, sends available host/local model metadata, and receives `chat.*` and `realtime.*` events.
3. The selected backend handles the request:
   - OpenClaw through the Gateway-backed host harness.
   - Hermes through its API harness when configured.
   - Codex through the bundled app-server harness when configured.
   - Local LiteRT-LM directly on Android when a `.litertlm` model is imported.
4. Replies, tool activity, status, usage, session history, and errors stream back to the Android timeline.
5. Phone-control tools are optional. When enabled, host agents can call the bridge MCP server and Lynk executes Android accessibility commands on the paired device.

## Dependencies

Required for the host bridge:

- Node.js 24+ and npm.
- Network reachability from Android to the bridge: same LAN, USB reverse via ADB, or Tailscale.
- At least one host backend for Host mode:
  - OpenClaw CLI/Gateway for the default OpenClaw harness.
  - Hermes API access plus `HERMES_API_KEY` for Hermes.
  - Codex CLI with `codex app-server` for Codex.

Required for the Android app:

- Android Studio or the Gradle wrapper in `android/`.
- Android SDK platform tools if using ADB install, USB reverse, or debug workflows.
- A physical Android device with overlay permission. Accessibility permission is required only for screen observation and phone-control tools.

Optional:

- Tailscale for off-LAN pairing without exposing the bridge publicly.
- OpenAI API key for realtime voice and bridge-side web search.
- A `.litertlm` model file for Local phone mode.
- MCP registration if you want OpenClaw, Hermes, or Codex to call Android phone tools.

## Install The Bridge

### npm package

The bridge package is intended to install as `lynk-bridge`:

```bash
npm install -g lynk-bridge
```

Global npm install provisions the bridge for normal use: it creates or preserves the host config, refreshes local integration discovery, and registers the bridge to start at login. Check startup and diagnostics with:

```bash
lynk-bridge-host service-status
lynk-bridge-host diagnostics
```

Print a pairing payload or QR:

```bash
lynk-bridge-host pairing
lynk-bridge-host pairing --qr
```

Optional phone-control MCP registration:

```bash
lynk-bridge-host mcp
```

Manual service controls:

```bash
lynk-bridge-host install-service
lynk-bridge-host uninstall-service
```

### Source checkout

Until the npm package is published, run the same bridge from this repo:

```bash
cd pc
npm install
npm run host:refresh
npm run bridge
```

In another terminal:

```bash
cd pc
npm run host:pairing
npm run host:pairing:qr
```

For background startup from a source checkout:

```bash
cd pc
npm run build
npm run host:install-service
npm run host:service-status
```

Use `npm run host:service-plan` if you need to inspect the exact OS-specific commands instead of applying them. Packaged installer scaffolding lives in `pc/installers/`; installers copy the built bridge bundle, run host refresh, register the bridge at login, preserve the generated config, and print the pairing QR. Set `LYNK_BRIDGE_CONFIGURE_MCP=1` during install to also configure available phone-control MCP integrations.

## Bridge Config

The bridge creates a persistent config with a strong token on first run:

- macOS: `~/Library/Application Support/Android Agent Bridge/config.json`
- Windows: `%ProgramData%\AndroidAgentBridge\config.json`
- Linux: `~/.config/android-agent-bridge/config.json`

Environment variables and `pc/.env.local` override that config for development. Copy `pc/.env.example` to `pc/.env.local` only when you need explicit overrides such as a non-default port, OpenClaw Gateway auth, Hermes API settings, Codex app-server settings, or an OpenAI key.

The phone must use the same token as the bridge. The easiest path is the QR/deep link from `lynk-bridge-host pairing --qr` or `npm run host:pairing:qr`; it includes the token, device ID, and endpoint candidates for USB reverse, Tailscale, LAN, and loopback.

## Install The Android App

Build and install from `android/` with Android Studio or Gradle:

```bash
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For USB testing, forward the bridge port and launch the app:

```bash
cd pc
npm run phone:usb
```

On Android, either scan the pairing QR or set these fields manually:

- WebSocket URL: one of the pairing `endpoints[].url` values, usually `ws://<your-computer-lan-ip>:8788/phone`.
- Device ID: `openclaw-agent` unless you changed `PHONE_AGENT_DEFAULT_DEVICE`.
- Token: the pairing `token` value.

Grant overlay permission, start the bubble, and grant Accessibility only when you want Lynk or a host agent to observe/control the phone.

## Backend Notes

- OpenClaw is the default host harness and uses Gateway sessions for normal chat history.
- Hermes appears in the Android model picker when `HERMES_API_KEY` is configured.
- Codex appears when the `codex app-server` command is available.
- Local LiteRT-LM appears when local mode is enabled in Android and a `.litertlm` model is installed.
- Keep OpenClaw Gateway, Hermes, Codex app-server, and similar host-agent transports on localhost or trusted private networks. Expose only the phone-facing bridge through Tailscale for off-LAN use.

See `docs/setup.md`, `docs/pairing.md`, `docs/protocol.md`, and `docs/host-installer.md` for deeper setup and protocol details.
