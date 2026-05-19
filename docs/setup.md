# Setup

## PC

Requirements:

- Node.js 24+
- OpenClaw CLI 2026.5.7+ installed and configured on the PC that should do the delegated work
- Codex CLI with `codex app-server` only if exercising the hand-written legacy dispatcher
- Same network reachability from phone to PC, either local Wi-Fi or Tailscale for off-LAN use
- Gradle or Android Studio for Android builds

Install, register the optional phone-control MCP server with OpenClaw, and start the bridge:

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
export PHONE_AGENT_DEFAULT_DEVICE=openclaw-agent
export PHONE_AGENT_DISPATCHER=openclaw
export OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789
export OPENCLAW_CHAT_SESSION_KEY=agent:main:explicit:open-claw-agent
npm run openclaw:mcp
npm run bridge
```

The Android chat overlay uses the OpenClaw Gateway directly for session chat. Start or install the Gateway first:

```bash
openclaw gateway status
openclaw gateway start
```

Set `OPENCLAW_GATEWAY_TOKEN` or `OPENCLAW_GATEWAY_PASSWORD` if your Gateway requires shared-secret authentication and the bridge cannot read it from `OPENCLAW_CONFIG_PATH` or `~/.openclaw/openclaw.json`. The older `openclaw agent --json` adapter remains available as a fallback for legacy `user_request` paths and realtime delegated tasks.

`PHONE_AGENT_TOKEN` is the Android-to-bridge pairing secret. Generate it yourself with `openssl rand -hex 32`; it is not a Tailscale, OpenAI, or OpenClaw token. To enter it on Android, open **Open Claw Agent**, tap **Open Connection & Config**, find the **Bridge** section, paste the printed value into **Auth token**, then tap **Save**.

The bridge exposes:

- `ws://0.0.0.0:8788/phone` for Android
- `http://127.0.0.1:8788/health` for local status
- protected `http://127.0.0.1:8788/api/*` routes for phones, audit, pets, agent control, and command dispatch. Call these with `Authorization: Bearer $PHONE_AGENT_TOKEN` or `X-Phone-Agent-Token: $PHONE_AGENT_TOKEN`.

The bridge server is split into focused HTTP, WebSocket, and realtime modules. Legacy Codex schema generation remains available with `npm run codex:schemas`, but `pc/src/generated/codex-app-server/` is local/gitignored and not required for normal Open Claw setup.

For off-LAN use, keep `OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789` and put only the phone-facing bridge on Tailscale. Run:

```bash
cd pc
npm run phone:tailscale
```

Then use the printed `ws://<pc-tailnet-name-or-ip>:8788/phone` URL in Android. Do not expose OpenClaw Gateway or app-server transports directly to the internet.

The realtime voice path is separate from the task dispatcher: Android starts the WebRTC call, the PC bridge creates the OpenAI Realtime session, and completed realtime intents route to OpenClaw by default. Phone-control tool calls are only one possible capability of that OpenClaw session.

## Android

Open `android/` in Android Studio or run Gradle from that directory. Install the app on the device, then:

1. Open **Open Connection & Config** and save the **WebSocket URL**, **Device ID**, **Auth token**, and **OpenAI API key for realtime voice** if you want realtime voice or composer transcription.
2. Grant overlay permission.
3. If Android shows **Restricted setting**, open **Settings > Apps > Open Claw Agent**, use the three-dot menu, choose **Allow restricted settings**, and authenticate.
4. Enable **Settings > Accessibility > Installed apps > Open Claw Agent**.
5. Confirm the switch still says **On** after leaving and returning to that page.
6. Start the foreground agent bubble.

While Open Claw Agent is running, tap the bubble to open a large chat modal. The modal loads Gateway session history, streams active replies, shows model/reasoning/session controls behind the `+` button, and keeps phone-control tool activity collapsed until expanded. The foreground notification includes a **Stop Turn** action for active chat, dispatcher, and realtime voice work, including moments when the floating bubble is temporarily hidden during taps, swipes, or screenshots.

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
