#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/opt/android-agent-bridge}"
NODE_BIN="${NODE_BIN:-node}"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_PATH="$UNIT_DIR/android-agent-bridge.service"

"$NODE_BIN" "$APP_ROOT/dist/host/cli.js" refresh || true

if command -v systemctl >/dev/null 2>&1; then
  mkdir -p "$UNIT_DIR"
  cat > "$UNIT_PATH" <<SERVICE
[Unit]
Description=Android Agent Bridge
After=network-online.target

[Service]
Type=simple
ExecStart=${NODE_BIN} ${APP_ROOT}/dist/bridge/server.js
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
SERVICE

  systemctl --user daemon-reload
  systemctl --user enable --now android-agent-bridge.service
  echo "Android Agent Bridge user service installed."
else
  AUTOSTART_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
  mkdir -p "$AUTOSTART_DIR"
  cat > "$AUTOSTART_DIR/android-agent-bridge.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Android Agent Bridge
Exec=${NODE_BIN} ${APP_ROOT}/dist/bridge/server.js
X-GNOME-Autostart-enabled=true
DESKTOP
  echo "systemd was not found; installed XDG autostart entry."
fi
