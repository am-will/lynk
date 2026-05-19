# Open Claw Dispatcher Migration Plan And Status

**Generated**: 2026-05-17  
**Estimated Complexity**: Medium to High

## Overview

Open Claw Agent should primarily be a phone-native endpoint into the user's installed Open Claw session on a remote PC. The Android bubble is the product surface: instead of opening Telegram, Discord, or another chat client to reach Open Claw, the user can tap the floating bubble, type or speak a request, and delegate work directly to Open Claw.

Phone control remains valuable, but it is a secondary capability. Most delegated work should happen on the remote PC where Open Claw is installed. The phone should act as an input surface, notification/status surface, and optional tool target only when the task actually requires interacting with the Android device.

The best path is to keep the Android bridge, Android overlay, and OpenAI Realtime voice flow, then keep Codex app-server as a hand-written legacy adapter while `OpenClawSessionClient` delivers general Open Claw tasks and exposes phone-control tools as optional capabilities.

## Implementation Status

- Default dispatcher selection now uses `PHONE_AGENT_DISPATCHER=openclaw` and `OpenClawSessionClient`.
- The Android chat overlay now uses the OpenClaw Gateway WebSocket path for primary session chat: `chat.history`, `chat.message`, `chat.send`, `chat.abort`, `sessions.*`, `models.list`, `commands.list`, and `tools.effective`.
- `OpenClawSessionClient` still delegates legacy `user_request` and fallback paths to `openclaw agent --json` with a stable `OPENCLAW_AGENT_SESSION_ID`.
- Realtime delegated OpenClaw work now routes through the Gateway chat bridge so start, steer, and stop text appears in the viewfinder as user messages and Gateway output streams back to the same chat.
- Realtime voice exposes `delegate_openclaw_task` for general remote-PC work and keeps `run_phone_task` for explicit Android phone work.
- `npm run openclaw:mcp` registers the existing `android-phone` MCP server with OpenClaw, so phone tools are optional capabilities of the OpenClaw session.
- The Android bubble now opens a large chat modal with scrollable Gateway history, a bottom composer, model/reasoning/session controls, usage summary, and expandable tool rows.
- The hand-written Codex app-server adapter remains available only as `PHONE_AGENT_DISPATCHER=codex` legacy compatibility.
- Generated Codex app-server schemas are no longer tracked. `npm run codex:schemas` can recreate local, gitignored inspection output, while the legacy adapter stays hand-written.

## Product Principles

- The default destination for a bubble request is Open Claw on the remote PC, not Android automation.
- Phone-control tools are available to Open Claw, but Open Claw should use them only when the user asks for phone work or when the task clearly needs phone context.
- The Android app should feel like a dedicated Open Claw remote: quick chat, voice, status, stop/cancel, and task result review.
- The bridge should avoid forcing every task into a phone-control prompt shape. General tasks, coding tasks, browser/desktop tasks, and research tasks should pass through naturally to Open Claw.

## Target Architecture

1. Android keeps connecting to the PC bridge over `/phone`.
2. Bubble text requests are routed to Open Claw as general delegated tasks by default.
3. Android voice keeps using OpenAI Realtime through the PC bridge; completed voice intents route to the same Open Claw task endpoint.
4. The PC bridge streams Gateway chat deltas, final answers, errors, metadata, and tool events back to the bubble.
5. Open Claw performs most work on the remote PC using its normal desktop/session capabilities.
6. The bridge exposes Android phone-control tools to Open Claw for optional phone interaction.
7. When Open Claw calls a phone tool, the local tool layer forwards commands through the bridge to Android and returns observations/results.
8. The Codex app-server client remains temporarily as a compatibility adapter until Open Claw parity is verified.

## Remaining Follow-Ups

- Realtime Gateway chat sessions are currently reused for a 15-minute window; future work can expose that window as user configuration if needed.
- Mid-task steering now enters the Gateway chat as a visible user message; behavior still depends on the installed OpenClaw Gateway's active-run semantics.
- General OpenClaw tasks currently serialize through the bridge's single active adapter process; future work can use OpenClaw's own concurrency model if needed.
- How should the bridge select a remote PC/session when multiple Open Claw sessions are available?
- What auth boundary is required between this bridge and the remote PC session beyond the local prototype token?

## Sprint 1: Stabilize The Adapter Boundary

**Goal**: Make the current dispatcher explicitly multi-adapter without changing behavior.

**Demo/Validation**:

- `PHONE_AGENT_DISPATCHER=codex npm run bridge` behaves like today.
- `PHONE_AGENT_USE_FALLBACK=1 npm run bridge` still exercises fallback.
- Existing queue, stop, steer, and realtime task tests continue to pass.

### Task 1.1: Rename Intent Without Breaking Code

- **Location**: `pc/src/dispatcher/`, `docs/app-server.md`
- **Description**: Keep `CodexAppServerClient` as a legacy implementation, but document it as one `AgentClient` adapter.
- **Dependencies**: None
- **Acceptance Criteria**:
  - Dispatcher terminology in docs and logs no longer implies Codex is the product.
  - Codex-specific env vars remain supported while the adapter exists.
- **Validation**:
  - `cd pc && npm run check`
  - `cd pc && npm test`

### Task 1.2: Add Dispatcher Selection Config

- **Location**: `pc/src/dispatcher/dispatcher.ts`, `pc/src/bridge/server.ts`
- **Description**: Introduce `PHONE_AGENT_DISPATCHER=codex|openclaw|fallback`, defaulting to OpenClaw.
- **Dependencies**: Task 1.1
- **Acceptance Criteria**:
  - Unknown dispatcher values fail fast with a clear bridge startup error.
  - Fallback path remains available for local bridge testing.
- **Validation**:
  - Unit tests for config parsing.
  - Manual startup with each supported value.

## Sprint 2: Define The Open Claw Endpoint Contract

**Goal**: Decide and document exactly how the phone bubble delegates general work to Open Claw before wiring production behavior.

**Demo/Validation**:

- A local test script can send a general task to the installed Open Claw session, receive status, and terminate the task.
- A contract document names every required operation and expected error.

### Task 2.1: Discover Open Claw Control APIs

- **Location**: Open Claw install docs/source, `docs/open-claw-migration-plan.md`
- **Description**: Identify whether Open Claw is best controlled through MCP, HTTP, websocket, CLI subprocess, or an existing app session protocol.
- **Dependencies**: None
- **Acceptance Criteria**:
  - The selected control surface supports general task start, status/result streaming, tool calls, and cancellation, or the plan documents gaps.
  - The integration path works with an installed remote-PC session instead of spawning a separate unrelated agent.
- **Validation**:
  - Manual proof of concept against the installed Open Claw session.

### Task 2.2: Specify Required Endpoint Methods

- **Location**: `pc/src/dispatcher/AgentClient.ts` or equivalent, `docs/protocol.md`
- **Description**: Map bubble behavior to Open Claw operations: start general task, stream status, send final result, steer task, interrupt task, close session, and call optional phone tools.
- **Dependencies**: Task 2.1
- **Acceptance Criteria**:
  - General non-phone tasks do not require Android automation or phone-tool prompts.
  - Every existing bridge feature has an Open Claw equivalent or explicit fallback behavior.
  - Realtime task queueing does not depend on Codex `turn/start` or `turn/steer` terms.
- **Validation**:
  - Contract tests with a fake Open Claw client.

### Task 2.3: Define Task Routing Semantics

- **Location**: `pc/src/bridge/`, `pc/src/dispatcher/`, `docs/protocol.md`
- **Description**: Separate general Open Claw tasks from phone-control tasks in bridge vocabulary and status reporting.
- **Dependencies**: Task 2.2
- **Acceptance Criteria**:
  - Bubble text submissions default to general Open Claw delegation.
  - Realtime voice intents can route to either general Open Claw tasks or phone-control tasks.
  - Phone-control queueing remains per device; general Open Claw tasks can follow Open Claw's own concurrency model.
- **Validation**:
  - Fake-client tests for general task, phone task, steer, stop, and result status.

## Sprint 3: Implement The Open Claw Endpoint Adapter

**Goal**: Add `OpenClawSessionClient` so the Android bubble can delegate ordinary Open Claw work, while keeping phone-control tools available as optional tools.

**Demo/Validation**:

- Bubble text request such as “Summarize my current project status” runs on the remote PC through Open Claw and returns status/final text to Android.
- Bubble text request such as “Open Settings on my phone” uses the exposed phone tools.
- Realtime voice can delegate a general Open Claw task without requiring Android automation.
- Stop and interrupt behave correctly from the Android bubble, notification, and realtime voice.

### Task 3.1: Build `OpenClawSessionClient`

- **Location**: `pc/src/dispatcher/`
- **Description**: Implement the selected Open Claw control protocol behind the existing `AgentClient` shape.
- **Dependencies**: Sprint 2
- **Acceptance Criteria**:
  - Sends general user tasks to Open Claw without wrapping them as phone-control instructions.
  - Includes phone-tool availability as optional context/tooling.
  - Streams meaningful working/done/error status to Android.
  - Surfaces Open Claw startup/session errors without silently falling through.
- **Validation**:
  - Unit tests with mocked Open Claw responses.
  - Manual general task execution against the installed session.

### Task 3.2: Expose Phone Tools To Open Claw

- **Location**: `pc/src/mcp/tools.ts`, `pc/src/mcp/androidPhoneServer.ts`, Open Claw tool config
- **Description**: Reuse the existing phone command implementation so Open Claw can observe, tap, type, launch apps, screenshot, wait, and ask for confirmation.
- **Dependencies**: Task 3.1
- **Acceptance Criteria**:
  - Tool names and arguments stay aligned with Android command validation.
  - Open Claw can choose not to use phone tools for remote-PC tasks.
  - Screenshot width/height metadata remains available for normalized tapping.
  - `phone_ask_user_confirmation` remains mandatory for high-risk actions.
- **Validation**:
  - Tool-level tests.
  - Manual observe/open/tap/type/screenshot confirmation flow.

### Task 3.3: Preserve Steering, Interrupts, And Task Status

- **Location**: `pc/src/bridge/RealtimeTaskManager.ts`, `pc/src/dispatcher/`
- **Description**: Map bubble stop/steer and realtime stop/steer onto Open Claw session capabilities without assuming every active task is a phone-control task.
- **Dependencies**: Task 3.1
- **Acceptance Criteria**:
  - General Open Claw task status is visible in the Android bubble.
  - Phone-control tasks still queue per device while a device task is running.
  - Interrupt urgency cancels or supersedes the relevant active Open Claw task.
  - Steering reaches the active task or returns a clear unsupported error.
- **Validation**:
  - Focused tests for general task status, phone queueing, interrupt, steer, and cancellation.

## Sprint 4: Flip Defaults And Retire Codex Language

**Goal**: Make Open Claw the default and demote Codex to optional legacy support or remove it.

**Demo/Validation**:

- Fresh setup docs lead to an Open Claw-powered phone-control demo without mentioning Codex as a required dependency.
- Fresh setup docs lead with the phone bubble as an Open Claw endpoint, with phone control documented as an optional tool capability.
- Legacy Codex tests either remain explicitly labeled or are deleted with the old adapter.

### Task 4.1: Update Defaults

- **Location**: `pc/src/dispatcher/dispatcher.ts`, `pc/package.json`, `README.md`, `docs/setup.md`
- **Description**: Default to `PHONE_AGENT_DISPATCHER=openclaw`.
- **Dependencies**: Sprint 3
- **Acceptance Criteria**:
  - Quick Start works with Open Claw for a general remote-PC task.
  - Phone-control demos still work as secondary examples.
  - Codex-specific setup is no longer in the primary path.
- **Validation**:
  - End-to-end Android demo from a clean checkout.

### Task 4.2: Remove Or Archive Codex Artifacts

- **Location**: `pc/src/dispatcher/CodexAppServerClient.ts`, local `pc/src/generated/codex-app-server/` inspection output, `docs/app-server.md`, `docs/codex-mcp.md`
- **Description**: Decide whether to keep Codex as a legacy adapter or remove generated schemas and docs.
- **Dependencies**: Task 4.1
- **Acceptance Criteria**:
  - No primary docs or agent guidance describe Codex as required.
  - Any retained Codex files are clearly marked legacy.
- **Validation**:
  - `rg "Codex|codex|app-server" *.md docs pc/src` returns only intentional legacy references.

## Testing Strategy

- Run PC static checks and tests after each dispatcher change: `cd pc && npm run check && npm test`.
- Add fake-client unit tests for Open Claw start, status streaming, tool call routing, cancellation, and errors.
- Add tests for general bubble delegation, status streaming, final result delivery, and cancellation.
- Add realtime tests for general task delegation plus `run_phone_task`, `steer_phone_task`, `stop_phone_task`, FIFO queueing, interrupt urgency, and tool result delivery.
- Manually test Android pairing, overlay permission, accessibility execution, screenshot coordinate metadata, and confirmation overlays.
- Run a full voice demo after adapter parity: start realtime, request a phone action, interrupt it, steer it, and complete a risky-action confirmation test.

## Risks And Mitigations

- **Open Claw may not expose a steerable session API**: keep queueing in the bridge and model steering as cancellation plus a new task if needed.
- **The product could drift back into phone-only automation**: keep docs, prompts, and UI copy explicit that Android is the endpoint and phone control is optional tooling.
- **Tool schemas may not map cleanly from MCP**: keep `pc/src/mcp/tools.ts` as the canonical command list and generate or adapt schemas from that source.
- **Remote PC auth may be underspecified**: keep local prototype token support, then add per-session credentials before exposing beyond a trusted LAN.
- **Status text may leak legacy naming**: audit Android UI strings, dispatcher logs, and docs during the default flip.
- **Realtime voice can outpace task execution**: keep the existing bridge queue and tool-result correlation so OpenAI Realtime receives deterministic completion or failure output.

## Rollback Plan

- Keep the Codex app-server adapter behind `PHONE_AGENT_DISPATCHER=codex` until Open Claw parity is proven.
- Keep `PHONE_AGENT_USE_FALLBACK=1` for bridge-only demos and Android command validation.
- If Open Claw integration fails during testing, switch the dispatcher env var back to `codex` or `fallback` without changing Android builds.
