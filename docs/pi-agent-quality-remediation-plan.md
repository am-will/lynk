# Pi Agent Quality Remediation Plan

## Objective

Resolve the thermo-nuclear code-quality findings from the Pi harness integration without changing the intended behavior: Pi remains a first-class host harness with custom workspaces, session history, streaming text, visible reasoning summaries, tool events, and Android model/workspace controls.

## Tasks

1. Centralize workspace-capable harness metadata.
   - Move `codex || opencode || pi` checks into canonical harness capability helpers.
   - Use the shared helper in bridge routing, session creation, readiness tests, and Android-side harness/workspace selection.
   - Shrink `OpenClawChatBridge.ts` back under the 1k-line threshold.

2. Canonicalize Pi session keys.
   - Stop treating synthetic keys like `pi:<device>` as durable Pi sessions.
   - Create or resolve a real SDK session before Pi send/history flows proceed.
   - Ensure the active chat key and previous-session key are the same canonical `pi:<sdkSessionId>` value.

3. Scope Pi active runs per session.
   - Replace the single global Pi `active` run with per-run/per-session tracking.
   - Allow independent Pi sessions to run concurrently while preserving one active run per session.
   - Make abort and steer target the selected session/run explicitly.

4. Decompose `PiChatClient`.
   - Extract Pi history/message normalization into a focused module.
   - Keep SDK runtime calls in `PiSdkClient` and adapter orchestration in `PiChatClient`.
   - Reduce `unknown`/`Record<string, unknown>` parsing inside the adapter body.

5. Data-drive Android workspace settings.
   - Replace copied Codex/OpenCode/Pi workspace UI blocks with harness workspace specs.
   - Keep persisted config keys and visible UI behavior unchanged.

6. Verification.
   - Run `cd pc && npm run check && npm test`.
   - Run `cd android && ./gradlew :app:testDebugUnitTest`.
   - Run `cd android && ./gradlew :app:assembleDebug`.
   - Confirm `android/app/build.gradle.kts` remains unstaged and untouched.

## Implementation Log

- 2026-06-01: Committed initial plan.
- 2026-06-01: Centralized workspace-harness checks in `AgentHarness` and `AgentConfig`, reused them in PC bridge/router and Android overlay/service paths, and reduced `OpenClawChatBridge.ts` from 1001 to 998 lines.
