# Pi Agent Harness Integration Plan

## Execution Rules

- Work in `/Users/am.will/Applications/open-claw-agent-pi-agent` on branch `codex/pi-agent-harness`.
- Complete tasks in order.
- Before moving to the next task, update the **Implementation Log** section with a concise dated entry: task number, summary, files changed, tests run, and commit hash.
- Commit after every task. Do not batch multiple tasks into one commit unless the task explicitly says so.
- Preserve unrelated dirty files in the original checkout. Do not revert pre-existing changes.

## Tasks

1. **Plan And Dependency Surface**
   - Add `@earendil-works/pi-coding-agent` to the PC package metadata and lockfile.
   - Save this plan file.
   - Commit: `docs: add Pi agent integration plan`.

2. **Config And Public Contract**
   - Add Pi env/config fields: agent cwd, agent dir, default model, run timeout.
   - Update host config defaults/redaction, diagnostics, integration discovery, `.env.example`, setup docs, and protocol docs.
   - Commit: `docs/config: add Pi harness configuration surface`.

3. **Protocol And Harness Identity**
   - Add `pi` to harness ids, labels, model/session namespacing, default session keys, protocol validation, readiness, and recovery text.
   - Ensure `pi:<provider>/<model>` and `pi:<sessionId>` route correctly.
   - Commit: `bridge: register Pi harness identity`.

4. **Pi SDK Client**
   - Add a Pi SDK wrapper that owns auth/model/settings/session managers, runtime creation and replacement, health checks, timeouts, abort, and shutdown.
   - Use `AgentSessionRuntime` and Pi session APIs, not RPC or JSON event mode.
   - Commit: `bridge: add Pi SDK client`.

5. **Pi Chat Adapter**
   - Add the Lynk chat adapter: list models, create/list sessions, read history, send prompts, stream deltas/tool events, steer active runs, abort runs, commands, tools, usage, and workspace metadata.
   - Normalize Pi sessions by workspace path/name like Codex and OpenCode.
   - Commit: `bridge: add Pi chat adapter`.

6. **Workspace Error Flow**
   - Share host workspace validation with Pi.
   - Add `pi.workspace_not_found` so Android can reuse create-folder confirmation.
   - Forward new-chat workspace options for Codex, OpenCode, and Pi.
   - Commit: `bridge: support Pi workspaces`.

7. **Android Harness UI**
   - Add Pi harness settings, default model/workspace preferences, model grouping, labels, session routing, diagnostics, and brand presentation.
   - Reuse workspace-grouped previous chats for Pi.
   - Commit: `android: add Pi harness UI`.

8. **Pi Logo**
   - Add the provided Pi SVG as Android vector resources and wire it into Pi brand presentation.
   - Commit: `android: add Pi brand logo`.

9. **Tests**
   - Add PC fake Pi SDK/runtime coverage for config, model/session normalization, workspace paths, prompt streaming, reasoning, tools, abort, steer, and usage.
   - Extend harness router and protocol contract tests.
   - Extend Android config/model/presentation/workspace tests.
   - Commit: `test: cover Pi harness integration`.

10. **Verification**
   - Run `cd pc && npm run check && npm test`.
   - Run `cd android && ./gradlew :app:testDebugUnitTest`.
   - Run `cd android && ./gradlew :app:assembleDebug`.
   - Fix failures in follow-up commits, one logical fix per commit.
   - Commit final docs/log update: `docs: record Pi integration verification`.

## Implementation Log

- 2026-06-01 Task 0: Created worktree `/Users/am.will/Applications/open-claw-agent-pi-agent` from current `HEAD` on branch `codex/pi-agent-harness`; left the original checkout dirty files untouched. Files changed: none. Tests: not run. Commit: pending.
- 2026-06-01 Task 1a: Saved the Pi integration plan file. Files changed: `docs/pi-agent-harness-integration-plan.md`. Tests: not run. Commit: `b689c9b`.
- 2026-06-01 Task 1b: Added `@earendil-works/pi-coding-agent@^0.78.0` to PC package metadata and lockfile; npm required `--min-release-age=0` because local npm release-age filtering excluded the May 29 package. Files changed: `pc/package.json`, `pc/package-lock.json`, `docs/pi-agent-harness-integration-plan.md`. Tests: not run. Commit: `791c915`.
- 2026-06-01 Task 2: Added Pi bridge config/env fields, persistent host config defaults, integration discovery, diagnostics, setup/protocol docs, and updated PC config fixtures. Files changed: `pc/src/bridge/config.ts`, `pc/src/host/HostConfigStore.ts`, `pc/src/host/IntegrationManager.ts`, `pc/src/host/Diagnostics.ts`, `pc/.env.example`, `docs/setup.md`, `docs/protocol.md`, PC bridge tests, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check`. Commit: `e6eebec`.
- 2026-06-01 Task 3: Registered Pi as a host harness id, label, model/session namespace, default session key, protocol model prefix, readiness/recovery target, and router test target. Files changed: `pc/src/bridge/AgentHarness.ts`, `pc/src/protocol/messages.ts`, `pc/src/bridge/OpenClawChatBridge.ts`, `pc/src/protocol/contracts.test.ts`, `pc/src/bridge/harness/HarnessChatRouter.test.ts`, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check`. Commit: `33ab865`.
- 2026-06-01 Task 4: Added a Pi SDK wrapper for auth/model/session managers, runtime creation/replacement, model lookup, thinking normalization, timeouts, abort, health, and shutdown. Files changed: `pc/src/bridge/pi/PiSdkClient.ts`, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check`. Commit: `085b678`.
- 2026-06-01 Task 5: Added the Pi chat adapter and registered it with the harness router: model listing, Pi session discovery, history normalization, prompt sends, steering, abort, streamed text/reasoning/tool events, usage, and session metadata. Files changed: `pc/src/bridge/pi/PiChatClient.ts`, `pc/src/bridge/harness/HarnessChatRouter.ts`, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check`. Commit: `a352e90`.
- 2026-06-01 Task 6: Added Pi workspace validation and `pi.workspace_not_found`, forwarded workspace options through bridge/router for Pi, and let Android recognize the Pi workspace creation error. Files changed: `pc/src/bridge/chat/ChatErrors.ts`, `pc/src/bridge/pi/PiWorkspace.ts`, `pc/src/bridge/pi/PiChatClient.ts`, `pc/src/bridge/harness/HarnessChatRouter.ts`, `pc/src/bridge/OpenClawChatBridge.ts`, `android/app/src/main/java/dev/androidagent/AgentForegroundService.kt`, `docs/protocol.md`, PC tests, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check`; `cd pc && npm test` (first full run had a Hermes CLI fallback timing failure; focused rerun passed; second full run passed). Commit: `e3b1f31`.
- 2026-06-01 Task 7: Added Pi to Android harness settings, default model/workspace preferences, model grouping and normalization, workspace session routing, diagnostics, status summaries, and brand presentation with a temporary generic logo pending the dedicated Pi vector. Files changed: Android config/service/overlay/catalog/settings/presentation resources, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd android && ./gradlew :app:testDebugUnitTest`. Commit: `747bf87`.
- 2026-06-01 Task 8: Added the provided Pi SVG as Android vector and plate drawables, then wired Pi brand and diagnostics presentation to the plate asset. Files changed: `android/app/src/main/res/drawable/pi_agent_logo.xml`, `android/app/src/main/res/drawable/pi_agent_logo_plate.xml`, Android presentation files, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd android && ./gradlew :app:testDebugUnitTest`. Commit: `62ad313`.
- 2026-06-01 Task 9: Added fake Pi adapter tests for model listing, workspace creation, `pi.workspace_not_found`, streaming text/reasoning/tool/final usage, steering, and abort; added Android assertions for Pi config, model catalog, and brand presentation. Fixed existing Pi runtimes to apply updated model/thinking controls and exposed Pi minimal reasoning. Files changed: `pc/src/bridge/pi/PiChatClient.ts`, `pc/src/bridge/pi/PiChatClient.test.ts`, Android unit tests, `docs/pi-agent-harness-integration-plan.md`. Tests: `cd pc && npm run check && npm test`; `cd android && ./gradlew :app:testDebugUnitTest`. Commit: pending.

## Assumptions

- Pi is a peer host harness, not a Codex or OpenCode sub-mode.
- Use the Pi SDK/runtime as the primary integration; do not implement RPC or JSON event mode for v1.
- Brand label is `Pi`; model/session prefix is lowercase `pi:`.
- Default Pi cwd is `PI_AGENT_CWD || process.cwd()`, default agent dir is Pi SDK default unless `PI_AGENT_DIR` is set, default thinking level is `medium`, and default timeout is 600 seconds.
- Pi sessions remain stored in Pi's own JSONL session format under its agent dir; Lynk indexes and displays them but does not rewrite them except through Pi SDK session APIs.
