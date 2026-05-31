# VPS / Headless Linux Setup

This guide covers running the Lynk bridge on a headless Linux server with Hermes as the AI backend. The bridge runs as a background service and the Android app connects to it over a private network such as Tailscale.

## Prerequisites

- Node.js 24+ (`node --version`)
- npm
- Tailscale installed and authenticated on the server
- A Hermes API server reachable from the server, often on `localhost`

## Install

```bash
npm install -g lynk-bridge
```

## Generate A Token

```bash
openssl rand -hex 32
```

Save this value. You will paste the same token into the Android app during pairing.

## Configure

Create `/etc/lynk-bridge.env` or another environment file with your settings:

```bash
PHONE_AGENT_TOKEN=<token from above>
PHONE_AGENT_DEFAULT_DEVICE=my-agent
HERMES_API_KEY=<your hermes api key>
HERMES_API_BASE_URL=http://127.0.0.1:8642/v1
HERMES_MODEL=your-default-model
HERMES_DEFAULT_SESSION_ID=my-agent
HERMES_RUN_TIMEOUT_SECONDS=600
```

When `HERMES_API_KEY` is set, Hermes is available in the Android model picker and new device state defaults to the Hermes harness. OpenClaw is not disabled by omitting `OPENCLAW_GATEWAY_URL`; if no OpenClaw Gateway is running, OpenClaw model listing may simply be empty or unhealthy.

## Run As A Systemd Service

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

Enable and start it:

```bash
systemctl --user daemon-reload
systemctl --user enable --now lynk-bridge.service
systemctl --user status lynk-bridge.service
```

Check local health:

```bash
curl http://127.0.0.1:8788/health
# {"ok":true,"phones":[]}
```

## Register Phone-Control MCP (Optional)

If you want Hermes to observe and control your phone through Lynk tools, register the phone MCP server from the global install:

```bash
lynk-bridge-host mcp
```

When working from a source checkout instead, use:

```bash
cd pc
npm run host:mcp
```

This writes the bridge URL and token into your local Hermes MCP config so phone tools such as `phone_observe` and `phone_tap_node` are available to the agent.

## Pair Your Android Device

The bridge listens on the Tailscale network interface. On the server, find your Tailscale IP:

```bash
tailscale ip -4
# 100.x.x.x
```

Use the raw IP if MagicDNS hostnames do not resolve on Android. On Android, open the Lynk app and go to Settings, then Connection:

| Field | Value |
|---|---|
| URL | `ws://100.x.x.x:8788/phone` |
| Token | the token generated above |

Tap Save, then open Chat. The bridge health endpoint will show your device in `phones` once connected.

## Hermes `/v1/runs` API Contract

The bridge drives Hermes through a `/v1/runs` REST API. If your Hermes server does not expose this path natively, use a small shim. The minimum contract is:

**`POST /v1/runs`** - create a run

```json
{ "input": "user message", "session_id": "...", "model": "..." }
```

Response:

```json
{ "run_id": "...", "session_id": "...", "status": "queued" }
```

**`GET /v1/runs/{id}/events`** - SSE stream

```text
event: delta
data: {"delta": "partial text"}

event: completed
data: {"status": "completed", "output": "full response"}
```

**`GET /v1/runs/{id}`** - final status, polled after SSE ends

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

The `usage` object must be a top-level field on the run response. A nested structure such as `{ "raw": { "usage": {...} } }` will not populate the usage panel. `context_tokens` is optional, but the Context gauge needs it to show the maximum context window.

**`GET /v1/health`** - health check

```json
{ "ok": true }
```

The `ok` field must be boolean `true` for the Android app to show the backend as healthy.

## Troubleshooting

**Bridge shows `phones: []`**

Android reconnects automatically after most non-manual disconnects, but the app must be running and Tailscale must be connected. If the phone stays absent after the service restarts, reopen the Lynk app or revisit Settings, then Chat, to prompt a fresh connection attempt.

**Model picker shows no Hermes models or the wrong harness**

Verify `HERMES_API_KEY` is set in the bridge environment and restart the service. Hermes is hidden when the key is missing.

**Usage panel shows `--`**

The `GET /v1/runs/{id}` response is missing top-level usage fields. Include `usage.input_tokens`, `usage.output_tokens`, and `usage.total_tokens` in the run response. For session lists, the bridge also accepts flat `input_tokens`, `output_tokens`, and `total_tokens` fields on session objects.

**MagicDNS hostname does not resolve on Android**

Use the raw Tailscale IP, such as `100.x.x.x`, instead of the MagicDNS hostname.

**`lynk-bridge-host mcp` fails on a global npm install**

Confirm the package installed its compiled files:

```bash
npm root -g
which lynk-bridge-mcp
```

As a workaround, manually add the MCP entry to your Hermes profile config using the global `lynk-bridge-mcp` binary:

```yaml
mcp_servers:
  android_phone:
    command: lynk-bridge-mcp
    args: []
    env:
      PHONE_AGENT_BRIDGE_URL: http://127.0.0.1:8788
      PHONE_AGENT_TOKEN: <your token>
    timeout: 120
```
