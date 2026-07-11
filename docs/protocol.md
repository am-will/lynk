# WebSocket Protocol

Android connects outbound to the PC bridge at `/phone`. The bridge validates `token` during registration. Android never implements MCP directly.

Network endpoints must use `wss://` and the normal Android platform certificate verifier. The bridge is a plain HTTP/WebSocket listener by default, so a WSS endpoint is advertised only when `PHONE_AGENT_PAIRING_WSS_URLS` explicitly names a real TLS terminator or reverse proxy. Cleartext `ws://` is limited to loopback/ADB; a Tailscale endpoint is allowed only through the explicit `PHONE_AGENT_PAIRING_ALLOW_INSECURE_TAILSCALE=1` development exception. Certificate pinning is not currently enabled, leaving the OkHttp client/TLS-terminator boundary available for a future managed pin policy.

In **Local phone** mode, Android bypasses `/phone` for chat turns and generates the same `chat.*` event shapes in-process. The WebSocket protocol below still describes Host bridge mode and remains the compatibility contract for PC/OpenClaw sessions.

The host bridge bounds WebSocket ingress before dispatch. An upgrade must use the origin-form `/phone` target with one valid `Host` header. At most 32 sockets are active at once, and each source address receives an upgrade burst of 12 with one slot restored every 5 seconds. A new socket must send a text registration frame of at most 16 KiB within 5 seconds. After registration, ordinary control frames are capped at 256 KiB and message bursts are rate limited; inline attachment turns retain a separate total frame allowance of about 67 MiB until the streaming attachment transport replaces inline base64. The bridge pings every 30 seconds and terminates a socket that does not answer the previous ping.

Policy closes use `4002` for missing registration, `4004` for re-registration or a device identity mismatch, `4008` for message rate exhaustion, `1003` for binary input, and `1009` for payload limits. Invalid credentials continue to use `4001`.

## Register

```json
{
  "type": "register",
  "deviceId": "openclaw-agent",
  "token": "<shared PHONE_AGENT_TOKEN>",
  "capabilities": ["accessibility_tree", "gestures", "text_input", "screenshots", "app_launch", "realtime_voice", "gateway_chat"]
}
```

Opening the TCP/WebSocket connection does not authenticate it. Until Android receives the exact `agent_status` acknowledgement `Registered <its deviceId>`, it accepts only non-registration `agent_status` progress/error messages. A command, chat, realtime, malformed registration acknowledgement, or any other frame before that acknowledgement is a protocol violation and closes the socket without dispatching the frame.

Registration permanently binds that socket to its `deviceId`. A second `register` frame is rejected. Every later message carrying `deviceId` must match the socket identity before any dispatcher runs, and command results without a `deviceId` resolve only against pending commands owned by that socket's registered device.

## Command

```json
{
  "id": "cmd_123",
  "type": "command",
  "requestOwner": "host:mcp:4ebf...",
  "command": "tap_node",
  "args": {
    "observationId": "123e4567-e89b-12d3-a456-426614174000",
    "nodeId": "n17"
  },
  "approvalCapability": "<opaque single-use capability>"
}
```

Sensitive commands require a preceding `ask_user_confirmation` command from the same `requestOwner`:

```json
{
  "id": "cmd_approval",
  "type": "command",
  "requestOwner": "host:mcp:4ebf...",
  "command": "ask_user_confirmation",
  "args": {
    "command": "tap_node",
    "args": {
      "observationId": "123e4567-e89b-12d3-a456-426614174000",
      "nodeId": "n17"
    },
    "message": "Open the selected item"
  }
}
```

An approved result contains `approvalCapability`, `approvalExpiresAtMs`, and `approvedAction`. The capability authorizes only the exact command/arguments and observation shown to the user, expires after 60 seconds, and is consumed once. It is transported as a command-envelope field, never inside the target command's `args`.

Android admits commands through one FIFO actor: one command can execute while at most 32 wait. Additional commands fail with `command_queue_full`. The bridge sends an owner-bound cancellation when a host request times out or its task stops:

```json
{
  "type": "command.cancel",
  "commandId": "cmd_123",
  "requestOwner": "host:mcp:4ebf...",
  "reason": "Timed out after 30000ms"
}
```

Cancellation removes queued work or cancels the exact active coroutine. Disconnect cancels all host-owned commands, local turn cancellation cancels that run's commands, and service teardown settles every active/queued command.

Coordinate taps use full-screen pixels, including the status and navigation bars. When a caller chooses a point from a screenshot that may have been shown at a scaled size, prefer `tap_normalized`:

```json
{
  "id": "cmd_124",
  "type": "command",
  "command": "tap_normalized",
  "args": { "xPct": 0.5, "yPct": 0.25 }
}
```

`type_text` sets or pastes text into the focused editable node. `submit_text` submits the focused field through IME enter when available, with an Android keyboard-position fallback for older or custom input surfaces.

## Result

```json
{
  "id": "cmd_123",
  "type": "result",
  "ok": true,
  "observation": {
    "observationId": "123e4567-e89b-12d3-a456-426614174000",
    "deviceId": "Galaxy",
    "package": "com.android.settings",
    "activity": "com.android.settings.Settings",
    "screenSummary": "Settings | Connections | Notifications",
    "nodes": [
      {
        "id": "n17",
        "viewIdResourceName": "app.lynk:id/openclaw_send_stop_button",
        "packageName": "app.lynk",
        "text": "",
        "contentDescription": "Send message",
        "stateDescription": "",
        "className": "android.widget.ImageButton",
        "clickable": true,
        "longClickable": false,
        "scrollable": false,
        "editable": false,
        "checkable": false,
        "checked": false,
        "selected": false,
        "enabled": true,
        "focused": false,
        "bounds": [936, 1908, 1032, 2004]
      }
    ]
  },
  "screenshot": {
    "widthPx": 1080,
    "heightPx": 2340
  },
  "error": null
}
```

Authorization failures use stable prefixes including `authorization_required`, `authorization_expired`, `authorization_replayed`, `authorization_wrong_owner`, `authorization_wrong_action`, `authorization_context_changed`, and `authorization_cancelled`.

Observation node IDs such as `n17` are ordinal and scoped to the observation UUID returned with them. `tap_node` and `long_press_node` require the exact `(observationId, nodeId)` pair. Android rejects an older generation with `stale_observation` and an absent node in the current generation with `unknown_node`; it never refreshes and silently reuses `n17` on another screen. Prefer `viewIdResourceName`, visible `text`, or `contentDescription` when choosing a node, but still send the generation pair.

`take_screenshot` results include `screenshotBase64` plus `screenshot.widthPx` and `screenshot.heightPx`. Those dimensions are the source of truth for mapping visual screenshot positions back to phone coordinates.

## OpenAgent ADB and UIAutomator Selectors

OpenAgent's own Android UI is exposed as a normal accessibility tree. Canonical selectors include:

- Bubble: `app.lynk:id/openclaw_bubble` or content description `OpenAgent`
- Chat composer: `app.lynk:id/openclaw_composer_input`
- Send or stop: `app.lynk:id/openclaw_send_stop_button`
- Model selector: `app.lynk:id/openclaw_model_selector`
- Reasoning selector: `app.lynk:id/openclaw_reasoning_selector`
- Settings action: `app.lynk:id/openclaw_header_settings_button`
- Confirmation allow/cancel: `app.lynk:id/openclaw_confirmation_allow_button` and `app.lynk:id/openclaw_confirmation_cancel_button`
- Main settings controls: `app.lynk:id/openclaw_connection_config_button`, `app.lynk:id/openclaw_system_prompt_menu_button`, `app.lynk:id/openclaw_models_harness_button`, `app.lynk:id/openclaw_agent_toggle_button`, and the individual config field IDs documented in Android resources

For manual verification:

```sh
adb shell uiautomator dump /sdcard/window.xml
adb shell uiautomator dump --compressed /sdcard/window.xml
adb shell cmd package resolve-activity app.lynk
```

When the active window package is `app.lynk`, Android keeps OpenAgent chrome attached during phone-control commands so `observe_screen`, `tap_node`, text entry, and screenshots can target the OpenAgent UI itself. When another app owns the active window, OpenAgent chrome is still suppressed during automation to avoid blocking taps and screenshots.

## User Request

```json
{
  "type": "user_request",
  "deviceId": "openclaw-agent",
  "inputType": "text",
  "text": "Open Settings"
}
```

## Agent Status

```json
{
  "type": "agent_status",
  "deviceId": "openclaw-agent",
  "status": "working",
  "text": "Agent started working"
}
```

## Agent Control

Android can ask the bridge to stop the active phone-control turn. The same message is used by the bubble stop button, realtime voice cancellation, and the foreground notification **Stop Turn** action.

```json
{
  "type": "agent_control",
  "deviceId": "openclaw-agent",
  "action": "stop",
  "reason": "Stopped from Android notification"
}
```

## Harness Chat

The large Android overlay uses explicit `chat.*` messages for harness-backed session chat. OpenClaw Gateway remains the default harness, while Hermes, Codex, OpenCode, Pi, and Devin can be selected through model-picker entries when they are configured and live on the bridge. The legacy `user_request` message remains available for compatibility, but normal typed overlay submissions use `chat.send`.

Prompt policy is session-oriented where the selected harness supports it. Normal `chat.send` messages do not carry the Android settings `systemPrompt`; the bridge sends only user text plus a short phone-task hint when the request is explicitly about phone control. Legacy `user_request` callers may still include `systemPrompt`, but Android does not send it by default.

Local phone mode mirrors these outbound event types locally: `chat.models`, `chat.sessions`, `chat.history`, `chat.state`, `chat.delta`, `chat.final`, `chat.error`, `chat.tool_event`, and `chat.tools`. Local session keys use the `local:` prefix, and the local model id is currently `local-litertlm`.

Host harnesses are selected by model id. OpenClaw keeps its existing bare model ids for backward compatibility, while non-default harness models are namespaced as `<harness>:<model>`, for example `hermes:gpt-5.5`, `codex:gpt-5.3-codex`, `opencode:anthropic/claude-sonnet-4-5`, `pi:anthropic/claude-sonnet-4-5`, or `devin:default`. Devin session keys are `devin:<ACP-session-id>`. The bridge emits optional `harnessId`, `harnessLabel`, and `modelId` metadata on model, session, and state messages so Android can group the picker and keep previous chats scoped to the active harness. The harness prefix is a Lynk selection prefix; the bridge strips the non-default harness prefix before calling that backend.

For multi-provider Hermes deployments, `/models` should include provider metadata when a bare model name is ambiguous. A Hermes model row such as `{ "id": "grok-4.3", "provider": "xai" }` is presented as `hermes:xai:grok-4.3`; the Hermes runs endpoint receives `xai:grok-4.3` and can split it for provider routing.

Android opens or refreshes the selected Gateway session after registration:

```json
{
  "type": "chat.open",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent"
}
```

Android sends user text, optional model, optional reasoning selection, and optional attachments. A `chat.send` should include either non-empty `text` or at least one attachment:

```json
{
  "type": "chat.send",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "text": "Summarize my current project status",
  "model": "hermes:gpt-5.5",
  "reasoningEffort": "high",
  "delivery": "normal",
  "attachments": [
    {
      "id": "att_123",
      "kind": "image",
      "displayName": "screenshot.png",
      "mimeType": "image/png",
      "sizeBytes": 153244,
      "contentBase64": "..."
    }
  ]
}
```

Attachment `kind` is `"image"` or `"file"`. Android sends selected files inline as base64 in `contentBase64`; each attachment is capped at 50 MiB before base64 encoding, and both Android and the PC protocol reject larger payloads. Host harnesses receive the attachment payload with the turn: OpenClaw receives the bridge attachment array, Hermes forwards the same array to run creation, and Codex converts image attachments to app-server image user input data URLs. Hermes adapters may drop unsupported attachment kinds, but they should not reinterpret the fields. Devin currently rejects attachments before starting a turn. `delivery` is optional and defaults to `"normal"`. Android uses `"queue"` or `"steer"` when the user sends text while a turn is already active:

- `"queue"` keeps the message FIFO and starts it as the next turn after the active run settles.
- `"steer"` sends the message into the active run at the next supported harness boundary. OpenClaw uses its explicit `/steer` path, Hermes uses active session steering, and Codex app-server uses `turn/steer` with the active turn id.
- Slash overrides `/queue <prompt>` and `/steer <prompt>` force the delivery for that prompt, regardless of the Android default toggle.

Devin supports active-turn cancellation through ACP `session/cancel`, but its tested ACP capabilities do not advertise active-turn steering. Stop the current turn and send a follow-up instead of using `delivery: "steer"` for Devin.

Hermes fast mode maps to `service_tier: "priority"` on `POST /runs`. When fast mode is off, Lynk omits `service_tier`; no other tier values are currently sent.

Android can stop active chat work, switch or create sessions, update model/reasoning, and invoke safe UI controls:

```json
{ "type": "chat.stop", "deviceId": "openclaw-agent", "sessionKey": "agent:main:explicit:open-claw-agent", "runId": "run_123" }
```

```json
{ "type": "chat.select_session", "deviceId": "openclaw-agent", "sessionKey": "agent:main:main" }
```

```json
{ "type": "chat.new_session", "deviceId": "openclaw-agent", "label": "Android bubble", "workspacePath": "/Users/me/project" }
```

`workspacePath` is optional and applies to Codex, OpenCode, Pi, and Devin. When present on a Codex `chat.new_session`, the bridge passes it as the app-server thread `cwd`; OpenCode receives it as the `directory` query/header; Pi creates its SDK session for that working directory; and Devin receives the absolute folder as ACP `session/new.cwd`. Android may include `createWorkspaceIfMissing: true` only after the user confirms creation of a missing host folder. A Devin miss uses `code: "devin.workspace_not_found"`. Android groups workspace-aware sessions by their returned `workspacePath`/`workspaceName`.

```json
{ "type": "chat.set_model", "deviceId": "openclaw-agent", "sessionKey": "agent:main:main", "model": "codex:gpt-5.3-codex" }
```

```json
{ "type": "chat.set_reasoning", "deviceId": "openclaw-agent", "sessionKey": "agent:main:main", "reasoningEffort": "medium" }
```

```json
{ "type": "chat.control_command", "deviceId": "openclaw-agent", "command": "fast", "args": { "enabled": true } }
```

```json
{ "type": "chat.control_command", "deviceId": "openclaw-agent", "command": "reasoning", "args": { "level": "stream" } }
```

OpenClaw exposes `verbose` and `reasoning` in its `chat.commands` metadata so Android can insert `/verbose ...` and `/reasoning ...` slash commands. The bridge still accepts the `reasoning` control command for programmatic callers and sends OpenClaw's real `/reasoning stream` or `/reasoning off` session directives.

The bridge returns session state, history, metadata, stream deltas, final text, errors, and expandable tool events:

```json
{
  "type": "chat.state",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "harnessId": "openclaw",
  "harnessLabel": "OpenClaw",
  "runId": "run_123",
  "isRunning": true,
  "status": "OpenClaw is working",
  "model": "gpt-5.5",
  "reasoningEffort": "high",
  "reasoningStream": true,
  "fastMode": true
}
```

```json
{
  "type": "chat.history",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "messages": [
    { "id": "u1", "role": "user", "text": "Hello", "timestamp": 1779070000000 },
    { "id": "a1", "role": "assistant", "text": "Hi.", "timestamp": 1779070001000 }
  ]
}
```

The bridge can also append a single visible message without replacing the timeline. Realtime-delegated requests, steers, and stop reasons use this so the viewfinder shows them as normal user bubbles while Gateway output continues streaming into the same chat:

```json
{
  "type": "chat.message",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "message": { "id": "user_realtime_call_abc", "role": "user", "text": "Open Facebook messages", "timestamp": 1779070000000 }
}
```

```json
{
  "type": "chat.delta",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123",
  "delta": "Working"
}
```

Reasoning stream deltas are temporary UI blocks. Android renders them while a run is reasoning, then removes them with an animated dissolve once real assistant output starts streaming or the final answer arrives:

```json
{
  "type": "chat.reasoning_delta",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123",
  "delta": "Inspecting the repository structure"
}
```

```json
{
  "type": "chat.reasoning_clear",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123"
}
```

```json
{
  "type": "chat.final",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123",
  "text": "Done."
}
```

Structured chat failures use `chat.error`. `message` is the user-readable fallback, while optional `code` and detail fields drive client UI flows without parsing text. Missing workspace prompts use `code: "codex.workspace_not_found"`, `code: "opencode.workspace_not_found"`, `code: "pi.workspace_not_found"`, or `code: "devin.workspace_not_found"` with `workspacePath`:

```json
{
  "type": "chat.error",
  "deviceId": "openclaw-agent",
  "sessionKey": "codex:019e56b1-639e-7ad2-b078-3106a2ee0874",
  "message": "Codex workspace folder not found: ~/missing",
  "code": "codex.workspace_not_found",
  "workspacePath": "~/missing"
}
```

When a user-initiated run reaches a terminal reply, the bridge also emits a per-session unread signal. Android uses this for native notification-tray entries, the floating bubble unread badge, and badges in the previous-chats picker. This message is sent even if the user has switched away from the session, so `chat.final`/`chat.error` timeline delivery can remain scoped to the selected session.

```json
{
  "type": "chat.reply_available",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123",
  "status": "completed",
  "textPreview": "Done.",
  "sessionId": "session_abc",
  "sessionDisplayName": "Trip planning",
  "harnessId": "openclaw",
  "harnessLabel": "OpenClaw",
  "model": "gpt-5.5-high-fast"
}
```

Android treats unread state as local and per session. Opening the modal while that `sessionKey` is selected, selecting the session from previous chats, or tapping that session's native notification marks only that session as read and cancels its tray notification. Unread replies for other sessions remain until those sessions are opened.

```json
{
  "type": "chat.tool_event",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "runId": "run_123",
  "eventId": "tool_1",
  "toolName": "exec",
  "title": "Ran npm test",
  "status": "completed",
  "args": { "command": "npm test" },
  "output": "ok"
}
```

Metadata messages are `chat.models`, `chat.commands`, `chat.tools`, `chat.sessions`, and `chat.usage`. The Android UI treats all of them as replaceable snapshots for its local chat state. `chat.models` may contain duplicate human-readable model names across harnesses; use `id` as the selection value and show `harnessLabel` next to the model. `chat.sessions` is scoped to the active harness, so histories do not mix between OpenClaw, Hermes, Codex, OpenCode, Pi, Devin, and Local LiteRT. Workspace-aware session rows include `workspacePath`, `workspaceName`, and `source`; some harnesses also supply `threadPath` or `preview`. Android uses the workspace fields to group previous sessions by host folder.

### Devin ACP mapping

The bridge runs `devin acp` as a private stdio child process and maps stable ACP updates onto the shared Lynk contract:

- `agent_message_chunk` becomes `chat.delta`; the accumulated response becomes one `chat.final`.
- `agent_thought_chunk` becomes `chat.reasoning_delta`, which Android renders as temporary thought/status text.
- `tool_call` and `tool_call_update` become stable-ID `chat.tool_event` rows; ACP plan updates are represented as informational plan tool events.
- `usage_update` carries consumed context, context-window size, and USD cost when supplied. Final ACP prompt usage is merged into the final/session usage snapshot.
- ACP available-command, config-option, current-mode, and session-info updates refresh Lynk's command, model/reasoning, title, and session metadata.

ACP permission requests become blocked `chat.tool_event` rows whose `actions` preserve every exact ACP `optionId`, name, and allow/reject kind. Android replies with the selected option through `devin.permission`; the bridge rejects stale, cross-session, or unoffered option IDs. It never invents an approval result.

For authenticated Devin CLI `3000.1.27`, Lynk uses ACP `session/list` as the authoritative catalog and `session/load` for complete history replay after a process or bridge restart. Local `devin-sessions.json` metadata only preserves Lynk workspace/model associations and empty Lynk-created sessions. `session/resume` is not advertised by that runtime and is not called. Session IDs observed in the real acceptance test are ephemeral and must not be presented as stable identifiers.

`chat.usage` and the matching token fields on `chat.sessions` use `totalTokens` as the current consumed-token numerator and `contextTokens` as the model's effective context-window denominator. `contextTokens` is not a second consumed-token count; it should reflect the configured or discovered model window for the active harness/model, such as Hermes `context_length`, Codex app-server provider bounds, or the local LiteRT-LM context setting. Android renders context percentage as `totalTokens / contextTokens` when both values are present.

The bridge exposes authenticated harness diagnostics at `/api/harnesses/health` and `/api/harnesses/readiness`. Harness health entries must use `ok: true` for healthy backends. A response such as `{ "status": "ok" }` is treated as unknown or unhealthy by Lynk UI code. Readiness combines configuration and model availability; health is the live reachability check used before chat sends.

Hermes-specific HTTP expectations are documented in `docs/hermes-runs-api.md`. The required API is a runs/SSE contract under `HERMES_API_BASE_URL`, including `/health`, `/models`, `/runs`, `/runs/{id}/events`, `/runs/{id}/stop`, session listing, and capabilities.

## Realtime Voice

Realtime voice mode uses Android WebRTC for live audio and the PC bridge for OpenAI Realtime session creation. Android creates the WebRTC offer, sends it to the PC bridge, and the bridge posts it to OpenAI's `/v1/realtime/calls` endpoint. Android message names use dotted `realtime.*` types.

Every inbound and outbound `realtime.*` message carries the same required UUID `voiceSessionId`. Android creates a fresh UUID before each start. The bridge uses the pair `(deviceId, voiceSessionId)` as the exact owner of the transport and delegated-task queue; Android ignores replies for any older ID. A replacement start invalidates the prior owner before asynchronous setup continues, so a late SDP, error, task result, or close cannot mutate the new call. This is a lockstep protocol requirement: missing or non-UUID IDs are rejected rather than assigned to whichever call happens to be current.

Prefer `OPENAI_API_KEY` on the PC bridge. The bridge-owned key takes precedence and never crosses the phone transport. An Android override is included in `realtime.start` only over `wss://`; it is omitted on loopback, ADB, and cleartext Tailscale development links. The bridge defaults voice sessions to `gpt-realtime-2`; override with `OPENAI_REALTIME_MODEL` if needed.

### Start

Android sends:

```json
{
  "type": "realtime.start",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "sdp": "v=0\r\n...",
  "model": "hermes:gpt-5.5",
  "reasoningEffort": "medium",
  "location": {
    "latitude": 31.7619,
    "longitude": -106.485,
    "accuracyMeters": 100,
    "provider": "network",
    "capturedAtMs": 1779050000000
  },
  "openAiApiKey": "sk-..."
}
```

Optional fields: `systemPrompt`, `model`, `reasoningEffort`, `location`, and `openAiApiKey`. `model` and `reasoningEffort` describe the selected chat backend when the voice session starts; delegated voice tool calls also send the current values per call so routing does not depend on stale speech-session state. These fields do not change the OpenAI Realtime speech model, which is still controlled by `OPENAI_REALTIME_MODEL`. `model` may be a bare OpenClaw model id, a namespaced host model such as `hermes:gpt-5.5`, `codex:gpt-5.3-codex`, `opencode:anthropic/claude-sonnet-4-5`, or `pi:anthropic/claude-sonnet-4-5`, or `local-litertlm` for Android local mode. `reasoningEffort` must be one of the shared reasoning option ids. `systemPrompt` is applied once when the realtime session starts; it is not resent per utterance or delegated task. Android sends `location` only when the user has granted location permission and the device has a recent best-effort location available. The bridge uses it as context for localized realtime answers and web searches.

The bridge replies with the remote SDP answer:

```json
{
  "type": "realtime.sdp",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "sdp": "v=0\r\n..."
}
```

### Events

Transcript deltas and final transcript text both use `realtime.transcript_delta`. Final text is marked with `isFinal: true`.

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "role": "assistant",
  "delta": "Open",
  "isFinal": false
}
```

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "role": "assistant",
  "delta": "",
  "text": "Opening Settings.",
  "isFinal": true
}
```

Raw non-audio realtime items are forwarded for Android-side normalization or debugging:

```json
{
  "type": "realtime.item_added",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "item": { "type": "message", "role": "assistant" }
}
```

### Tool Calls

Realtime voice sessions expose high-level OpenAI function tools. Android parses function-call events from the WebRTC data channel and either handles local-selected tasks on-device or relays bridge-selected tasks to the PC bridge.

Use `delegate_agent_task` for general work that should happen in the currently selected backend:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_general",
  "name": "delegate_agent_task",
  "model": "hermes:gpt-5.5",
  "reasoningEffort": "medium",
  "arguments": {
    "instruction": "Summarize my current project status",
    "urgency": "normal"
  }
}
```

For host models, the bridge validates that `name` is `delegate_agent_task`, rejects empty or oversized instructions, and routes the task through the visible chat session as a general task for the selected harness. For `local-litertlm`, Android runs the delegated instruction through the local LiteRT-LM controller and returns the local result to the realtime session. `delegate_openclaw_task` remains accepted as a compatibility alias. The delegated instruction is also emitted as `chat.message` with `role: "user"` so it appears in the Android viewfinder before the backend stream starts.

Use `run_phone_task` for new actionable phone tasks:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_abc",
  "itemId": "item_abc",
  "name": "run_phone_task",
  "model": "codex:gpt-5.3-codex",
  "reasoningEffort": "medium",
  "arguments": {
    "instruction": "Open Facebook messages",
    "urgency": "normal"
  }
}
```

For bridge-routed work, the bridge validates `model` and `reasoningEffort` on each tool call, rejects empty or oversized instructions, and routes the task through the same visible chat session. Only one realtime task runs per device. Later calls queue FIFO up to the bridge limit; calls with `"urgency": "interrupt"` interrupt the active task before starting the new task.

Realtime chat reuses the previous realtime session for 15 minutes after the last accepted realtime task, steer, or stop. If the window has expired, the bridge starts a fresh chat before sending the realtime request so the viewfinder shows a clean task thread.

Use `steer_phone_task` when the user corrects or adds information while a phone task is running. The bridge sends the guidance into the active Gateway chat as a visible user message:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_steer",
  "name": "steer_phone_task",
  "arguments": {
    "guidance": "Stop looking in settings; use the already open Messages app."
  }
}
```

Use `steer_agent_task` the same way for a general selected-harness task. `steer_openclaw_task` remains accepted as a compatibility alias.

Use `stop_phone_task` when the user says to stop, pause, cancel, or leave the phone as-is. The bridge cancels queued realtime tasks, aborts the active Gateway chat run, and appends the stop reason as a visible user message:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_stop",
  "name": "stop_phone_task",
  "arguments": {
    "reason": "User said stop."
  }
}
```

Use `stop_agent_task` the same way for a general selected-harness task. `stop_openclaw_task` remains accepted as a compatibility alias.

Use `hang_up_realtime` when the user says to hang up, end the call, or stop listening. By default it closes only the realtime voice session and lets bridge-routed host or phone work continue. Android-local LiteRT-LM delegated work is owned by the voice session, so every local voice terminal path cancels and joins that work before the shared local engine is released. Set `stopPhoneTask` only when the user explicitly asks to stop bridge-routed phone work and hang up:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_hangup",
  "name": "hang_up_realtime",
  "arguments": {
    "reason": "User asked to hang up.",
    "stopPhoneTask": false
  }
}
```

Use `web_search` for current-information questions that do not require controlling the phone. The bridge answers through OpenAI Responses API web search and returns the text as the tool output:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_search",
  "name": "web_search",
  "arguments": {
    "query": "El Paso TX weather today"
  }
}
```

Task status updates are sent whenever the active task or queue changes:

```json
{
  "type": "realtime.task_status",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "running": true,
  "queued": 1,
  "currentTask": "Open Facebook messages",
  "completed": 0,
  "failed": 0
}
```

When the task finishes, fails, times out, or is cancelled, the bridge sends a correlated result:

```json
{
  "type": "realtime.tool_result",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "callId": "call_abc",
  "ok": true,
  "status": "completed",
  "output": "Facebook messages are open."
}
```

Android sends that result back to OpenAI Realtime as a `function_call_output` conversation item, followed by `response.create`, so Realtime can speak the outcome while the WebRTC session remains connected.

Speech-start notifications are shown when the WebRTC data channel emits an OpenAI speech-start event:

```json
{
  "type": "realtime.speech_started",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "role": "user",
  "itemId": null
}
```

### Device Validation

Use these checks after starting the PC bridge and Android bubble:

1. Start realtime voice and say, “Open Facebook messages.”
2. Verify Realtime gives a short spoken acknowledgement and does not hang up.
3. Verify the Android voice panel shows the active task and queued count.
4. While the task is running, speak another actionable instruction and verify it queues, or use an explicit interrupt instruction and verify the active task is cancelled.
5. After the first task changes phone state, say a follow-up such as “Message Alice…” and verify the active dispatcher acts from the current screen.
6. Verify risky actions still trigger the Android confirmation flow before proceeding.

### Stop, Errors, And Close

Android sends:

```json
{
  "type": "realtime.stop",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "reason": "User hung up"
}
```

The bridge sends `realtime.error` if OpenAI rejects startup, stop, or a runtime realtime event fails:

```json
{
  "type": "realtime.error",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "message": "OpenAI realtime call failed: 401 Unauthorized"
}
```

The bridge sends `realtime.closed` when the realtime transport closes or the local session is cleaned up:

```json
{
  "type": "realtime.closed",
  "deviceId": "openclaw-agent",
  "voiceSessionId": "3d594650-3436-4f71-8ec3-3ad304f12c83",
  "reason": "User hung up"
}
```
