# Host Bridge Installer

The Host Bridge installer packages the PC bridge as a background companion for Android Agent. Its job is to remove terminal setup from the Play Store onboarding path.

## User Flow

1. Install the host bridge companion on macOS, Windows, or Linux.
2. The installer creates or preserves the host config and registers the bridge to start at login.
3. Open the host pairing screen or run:

   ```bash
   cd pc
   npm run host:pairing:qr
   ```

4. Scan the QR code with Android. The QR encodes an `android-agent://pair` deep link with the token, pairing ID, and ordered endpoint candidates.
5. Android tries the saved endpoint candidates until one completes bridge registration.

Manual URL/token entry remains available in Android settings as a fallback.

The installer does not register MCP servers with OpenClaw, Hermes, or Codex automatically. Host chat is available after pairing; phone-control tools inside those host agents are an optional integration the user can install later.

## Installed Config

The bridge creates a strong token on first run and stores consumer config outside the app bundle:

- macOS: `~/Library/Application Support/Android Agent Bridge/config.json`
- Windows: `%ProgramData%\\AndroidAgentBridge\\config.json`
- Linux: `~/.config/android-agent-bridge/config.json`

Environment variables still override the file for development and support sessions.

## Discovery

Pairing candidates are ordered for the common paths:

1. USB reverse (`ws://127.0.0.1:8788/phone`) when ADB is used.
2. Tailscale MagicDNS or tailnet IP when Tailscale is installed and running.
3. Local LAN IPv4 addresses, excluding loopback, Docker, VM, link-local, and tunnel interfaces.
4. Loopback fallback.

Only the phone-facing bridge should be reachable over Tailscale. OpenClaw Gateway, Hermes, Codex app-server, and other host-agent transports should stay on localhost or trusted private networks.

## Refresh Integrations

If a user installs Hermes, Codex, OpenClaw, Tailscale, or ADB after installing the bridge, run:

```bash
cd pc
npm run host:refresh
```

The installed companion should expose the same action as **Refresh Integrations**. Refresh rescans known tools, stores discovered absolute paths, and reports whether a bridge restart is recommended.

## Optional MCP Registration

If the user wants OpenClaw, Hermes, or Codex to control the Android phone through MCP tools, run:

```bash
cd pc
npm run host:mcp
```

This command updates the available host-agent MCP registrations with the current bridge URL and token. It is safe to rerun after moving the app, changing the bridge port, regenerating the pairing token, or installing a host agent later. `npm run host:refresh -- --configure-mcp` performs the same MCP update while also refreshing integration discovery.

## Diagnostics

For support, run:

```bash
cd pc
npm run host:diagnostics
```

The diagnostics bundle redacts secrets and includes OS details, config shape, endpoint discovery, service plan, and integration status. The bridge also exposes authenticated diagnostics at `/api/diagnostics`.

## Release Checklist

- Build and test PC bridge: `cd pc && npm ci && npm run check && npm test && npm run build`.
- Build and test Android: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- Package the host bridge with `pc/dist`, `pc/package.json`, `pc/package-lock.json`, and `pc/installers`.
- Code sign and notarize macOS artifacts.
- Sign Windows installer artifacts.
- Smoke test clean macOS, Windows, and Ubuntu VMs.
- Verify LAN pairing, Tailscale pairing, wrong-token recovery, refresh after installing Codex/Hermes, optional `host:mcp` registration, and uninstall/upgrade token preservation.
