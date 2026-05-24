# WebSocket Protocol

Android connects outbound to the PC bridge at `/phone`. The bridge validates `token` during registration. Android never implements MCP directly.

In **Local phone** mode, Android bypasses `/phone` for chat turns and generates the same `chat.*` event shapes in-process. The WebSocket protocol below still describes Host bridge mode and remains the compatibility contract for PC/OpenClaw sessions.

## Register

```json
{
  "type": "register",
  "deviceId": "openclaw-agent",
  "token": "<shared PHONE_AGENT_TOKEN>",
  "capabilities": ["accessibility_tree", "gestures", "text_input", "screenshots", "app_launch", "realtime_voice", "gateway_chat"]
}
```

## Command

```json
{
  "id": "cmd_123",
  "type": "command",
  "command": "tap_node",
  "args": { "nodeId": "n17" }
}
```

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
    "deviceId": "Galaxy",
    "package": "com.android.settings",
    "activity": "com.android.settings.Settings",
    "screenSummary": "Settings | Connections | Notifications",
    "nodes": [
      {
        "id": "n17",
        "viewIdResourceName": "dev.openclawagent:id/openclaw_send_stop_button",
        "packageName": "dev.openclawagent",
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

Observation node IDs such as `n17` are ephemeral and only valid until the next observation. Prefer `viewIdResourceName`, visible `text`, or `contentDescription` as stable selectors when available. Android exports OpenAgent resource IDs for meaningful controls under `dev.openclawagent:id/...`; decorative artwork is intentionally hidden from accessibility so the tree stays focused on actionable UI.

`take_screenshot` results include `screenshotBase64` plus `screenshot.widthPx` and `screenshot.heightPx`. Those dimensions are the source of truth for mapping visual screenshot positions back to phone coordinates.

## OpenAgent ADB and UIAutomator Selectors

OpenAgent's own Android UI is exposed as a normal accessibility tree. Canonical selectors include:

- Bubble: `dev.openclawagent:id/openclaw_bubble` or content description `OpenAgent`
- Chat composer: `dev.openclawagent:id/openclaw_composer_input`
- Send or stop: `dev.openclawagent:id/openclaw_send_stop_button`
- Model selector: `dev.openclawagent:id/openclaw_model_selector`
- Reasoning selector: `dev.openclawagent:id/openclaw_reasoning_selector`
- Settings action: `dev.openclawagent:id/openclaw_header_settings_button`
- Confirmation allow/cancel: `dev.openclawagent:id/openclaw_confirmation_allow_button` and `dev.openclawagent:id/openclaw_confirmation_cancel_button`
- Main settings controls: `dev.openclawagent:id/openclaw_connection_config_button`, `dev.openclawagent:id/openclaw_system_prompt_menu_button`, `dev.openclawagent:id/openclaw_models_harness_button`, `dev.openclawagent:id/openclaw_agent_toggle_button`, and the individual config field IDs documented in Android resources

For manual verification:

```sh
adb shell uiautomator dump /sdcard/window.xml
adb shell uiautomator dump --compressed /sdcard/window.xml
adb shell cmd package resolve-activity dev.openclawagent
```

When the active window package is `dev.openclawagent`, Android keeps OpenAgent chrome attached during phone-control commands so `observe_screen`, `tap_node`, text entry, and screenshots can target the OpenAgent UI itself. When another app owns the active window, OpenAgent chrome is still suppressed during automation to avoid blocking taps and screenshots.

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

The large Android overlay uses explicit `chat.*` messages for harness-backed session chat. OpenClaw Gateway remains the default harness, while Hermes and Codex can be selected through model-picker entries when they are configured on the bridge. The legacy `user_request` message remains available for compatibility, but normal typed overlay submissions use `chat.send`.

Prompt policy is session-oriented where the selected harness supports it. Normal `chat.send` messages do not carry the Android settings `systemPrompt`; the bridge sends only user text plus a short phone-task hint when the request is explicitly about phone control. Legacy `user_request` callers may still include `systemPrompt`, but Android does not send it by default.

Local phone mode mirrors these outbound event types locally: `chat.models`, `chat.sessions`, `chat.history`, `chat.state`, `chat.delta`, `chat.final`, `chat.error`, `chat.tool_event`, and `chat.tools`. Local session keys use the `local:` prefix, and the local model id is currently `local-litertlm`.

Host harnesses are selected by model id. OpenClaw keeps its existing bare model ids for backward compatibility, while non-default harness models are namespaced as `<harness>:<model>`, for example `hermes:gpt-5.5` or `codex:gpt-5.3-codex`. The bridge emits optional `harnessId`, `harnessLabel`, and `modelId` metadata on model, session, and state messages so Android can group the picker and keep previous chats scoped to the active harness.

Android opens or refreshes the selected Gateway session after registration:

```json
{
  "type": "chat.open",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent"
}
```

Android sends user text, optional model, and optional reasoning selection:

```json
{
  "type": "chat.send",
  "deviceId": "openclaw-agent",
  "sessionKey": "agent:main:explicit:open-claw-agent",
  "text": "Summarize my current project status",
  "model": "hermes:gpt-5.5",
  "reasoningEffort": "high",
  "delivery": "normal"
}
```

`delivery` is optional and defaults to `"normal"`. Android uses `"queue"` or `"steer"` when the user sends text while a turn is already active:

- `"queue"` keeps the message FIFO and starts it as the next turn after the active run settles.
- `"steer"` sends the message into the active run at the next supported harness boundary. OpenClaw uses its explicit `/steer` path, Hermes uses active session steering, and Codex app-server uses `turn/steer` with the active turn id.
- Slash overrides `/queue <prompt>` and `/steer <prompt>` force the delivery for that prompt, regardless of the Android default toggle.

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

`workspacePath` is optional and Codex-only. When present on a Codex `chat.new_session`, the bridge passes it as the app-server thread `cwd` so the new thread starts in that workspace. Android may include `createWorkspaceIfMissing: true` after user confirmation to create a missing Codex workspace folder before starting the thread. Other harnesses ignore these workspace fields.

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
  "sessionDisplayName": "Trip planning"
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

Metadata messages are `chat.models`, `chat.commands`, `chat.tools`, `chat.sessions`, and `chat.usage`. The Android UI treats all of them as replaceable snapshots for its local chat state. `chat.models` may contain duplicate human-readable model names across harnesses; use `id` as the selection value and show `harnessLabel` next to the model. `chat.sessions` is scoped to the active harness, so histories do not mix between OpenClaw, Hermes, Codex, and Local LiteRT. Codex session rows may additionally include `workspacePath`, `workspaceName`, `threadPath`, `preview`, and `source`; Android uses those optional fields only for the Codex previous-sessions picker so Codex threads can be grouped by their desktop workspace folder.

`chat.usage` and the matching token fields on `chat.sessions` use `totalTokens` as the current consumed-token numerator and `contextTokens` as the model's effective context-window denominator. `contextTokens` is not a second consumed-token count; it should reflect the configured or discovered model window for the active harness/model, such as Hermes `context_length`, Codex app-server provider bounds, or the local LiteRT-LM context setting. Android renders context percentage as `totalTokens / contextTokens` when both values are present.

## Realtime Voice

Realtime voice mode uses Android WebRTC for live audio and the PC bridge for OpenAI Realtime session creation. Android creates the WebRTC offer, sends it to the PC bridge, and the bridge posts it to OpenAI's `/v1/realtime/calls` endpoint. Android message names use dotted `realtime.*` types.

The OpenAI API key can be supplied either by setting `OPENAI_API_KEY` on the PC bridge or by saving it in the Android app settings. If the Android app sends an `openAiApiKey` in `realtime.start`, the bridge uses it only for that realtime call. The bridge defaults voice sessions to `gpt-realtime-2`; override with `OPENAI_REALTIME_MODEL` if needed.

### Start

Android sends:

```json
{
  "type": "realtime.start",
  "deviceId": "openclaw-agent",
  "sdp": "v=0\r\n...",
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

Optional fields: `systemPrompt`, `model`, `reasoningEffort`, `location`, and `openAiApiKey`. `systemPrompt` is applied once when the realtime session starts; it is not resent per utterance or delegated task. Android sends `location` only when the user has granted location permission and the device has a recent best-effort location available. The bridge uses it as context for localized realtime answers and web searches.

The bridge replies with the remote SDP answer:

```json
{
  "type": "realtime.sdp",
  "deviceId": "openclaw-agent",
  "sdp": "v=0\r\n..."
}
```

### Events

Transcript deltas and final transcript text both use `realtime.transcript_delta`. Final text is marked with `isFinal: true`.

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "openclaw-agent",
  "role": "assistant",
  "delta": "Open",
  "isFinal": false
}
```

```json
{
  "type": "realtime.transcript_delta",
  "deviceId": "openclaw-agent",
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
  "item": { "type": "message", "role": "assistant" }
}
```

### Tool Calls

Realtime voice sessions expose high-level OpenAI function tools. Android parses function-call events from the WebRTC data channel and relays the completed call to the PC bridge.

Use `delegate_agent_task` for general work that should happen in the currently selected host harness on the remote PC:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "callId": "call_general",
  "name": "delegate_agent_task",
  "arguments": {
    "instruction": "Summarize my current project status",
    "urgency": "normal"
  }
}
```

The bridge validates that `name` is `delegate_agent_task`, rejects empty or oversized instructions, and routes the task through the visible chat session as a general task for the selected harness. `delegate_openclaw_task` remains accepted as a compatibility alias. The delegated instruction is also emitted as `chat.message` with `role: "user"` so it appears in the Android viewfinder before the harness stream starts.

Use `run_phone_task` for new actionable phone tasks:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
  "callId": "call_abc",
  "itemId": "item_abc",
  "name": "run_phone_task",
  "arguments": {
    "instruction": "Open Facebook messages",
    "urgency": "normal"
  }
}
```

The bridge validates that `name` is `run_phone_task`, rejects empty or oversized instructions, and routes the task through the same visible chat session. Only one realtime task runs per device. Later calls queue FIFO up to the bridge limit; calls with `"urgency": "interrupt"` interrupt the active task before starting the new task.

Realtime chat reuses the previous realtime session for 15 minutes after the last accepted realtime task, steer, or stop. If the window has expired, the bridge starts a fresh chat before sending the realtime request so the viewfinder shows a clean task thread.

Use `steer_phone_task` when the user corrects or adds information while a phone task is running. The bridge sends the guidance into the active Gateway chat as a visible user message:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
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
  "callId": "call_stop",
  "name": "stop_phone_task",
  "arguments": {
    "reason": "User said stop."
  }
}
```

Use `stop_agent_task` the same way for a general selected-harness task. `stop_openclaw_task` remains accepted as a compatibility alias.

Use `hang_up_realtime` when the user says to hang up, end the call, or stop listening. By default it closes only the realtime voice session and lets any running phone task continue. Set `stopPhoneTask` only when the user explicitly asks to stop the phone task and hang up:

```json
{
  "type": "realtime.tool_call",
  "deviceId": "openclaw-agent",
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
  "reason": "User hung up"
}
```

The bridge sends `realtime.error` if OpenAI rejects startup, stop, or a runtime realtime event fails:

```json
{
  "type": "realtime.error",
  "deviceId": "openclaw-agent",
  "message": "OpenAI realtime call failed: 401 Unauthorized"
}
```

The bridge sends `realtime.closed` when the realtime transport closes or the local session is cleaned up:

```json
{
  "type": "realtime.closed",
  "deviceId": "openclaw-agent",
  "reason": "User hung up"
}
```
