# Legacy Codex MCP Configuration

This configuration is for the hand-written Codex app-server compatibility path. Keep it working until the Open Claw session adapter can expose the same phone-control tools, but do not treat Codex as the long-term product dependency.

Run the PC bridge first:

```bash
cd pc
export PHONE_AGENT_TOKEN="$(openssl rand -hex 32)"
echo "Android token: $PHONE_AGENT_TOKEN"
npm run bridge
```

Preferred setup:

```bash
cd pc
npm run codex:mcp
```

This rewrites the `android-phone` MCP entry in Codex's user config with the current checkout path, bridge URL, and token. It is safe to rerun after moving the checkout or rotating `PHONE_AGENT_TOKEN`.

For project-scoped Codex config, create `.codex/config.toml` with paths for your checkout:

```toml
[mcp_servers.android-phone]
command = "<repo-root>/pc/node_modules/.bin/tsx"
args = ["<repo-root>/pc/src/mcp/androidPhoneServer.ts"]
cwd = "<repo-root>/pc"
enabled = true
startup_timeout_sec = 20
tool_timeout_sec = 60

[mcp_servers.android-phone.env]
PHONE_AGENT_BRIDGE_URL = "http://127.0.0.1:8788"
PHONE_AGENT_TOKEN = "<same-token-saved-in-android>"
```

Codex only loads project `.codex/config.toml` for trusted projects. To make the server available immediately through the official CLI-managed config, run:

```bash
codex mcp add android-phone \
  --env PHONE_AGENT_BRIDGE_URL=http://127.0.0.1:8788 \
  --env PHONE_AGENT_TOKEN="$PHONE_AGENT_TOKEN" \
  -- "$(pwd)/pc/node_modules/.bin/tsx" "$(pwd)/pc/src/mcp/androidPhoneServer.ts"
```

Verify:

```bash
codex mcp list
codex mcp get android-phone
```

If using Codex app-server, reload MCP config or restart app-server after changing config. The legacy adapter is hand-written, uses `turn/start`, and expects Codex to discover the `android-phone` tools from config. Generated Codex schemas are optional local inspection output only and are not tracked.

The server exposes:

- `phone_observe`
- `phone_open_app`
- `phone_tap_node`
- `phone_tap_xy`
- `phone_long_press_node`
- `phone_type_text`
- `phone_submit_text`
- `phone_scroll`
- `phone_swipe`
- `phone_press_back`
- `phone_press_home`
- `phone_open_recents`
- `phone_take_screenshot`
- `phone_ask_user_confirmation`
- `phone_wait`
