# lynk-bridge

`lynk-bridge` is the host companion for Lynk, an Android chat and voice endpoint for AI agents. It pairs the phone over WebSocket, routes chat to OpenClaw, Hermes, or Codex host harnesses, starts realtime voice sessions, and optionally exposes Android phone-control tools through MCP.

## Install

```bash
npm install -g lynk-bridge
lynk-bridge-host refresh
lynk-bridge
```

In another terminal:

```bash
lynk-bridge-host pairing --qr
```

Optional commands:

```bash
lynk-bridge-host mcp
lynk-bridge-host diagnostics
lynk-bridge-mcp
```

Requires Node.js 24+. Host backends are optional but at least one is needed for Host mode: OpenClaw CLI/Gateway, Hermes API with `HERMES_API_KEY`, or Codex CLI with `codex app-server`.
