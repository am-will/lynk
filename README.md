# Open Claw Agent

Open Claw Agent is an Android bubble endpoint for delegating work to an installed Open Claw session on the user's remote PC. The phone app is the always-available chat and voice surface; Open Claw does most work on the remote PC, and Android phone control is exposed as an optional tool capability when a task actually needs the phone.

Target control loop:

1. Android overlay bubble sends a text request to the PC bridge over WebSocket.
2. The bridge sends the request to the Open Claw session adapter as a general delegated task.
3. Open Claw handles the work on the remote PC and streams status/results back to the bubble.
4. If the task needs Android interaction, Open Claw can call the phone-control tools exposed by the bridge.
5. Android executes those optional phone commands with `AccessibilityService` and returns observations.

Current prototype note: the PC dispatcher still contains a hand-written Codex app-server compatibility path. Treat it as legacy scaffolding for the Open Claw migration, not the product direction. Generated Codex schemas are local/gitignored inspection output only.

## Quick Start

```bash
cd pc
npm install
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
export PHONE_AGENT_DISPATCHER=openclaw
npm run openclaw:mcp
npm run bridge
```

Then build and install the Android app from `android/` with Android Studio or Gradle. On the phone, set:

- WebSocket URL: `ws://<your-computer-lan-ip>:8788/phone`
- Device ID: `openclaw-agent`
- Token: the `PHONE_AGENT_TOKEN` value printed above

Grant overlay and accessibility permissions, start the agent bubble, then send:

```text
Open Settings.
```

See `docs/setup.md`, `docs/pairing.md`, and `docs/demo.md` for details.
