#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/Applications/Android Agent Bridge.app/Contents/Resources/bridge}"
NODE_BIN="${NODE_BIN:-/usr/local/bin/node}"
LABEL="dev.androidagent.bridge"
PLIST="$HOME/Library/LaunchAgents/${LABEL}.plist"

mkdir -p "$HOME/Library/LaunchAgents"
"$NODE_BIN" "$APP_ROOT/dist/host/cli.js" refresh || true

cat > "$PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>${NODE_BIN}</string>
    <string>${APP_ROOT}/dist/bridge/server.js</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${HOME}/Library/Logs/android-agent-bridge.log</string>
  <key>StandardErrorPath</key><string>${HOME}/Library/Logs/android-agent-bridge.err.log</string>
</dict>
</plist>
PLIST

launchctl bootout "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"
launchctl enable "gui/$(id -u)/${LABEL}"
