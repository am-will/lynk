# Thermo-Nuclear Review Remediation Plan

## Summary

Refactor the last-18-hours changes to remove committed debug side effects, decompose the OpenCode bridge, clarify OpenCode SDK boundaries, and bring oversized files back under maintainable limits without changing user-visible chat behavior.

## Enumerated Steps

1. Remove hard-coded debug telemetry from Hermes and OpenClaw bridge paths.
2. Extract Hermes pure helpers into focused modules and keep `HermesChatClient.ts` below 900 lines.
3. Extract OpenCode pure model, tool, message, and event normalization.
4. Extract OpenCode session catalog behavior.
5. Extract OpenCode run orchestration and tighten the OpenCode SDK boundary.
6. Split the oversized OpenClaw bridge tests into focused test files.
7. Re-check file sizes, run verification, and do a final maintainability pass.

## Constraints

- Preserve user-visible chat behavior for OpenCode, Hermes, Codex, and OpenClaw.
- Keep OpenCode one-active-task-at-a-time unless a later product decision changes that.
- Do not modify unrelated dirty files, including `android/app/build.gradle.kts`.
- Commit each completed step before moving to the next one.

## Step Log

- Step 0: Plan saved. Implementation not started.
- Step 1: Removed hard-coded debug telemetry helpers and call sites from Hermes and OpenClaw bridge code. Verified with `rg` for debug endpoint/call names and `cd pc && npm run check`.
- Step 2: Extracted Hermes model, skill, toolset, and CLI helpers into `pc/src/bridge/hermes/HermesChatHelpers.ts`; `HermesChatClient.ts` is now 722 lines. Verified with `cd pc && npm run check` and `node --import tsx --test src/bridge/HermesChatClient.test.ts`.
- Step 3: Extracted OpenCode payload, event, model, tool, and message normalization into `pc/src/bridge/opencode/OpenCodeNormalizers.ts`; `OpenCodeChatClient.ts` is now 828 lines. Verified with `cd pc && npm run check` and OpenCode-focused tests.
