# OpenAgent

OpenAgent is an Android bubble/chat/voice endpoint for delegating work to host-side agents or an on-device LiteRT-LM model. The phone app is the always-available surface; Host mode routes through the PC bridge to OpenClaw, Hermes, or Codex, while Local phone mode can chat and call Android/local tools directly on the device.

Target control loop:

1. Android overlay bubble sends a text request to the PC bridge over WebSocket.
2. The bridge routes the request to the selected host harness as a general delegated task.
3. OpenClaw, Hermes, or Codex handles the work on the remote PC and streams status/results back to the bubble.
4. If the task needs Android interaction, the host agent can call the phone-control tools exposed by the bridge.
5. Android executes those optional phone commands with `AccessibilityService` and returns observations.

Current prototype note: OpenClaw is the default host harness and has the most Gateway-specific code, but Hermes and Codex are supported host harnesses. Generated Codex schemas are local/gitignored inspection output only.

## Host and Local Modes

- **Host bridge** is the default mode. Android connects to the PC bridge over LAN, USB reverse, or Tailscale and uses the selected OpenClaw, Hermes, or Codex harness on the host.
- **Local phone** runs a `.litertlm` model through LiteRT-LM on Android. Import the model from **Connection & Config**, choose CPU/GPU/NPU, then switch **Run on** to **Local phone**.
- Local mode reuses the Android accessibility tools and adds app-private workspace tools. Full shell/git/build execution requires a future Termux helper, so keep Host mode for mature desktop/coding workflows.

## Install The Host Bridge

The bridge can be run directly from this checkout today. Packaged installer scaffolding lives in `pc/installers/` and is documented in `docs/host-installer.md`; a finished platform installer should copy the built bridge bundle, run the host refresh command once, register the bridge at login, preserve the generated config, then show the pairing QR.

From a source checkout:

```bash
cd pc
npm install
npm run host:refresh
npm run bridge
```

On first run, the bridge creates a persistent config file with a strong token:

- macOS: `~/Library/Application Support/Android Agent Bridge/config.json`
- Windows: `%ProgramData%\AndroidAgentBridge\config.json`
- Linux: `~/.config/android-agent-bridge/config.json`

Environment variables and `pc/.env.local` still override that config for development. Copy `pc/.env.example` to `pc/.env.local` only when you need explicit local overrides such as a non-default port, OpenClaw Gateway auth, Hermes API settings, Codex app-server settings, or an OpenAI key for realtime voice.

With the bridge running, print a pairing payload or QR in another terminal:

```bash
cd pc
npm run host:pairing
npm run host:pairing:qr
```

The QR/deep link includes the generated token, device ID, and ordered endpoint candidates for USB reverse, Tailscale, LAN, and loopback. Manual pairing remains available in Android settings if the QR flow is not available.

For source-checkout background startup, build first and inspect the OS-specific service plan:

```bash
cd pc
npm run build
npm run host:service-plan
```

The scripts under `pc/installers/` perform the same service registration for packaged installs after the bundle has been copied into its platform app directory.

Phone-control MCP registration is optional. Run this later if you want installed OpenClaw, Hermes, or Codex agents to call Android phone tools through the bridge:

```bash
cd pc
npm run host:mcp
```

After installing or changing OpenClaw, Hermes, Codex, Tailscale, or ADB, refresh discovery:

```bash
cd pc
npm run host:refresh
```

For support diagnostics:

```bash
cd pc
npm run host:diagnostics
```

Then build and install the Android app from `android/` with Android Studio or Gradle. On the phone, set:

- WebSocket URL: one of the `endpoints[].url` values from `npm run host:pairing`, usually `ws://<your-computer-lan-ip>:8788/phone`
- Device ID: `openclaw-agent`
- Token: the `token` value from `npm run host:pairing`

Grant overlay and accessibility permissions, start the agent bubble, then send:

```text
Open Settings.
```

See `docs/setup.md`, `docs/pairing.md`, and `docs/demo.md` for details.
