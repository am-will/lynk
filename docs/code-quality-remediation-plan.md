# Lynk Code Quality Remediation Plan

**Generated:** 2026-07-11  
**Complexity:** High  
**Execution model:** One autonomous subagent per issue, one isolated Git worktree per subagent, orchestrator-controlled integration.

## Objective

Resolve the release-blocking, security-critical, correctness, lifecycle, persistence, and performance defects identified by the whole-codebase review. Every issue branch must contain small, reviewable commits for each implementation step, focused tests, and a final verification commit when test-only changes are needed. The orchestrator will review and cherry-pick or merge each branch into `main`, resolve conflicts, run the complete repository verification matrix, and repair integration regressions.

## Global Rules For Every Issue Agent

1. Work only in the assigned worktree and branch.
2. Read the root `AGENTS.md` before editing.
3. Preserve public protocol compatibility unless the issue explicitly changes the contract; update both TypeScript and Kotlin when it does.
4. Do not edit unrelated files or the user-owned `.devin/` tree.
5. Commit every atomic implementation step separately. Do not leave successful work uncommitted.
6. Add focused regression tests before or with the fix.
7. Run the narrow tests after each step and the issue-level acceptance suite before reporting completion.
8. Report the ordered commit list, tests run, remaining risks, and any integration dependencies.

## Integration Waves

### Wave 1: Release And Trust Boundary

These tracks can start from the same baseline and run in parallel.

#### Issue R1: Packaged bridge startup failure

- **Branch:** `codex/remediate-package-startup`
- **Primary files:** `pc/src/dispatcher/promptPolicy.ts`, Android default prompt source, `pc/package.json`, `.github/workflows/host-bridge-package.yml`, package smoke tests.
- **Sub-plan:**
  1. Introduce one packaged, language-neutral prompt asset or generated source of truth.
  2. Make TypeScript and Android consume deterministic generated/runtime copies without reading repository source at runtime.
  3. Add clean `npm pack` installation tests that execute all three bins, start the bridge, and verify `/health`.
  4. Repair archive contents/scripts/README assumptions exposed by the clean install test.
- **Acceptance:** Installed npm tarball and tagged-archive staging start outside the repository with no Android source tree.

#### Issue R2: Pairing takeover and pre-registration command execution

- **Branch:** `codex/remediate-pairing-trust`
- **Primary files:** Android manifest, `PairingDeepLink.kt`, `LauncherActivity.kt`, `PhoneWebSocketClient.kt`, pairing tests/docs.
- **Sub-plan:**
  1. Parse and validate pairing payloads without mutating active configuration.
  2. Add an explicit user confirmation transaction showing normalized endpoints and replacement warning.
  3. Add expiry/nonce fields and reject unsafe schemes or malformed endpoints.
  4. Gate every inbound bridge frame except the registration handshake until registration succeeds.
  5. Add hostile-link and pre-registration command regression tests.
- **Acceptance:** No external intent silently changes pairing; commands cannot execute before authenticated registration.

#### Issue R3: WebSocket ingress hardening and dependency vulnerabilities

- **Branch:** `codex/remediate-ws-ingress`
- **Primary files:** `pc/src/bridge/server.ts`, `phoneWebSocket.ts`, `config.ts`, `pc/package.json`, lockfile, ingress tests.
- **Sub-plan:**
  1. Safely parse upgrade paths and reject malformed Host/request data without throwing.
  2. Add registration deadline, heartbeat, connection budget, rate limits, and bounded control-frame payloads.
  3. Enforce strong token validation with an explicit development override.
  4. Upgrade `ws`, Pi, MCP/transitive dependencies, and remove runtime-only dependency mistakes such as production `tsx` where possible.
  5. Add raw-socket, oversized-frame, idle-client, weak-token, and audit regression tests.
- **Acceptance:** `npm audit --omit=dev` has no known high vulnerabilities; malformed or abusive unauthenticated clients cannot terminate or monopolize the bridge.

### Wave 2: Authorization And Serialized Ownership

Start after Wave 1 is integrated so these branches inherit the hardened transport baseline.

#### Issue R4: Transport confidentiality and device identity binding

- **Branch:** `codex/remediate-transport-identity`
- **Primary files:** endpoint discovery/pairing payload, Android config and socket client, host socket dispatch, protocol tests/docs.
- **Sub-plan:**
  1. Define secure endpoint policy: `wss` for network hosts, explicit opt-in development exceptions for ADB/loopback/trusted overlay.
  2. Stop sending provider API keys over insecure transport; prefer host-owned keys or ephemeral credentials.
  3. Bind post-registration messages to the socket identity and reject payload/device mismatches centrally.
  4. Validate result ownership in `PhoneHub` and prevent re-registration on an established socket.
  5. Add cross-platform endpoint and spoofing tests.
- **Acceptance:** A registered device cannot act as another device, and long-lived secrets are never sent over an unprotected network endpoint.

#### Issue R5: Enforced approval capabilities for sensitive phone actions

- **Branch:** `codex/remediate-action-authorization`
- **Primary files:** phone command schemas, accessibility executor, confirmation overlay, bridge/MCP tool path, safety docs/tests.
- **Sub-plan:**
  1. Classify commands by risk in the canonical command schema.
  2. Issue short-lived, single-use approval capabilities from the UI, bound to session, action summary, and observation generation.
  3. Enforce the capability below model/bridge dispatch before sensitive commands execute.
  4. Define denial, expiry, replay, changed-screen, and cancellation behavior.
  5. Add unit and instrumentation-friendly authorization tests.
- **Acceptance:** Sensitive actions cannot execute by skipping `ask_user_confirmation`, reusing approval, or changing target context.

#### Issue R6: Host chat send admission race

- **Branch:** `codex/remediate-host-run-state`
- **Primary files:** `OpenClawChatBridge.ts`, `phoneWebSocket.ts`, harness state store and focused tests.
- **Sub-plan:**
  1. Model per-device/session run lifecycle as an exhaustive state machine.
  2. Reserve `starting` synchronously before asynchronous health/session/harness work.
  3. Define queue, steer, stop, rollback, and terminal settlement semantics.
  4. Replace scattered run flags with atomic transitions.
  5. Add concurrent-send, stop-during-start, failure rollback, and ownership tests.
- **Acceptance:** Concurrent sends never reach a single-run harness simultaneously and every caller receives one terminal outcome.

### Wave 3: Android Execution And Cancellation

#### Issue R7: Phone command actor and stale node protection

- **Branch:** `codex/remediate-phone-command-actor`
- **Primary files:** `AccessibilityCommandExecutor.kt`, `ScreenObserver.kt`, local tool registry, phone command tests.
- **Sub-plan:**
  1. Replace independent launches with one lifecycle-owned cancellable command actor.
  2. Add observation-generation IDs and require `(observationId, nodeId)` for node actions.
  3. Reject stale/unknown generations rather than refreshing and reusing ordinal IDs.
  4. Propagate cancellation from local/host task ownership into command execution.
  5. Add concurrency, stale-node, cancellation, and service-close tests.
- **Acceptance:** Commands are ordered, cancellable, and cannot act on a node from a different observation.

#### Issue R8: Local model engine ownership and run cancellation

- **Branch:** `codex/remediate-local-engine-state`
- **Primary files:** local chat coordinator/client, LiteRT runtime, local realtime delegate, service composition, tests.
- **Sub-plan:**
  1. Introduce one application-scoped local engine manager/actor shared by chat and voice.
  2. Track explicit turn generations instead of a nullable global job.
  3. Make stop/new-session/route-switch/close cancel-and-join before replacement or native engine close.
  4. Prevent old completion paths from clearing newer ownership.
  5. Add race, shared-engine, close-during-generation, and memory-ownership tests.
- **Acceptance:** Only one generation owns the engine, chat/voice do not double-load the model, and cancellation settles before replacement.

#### Issue R9: External Termux cancellation

- **Branch:** `codex/remediate-termux-cancellation`
- **Primary files:** `TermuxCommandRunner.kt`, local tool registry/policy, Termux protocol/tests/docs.
- **Sub-plan:**
  1. Preserve coroutine cancellation instead of converting it to a normal tool error.
  2. Introduce tracked execution IDs/PIDs or a Termux-side wrapper capable of killing a process group.
  3. Kill active work on cancellation, timeout, session stop, and service destruction.
  4. Make unsupported cancellation explicit and require confirmation until a kill is verified.
  5. Add fake-service lifecycle tests plus a documented device verification script.
- **Acceptance:** A stopped or timed-out Termux command cannot continue mutating files in the background.

### Wave 4: Voice, Tools, And Data Movement

#### Issue R10: Voice session lifecycle, queued work, and transcription bounds

- **Branch:** `codex/remediate-voice-lifecycle`
- **Primary files:** voice runtime/session/coordinator/local delegate, foreground service, transcription manager, tests.
- **Sub-plan:**
  1. Introduce a sealed voice-session state machine that owns microphone foreground lease, WebRTC session, audio focus, and backend task session.
  2. Cleanup exactly once on failed/closed/disconnected/error/stop transitions.
  3. Cancel-and-join local delegated work and clear bounded/deduplicated queues on hang-up.
  4. Stream transcription audio into bounded temporary storage with duration, byte, and initial-silence limits; throttle level updates.
  5. Add lifecycle, retry, hang-up, queue-limit, and long-recording tests.
- **Acceptance:** No retry leaks audio resources, hang-up leaves no work active, and transcription memory remains bounded.

#### Issue R11: Strict local tool-call boundary

- **Branch:** `codex/remediate-local-tool-parser`
- **Primary files:** local tool policy/parser/controller/prompt builder and tests.
- **Sub-plan:**
  1. Define one strict discriminated tool-call envelope produced through a dedicated model control channel.
  2. Remove broad substring authorization, arbitrary JSON extraction, repair, and regex fallback for side-effecting calls.
  3. Separate explanatory text from executable output and validate arguments against each tool schema.
  4. Require confirmation/capability for local writes and Termux execution.
  5. Add adversarial explanatory-output, malformed JSON, unknown tool, and argument validation tests.
- **Acceptance:** Example JSON or malformed model text cannot execute a tool.

#### Issue R12: Bounded attachment and model import pipeline

- **Branch:** `codex/remediate-attachment-pipeline`
- **Primary files:** Android attachment/model stores and UI callbacks, protocol attachment schema, bridge upload/storage, tests.
- **Sub-plan:**
  1. Move imports off the main thread and stream through capped temporary files with free-space checks, progress, cancellation, and atomic rename.
  2. Enforce attachment count and aggregate budgets before and during copy.
  3. Add an authenticated streaming upload/blob-reference path so chat JSON does not carry inline Base64.
  4. Store only metadata/content IDs in session history and add rejected/partial/orphan cleanup.
  5. Add oversized, unknown-length provider, cancellation, low-space, multi-file, and retention tests.
- **Acceptance:** Large inputs do not block the UI or multiply memory use; rejected/abandoned data is deleted.

### Wave 5: Host Persistence And Harness Reliability

#### Issue R13: Private atomic persistence, audit retention, and path ownership

- **Branch:** `codex/remediate-host-storage`
- **Primary files:** host config/path resolution, session stores, audit log, service manager, adapter cache paths, tests.
- **Sub-plan:**
  1. Introduce `HostPaths` separating immutable install root, private data root, audit root, blob root, and explicit workspace root.
  2. Migrate all CWD-relative session/cache paths into private data storage.
  3. Implement atomic persistence or SQLite, corruption backup/recovery, `0700/0600` permissions, size/history limits, and debounced async writes.
  4. Convert audit events to allowlisted metadata with rotation/retention and no raw prompts/RPC parameters.
  5. Add migration, permission, corruption, rotation, upgrade-path, and event-loop responsiveness tests.
- **Acceptance:** Package upgrades cannot delete sessions, local users cannot read sensitive state by default, and persistence cannot block request handling with whole-catalog rewrites.

#### Issue R14: Harness isolation, process lifecycle, timeouts, and typed failures

- **Branch:** `codex/remediate-harness-reliability`
- **Primary files:** harness router, Hermes discovery, Codex app-server client, OpenCode run driver, bridge error/session handling, tests.
- **Sub-plan:**
  1. Route sends directly to the selected adapter; keep aggregate health diagnostic-only and bounded-parallel.
  2. Move Hermes discovery off the event loop and cache it with explicit refresh/TTL.
  3. Add single-generation startup locks, RPC deadlines, pending rejection, and robust child teardown to Codex.
  4. Make OpenCode timeout a typed failure rather than a successful final response.
  5. Introduce typed adapter failures; create sessions only on explicit not-found and commit local model/reasoning state only after remote success.
  6. Add unavailable-unrelated-harness, hung RPC, concurrent startup, timeout, patch rejection, and history-auth-error tests.
- **Acceptance:** An unrelated harness cannot block a selected harness, no RPC can hang forever, and backend failures cannot create duplicate or falsely configured sessions.

## Deferred Until The Critical Merge Stabilizes

- Full generated TypeScript/Kotlin protocol replacement. During the critical tracks, add shared golden fixtures and production boundary validation where touched; perform full code generation as a dedicated follow-up because it conflicts with nearly every protocol-changing branch.
- Pet spritesheet symlink/sibling-prefix containment hardening. It is small but lower priority than the remote control and lifecycle blockers.
- Broad decomposition of `OverlayController`, `AgentForegroundService`, and near-1,000-line UI/model files. Critical tracks should extract ownership boundaries when required, but a cosmetic whole-file split is deferred until behavior is stable.
- Documentation/legacy-brand cleanup and broader build reproducibility work beyond dependency pins required by the critical tracks.

## Orchestrator Merge Procedure

1. Review each branch diff and commit sequence against its issue acceptance criteria.
2. Run the branch's focused tests in its worktree.
3. Merge or cherry-pick commits into `main` in wave order; preserve atomic commit history where practical.
4. Resolve conflicts by retaining the newer canonical owner/state model, not by restoring duplicate branches or compatibility shims.
5. After each wave run PC typecheck/tests and relevant Android unit/build/lint tasks.
6. After all waves run the full matrix:
   - `cd pc && npm run check && npm test && npm run build`
   - `cd pc && npm pack` followed by clean install/bin/bridge health smoke test
   - `cd pc && npm audit --omit=dev`
   - `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
   - Targeted hostile pairing, malformed WebSocket, cancellation, voice retry, attachment, persistence-corruption, and harness-timeout probes
7. Inspect the final diff for protocol alignment, secret leakage, machine-specific paths, and accidental `.devin/` changes.

## Rollback Strategy

- Each issue is isolated in atomic commits, allowing issue-level revert without discarding other remediation.
- Migration changes must retain backups and idempotent rollback/read compatibility for existing user state.
- Protocol changes must be capability-gated or backward-compatible until both Android and bridge are deployed together.
- If an integration wave fails, revert only that wave's merge commits, repair in the original worktrees, and re-run focused tests before reintegration.
