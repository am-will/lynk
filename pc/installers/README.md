# Lynk Bridge Installers

This directory contains installer scaffolding for the Lynk host bridge companion. The scripts assume the packaged app has already run `npm run build` and includes the `pc/dist` output plus production dependencies or a bundled Node runtime.

## Packaging Contract

Each platform installer should:

1. Copy the bridge bundle to an application directory.
2. Create or preserve the generated host config and refresh integration discovery:
   `node dist/host/cli.js refresh`
3. Register and start the bridge at login:
   `node dist/host/cli.js install-service`
4. Verify service registration:
   `node dist/host/cli.js service-status`
5. Print a pairing payload or QR:
   `node dist/host/cli.js pairing --qr`
6. Optionally configure phone-control MCP for installed host agents:
   `node dist/host/cli.js mcp`
7. Preserve the generated host config across upgrades.

The generated config lives outside the app bundle and is intentionally not removed during upgrades.

Use `LYNK_BRIDGE_CONFIGURE_MCP=1` when running the platform scripts if the installer should configure available MCP integrations during install. Without that variable, host chat works after pairing and MCP phone-control tools can be added later.

## Platform Scripts

- `macos/postinstall.sh` refreshes integrations, installs a user LaunchAgent, starts it, prints status, and prints a pairing QR.
- `windows/install.ps1` refreshes integrations, installs a login scheduled task and firewall rule, starts it, prints status, and prints a pairing QR.
- `linux/install.sh` refreshes integrations, installs a user systemd service when available or XDG autostart fallback, starts it when possible, prints status, and prints a pairing QR.

These are intentionally small so the final signed/notarized installers can call them after copying files.
