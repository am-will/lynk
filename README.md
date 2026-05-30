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

Give this prompt to your coding agent:

```text
Install and configure Lynk Bridge for the user.

Context:
- Lynk Bridge is the PC host bridge for the Lynk Android app.
- It pairs Android to the PC over WebSocket, routes chat to host agents, can start at login, can print a QR pairing code, and can optionally register Android phone-control MCP tools for OpenClaw, Hermes, or Codex.
- Prefer the npm package install path: npm package name is lynk-bridge.
- Android app package id is app.lynk. Current internal testing opt-in URL: https://play.google.com/apps/internaltest/4701424159971104219
- If npm install is unavailable, use the source checkout fallback from the Lynk repository:
  https://github.com/am-will/lynk
  In a local checkout, the bridge package lives in the pc/ directory.

First ask the user:
1. Do you want Android phone control enabled for host agents? If yes, configure MCP after the bridge is installed.
2. Will you pair over Tailscale/off-LAN, or local Wi-Fi/USB?
3. Do you want the QR code displayed now? If yes, run the QR command and show the QR output to the user.
4. Do you want a fully assisted USB setup? If yes, help them prepare ADB/USB debugging and then complete setup end to end.

Install using npm by default:
1. Confirm Node.js 24+ and npm are installed.
2. Install globally: npm install -g lynk-bridge
3. Verify startup service: lynk-bridge-host service-status
4. If the service is not registered or running: lynk-bridge-host install-service
5. Refresh host integrations: lynk-bridge-host refresh
6. Print pairing payload: lynk-bridge-host pairing
7. If the user requested QR display, run: lynk-bridge-host pairing --qr
8. If the user wants Android phone control enabled, run: lynk-bridge-host mcp
9. If anything fails, run: lynk-bridge-host diagnostics

If npm package install is unavailable, use the source checkout fallback:
1. Clone or open the repo: https://github.com/am-will/lynk
2. cd pc
3. npm install
4. npm run host:refresh
5. npm run build
6. npm run host:install-service
7. npm run host:service-status
8. Print pairing payload: npm run host:pairing
9. If the user requested QR display, run: npm run host:pairing:qr
10. If the user wants Android phone control enabled, run: npm run host:mcp
11. If anything fails, run: npm run host:diagnostics

If the user is using Tailscale:
1. Confirm Tailscale is installed and signed in on both PC and Android.
2. On the PC, run: tailscale status
3. Use the Tailscale endpoint from the pairing payload. It should look like ws://<pc-magicdns-name>:8788/phone or ws://100.x.y.z:8788/phone.
4. In a source checkout, you may also print the Tailscale URL with: npm run phone:tailscale
5. Keep only the Lynk phone-facing bridge reachable over Tailscale. Do not expose OpenClaw Gateway, Hermes, Codex app-server, or other host-agent transports publicly.

Help the user get connected:
1. Make sure the bridge service is running.
2. Make sure Android has the Lynk app installed. For testers, use the internal testing opt-in URL: https://play.google.com/apps/internaltest/4701424159971104219
3. Have the user scan the QR code, or manually enter the WebSocket URL, device ID, and token from the pairing payload.
4. Ask the user to start the Lynk bubble on Android.
5. Verify the phone registered by checking bridge health or diagnostics.
6. If the phone does not connect, check firewall/local-network permissions, Tailscale ACLs if applicable, and whether the bridge is listening on TCP port 8788.

If the user wants fully assisted USB setup:
1. Install Android platform tools / adb if missing.
2. Ask the user to enable Developer options on Android:
   - Settings > About phone > tap Build number 7 times.
3. Ask the user to enable USB debugging:
   - Settings > System > Developer options > USB debugging.
4. Ask the user to plug the phone into the computer over USB, unlock it, and accept the "Allow USB debugging?" RSA prompt.
5. Verify the device is connected with: adb devices
6. If the device shows unauthorized, ask the user to accept the prompt on the phone, then rerun adb devices.
7. Install Lynk from the internal testing opt-in URL, or if working from a source checkout and a debug APK is available, install it with:
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
8. Set up USB reverse port forwarding:
   adb reverse tcp:8788 tcp:8788
   In a source checkout, npm run phone:usb also does this and launches the app.
9. Pair using the loopback endpoint ws://127.0.0.1:8788/phone plus the device ID and token from the pairing payload, or show the QR if requested.
10. Tell the user if Android requires manual approval for overlay permission, Accessibility permission, restricted settings, notification permission, or battery optimization. Do not claim these can always be granted silently.
11. Verify connection by checking bridge health or diagnostics. The phone should appear in the registered devices list.

Expected result:
- Lynk Bridge is installed.
- The bridge starts automatically at login.
- The user has a pairing payload and QR code if requested.
- Android can connect to the bridge.
- Optional phone-control MCP tools are installed only if the user requested them.
- Tailscale pairing is configured if the user chose Tailscale.
- Fully assisted USB setup works when the user has enabled USB debugging, trusted the computer, and completed any Android-only permission prompts.
```

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

Internal testing opt-in URL:

```text
https://play.google.com/apps/internaltest/4701424159971104219
```

Play Store package-id URL, once the production listing is published:

```text
https://play.google.com/store/apps/details?id=app.lynk
```

Current Play Console status checked on May 30, 2026: `app.lynk` has internal testing release `2 (0.1.1)` available to internal testers, with Production inactive. Testers must be added to the selected internal testing email list before the opt-in link will work for their Google account.

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

### Fully Assisted USB Setup

If you want an agent to set up the bridge end to end with minimal manual steps, prepare the phone for ADB first:

1. Install Android platform tools / ADB on the computer.
2. On Android, enable Developer options: **Settings > About phone**, then tap **Build number** 7 times.
3. Enable **USB debugging** in **Settings > System > Developer options**.
4. Plug the phone into the computer over USB.
5. Unlock the phone and accept the **Allow USB debugging?** RSA prompt.
6. Ask your coding agent to install and configure Lynk Bridge end to end.

The agent can then:

- Verify the device with `adb devices`.
- Install the APK if working from a source checkout and a debug build exists.
- Run `adb reverse tcp:8788 tcp:8788` or `cd pc && npm run phone:usb`.
- Start or verify the bridge service.
- Print or display the pairing QR.
- Help pair Android using `ws://127.0.0.1:8788/phone`.
- Configure optional MCP phone-control tools if you opted in.
- Verify the phone registered with bridge health or diagnostics.

Android may still require user approval for overlay permission, Accessibility, restricted settings, notifications, battery optimization, and the USB debugging trust prompt. Those prompts are OS-controlled and should be completed on the phone.

## Backend Notes

- OpenClaw is the default host harness and uses Gateway sessions for normal chat history.
- Hermes appears in the Android model picker when `HERMES_API_KEY` is configured.
- Codex appears when the `codex app-server` command is available.
- Local LiteRT-LM appears when local mode is enabled in Android and a `.litertlm` model is installed.
- Keep OpenClaw Gateway, Hermes, Codex app-server, and similar host-agent transports on localhost or trusted private networks. Expose only the phone-facing bridge through Tailscale for off-LAN use.

See `docs/setup.md`, `docs/pairing.md`, `docs/protocol.md`, and `docs/host-installer.md` for deeper setup and protocol details.
