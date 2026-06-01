# OpenCode Harness Integration Plan

## Execution Rules

- Save this file at `docs/opencode-harness-integration-plan.md`.
- Complete tasks in order.
- Before moving to the next task, update the **Implementation Log** section with a concise dated entry: task number, summary, files changed, tests run, and commit hash.
- Commit after every task. Do not batch multiple tasks into one commit unless the task explicitly says so.
- Preserve unrelated dirty files. Do not revert pre-existing changes.

## Tasks

1. **Document And Config Surface**
   - Add OpenCode env/config fields: server URL, serve command, cwd, username, password, default agent, run timeout.
   - Update `.env.example`, host config defaults/redaction, setup docs, and protocol docs.
   - Commit: `docs/config: add OpenCode harness configuration plan surface`.

2. **Protocol And Harness Identity**
   - Add `opencode` to harness ids, labels, model/session namespacing, default session keys, and protocol validation.
   - Ensure `opencode:<provider>/<model>` and `opencode:<sessionId>` route correctly.
   - Commit: `bridge: register OpenCode harness identity`.

3. **OpenCode Server Client**
   - Implement an OpenCode HTTP/SDK client with health, auth, managed server startup, timeout, SSE event subscription, and shutdown.
   - Use OpenCode's existing `directory` query/header for workspace scoping.
   - Commit: `bridge: add OpenCode server client`.

4. **OpenCode Chat Adapter**
   - Add the Lynk chat adapter: list models, create/list sessions, read history, send async prompts, stream deltas/tool events, abort runs, commands, tools, and usage.
   - Normalize OpenCode sessions by workspace path/name like Codex.
   - Commit: `bridge: add OpenCode chat adapter`.

5. **Workspace Error Flow**
   - Generalize Codex workspace validation for Codex and OpenCode.
   - Add `opencode.workspace_not_found` so Android can reuse create-folder confirmation.
   - Commit: `bridge: share workspace validation for host harnesses`.

6. **Android Harness UI**
   - Add OpenCode harness enable toggle, default model setting, default workspace setting, model grouping, labels, session routing, and brand presentation.
   - Reuse workspace-grouped previous chats for OpenCode.
   - Commit: `android: add OpenCode harness UI`.

7. **Permissions And Tool Events**
   - Surface OpenCode permission requests with Allow Once, Always Allow, and Reject.
   - Map OpenCode tool/patch/status/error events into Lynk `chat.tool_event`, `chat.delta`, `chat.final`, and `chat.error`.
   - Commit: `bridge android: handle OpenCode permissions and events`.

8. **Tests**
   - Add PC fake OpenCode server/SSE tests for auth, workspace directory, model/session normalization, prompt streaming, abort, permissions, commands, and tools.
   - Extend harness router and protocol contract tests.
   - Extend Android config/model/presentation/workspace tests.
   - Commit: `test: cover OpenCode harness integration`.

9. **Verification**
   - Run `cd pc && npm run check && npm test`.
   - Run `cd android && ./gradlew :app:testDebugUnitTest`.
   - Run `cd android && ./gradlew :app:assembleDebug` if Android sources/resources changed.
   - Fix failures in follow-up commits, one logical fix per commit.
   - Commit final docs/log update: `docs: record OpenCode integration verification`.

## Implementation Log

- 2026-06-01 Task 1: Added the OpenCode plan file, host/env config fields, redaction/diagnostics/discovery hooks, setup/protocol docs, and SDK dependency metadata. Files changed: `docs/opencode-harness-integration-plan.md`, `docs/protocol.md`, `docs/setup.md`, `pc/.env.example`, `pc/package.json`, `pc/package-lock.json`, `pc/src/bridge/config.ts`, `pc/src/host/Diagnostics.ts`, `pc/src/host/HostConfigStore.ts`, `pc/src/host/IntegrationManager.ts`. Tests: `cd pc && npm run check`. Commit: `9a492d7`.
- 2026-06-01 Task 2: Registered OpenCode as a host harness id, model/session namespace, protocol model prefix, and readiness/recovery target. Files changed: `docs/opencode-harness-integration-plan.md`, `docs/protocol.md`, `pc/src/bridge/AgentHarness.ts`, `pc/src/bridge/OpenClawChatBridge.ts`, `pc/src/protocol/messages.ts`. Tests: `cd pc && npm run check`. Commit: `10b402d`.
- 2026-06-01 Task 3: Added the OpenCode SDK-backed server client with managed server startup, Basic Auth, health probing, request timeout, session/model/command/tool methods, SSE subscription access, and permission replies. Files changed: `docs/opencode-harness-integration-plan.md`, `pc/src/bridge/opencode/OpenCodeServerClient.ts`. Tests: `cd pc && npm run check`. Commit: `4a7743d`.
- 2026-06-01 Task 4: Added the OpenCode chat adapter for model listing, session create/list/history, async prompts, polled deltas/finals, abort, commands, tools, usage, workspace metadata, and harness router wiring. Files changed: `docs/opencode-harness-integration-plan.md`, `pc/src/bridge/harness/HarnessChatRouter.ts`, `pc/src/bridge/opencode/OpenCodeChatClient.ts`, `pc/src/bridge/opencode/OpenCodeServerClient.ts`. Tests: `cd pc && npm run check`. Commit: `9e542d2`.
- 2026-06-01 Task 5: Extracted shared host workspace validation, preserved Codex errors, added `opencode.workspace_not_found`, and allowed OpenCode new chats to use workspace confirmation. Files changed: `docs/opencode-harness-integration-plan.md`, `pc/src/bridge/OpenClawChatBridge.ts`, `pc/src/bridge/chat/ChatErrors.ts`, `pc/src/bridge/codex/CodexWorkspace.ts`, `pc/src/bridge/opencode/OpenCodeChatClient.ts`, `pc/src/bridge/opencode/OpenCodeWorkspace.ts`, `pc/src/bridge/workspace/HostWorkspace.ts`. Tests: `cd pc && npm run check`. Commit: `6315a5a`.
- 2026-06-01 Task 6: Added Android OpenCode harness settings, default model/workspace preferences, model grouping, diagnostics entry, brand presentation, workspace-scoped new chats, and workspace-grouped previous sessions. Files changed: `android/app/src/main/java/dev/androidagent/AgentConfig.kt`, `android/app/src/main/java/dev/androidagent/AgentForegroundService.kt`, `android/app/src/main/java/dev/androidagent/OverlayController.kt`, `android/app/src/main/java/dev/androidagent/chat/ChatModelCatalog.kt`, `android/app/src/main/java/dev/androidagent/overlay/ChatPresentationHelpers.kt`, `android/app/src/main/java/dev/androidagent/settings/DiagnosticsBackendTester.kt`, `android/app/src/main/java/dev/androidagent/settings/SettingsStatusProvider.kt`, `android/app/src/main/java/dev/androidagent/settings/screens/ActivityDiagnosticsScreen.kt`, `android/app/src/main/java/dev/androidagent/settings/screens/RuntimeSettingsScreen.kt`, `android/app/src/main/res/values/ids.xml`. Tests: `cd android && ./gradlew :app:testDebugUnitTest`. Commit: `e63d442`.
- 2026-06-01 Task 7: Mapped OpenCode SSE text, reasoning, tool, patch, status, usage, and error events into Lynk chat events; added OpenCode permission actions with Allow Once, Always Allow, and Reject replies through Android tool rows. Files changed: `android/app/src/main/java/dev/androidagent/OverlayController.kt`, `android/app/src/main/java/dev/androidagent/chat/ChatModels.kt`, `android/app/src/main/java/dev/androidagent/overlay/ChatTimelineBinder.kt`, `docs/opencode-harness-integration-plan.md`, `pc/src/bridge/OpenClawChatBridge.ts`, `pc/src/bridge/OpenClawChatTypes.ts`, `pc/src/bridge/OpenClawControlCommands.ts`, `pc/src/bridge/OpenClawGatewayNormalizers.ts`, `pc/src/bridge/chat/ChatTransportTypes.ts`, `pc/src/bridge/harness/HarnessChatAdapter.ts`, `pc/src/bridge/harness/HarnessChatRouter.ts`, `pc/src/bridge/opencode/OpenCodeChatClient.ts`, `pc/src/bridge/opencode/OpenCodeServerClient.ts`, `pc/src/protocol/messages.ts`. Tests: `cd pc && npm run check`; `cd android && ./gradlew :app:testDebugUnitTest`; live OpenCode smoke with `opencode/mimo-v2.5-free` and `openai/gpt-5.5`. Commit: `23ff846`.

## Assumptions

- Use the existing `@opencode-ai/sdk` dependency already present in the dirty package files.
- OpenCode is a peer host harness, not a Codex sub-mode.
- Use OpenCode's existing server functionality only; do not add custom OpenCode storage behavior.
- Keep OpenCode bound to localhost/private network by default.
