# Pairing

Use a shared token for the prototype. The token is just a random secret that you generate locally; it does not come from Tailscale, OpenAI, or OpenClaw.

Generate one on the PC:

```bash
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "$PHONE_AGENT_TOKEN"
```

Paste that exact printed value into the Android **Auth token** field:

1. Open the **OpenAgent** Android app.
2. Tap **Open Connection & Config**.
3. In the **Connection & Config** dialog, find the **Bridge** section.
4. Paste the value into **Auth token**.
5. Tap **Save**.

If you later start the bridge from a new shell, export the same token again or generate a new one and update **Auth token** on Android to match.

## Tailscale Remote Mode

Use this mode when the Android phone and PC are not on the same LAN. It keeps the bridge private to your Tailscale tailnet instead of exposing OpenClaw, Hermes, Codex, the bridge, or any app-server transport to the public internet. For a VPS or SSH-only Linux host, this is the primary path; see `docs/vps-headless-linux.md`.

1. On the PC, confirm Tailscale is connected:

```bash
tailscale status
```

2. Install Tailscale on Android, sign in to the same tailnet, and confirm the phone can see this PC in the Tailscale app.

3. Print the Android bridge URL from the PC:

```bash
cd pc
npm run phone:tailscale
```

The helper prints a `ws://...:8788/phone` URL and any fallback URLs. Pairing payloads include both MagicDNS and `100.x.y.z` tailnet IP candidates when Tailscale reports both.

4. Generate a bridge token, then start OpenClaw Gateway and the bridge on the PC:

```bash
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
openclaw gateway start
export OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789
npm run bridge
```

`OPENCLAW_GATEWAY_URL` should stay on `127.0.0.1`; only the phone-facing bridge uses the tailnet path.

5. On Android, open **OpenAgent**, tap **Open Connection & Config**, then set these fields in the **Bridge** section:

- WebSocket URL: `ws://<pc-tailnet-name-or-ip>:8788/phone`
- Device ID: `openclaw-agent`
- Auth token: the printed `Android token` value from the PC shell

For example:

- WebSocket URL: `ws://100.88.12.34:8788/phone`
- WebSocket URL: `ws://your-mac.your-tailnet.ts.net:8788/phone`

6. Put the phone on mobile data, keep Tailscale connected, then tap **Save** and **Start Agent Bubble**.

The bridge `/health` endpoint shows connected phones:

```bash
curl http://127.0.0.1:8788/health
```

You should see the device under `phones`.

If the phone does not register:

- Confirm both devices are online in the Tailscale app or admin console.
- Confirm your tailnet ACLs allow the phone to reach this PC on TCP port `8788`.
- Confirm the bridge is listening on all interfaces:

```bash
lsof -nP -iTCP:8788 -sTCP:LISTEN
```

The listener should show `*:8788`, not only `127.0.0.1:8788`.

- Keep the phone unlocked while starting the agent.
- If macOS prompts for firewall access, allow it for the terminal or Node.js process running the bridge.
- If MagicDNS fails on Android, either enable **Use Tailscale DNS** in the Android Tailscale app or use the `100.x.y.z` Tailscale IP from `npm run phone:tailscale`.

Do not expose OpenClaw Gateway, Codex app-server, or the bridge directly to the public internet. Tailscale Funnel, Cloudflare Tunnel, and ngrok are intentionally not the default for this prototype because they add public ingress risk.

## Local Wi-Fi

This is the preferred mode once the app is installed. It does not rely on `adb reverse`, so the phone can be unplugged as long as it stays on the same LAN as the PC.

1. Start the bridge on the PC:

```bash
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
npm run bridge
```

2. Find the PC LAN IP:

```bash
ipconfig getifaddr en0
```

If that is empty, inspect active interfaces:

```bash
ifconfig | rg "inet .*broadcast|status: active|^[a-z].*:"
```

3. On Android, open **OpenAgent**, tap **Open Connection & Config**, then set these fields in the **Bridge** section:

- WebSocket URL: `ws://<pc-lan-ip>:8788/phone`
- Device ID: `openclaw-agent`
- Auth token: the printed `Android token` value from the PC shell

For example:

- WebSocket URL: `ws://192.168.1.163:8788/phone`

4. Tap **Save**, then **Start Agent Bubble**.

The bridge `/health` endpoint shows connected phones:

```bash
curl http://127.0.0.1:8788/health
```

You should see the device under `phones`.

If the phone does not register:

- Confirm the phone and PC are on the same Wi-Fi/subnet.
- Keep the phone unlocked while starting the agent.
- Confirm the bridge is listening on all interfaces:

```bash
lsof -nP -iTCP:8788 -sTCP:LISTEN
```

The listener should show `*:8788`, not only `127.0.0.1:8788`.

- If macOS prompts for local-network/firewall access, allow it for the terminal or Node.js process running the bridge.

## USB Development Mode

USB mode is useful while installing or debugging, but it depends on `adb reverse`. It is not part of the normal pairing QR because the app must keep working after the cable is unplugged.

```bash
cd pc
npm run phone:usb
```

In this mode the Android app may use:

```text
ws://127.0.0.1:8788/phone
```

Run `npm run phone:usb` again after reconnecting USB.

To intentionally include USB reverse in a development pairing payload, run:

```bash
PHONE_AGENT_PAIRING_INCLUDE_USB=1 npm run host:pairing:qr
```
