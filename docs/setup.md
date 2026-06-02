# Setup

## PC

For Play Store style onboarding, prefer the Host Bridge installer flow in `docs/host-installer.md`. The installer creates the token, registers the background service, discovers LAN/Tailscale endpoints, and generates an Android pairing QR/deep link. For a VPS or SSH-only Linux host, use `docs/vps-headless-linux.md`.

Requirements:

- Node.js 24+
- OpenClaw CLI 2026.5.7+ installed and configured on the PC that should do the delegated work
- Codex CLI with `codex app-server` if selecting the Codex harness or exercising the hand-written legacy dispatcher
- OpenCode CLI with `opencode serve` if selecting the OpenCode harness, or a private `OPENCODE_SERVER_URL`
- Pi SDK configuration if selecting the Pi harness
- Hermes API server access if selecting the Hermes harness
- Same network reachability from phone to PC, either local Wi-Fi or Tailscale for off-LAN use
- Gradle or Android Studio for Android builds

Install and start the bridge:

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
export PHONE_AGENT_DEFAULT_DEVICE=openclaw-agent
export PHONE_AGENT_DISPATCHER=openclaw
export OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789
export OPENCLAW_CHAT_SESSION_KEY=agent:main:explicit:open-claw-agent
npm run bridge
```

The Android chat overlay uses the OpenClaw Gateway directly for session chat. Start or install the Gateway first:

```bash
openclaw gateway status
openclaw gateway start
```

Set `OPENCLAW_GATEWAY_TOKEN` or `OPENCLAW_GATEWAY_PASSWORD` if your Gateway requires shared-secret authentication and the bridge cannot read it from `OPENCLAW_CONFIG_PATH` or `~/.openclaw/openclaw.json`. The older `openclaw agent --json` adapter remains available as a fallback for legacy `user_request` paths and realtime delegated tasks.

`PHONE_AGENT_TOKEN` is the Android-to-bridge pairing secret. Generate it yourself with `openssl rand -hex 32`; it is not a Tailscale, OpenAI, or OpenClaw token. To enter it on Android, open **OpenAgent**, tap **Open Connection & Config**, find the **Bridge** section, paste the printed value into **Auth token**, then tap **Save**.

Phone-control MCP registration is optional. If you want installed host agents to call `phone_observe`, `phone_open_app`, and the other Android phone tools, run this after the bridge config exists:

```bash
cd pc
npm run host:mcp
```

The command updates available OpenClaw, Hermes, and Codex MCP config with the current bridge URL and token. You can rerun it after changing the token, moving the checkout, or installing one of those host agents later. For Hermes profiles, set `HERMES_CONFIG_PATH` to the active profile config before running the command. Host agents should also load the `android-control` skill from `.agents/skills/android-control/SKILL.md` so phone tasks use observe-act-verify behavior.

The bridge exposes:

- `ws://0.0.0.0:8788/phone` for Android
- `http://127.0.0.1:8788/health` for local status
- protected `http://127.0.0.1:8788/api/*` routes for phones, audit, pets, harness diagnostics, agent control, and command dispatch. Call these with `Authorization: Bearer $PHONE_AGENT_TOKEN` or `X-Phone-Agent-Token: $PHONE_AGENT_TOKEN`.

The bridge server is split into focused HTTP, WebSocket, and realtime modules. Legacy Codex schema generation remains available with `npm run codex:schemas`, but `pc/src/generated/codex-app-server/` is local/gitignored and not required for normal Open Claw setup.

The Android model picker can select multiple harnesses through this same bridge:

- **OpenClaw** is enabled by default and remains the default. Its Gateway sessions are the source of truth for normal OpenClaw chat history.
- **Hermes** appears when the `hermes` CLI is installed or when `HERMES_API_KEY` is set. A standard Hermes install works through the CLI fallback. For richer session history, steering, and SSE streaming, configure a Lynk-compatible Hermes runs API with `HERMES_API_BASE_URL`, `HERMES_API_KEY`, `HERMES_MODEL`, `HERMES_DEFAULT_SESSION_ID`, and `HERMES_RUN_TIMEOUT_SECONDS` in `pc/.env.local` or the persistent host config. If that runs API is offline but `HERMES_API_KEY` is set, Lynk can stream from the OpenAI-compatible `chat_completions` provider in `~/.hermes/config.yaml`; see `docs/hermes-runs-api.md`.
- **Codex** appears through the bundled Codex app-server adapter. Configure `CODEX_APP_SERVER_COMMAND` and `CODEX_AGENT_CWD` if the defaults do not match your machine.
- **OpenCode** appears through OpenCode's server API. Configure `OPENCODE_SERVER_URL` to reuse a private server, or configure `OPENCODE_SERVER_COMMAND`, `OPENCODE_AGENT_CWD`, `OPENCODE_SERVER_USERNAME`, `OPENCODE_SERVER_PASSWORD`, `OPENCODE_DEFAULT_AGENT`, and `OPENCODE_RUN_TIMEOUT_SECONDS` if Lynk should start and manage `opencode serve`.
- **Pi** appears through the bundled Pi SDK adapter. Configure `PI_AGENT_CWD`, `PI_AGENT_DIR`, `PI_DEFAULT_MODEL`, and `PI_RUN_TIMEOUT_SECONDS` if the defaults do not match your machine. Pi credentials and sessions stay in Pi's agent directory.
- **Local LiteRT-LLM** appears only when enabled in Android and a `.litertlm` model is installed.

Harnesses are selected from the same Android model picker as normal models. Histories are scoped by harness, so selecting Hermes shows Hermes sessions, selecting Codex shows Codex sessions, selecting OpenCode shows OpenCode sessions, selecting Pi shows Pi sessions, and selecting OpenClaw shows OpenClaw Gateway sessions. Android's **Models & Harness** settings can hide harnesses from the phone's model picker without changing the PC bridge configuration.

For off-LAN use, keep `OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789` and put only the phone-facing bridge on Tailscale. Run:

```bash
cd pc
npm run phone:tailscale
```

Then use the printed `ws://<pc-tailnet-name-or-ip>:8788/phone` URL in Android. Pairing payloads include both MagicDNS and tailnet IP candidates when Tailscale reports both. On Android, the `100.x.y.z` tailnet IP is the safest manual value unless **Use Tailscale DNS** is enabled in the Tailscale app. Do not expose OpenClaw Gateway, Hermes, Codex app-server, OpenCode server, Pi SDK internals, or bridge transports directly to the public internet.

The realtime voice path is separate from the task dispatcher: Android starts the WebRTC call, the PC bridge creates the OpenAI Realtime session, and completed general realtime intents route to the currently selected backend. Host selections use the PC harness router; Local LiteRT-LM selections run delegated work on Android. Phone-control tool calls remain a separate phone-task path.

## Android

Open `android/` in Android Studio or run Gradle from that directory. Install the app on the device, then:

1. Open **Connection & Config**. Save the **WebSocket URL**, **Device ID**, **Auth token**, and **OpenAI API key for realtime voice** if you want realtime voice or composer transcription.
2. Grant overlay permission.
3. If Android shows **Restricted setting**, open **Settings > Apps > OpenAgent**, use the three-dot menu, choose **Allow restricted settings**, and authenticate.
4. Enable **Settings > Accessibility > Installed apps > OpenAgent**.
5. Confirm the switch still says **On** after leaving and returning to that page.
6. Start the foreground agent bubble.

While OpenAgent is running, tap the bubble to open a large chat modal. The modal loads Gateway session history, streams active replies, shows model/reasoning/session controls behind the `+` button, and keeps phone-control tool activity collapsed until expanded. The foreground notification includes a **Stop Turn** action for active chat, dispatcher, and realtime voice work, including moments when the floating bubble is temporarily hidden during taps, swipes, or screenshots.

The model picker controls the active harness. Host models use the PC bridge and may include OpenClaw, Hermes, Codex, OpenCode, and Pi entries. Use **Models & Harness** to toggle which harness sections appear in the picker, and use **System Prompt** to edit the default prompt. **Local LiteRT-LLM** appears when its harness toggle is on and a `.litertlm` file is installed; local phone mode stores its chat sessions under the app's private storage and emits the same `chat.*` timeline events as Host mode. It can call Android accessibility tools directly and can read/search/write files in its app-private local workspace when local developer tools are enabled. Termux command execution is reserved for a dedicated helper and reports a configuration error until that helper exists.

For adb installs, build `android/app/build/outputs/apk/debug/app-debug.apk` and run:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

For USB testing, forward the bridge port so the phone can use the default loopback URL:

```bash
adb reverse tcp:8788 tcp:8788
```

Accessibility is intentionally user-controlled by Android. It is commonly disabled by the OS after reinstalling/updating a sideloaded APK, after uninstall/reinstall cycles, or if Android's restricted-settings gate has not been allowed. For the most stable testing loop: install once, allow restricted settings once, enable Accessibility manually once, then use normal app restarts without reinstalling.

If using adb to enable the service on a test device, preserve other enabled services by appending with `:` instead of replacing the whole setting.
