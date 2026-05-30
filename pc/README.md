# lynk-bridge

`lynk-bridge` is the host companion for Lynk, an Android chat and voice endpoint for AI agents. It pairs the phone over WebSocket, routes chat to OpenClaw, Hermes, or Codex host harnesses, starts realtime voice sessions, and optionally exposes Android phone-control tools through MCP.

## Install

```bash
npm install -g lynk-bridge
```

Global install creates or preserves the host config, refreshes local integrations, and registers the bridge to start at login. Check it with:

```bash
lynk-bridge-host service-status
lynk-bridge-host diagnostics
```

Pair Android with:

```bash
lynk-bridge-host pairing
lynk-bridge-host pairing --qr
```

Optional commands:

```bash
lynk-bridge-host refresh
lynk-bridge-host mcp
lynk-bridge-host install-service
lynk-bridge-host uninstall-service
lynk-bridge-mcp
```

Requires Node.js 24+. Host backends are optional but at least one is needed for Host mode: OpenClaw CLI/Gateway, Hermes API with `HERMES_API_KEY`, or Codex CLI with `codex app-server`.
