# Host Bridge Installer

The Host Bridge installer packages the PC bridge as a background companion for Android Agent. Its job is to remove terminal setup from the Play Store onboarding path.

## User Flow

1. Install the host bridge companion on macOS, Windows, or Linux.
2. The installer creates or preserves the host config and registers the bridge to start at login.
3. Open the host pairing screen or run the installed CLI:

   ```bash
   lynk-bridge-host pairing --qr
   ```

4. Scan the QR code with Android. The QR encodes an `android-agent://pair` deep link with the token, pairing ID, ordered endpoint candidates, a five-minute expiry, and a one-time nonce.
5. Lynk shows the normalized endpoints and a warning if this would replace an existing pairing. Nothing is saved until the user explicitly approves.
6. Android tries the approved endpoint candidates until one completes bridge registration.

Legacy links without expiry and nonce still reach the confirmation screen for compatibility, but are clearly marked as legacy and always require explicit approval. A current link cannot be approved twice on the same phone.

Manual URL/token entry remains available in Android settings as a fallback. On headless Linux or VPS hosts, prefer `lynk-bridge-host pairing` without `--qr`; see `docs/vps-headless-linux.md`.

The installer does not register MCP servers with OpenClaw, Hermes, or Codex automatically. Host chat is available after pairing; phone-control tools inside those host agents are an optional integration the user can install later.

## Installed Config

The bridge creates a strong token on first run and stores consumer config outside the app bundle:

- macOS: `~/Library/Application Support/Android Agent Bridge/config.json`
- Windows: `%ProgramData%\\AndroidAgentBridge\\config.json`
- Linux: `~/.config/android-agent-bridge/config.json`

Environment variables still override the file for development and support sessions.

## Discovery

Pairing candidates are ordered from endpoints that are actually usable:

1. Explicit certificate-valid endpoints from `PHONE_AGENT_PAIRING_WSS_URLS`.
2. USB reverse or loopback development endpoints when their opt-ins are set.
3. Tailscale `ws://` endpoints only when `PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE=1` explicitly enables the trusted-overlay development exception.

The Node bridge does not terminate TLS by default and therefore never invents LAN or Tailscale `wss://` candidates. Without a usable endpoint, pairing JSON includes an actionable warning and QR mode exits without producing a dead QR.

USB reverse is not included in normal pairing because it depends on `adb reverse`. For development-only USB pairing, set `PHONE_AGENT_PAIRING_INCLUDE_USB=1` before printing the pairing QR.
To make an installed bridge restore its development USB tunnel automatically, set `"phoneAgentAdbReverse": true` in the persistent host config and restart the service. The setting is intentionally opt-in and does not expose any non-phone harness transport.
Loopback is also omitted from normal Android pairing because `127.0.0.1` points at the phone, not the host. For host-local diagnostics, set `PHONE_AGENT_PAIRING_INCLUDE_LOOPBACK=1`.

Only the token-authenticated phone-facing bridge should be reachable over Tailscale. OpenClaw Gateway, Hermes, Codex app-server, Devin ACP stdio, and other host-agent transports should stay on localhost or trusted private networks. On Android, MagicDNS may require enabling **Use Tailscale DNS** in the Tailscale app; the `100.x.y.z` candidate remains the reliable fallback.

## Refresh Integrations

If a user installs Hermes, Codex, OpenClaw, Devin, Tailscale, or ADB after installing the bridge, run:

```bash
lynk-bridge-host refresh
```

The installed companion should expose the same action as **Refresh Integrations**. Refresh rescans known tools, stores discovered absolute paths, and reports whether a bridge restart is recommended. For Devin it also runs a bounded, output-redacted `devin auth status` check. The result distinguishes missing CLI, unauthenticated CLI, timeout/spawn failure, and authenticated readiness without including identity or token output. Authenticate with `devin auth login`, rerun refresh, and restart the service when refresh recommends it.

## Optional MCP Registration

If the user wants OpenClaw, Hermes, or Codex to control the Android phone through MCP tools, run:

```bash
lynk-bridge-host mcp
```

This command updates the available host-agent MCP registrations with the current bridge URL and token. It is safe to rerun after moving the app, changing the bridge port, regenerating the pairing token, or installing a host agent later. `npm run host:refresh -- --configure-mcp` performs the same MCP update while also refreshing integration discovery. Hermes profile users should set `HERMES_CONFIG_PATH` to the active profile config. Host agents should load `.agents/skills/android-control/SKILL.md` alongside the MCP tools for proper observe-act-verify phone-control behavior.

## Diagnostics

For support, run:

```bash
lynk-bridge-host diagnostics
```

The diagnostics bundle redacts secrets and includes OS details, config shape, endpoint discovery, service plan, and integration status. Devin diagnostics report only safe configuration/auth/readiness fields. The bridge also exposes authenticated diagnostics at `/api/diagnostics`; live harness state is available from the authenticated `/api/harnesses/health` and `/api/harnesses/readiness` routes.

## Release Checklist

- Build and test PC bridge: `cd pc && npm ci && npm run check && npm test && npm run build`.
- Build and test Android: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`.
- Package the host bridge with `pc/dist`, `pc/package.json`, `pc/package-lock.json`, `pc/.env.example`, `pc/installers`, production dependencies, and the archive README.
- Code sign and notarize macOS artifacts.
- Sign Windows installer artifacts.
- Smoke test clean macOS, Windows, and Ubuntu VMs.
- Verify LAN pairing, Tailscale pairing, wrong-token recovery, refresh after installing Codex/Hermes/Devin, Devin auth failure and live ACP readiness, optional `host:mcp` registration, and uninstall/upgrade token preservation.

## Extracted Tagged Archives

Tagged release archives already contain production dependencies. From the extracted archive root, run the compiled entrypoints directly; the source-only `npm run` commands are not part of this artifact:

```bash
node dist/bin/lynk-bridge-host.js help
node dist/bin/lynk-bridge-host.js pairing --qr
node dist/bin/lynk-bridge.js
```

The MCP stdio server entrypoint is `node dist/bin/lynk-bridge-mcp.js`. Keep the extracted directory intact because its `dist`, `node_modules`, and installer files form one runtime unit.
