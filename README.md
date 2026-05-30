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

You can run the bridge either as an installed npm package or directly from this source checkout. The installed package commands are shorter and are intended for normal use; the `npm run ...` commands remain supported for development and local checkouts.

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

Run the same bridge from this repo with the old `npm run` workflow:

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

### Command Reference

Each bridge operation has an installed-package command and a source-checkout command:

| Task | Installed package | Source checkout |
| --- | --- | --- |
| Start bridge in foreground | `lynk-bridge` | `cd pc && npm run bridge` |
| Refresh integrations | `lynk-bridge-host refresh` | `cd pc && npm run host:refresh` |
| Refresh and configure MCP | `lynk-bridge-host refresh --configure-mcp` | `cd pc && npm run host:refresh -- --configure-mcp` |
| Pairing payload | `lynk-bridge-host pairing` | `cd pc && npm run host:pairing` |
| Pairing QR | `lynk-bridge-host pairing --qr` | `cd pc && npm run host:pairing:qr` |
| Install startup service | `lynk-bridge-host install-service` | `cd pc && npm run host:install-service` |
| Remove startup service | `lynk-bridge-host uninstall-service` | `cd pc && npm run host:uninstall-service` |
| Service status | `lynk-bridge-host service-status` | `cd pc && npm run host:service-status` |
| Diagnostics | `lynk-bridge-host diagnostics` | `cd pc && npm run host:diagnostics` |
| Optional phone-control MCP | `lynk-bridge-host mcp` | `cd pc && npm run host:mcp` |
| MCP server process | `lynk-bridge-mcp` | `cd pc && npm run mcp` |
| USB reverse pairing | use source checkout or `adb reverse tcp:8788 tcp:8788` | `cd pc && npm run phone:usb` |
| Tailscale URL helper | use `lynk-bridge-host pairing` endpoint list | `cd pc && npm run phone:tailscale` |

## Bridge Config

The bridge creates a persistent config with a strong token on first run:

- macOS: `~/Library/Application Support/Android Agent Bridge/config.json`
- Windows: `%ProgramData%\AndroidAgentBridge\config.json`
- Linux: `~/.config/android-agent-bridge/config.json`

Environment variables and `pc/.env.local` override that config for development. Copy `pc/.env.example` to `pc/.env.local` only when you need explicit overrides such as a non-default port, OpenClaw Gateway auth, Hermes API settings, Codex app-server settings, or an OpenAI key.

The phone must use the same token as the bridge. The easiest path is the QR/deep link from `lynk-bridge-host pairing --qr` or `npm run host:pairing:qr`; it includes the token, device ID, and endpoint candidates for USB reverse, Tailscale, LAN, and loopback.

## Tailscale Pairing

Use Tailscale when the Android phone and PC are not on the same LAN. This keeps the phone-facing bridge private to your tailnet. Keep OpenClaw Gateway, Hermes, Codex app-server, and other host-agent transports on localhost or trusted private networks.

1. Install and sign in to Tailscale on the PC and Android phone with the same tailnet.

2. Confirm the PC is online:

```bash
tailscale status
```

3. Start or verify the bridge. Installed package:

```bash
lynk-bridge-host service-status
lynk-bridge-host install-service
```

Source checkout:

```bash
cd pc
npm run host:service-status
npm run host:install-service
```

For foreground development instead, run `lynk-bridge` or `cd pc && npm run bridge`.

4. Print pairing data. Installed package:

```bash
lynk-bridge-host pairing
lynk-bridge-host pairing --qr
```

Source checkout:

```bash
cd pc
npm run host:pairing
npm run host:pairing:qr
```

The pairing payload includes ordered endpoint candidates. Use the `tailscale` endpoint when present, usually one of:

```text
ws://<pc-magicdns-name>:8788/phone
ws://100.x.y.z:8788/phone
```

For a source checkout, you can also print just the Tailscale bridge URL:

```bash
cd pc
npm run phone:tailscale
```

5. On Android, scan the QR or manually set:

- WebSocket URL: the Tailscale endpoint from the pairing payload.
- Device ID: the pairing `deviceId`, usually `openclaw-agent`.
- Token: the pairing `token`.

6. If pairing fails:

- Confirm both devices are online in Tailscale.
- Confirm tailnet ACLs allow the phone to reach the PC on TCP port `8788`.
- Confirm the bridge is listening on all interfaces, not only loopback:

```bash
lsof -nP -iTCP:8788 -sTCP:LISTEN
```

The listener should show `*:8788` or `0.0.0.0:8788`.

- If MagicDNS fails on Android, use the `100.x.y.z` Tailscale IP instead.
- If macOS prompts for firewall or local-network access, allow the Node.js process running the bridge.

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
