# VPS And Headless Linux

This path is for running `lynk-bridge` on a Linux VPS or SSH-only host with an Android phone connected over Tailscale. On a VPS there is usually no useful LAN or USB path, so Tailscale is the primary pairing route.

## Network Shape

Keep backend agents private on the VPS:

- Bind OpenClaw Gateway, Hermes, Codex app-server, dashboards, and shims to `127.0.0.1`.
- Reach the phone-facing bridge over the tailnet on TCP `8788`.
- Do not open `8788`, OpenClaw Gateway, Hermes, Codex app-server, or any shim in the cloud provider public firewall/security group.
- Do not use Tailscale Funnel, ngrok, or a public reverse proxy for this prototype unless you have added stronger auth in front of the bridge.

The bridge still listens on `0.0.0.0:8788` by default so tailnet and LAN clients can reach it. On a VPS, rely on the host firewall and cloud security group to keep that port off the public internet.

## Install And Service

Install the bridge globally after publishing, or run from a checkout:

```bash
npm install -g lynk-bridge
lynk-bridge-host install-service
```

The Linux installer creates a user systemd service at `~/.config/systemd/user/lynk-bridge.service`. For an SSH-only VPS, enable lingering so the user service starts at boot without an interactive login:

```bash
sudo loginctl enable-linger "$USER"
systemctl --user daemon-reload
systemctl --user enable --now lynk-bridge.service
systemctl --user status lynk-bridge.service
```

Useful logs and checks:

```bash
journalctl --user -u lynk-bridge.service -f
lynk-bridge-host service-status
lynk-bridge-host diagnostics
curl http://127.0.0.1:8788/health
```

The persistent bridge config lives at `~/.config/android-agent-bridge/config.json` on Linux. Environment variables still override it for support sessions.

## Tailscale Pairing

Install and start Tailscale on the VPS and Android phone, then confirm both devices are in the same tailnet:

```bash
tailscale status
tailscale ip -4
```

For headless pairing, prefer plain output over QR:

```bash
lynk-bridge-host pairing
```

Copy the `deepLink` to the phone if your workflow supports opening it there, or manually enter these fields in Android **Connection & Config**:

- WebSocket URL: `ws://100.x.y.z:8788/phone`
- Device ID: the printed device id
- Auth token: the printed token

`lynk-bridge-host pairing --qr` renders a terminal QR. It can work over SSH, but narrow terminals and some VPS consoles make it unreadable. The non-QR pairing output is the reliable headless path.

Android Tailscale does not always use MagicDNS unless **Use Tailscale DNS** is enabled in the Android Tailscale app. Pairing payloads include both MagicDNS and tailnet IP candidates when available, but the `100.x.y.z` URL is the safest manual value for VPS testing.

## Hermes On A VPS

If selecting the Hermes harness, run a Lynk-compatible Hermes runs API on the VPS and keep it bound to localhost, for example `http://127.0.0.1:8642/v1`. Set the bridge config or environment:

```bash
export HERMES_API_BASE_URL=http://127.0.0.1:8642/v1
export HERMES_API_KEY=...
export HERMES_MODEL=...
```

If the bridge runs as a systemd user service, prefer storing these values in `~/.config/android-agent-bridge/config.json` or an environment file loaded by your user service. Verify from the VPS:

```bash
curl -H "Authorization: Bearer $HERMES_API_KEY" http://127.0.0.1:8642/v1/health
lynk-bridge-host diagnostics
```

## Optional MCP

After the bridge config exists, register phone-control MCP tools:

```bash
lynk-bridge-host mcp
```

For Hermes profile-based setups, point `HERMES_CONFIG_PATH` at the profile config that Hermes actually loads, for example:

```bash
HERMES_CONFIG_PATH="$HOME/.hermes/profiles/default/config.yaml" lynk-bridge-host mcp
```

Install or reference the `android-control` skill from `.agents/skills/android-control/SKILL.md` in the host agent environment so phone-control turns use observe-act-verify behavior.
