#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/opt/lynk-bridge}"
NODE_BIN="${NODE_BIN:-node}"
CLI="$APP_ROOT/dist/host/cli.js"

"$NODE_BIN" "$CLI" refresh ${LYNK_BRIDGE_CONFIGURE_MCP:+--configure-mcp} || true
"$NODE_BIN" "$CLI" install-service
"$NODE_BIN" "$CLI" service-status || true

echo "Lynk Bridge installed and configured to start at login."
echo "Pair Android with:"
"$NODE_BIN" "$CLI" pairing --qr
