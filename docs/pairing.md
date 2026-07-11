# Pairing

Use a shared token for the prototype. The token is just a random secret that you generate locally; it does not come from Tailscale, OpenAI, or OpenClaw.

## QR and deep-link approval

The recommended host flow prints a QR code:

```bash
cd pc
npm run host:pairing:qr
```

Current QR links expire after five minutes and include a one-time nonce. Scanning opens a Lynk confirmation screen; it does not change the saved bridge immediately. Verify every displayed endpoint and approve the replacement warning, if shown. The authentication token is deliberately hidden on this screen.

The bridge does not synthesize TLS. For normal LAN or remote pairing, configure a real TLS terminator and set its endpoint explicitly, for example `PHONE_AGENT_PAIRING_WSS_URLS=wss://lynk.example.com/phone`. If no usable secure, USB, loopback, or explicitly permitted Tailscale endpoint exists, JSON pairing output contains `deepLink: null` and an actionable warning; QR mode exits instead of displaying a dead code.

Older `android-agent://pair` and `openclaw-agent://pair` links remain usable for compatibility, but Lynk labels them as legacy because they have no expiry or nonce. Only approve a legacy link if you generated it yourself and can verify its endpoint. Reopening a current link after approval is rejected as a replay.

Cancelling or dismissing the confirmation leaves the existing pairing and running service unchanged. Approving a replacement stops an existing bridge service so the old trusted connection is not left active; restart Lynk when you are ready to connect with the new pairing.

Generate one on the PC:

```bash
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "$PHONE_AGENT_TOKEN"
```

Paste that exact printed value into the Android **Auth token** field:

1. Open the **Lynk** Android app.
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

3. For normal use, terminate TLS in a private reverse proxy or platform service and advertise the URL:

```bash
cd pc
PHONE_AGENT_PAIRING_WSS_URLS=wss://lynk.your-tailnet.ts.net/phone npm run host:pairing:qr
```

The configured endpoint must actually terminate TLS with a certificate accepted by Android and forward `/phone` to the bridge. Lynk intentionally does not advertise guessed `wss://` URLs.

For trusted-overlay development only, Tailscale already encrypts the overlay but the WebSocket hop remains `ws://`. Opt in explicitly when generating pairing data:

```bash
PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE=1 npm run host:pairing:qr
```

The confirmation screen labels this as cleartext development, and Android will not transmit its saved OpenAI API key over the connection. Prefer `OPENAI_API_KEY` on the PC bridge.

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

- WebSocket URL: the configured `wss://` endpoint, or the explicitly approved Tailscale development endpoint
- Device ID: `openclaw-agent`
- Auth token: the printed `Android token` value from the PC shell

For example:

- WebSocket URL: `wss://lynk.your-tailnet.ts.net/phone`
- Development exception: `ws://100.88.12.34:8788/phone`

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

LAN mode requires a real TLS endpoint. Direct `ws://192.168.x.x` pairing is rejected because the shared token, chat content, screenshots, and commands would otherwise cross the LAN in cleartext.

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

3. Configure a private TLS terminator that forwards `/phone` to the bridge, set `PHONE_AGENT_PAIRING_WSS_URLS`, then use the generated QR. Manual fields are:

- WebSocket URL: a certificate-valid `wss://` endpoint
- Device ID: `openclaw-agent`
- Auth token: the printed `Android token` value from the PC shell

For example:

- WebSocket URL: `wss://lynk.home.example/phone`

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
PHONE_AGENT_ADB_REVERSE=1 npm run bridge
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
