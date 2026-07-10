# Devin ACP Harness Implementation Plan

**Plan URI:** `file:///Users/am.will/Applications/open-claw-agent/devin-implement.md`
**Repository:** `/Users/am.will/Applications/open-claw-agent`
**Generated:** 2026-07-10
**Execution model:** dependency-ordered waves with SWE 1.7 Lightning subagents, test-driven development, orchestrator review, and focused commits

## 1. Objective

Add Devin CLI as a first-class, workspace-aware Lynk host harness using the structured ACP stdio server started by:

```text
devin acp
```

The bridge is the ACP client. The implementation must not scrape Devin's terminal UI and must not use `devin -p` as the primary integration.

The finished integration must support:

- Harness ID `devin`.
- Session keys `devin:<session-id>`.
- Model selections `devin:<model-id>`.
- Host workspace selection, missing-folder confirmation, and confirmed folder creation.
- Multiple independent sessions in different host folders.
- Persistent session-to-workspace associations across bridge restarts.
- ACP-authoritative session discovery and history replay.
- Streamed assistant text, thought/status information, plans, tool activity, usage, and final responses through Lynk's existing `chat.*` contract.
- Active-turn cancellation.
- ACP permission request presentation and exact option replies through the existing Android tool-action UI.
- Capability-gated behavior, safe default permissions, clear health/readiness diagnostics, and no network exposure of the ACP subprocess or credentials.

## 2. Confirmed Runtime Capability Baseline

The installed authenticated Devin CLI has been tested directly:

- Devin CLI version: `3000.1.27`.
- `agentCapabilities.loadSession: true`.
- `agentCapabilities.sessionCapabilities.list: {}`.
- `agentCapabilities.sessionCapabilities.additionalDirectories: {}`.
- `session/resume` is not advertised and is not required.
- A live ACP test created session `zinc-potato` in `/tmp/lynk-devin-acp-resume-probe`, completed a turn, terminated the ACP process, started a fresh process, found the session through `session/list`, loaded it through `session/load`, replayed history, and correctly recalled a marker from the previous process.

Implementation consequence:

- Use `session/list` as the authoritative session catalog.
- Use `session/load` for restart restoration and complete history replay.
- Do not call or claim `session/resume` support.
- Preserve local Lynk metadata as a durable association/cache, not as a fabricated replacement for ACP history.
- Add the exact process-restart/list/load/history/context-recovery flow as an opt-in authenticated integration acceptance test.

## 3. Repository Constraints and Current Worktree

- Follow root `AGENTS.md` completely.
- Product name is Lynk.
- Preserve OpenClaw, Hermes, Codex, OpenCode, Pi, Local LiteRT-LM, realtime voice, and phone-control behavior.
- Canonical TypeScript protocol owner: `pc/src/protocol/messages.ts`.
- Canonical harness registry/router owners: `pc/src/bridge/AgentHarness.ts` and `pc/src/bridge/harness/`.
- Canonical host workspace helper: `pc/src/bridge/workspace/HostWorkspace.ts`.
- Keep ACP-specific code under `pc/src/bridge/devin/`.
- Android must reuse/generalize the existing dynamic workspace UI, not add a Devin-only screen.
- Do not modify or stage the pre-existing untracked `.devin/` directory. It contains the repository's SWE 1.7 Lightning agent definition.
- At plan creation, branch `main` is ahead of `origin/main` by five commits and the only untracked path is `.devin/`. Re-check before every commit.
- Do not push.

## 4. Execution Strategy

### 4.1 Orchestrator responsibilities

The main agent is responsible for:

1. Maintaining this plan and implementation log.
2. Creating each wave's detailed subagent prompts.
3. Ensuring parallel agents have disjoint file ownership.
4. Reviewing every changed file and every subagent result.
5. Rejecting or correcting incomplete, unsafe, over-broad, or non-idiomatic work.
6. Running focused and full verification.
7. Reading staged diffs explicitly and running `git diff --check` before every commit.
8. Creating the requested focused commit series.
9. Preserving all pre-existing user changes.
10. Performing final real bridge/Android/device checks when automated checks pass.

Subagents must not commit, push, rewrite history, delete user files, modify `.devin/`, or edit files outside their assigned ownership.

### 4.2 Parallel scheduling algorithm

For each wave:

1. Identify tasks whose prerequisites are complete.
2. Partition ready tasks by non-overlapping file ownership.
3. Launch one SWE 1.7 Lightning subagent per eligible task in parallel.
4. Keep dependent or shared-file tasks queued for the next loop.
5. Wait for all agents in the wave.
6. Review each result independently.
7. Run focused tests and inspect the full diff.
8. Repair issues directly or resume the responsible subagent with exact review feedback.
9. Update this plan's implementation log.
10. Commit only the coherent implementation slice required by the commit sequence.
11. Continue to the next dependency wave.

No two concurrent agents may edit the same source or test file. Concurrent agents must return a structured log to the orchestrator rather than append directly to this shared plan, avoiding lost updates. The orchestrator appends reviewed logs here.

### 4.3 Required subagent prompt content

Every subagent prompt must include:

- Repository absolute path.
- Plan URI and instruction to read this complete plan first.
- Root `AGENTS.md` requirement.
- Exact goal and acceptance criteria for that task.
- Confirmed Devin capability baseline.
- Explicit owned files and forbidden files.
- Existing implementation patterns/files to read completely.
- TDD instructions: add a failing focused test first when practical, then implement, then rerun.
- Required focused commands.
- Security constraints: no secret logging, no unrestricted permission mode, no terminal scraping.
- Compatibility constraints.
- Instruction not to commit or push.
- Required final report: files changed, tests run with exact results, design decisions, capability assumptions, remaining concerns, and suggested implementation-log entry.

### 4.4 Review standard for every subagent

The orchestrator must verify:

- Logic resides in its canonical owning layer.
- ACP behavior is based on official SDK/schema types or the tested runtime response.
- Capability checks gate all optional ACP methods.
- Types are explicit and exhaustive; no broad `any` or speculative casts.
- Process exit, timeout, malformed traffic, cancellation, pending permission, and shutdown states are coherent.
- Session IDs, workspaces, run IDs, and events cannot leak across sessions.
- No secrets, full environment dumps, or prompt contents are logged.
- Existing harness behavior remains unchanged.
- Tests protect boundaries, not merely implementation details.
- Files remain below the repository's decomposition threshold.
- No unrelated changes are included.

## 5. Dependency and Wave Graph

```text
Wave 0: Plan + worktree baseline
  |
  +--> Wave 1A: PC identity/config/discovery/protocol foundation
  |      |
  |      +--> Wave 2: ACP transport
  |              |
  |              +--> Wave 3: workspace/session adapter
  |                       |
  |                       +--> Wave 4: streaming/permissions/cancellation
  |
  +--> Wave 1B: Android generic workspace refactor + Devin controls
                         |
                         +--> Wave 5: Android integration review/finalization

Wave 4 + Wave 5
  |
  +--> Wave 6A: PC cross-boundary and authenticated integration tests
  +--> Wave 6B: Android regression completion
          |
          +--> Wave 7: documentation
                  |
                  +--> Wave 8: full verification + real device smoke + final review
```

Wave 1A and Wave 1B are safe to run concurrently because they own disjoint `pc/` and `android/` files. Wave 6A and Wave 6B may run concurrently if their file ownership remains disjoint. All ACP transport/session/event waves are sequential because each depends on the API and lifecycle decisions of the prior wave.

## 6. Detailed Implementation Plan

## Wave 0 — Save plan and baseline

**Owner:** orchestrator
**Parallel:** no

### Tasks

- Verify repository root and current branch/status/diff/log.
- Record pre-existing untracked/modified paths.
- Save this plan.
- Never stage `.devin/`.

### Exit gate

- Plan exists at repository root.
- Worktree baseline is recorded.
- Task tracker is initialized.

---

## Wave 1A — PC harness identity, configuration, discovery, diagnostics, and protocol

**Subagent:** SWE 1.7 Lightning
**Parallel with:** Wave 1B
**Commit:** `feat(devin): add harness identity and configuration`

### Owned files

- `pc/src/bridge/AgentHarness.ts`
- `pc/src/bridge/config.ts`
- `pc/src/bridge/chat/ChatErrors.ts`
- `pc/src/bridge/harness/HarnessChatRouter.ts` only for registry factory preparation if a transport stub is not needed; avoid adding an unusable adapter in this wave
- `pc/src/host/CommandDiscovery.ts`
- `pc/src/host/HostConfigStore.ts`
- `pc/src/host/IntegrationManager.ts`
- `pc/src/host/Diagnostics.ts`
- `pc/src/protocol/messages.ts`
- Adjacent focused tests for these files
- `pc/.env.example` if it documents all host harness variables

### Required design

- Add harness ID `devin`, label `Devin`, workspace support, and default `devin:<sanitized-device>` key.
- Add config:
  - `DEVIN_ACP_COMMAND`, default `devin acp`.
  - `DEVIN_AGENT_CWD`, default package root.
  - `DEVIN_RUN_TIMEOUT_SECONDS`, safe positive default.
  - `devinConfigured` based on command availability so the adapter can exist; runtime auth/model readiness remains authoritative.
- Extend host config with matching persisted values while retaining schema compatibility.
- Extend command discovery with the official common `~/.local/bin/devin` fallback.
- Host refresh must distinguish:
  - not installed;
  - installed but authentication check failed;
  - installed and authenticated.
- Authentication detection may execute `devin auth status` with a bounded timeout. It must use exit status, discard/sanitize output, never log identity/token details, and be dependency-injectable for tests.
- Use the refreshed discovered absolute executable when a login service has a narrower PATH.
- Add `devin.workspace_not_found`.
- Accept `devin:<non-empty-model-id>` wherever selected host model IDs are validated.
- Add harness/readiness/model namespacing tests and update existing exhaustive harness expectations.

### TDD tests

- Harness registry order, label, workspace support, enabled state, key/model namespacing.
- Command discovery on PATH and `~/.local/bin/devin` fallback.
- Auth status success/failure/timeout without output leakage.
- Host config defaults and package-root cwd.
- Diagnostics expose only safe Devin status fields.
- Protocol accepts `devin:default` and rejects malformed Devin selections.
- Router forwards workspace options for Devin and keeps non-workspace behavior unchanged.

### Focused verification

```bash
cd pc
npm run check
node --import tsx --test \
  src/bridge/harness/HarnessChatRouter.test.ts \
  src/bridge/config.test.ts \
  src/host/CommandDiscovery.test.ts \
  src/host/HostConfigStore.test.ts \
  src/protocol/contracts.test.ts
```

---

## Wave 1B — Android generic workspace architecture and Devin harness controls

**Subagent:** SWE 1.7 Lightning
**Parallel with:** Wave 1A
**Commit:** held until `feat(android): add Devin harness and workspace controls`

### Owned files

- `android/app/src/main/java/dev/androidagent/AgentConfig.kt`
- `android/app/src/main/java/dev/androidagent/CodexWorkspacePrefs.kt` and renamed replacement
- `android/app/src/main/java/dev/androidagent/chat/ChatModelCatalog.kt`
- `android/app/src/main/java/dev/androidagent/overlay/CodexSessionPickerSections.kt` and renamed replacement
- `android/app/src/main/java/dev/androidagent/settings/screens/RuntimeSettingsScreen.kt`
- `android/app/src/main/java/dev/androidagent/OverlayController.kt`
- `android/app/src/main/java/dev/androidagent/AgentForegroundService.kt`
- Relevant resource IDs only when required
- Adjacent Android tests

### Required design

- Add `HARNESS_DEVIN`, label/order metadata, enabled preference, default-model preference, and workspace preference.
- Add Devin model/session prefix parsing and `devin:` normalization.
- Replace Codex-named generic workspace utilities with `HostWorkspacePaths`.
- Replace Codex-named generic session grouping with `WorkspaceSessionPickerSections`.
- Replace per-harness workspace callbacks in `OverlayController` with generic `(harnessId) -> path` and `(harnessId, path) -> Unit` callbacks.
- Prefer a generic per-harness workspace map in `AgentConfig`; migrate existing Codex/OpenCode/Pi SharedPreferences keys without data loss and persist Devin's workspace.
- Keep the existing dynamically generated workspace settings cards.
- Rename workspace error helpers to generic names and include `devin.workspace_not_found`.
- Use harness-specific confirmation copy derived from selected harness metadata.
- Reuse existing `ChatToolAction` rendering/reply path; do not add a Devin-specific permission dialog.
- Preserve Codex/OpenCode/Pi behavior with explicit regression tests.

### TDD tests

- Devin host harness descriptor and workspace support.
- Devin model namespacing and session-key harness detection.
- Per-harness workspace persistence/migration.
- Session grouping by workspace for Codex, OpenCode, Pi, and Devin.
- Generic missing-workspace detection and Devin-specific confirmation copy/payload.
- Tool actions preserve opaque command arguments.

### Focused verification

```bash
cd android
./gradlew :app:testDebugUnitTest --tests 'dev.androidagent.AgentConfigModeTest' \
  --tests 'dev.androidagent.NewChatSessionCoordinatorTest' \
  --tests 'dev.androidagent.chat.ChatStateReducerTest' \
  --tests 'dev.androidagent.overlay.ChatPresentationHelpersTest'
```

### Holding rule

Do not commit this wave immediately. The orchestrator reviews it and keeps the disjoint Android changes unstaged while PC commits 1–4 are produced. Rebase/fix against later protocol decisions in Wave 5 before the Android commit.

---

## Wave 2 — ACP process and typed client transport

**Subagent:** SWE 1.7 Lightning
**Depends on:** Wave 1A
**Commit:** `feat(devin): add ACP client transport`

### Owned files

- `pc/package.json`
- `pc/package-lock.json`
- New files under `pc/src/bridge/devin/` limited to transport/capability/process concerns
- Adjacent transport tests and fake ACP process/stream fixtures
- Minimal router factory wiring only if the transport can expose a coherent health-only adapter boundary

### Required design

- Install exact `@agentclientprotocol/sdk@1.1.0`. This version is older than seven days and includes typed `session/list`, `session/load`, config options, request IDs, and current client builders.
- Use non-deprecated `client(...).connect(...)` and `ndJsonStream`; do not use deprecated `ClientSideConnection`.
- Spawn default command `devin acp` without a shell.
- Own one long-lived subprocess and client connection per Devin harness adapter.
- Implement initialization exactly once per connection with:
  - ACP protocol version from SDK;
  - client info identifying Lynk bridge;
  - no filesystem capability;
  - no terminal capability;
  - no boolean config, elicitation, or Cognition-specific capability claims.
- Preserve a typed capability snapshot including `loadSession`, `session.list`, `session.additionalDirectories`, prompt capabilities, auth methods, and agent info.
- Optional methods must be called only when advertised.
- Implement a start mutex/state machine so concurrent callers share startup.
- Implement bounded startup/request timeouts.
- Treat stdout exclusively as ACP NDJSON.
- Keep a bounded stderr ring for diagnostics, sanitize secrets/token-like values, and never include prompt bodies or environment dumps.
- On malformed messages, protocol mismatch, process error, or unexpected exit:
  - classify the failure;
  - reject pending work once;
  - close stream resources;
  - notify lifecycle listeners;
  - allow a later lazy restart.
- On bridge close, terminate process and settle all pending work deterministically.

### TDD tests

- Successful initialize and exact client capability payload.
- Captured tested Devin capability baseline.
- Shared concurrent startup.
- Protocol mismatch.
- Malformed NDJSON/schema message.
- Startup timeout.
- Authentication-required error classification.
- Unexpected process exit rejects requests and permits restart.
- Close terminates subprocess and pending requests.
- Sanitized bounded stderr diagnostics.

### Focused verification

```bash
cd pc
npm run check
node --import tsx --test 'src/bridge/devin/**/*test.ts'
npm run build
```

---

## Wave 3 — Workspace-scoped sessions, catalog, history, models, and persistence

**Subagent:** SWE 1.7 Lightning
**Depends on:** Wave 2
**Commit:** `feat(devin): add workspace-scoped session adapter`

### Owned files

- New session/adapter/workspace/catalog files under `pc/src/bridge/devin/`
- `pc/src/bridge/harness/HarnessChatRouter.ts`
- Minimal adapter interface changes required for coherent capability behavior
- Focused Devin adapter/session tests

### Required design

- Create focused modules rather than a monolith:
  - `DevinChatClient.ts` for harness interface orchestration.
  - `DevinSessionCatalog.ts` for list/merge/pagination.
  - `DevinWorkspace.ts` for `prepareHostWorkspace()` configuration.
  - A small session state/config normalizer only if needed.
- Reuse `InMemoryHarnessSessionStore`; do not duplicate a generic session store.
- Store Devin metadata at a stable path adjacent to persistent host config rather than process cwd.
- Persist empty Lynk-created sessions because workspace associations must survive immediately.
- Session creation:
  1. Prepare/validate/create workspace.
  2. Call ACP `session/new` with absolute `cwd`, empty MCP server list, and no additional directories unless the user-facing contract later exposes them.
  3. Use returned ID to create exact key `devin:<session-id>`.
  4. Parse returned config options/modes/commands.
  5. If a requested model option exists, call `session/set_config_option` using the exact config ID and value.
  6. Apply thought/reasoning only when advertised by returned config options.
  7. Persist session ID, cwd, display metadata, selected model/reasoning, and config IDs.
- Session listing:
  - Require advertised `session.list` for remote enumeration.
  - Call unfiltered `session/list` and follow opaque cursors.
  - Map `sessionId`, `cwd`, `title`, `updatedAt` into Lynk summaries.
  - Merge by session ID with ACP title/cwd/time authoritative; local model/usage/display data are fallback.
  - Grouping is achieved through existing `workspacePath`/`workspaceName` fields.
- History/restoration:
  - Use `session/load` after a fresh process or when a selected session is not attached.
  - Pass the exact persisted/listed cwd.
  - Collect replayed `user_message_chunk` and `agent_message_chunk` notifications until load returns.
  - Keep replay separate from live turn events.
  - Replace cached history with replayed ACP history when available.
- Model discovery:
  - ACP exposes models as session config options, not a global list method.
  - Create an empty probe session in configured default cwd and inspect config options.
  - Close it if `session.close` is advertised; empty Devin sessions are not persisted before a prompt.
  - Cache only currently advertised choices.
  - If no model config option is exposed, publish one selector `devin:default` labeled `Devin default` and do not claim a concrete model.
- Commands come from advertised available-command updates; effective tools remain empty until ACP reports actual tool activity because ACP has no static tool-list contract.
- Harness attachments remain disabled unless the negotiated prompt capability and adapter boundary can support them honestly.
- Use `HarnessRunLifecycle` with `per-session` concurrency.

### TDD tests

- Existing workspace.
- Missing workspace returns `devin.workspace_not_found`.
- Confirmed recursive workspace creation.
- File path rejected as workspace.
- `~` and `~/...` expansion.
- Exact key/model namespacing.
- Two sessions in different folders retain separate cwd/session IDs.
- Pagination and remote/local metadata merge.
- Stable metadata survives store reconstruction.
- `session/load` uses original cwd and replaces local history with replay.
- Model config discovery/set behavior and honest default fallback.
- Optional method gating.

### Focused verification

```bash
cd pc
npm run check
node --import tsx --test \
  'src/bridge/devin/**/*test.ts' \
  src/bridge/harness/HarnessChatRouter.test.ts
npm run build
```

---

## Wave 4 — Streaming, tool/status normalization, permissions, cancellation, and final lifecycle

**Subagent:** SWE 1.7 Lightning
**Depends on:** Wave 3
**Commit:** `feat(devin): route streamed updates and permissions`

### Owned files

- New event/run/permission modules under `pc/src/bridge/devin/`
- `pc/src/bridge/harness/HarnessControlActions.ts`
- `pc/src/bridge/chat/ChatTransportTypes.ts`
- `pc/src/bridge/harness/HarnessChatAdapter.ts` only if required by the explicit permission decision type
- `pc/src/bridge/OpenClawControlCommands.ts` only for generic routing changes
- Focused event, permission, cancellation, lifecycle tests

### Required design

- Create `DevinAcpEventNormalizer.ts` with exhaustive handling of stable ACP update variants.
- Create a run driver around `HarnessRunLifecycle`, not another ad hoc active-run map.
- Normalize:
  - `agent_message_chunk` text into append/replace-safe `chat.delta` accumulation.
  - `agent_thought_chunk` text into `chat.reasoning_delta`.
  - `tool_call` and `tool_call_update` into stable `chat.tool_event` IDs and status transitions.
  - Tool content text/diffs/locations into safe summary/output fields; do not forward arbitrary `_meta` wholesale.
  - `plan` into one informational plan tool event whose output is replaced by each complete plan snapshot.
  - `usage_update` into Lynk usage fields.
  - `session_info_update` into local title/update metadata.
  - `config_option_update`, mode/model/thought changes, and available commands into session caches.
  - Prompt response stop reason plus accumulated assistant text into final/error/cancel behavior.
- Do not emit history replay chunks as new live deltas.
- Permission broker:
  - The SDK request handler returns a pending Promise.
  - Generate a local permission ID and retain session ID, run ID, tool call, exact ACP options, and resolver.
  - Emit one blocked `chat.tool_event` with an action for every ACP option.
  - Action args include local permission ID and exact opaque `optionId`.
  - Generalize harness permission decisions as a discriminated union, preserving OpenCode's `once|always|reject` behavior and supporting ACP opaque selections.
  - Android response selects only an option originally offered for that permission.
  - Duplicate/stale/foreign-session replies are rejected safely.
- Cancellation:
  - Send ACP `session/cancel` for the exact session.
  - Immediately resolve all pending permissions for that run/session as `{ outcome: 'cancelled' }`.
  - Preemptively mark unfinished tools cancelled/failed in Lynk state while still accepting final ACP updates until prompt returns.
  - Make cancellation idempotent and race-safe.
- Unexpected process exit:
  - Error all active runs once.
  - Cancel permission promises.
  - Clear lifecycle entries.
  - Keep completed persisted sessions loadable after lazy restart.
- Safe permission policy:
  - Default Devin mode remains normal.
  - Never set bypass/unrestricted mode automatically.
  - If a mode change is surfaced as an ACP permission option, require explicit Android selection.

### TDD tests

- Text chunks aggregate and final text matches.
- Thought chunks are not mixed into final text.
- Plan replacement semantics.
- Tool status/content transitions.
- Usage normalization.
- Session metadata/config updates.
- Exact permission option labels/IDs and response correlation.
- `reject_always` and arbitrary option IDs are not collapsed.
- Invalid/stale/cross-session permission replies.
- Stop sends cancel and resolves pending permission as cancelled.
- Cancel/final race emits one terminal state.
- Unexpected process exit errors every affected run once.
- Two concurrent sessions do not mix updates.

### Focused verification

```bash
cd pc
npm run check
node --import tsx --test \
  'src/bridge/devin/**/*test.ts' \
  src/bridge/harness/HarnessRunLifecycle.test.ts
npm test
npm run build
```

---

## Wave 5 — Android integration review and finalization

**Subagent:** SWE 1.7 Lightning reviewer/fixer
**Depends on:** Wave 1B and stable Wave 4 wire behavior
**Commit:** `feat(android): add Devin harness and workspace controls`

### Owned files

- Android files changed in Wave 1B only
- Android tests for these areas
- No PC files

### Tasks

- Read the actual PC wire behavior produced by Waves 1–4.
- Review all Wave 1B changes against it.
- Fix model/session namespacing, workspace error handling, permission action args, and confirmation copy mismatches.
- Confirm SharedPreferences migration preserves existing Codex/OpenCode/Pi paths and defaults.
- Confirm dynamic workspace settings do not require another Devin-only screen.
- Confirm session groups are scoped by active harness and grouped by folder.
- Confirm unavailable Devin models do not appear in the picker.
- Add/fix regression tests.

### Verification

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

---

## Wave 6A — PC cross-boundary and real authenticated acceptance tests

**Subagent:** SWE 1.7 Lightning
**Parallel with:** Wave 6B
**Depends on:** Waves 4 and 5 wire contract
**Commit:** `test(devin): cover ACP lifecycle and workspace behavior`

### Owned files

- New/adjacent PC Devin integration tests
- Existing PC bridge/router/protocol tests only where necessary
- Optional test script entry in `pc/package.json` if not touched concurrently
- No Android files

### Required tests

- Command discovery and enabled/disabled state.
- Initialization/capability negotiation.
- Existing/missing/created/`~` workspaces.
- Namespacing.
- Concurrent sessions in separate folders.
- Persistence and reconstruction.
- Text/thought/tool/status/final normalization.
- Permission request/reply.
- Cancellation and process termination.
- Unexpected process exit.
- Authentication/readiness errors with no secret leakage.
- Stateful fake ACP process restart/list/load/history/context test.
- Opt-in real authenticated test reproducing the confirmed `zinc-potato` behavior with a new unique marker and temporary workspace.

### Live-test rules

- Must be opt-in via an explicit environment flag or dedicated npm script.
- Must print no credentials and no full environment.
- May print session ID, temporary workspace, capability booleans, and pass/fail stage.
- Must not report skipped/unexecuted work as passing.
- Must use `session/list` and `session/load`, not terminal scraping or CLI session commands.
- If it creates only temporary local files, clean those files after the test; do not delete remote/session data unless the test created it and deletion support is explicitly advertised and safe.

---

## Wave 6B — Android regression completion

**Subagent:** SWE 1.7 Lightning
**Parallel with:** Wave 6A
**Depends on:** Wave 5
**Commit:** included in `test(devin): cover ACP lifecycle and workspace behavior` only if test-only; otherwise amend Android commit before proceeding

### Owned files

- Android test files only
- No PC files

### Required tests

- Devin model parsing/harness selection.
- Per-harness default workspace persistence.
- Session grouping by folder.
- Devin-specific folder creation confirmation.
- Permission action preservation.
- Codex/OpenCode/Pi workspace behavior regression.

---

## Wave 7 — Documentation

**Subagent:** SWE 1.7 Lightning
**Depends on:** stable implementation and test results
**Commit:** `docs(devin): document setup, safety, and limitations`

### Owned files

- `docs/setup.md`
- `docs/protocol.md`
- `docs/safety.md`
- `docs/limitations.md`
- `docs/host-installer.md`
- `README.md` only if its harness list/setup materially requires synchronization
- `pc/.env.example` only if not already updated in Wave 1A

### Required documentation

- Install and authenticate Devin CLI on the host.
- Default/configurable ACP command and cwd.
- `host:refresh`, readiness, and diagnostics behavior.
- `devin:` model/session namespacing.
- Workspace creation and grouping.
- ACP list/load persistence model.
- Stream/update/tool/usage mapping.
- Permission UI and safe normal mode; no bypass by default.
- Only the authenticated Lynk bridge is phone-facing.
- Tested capabilities for Devin CLI 3000.1.27.
- Explicitly state `session/resume` is not advertised and is not used.
- Exact limitations discovered in real testing; no fabricated feature parity.
- Real authenticated smoke-test command and observed result.

---

## Wave 8 — Full verification, real bridge/device acceptance, and final review

**Owner:** orchestrator
**Parallel:** independent read-only checks may run concurrently; fixes are sequential

### PC automated checks

```bash
cd /Users/am.will/Applications/open-claw-agent/pc
npm run check
npm test
npm run build
```

### Authenticated Devin acceptance check

Run the dedicated opt-in test and record the exact command/output summary. It must verify fresh process list/load/history/context recovery.

### Android automated checks

```bash
cd /Users/am.will/Applications/open-claw-agent/android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

### Host bridge checks

- Run host integration refresh and verify installed/authenticated Devin is ready.
- Run diagnostics and verify missing/auth/startup states are understandable and redacted.
- Start/restart the bridge and verify `/health` plus authenticated harness readiness.
- Verify no ACP listener/network port is opened.

### Android/device checks

Use USB/ADB and the existing Tailscale bridge only after automated checks pass:

1. Install/update the debug APK if needed.
2. Preserve/re-check overlay and accessibility permissions.
3. Verify Devin appears only when the host exposes a live Devin model selector.
4. Select Devin and an existing workspace.
5. Create a session in workspace A and a separate session in workspace B.
6. Send prompts to both and verify no state/cwd leakage.
7. Verify streamed assistant text, thought/status, tool events, and final response.
8. Trigger a safe permission request and test allow and reject paths.
9. Stop an active turn and verify cancellation.
10. Restart the bridge, list/select the prior session, load history, and verify retained context.
11. Trigger a missing folder, confirm Devin-specific copy, create it, and verify session cwd.
12. Re-check Codex, OpenCode, and Pi workspace controls/session grouping.

Do not automate irreversible or sensitive phone actions. Any risky action requires explicit confirmation.

### Final commit/diff review

- Confirm requested commit sequence exists and each commit is coherent.
- Review all commits, not only the final diff.
- Run `git diff --check` before every commit and once at the end.
- Verify no secrets, local tokens, LAN addresses, device IDs, generated build artifacts, `.devin/`, or machine-specific config are staged.
- Verify final worktree contains no unintended changes.
- Do not push.

## 7. Commit Sequence and Gates

1. `feat(devin): add harness identity and configuration`
2. `feat(devin): add ACP client transport`
3. `feat(devin): add workspace-scoped session adapter`
4. `feat(devin): route streamed updates and permissions`
5. `feat(android): add Devin harness and workspace controls`
6. `test(devin): cover ACP lifecycle and workspace behavior`
7. `docs(devin): document setup, safety, and limitations`

Every implementation commit must contain its focused tests. Commit 6 adds cross-boundary, restart, regression, and opt-in real integration coverage; it must not be the first point at which core behavior is tested.

Before each commit:

```bash
git status --short
git diff --check
git diff -- <owned files>
git diff --cached --check
git diff --cached
```

Commit format:

```text
<requested subject>

Generated with [Devin](https://devin.ai)

Co-Authored-By: Devin <158243242+devin-ai-integration[bot]@users.noreply.github.com>
```

## 8. Acceptance Checklist

- [ ] Refresh detects installed and authenticated Devin CLI.
- [ ] Devin appears in Android only when runtime model availability is established.
- [ ] User can select Devin, choose/create a folder, and create a session.
- [ ] Session stays bound to its folder for every turn and load.
- [ ] Multiple sessions in different folders have no state leakage.
- [ ] Text, thought/status, plans, tools, usage, and finals use normal Lynk timeline events.
- [ ] Stop works.
- [ ] Permission requests display exact advertised options and replies correlate correctly.
- [ ] Session/workspace metadata survives bridge restart.
- [ ] `session/list` and `session/load` restore authoritative history/context.
- [ ] Health/diagnostics distinguish installation, auth, protocol/startup, and unexpected-exit failures without secrets.
- [ ] PC checks pass.
- [ ] Android tests and assemble pass.
- [ ] Real authenticated restart/load/context smoke test passes and is documented.
- [ ] Device smoke checks pass or exact blockers are recorded.
- [ ] Seven focused commits exist.
- [ ] Final diff/worktree is clean except explicitly preserved pre-existing user paths.

## 9. Implementation Log

The orchestrator updates this section after reviewing each subagent. Parallel subagents return proposed entries rather than editing this file concurrently.

### Baseline

- 2026-07-10: Read root rules, mapped PC/Android harness architecture, read official Devin CLI and ACP SDK/schema documentation, and incorporated the user's live Devin 3000.1.27 capability/restart test.
- 2026-07-10: Worktree baseline: `main` ahead of `origin/main` by five commits; pre-existing untracked `.devin/` preserved and excluded from staging.

### Wave 1A — completed

- Agent ID: `f76b52f7` (SWE 1.7 Lightning).
- Files: Added Devin harness identity/configuration, host command and auth discovery, safe diagnostics/readiness, model protocol validation, `devin.workspace_not_found`, and focused PC tests. New files: `pc/src/host/DevinAuthProbe.ts`, `pc/src/host/DevinAuthProbe.test.ts`, and `pc/src/host/IntegrationManager.test.ts`.
- Tests: Independent orchestrator run passed `npm run check`, `npm run build`, and all 235 PC tests; `git diff --check` passed.
- Review/fixes: Corrected quoting for discovered executable paths containing spaces; replaced unreliable child timeout-event handling with an explicit race-safe timer; kept installed Devin configured but not ready on auth timeout/spawn failure; replaced hardcoded host model classification with canonical `isHarnessId()`; added real timeout/exit and integration-status regressions.
- Commit: `feat(devin): add harness identity and configuration`.

### Wave 1B — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit held until Wave 5.

### Wave 2 — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 3 — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 4 — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 5 — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 6A — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 6B — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 7 — pending

- Agent ID:
- Files:
- Tests:
- Review/fixes:
- Commit:

### Wave 8 — pending

- PC verification:
- Authenticated Devin test:
- Android verification:
- Host/device smoke:
- Final review:
