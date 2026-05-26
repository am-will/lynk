# Android Agent Host Bridge Installers

This directory contains installer scaffolding for the host bridge companion. The scripts assume the packaged app has already run `npm run build` and includes the `pc/dist` output plus production dependencies or a bundled Node runtime.

## Packaging Contract

Each platform installer should:

1. Copy the bridge bundle to an application directory.
2. Run the host refresh command once:
   `node dist/host/cli.js refresh`
3. Register the bridge to start at login or boot.
4. Preserve the generated host config across upgrades.
5. Open the pairing UI or print `node dist/host/cli.js pairing`.

The generated config lives outside the app bundle and is intentionally not removed during upgrades.

## Platform Scripts

- `macos/postinstall.sh` installs a user LaunchAgent.
- `windows/install.ps1` installs a login scheduled task and firewall rule.
- `linux/install.sh` installs a user systemd service when available.

These are intentionally small so the final signed/notarized installers can call them after copying files.
