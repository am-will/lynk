# VPS / Headless Linux Setup

This guide covers running the Lynk bridge on a headless Linux server (VPS, home server, cloud VM) with Hermes as the AI backend. Unlike a desktop install, there is no GUI, no OpenClaw, and the bridge runs as a background service.

## Prerequisites

- Node.js 24+ (`node --version`)
- npm
- [Tailscale](https://tailscale.com/download/linux) installed and authenticated on the server
- A Hermes API server accessible from the VPS (can be `localhost`)

## Install

```bash
npm install -g lynk-bridge
```

## Generate a token

```bash
openssl rand -hex 32
```

Save this value — you will paste it into the Android app during pairing.

## Configure

Create `/etc/lynk-bridge.env` (or any path you prefer) with your settings:

```bash
PHONE_AGENT_TOKEN=<token from above>
PHONE_AGENT_DEFAULT_DEVICE=my-agent
HERMES_API_KEY=<your hermes api key>
HERMES_API_BASE_URL=http://127.0.0.1:8642/v1
HERMES_MODEL=your-default-model
HERMES_DEFAULT_SESSION_ID=my-agent
HERMES_RUN_TIMEOUT_SECONDS=600
```

> **No OpenClaw needed.** The bridge will show only the Hermes harness in the Android model picker when `HERMES_API_KEY` is set and `OPENCLAW_GATEWAY_URL` is not.

## Run as a systemd service

Create `~/.config/systemd/user/lynk-bridge.service`:

```ini
[Unit]
Description=Lynk Bridge
After=network-online.target

[Service]
Type=simple
EnvironmentFile=/etc/lynk-bridge.env
ExecStart=/usr/bin/env lynk-bridge
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
```

Enable and start:

```bash
systemctl --user daemon-reload
systemctl --user enable --now lynk-bridge.service
systemctl --user status lynk-bridge.service
```

Check it is healthy:

```bash
curl http://127.0.0.1:8788/health
# {"ok":true,"phones":[]}
```

## Register the phone-control MCP (optional)

If you want Hermes to be able to observe and control your phone:

```bash
lynk-bridge host:mcp
```

This writes the bridge URL and token into your local Hermes MCP config so phone tools (`phone_observe`, `phone_tap_node`, etc.) are available to the agent.

## Pair your Android device

The bridge listens on the Tailscale network interface. On the server, find your Tailscale IP:

```bash
tailscale ip -4
# 100.x.x.x
```

> **Use the raw IP, not the MagicDNS hostname.** Android does not resolve Tailscale MagicDNS hostnames by default. The raw `100.x.x.x` address always works.

On Android, open the Lynk app and go to **Settings → Connection**:

| Field | Value |
|---|---|
| URL | `ws://100.x.x.x:8788/phone` |
| Token | *(the token you generated above)* |

Tap **Save** (or equivalent), then navigate to the **Chat** tab to trigger the connection. The bridge health endpoint will show your device in `phones` once connected.

## Hermes `/v1/runs` API contract

The bridge drives Hermes through a `/v1/runs` REST API. If your Hermes server does not expose this path natively, you will need a small shim. The minimum contract is:

**`POST /v1/runs`** — create a run
```json
{ "input": "user message", "session_id": "...", "model": "..." }
→ { "run_id": "...", "session_id": "...", "status": "queued" }
```

**`GET /v1/runs/{id}/events`** — SSE stream
```
event: delta
data: {"delta": "partial text"}

event: completed
data: {"status": "completed", "output": "full response"}
```

**`GET /v1/runs/{id}`** — final status (polled after SSE ends)
```json
{
  "run_id": "...",
  "status": "completed",
  "output": "full response",
  "usage": {
    "input_tokens": 1234,
    "output_tokens": 56,
    "total_tokens": 1290,
    "context_tokens": 128000
  }
}
```

> **`usage` must be a top-level field.** The bridge reads `response.usage` directly. A nested structure like `{ "raw": { "usage": {...} } }` will not populate the usage panel.

> **`context_tokens`** is optional but required for the Context gauge in the usage panel. Set it to the model's maximum context window size.

**`GET /v1/health`** — health check
```json
{ "ok": true }
```

> The `ok` field must be boolean `true`. The Android app checks `ok === true` to display "Gateway: ok" status.

## Troubleshooting

**Bridge shows `phones: []` after reconnect**
The WebSocket drops when the bridge restarts. Open the Lynk app, go to **Settings → Connection**, and navigate back to Chat to trigger a reconnect. There is no auto-reconnect on bridge restart.

**Model picker shows no models / wrong harness**
Verify `HERMES_API_KEY` is set in the environment. The Hermes harness is hidden when the key is missing.

**Usage panel shows `--`**
The `GET /v1/runs/{id}` response is missing a top-level `usage` object. See the API contract above.

**MagicDNS hostname doesn't resolve on Android**
Use the raw Tailscale IP (`100.x.x.x`) instead of the MagicDNS hostname. Android does not use the Tailscale DNS resolver by default.

**`lynk-bridge host:mcp` fails on a global npm install**
The `host:mcp` command requires the compiled source to be present. On a global install this may fail if `dist/` is incomplete. As a workaround, manually add the MCP entry to your Hermes profile config pointing to the `lynk-bridge-mcp.js` binary:

```yaml
mcp_servers:
  android_phone:
    command: node
    args:
      - /path/to/node_modules/lynk-bridge/dist/bin/lynk-bridge-mcp.js
    env:
      PHONE_AGENT_BRIDGE_URL: http://127.0.0.1:8788
      PHONE_AGENT_TOKEN: <your token>
    timeout: 120
```
