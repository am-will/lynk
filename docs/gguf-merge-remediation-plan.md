# Plan: GGUF Merge Remediation

**Generated**: 2026-07-14
**Estimated Complexity**: High

## Overview

Resolve all six blockers from the GGUF merge review as six atomic, independently reviewed commits. Use explicit runtime contracts instead of extension-specific conditionals, preserve LiteRT-LM behavior, keep Android API 26 compatibility, and finish with clean CI-equivalent validation plus an in-place install on the connected phone.

## Prerequisites

- Work only on `codex/gguf-merge-remediation` in the clean remediation worktree.
- Initialize the pinned `android/third_party/llama.cpp` submodule before Android validation.
- Preserve the original checkout's unrelated uncommitted changes.
- Each issue is implemented and committed by its own sub-agent branch, then integrated in dependency order.

## Sprint 1: Restore the Runtime Contract

**Goal**: Give callers a truthful, backend-owned description of effective context and media capabilities.

**Demo/Validation**:

- A GGUF context downshift is reflected in prompt history budgeting and `chat.*` usage metadata.
- Text-only GGUF does not receive image paths or expose an unusable screenshot-image continuation.
- LiteRT-LM image behavior remains unchanged.

### Task 1.1: Add a local runtime profile

- **Issue**: Review finding 1.
- **Location**: `LocalModelRuntime.kt`, `LocalModelRuntimeRouter.kt`, `GgufRuntime.kt`, `LiteRtLmRuntime.kt`, `LocalAgentController.kt`, `LocalChatMessages.kt`, attachment/tool policy code, focused tests.
- **Description**: Add a canonical profile/capability contract owned by the selected runtime. Carry effective context tokens and image-input support to history budgeting, usage metadata, attachment preparation, and tool continuation logic.
- **Dependencies**: None.
- **Acceptance Criteria**:
  - Effective GGUF context is the single source of truth after planner fallback.
  - GGUF image attachments fail before a turn with a precise message, while screenshot-producing tools cannot poison a later round.
  - Existing LiteRT-LM image and context behavior is preserved.
- **Validation**: Focused runtime/router/controller/message tests and Android unit tests.
- **Commit**: `fix(android): model local runtime capabilities`

## Sprint 2: Make Native Execution Safe

**Goal**: Ensure cancellation and GPU fallback have explicit, testable semantics.

**Demo/Validation**:

- Stop/timeout reaches native load or prefill promptly enough to release the turn.
- Only confirmed Vulkan failures trigger CPU fallback.
- Retried output never combines deltas from separate attempts.

### Task 2.1: Make GGUF loading and prefill cancellation-aware

- **Issue**: Review finding 2.
- **Location**: `GgufRuntime.kt`, `GgufNative.kt`, `gguf_runtime.cpp`, native/runtime tests.
- **Description**: Introduce cancellation during model loading/context creation and make the native abort path fire when coroutine cancellation begins, not after structured-concurrency completion.
- **Dependencies**: Task 1.1 integrated.
- **Acceptance Criteria**:
  - Cancellation during load and prefill terminates without waiting for normal completion.
  - Session ownership remains race-free and handles close exactly once.
- **Validation**: Deterministic cancellation tests plus Android unit/build validation.
- **Commit**: `fix(android): cancel GGUF native work promptly`

### Task 2.2: Type and scope Vulkan fallback

- **Issue**: Review finding 3.
- **Location**: `GgufRuntime.kt`, `GgufNative.kt`, `gguf_runtime.cpp`, fallback tests.
- **Description**: Distinguish backend/device-loss failures from validation, configuration, cancellation, and callback errors. Scope GPU-disable state appropriately and prevent first-attempt deltas from contaminating a CPU retry.
- **Dependencies**: Task 2.1 integrated.
- **Acceptance Criteria**:
  - Missing model, unsupported image, prompt overflow, and callback failures never disable Vulkan.
  - Confirmed Vulkan failure retries once on CPU with a clean output stream.
- **Validation**: Focused typed-failure and stream-retry tests.
- **Commit**: `fix(android): scope GGUF Vulkan fallback`

## Sprint 3: Repair Platform and Build Contracts

**Goal**: Restore declared Android compatibility and make clean CI checkouts reproducible.

**Demo/Validation**:

- Lint passes with `minSdk 26`.
- CI-style checkout configures native build with the submodule present.
- Native generic code targets the ARM64 ABI baseline.

### Task 3.1: Guard SoC detection by Android API level

- **Issue**: Review finding 4.
- **Location**: `GgufRuntime.kt` and focused device-policy tests.
- **Description**: Replace the unconditional API-31 field read with an API-safe device policy helper.
- **Dependencies**: None.
- **Acceptance Criteria**: No `NewApi` lint error and API 26-30 takes a safe path.
- **Validation**: Unit tests and `:app:lintDebug`.
- **Commit**: `fix(android): make GGUF device policy API safe`

### Task 3.2: Initialize llama.cpp in CI

- **Issue**: Review finding 5.
- **Location**: `.github/workflows/ci.yml`.
- **Description**: Configure the Android checkout to initialize recursive submodules and add a fast assertion that the pinned llama.cpp source is present.
- **Dependencies**: None.
- **Acceptance Criteria**: A clean CI-style checkout reaches native configure/build.
- **Validation**: Local no-cache worktree simulation and workflow inspection.
- **Commit**: `fix(ci): initialize Android submodules`

### Task 3.3: Restore the ARM64 baseline

- **Issue**: Review finding 6.
- **Location**: `android/app/build.gradle.kts`, CMake configuration, build verification.
- **Description**: Remove global ARMv8.2/dotprod assumptions from generic native translation units and rely on llama.cpp/ggml runtime feature dispatch or narrowly scoped optimized kernels.
- **Dependencies**: None.
- **Acceptance Criteria**: Generic compile commands no longer require ARMv8.2 dotprod; GGUF still builds for `arm64-v8a`.
- **Validation**: Inspect generated compile commands and assemble the debug APK.
- **Commit**: `fix(android): preserve ARM64 runtime dispatch`

## Sprint 4: Integration and Device Acceptance

**Goal**: Produce a merge-ready branch and verify the installed app on the connected Samsung device.

**Demo/Validation**:

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passes from a clean initialized worktree.
- `git diff --check` passes and the six issue commits remain reviewable.
- `adb install -r` succeeds without wiping app data.
- `app.lynk` package/version is verified, USB reverse is restored if needed, and bridge `/health` shows the phone registered when the bridge is running.

### Task 4.1: Integrate, review, and reinstall

- **Location**: Entire six-commit series plus connected device.
- **Dependencies**: Tasks 1.1 through 3.3.
- **Acceptance Criteria**:
  - No unresolved review blocker remains.
  - Required Android validation passes.
  - APK is installed in place and visible package state is verified.
- **Validation**: Full test/build/lint, APK inspection, `adb install -r`, package read-back, app launch/observation, and bridge health check.
- **Commit**: No squash; retain the plan commit and six atomic issue commits. Add only a final integration/test fix commit if validation uncovers a cross-issue defect.

## Testing Strategy

- Add focused unit tests with each behavioral commit.
- Use clean-worktree native builds with the pinned submodule.
- Run the complete Android unit, assemble, and lint suite after integration.
- Treat the optional model-backed instrumentation smoke test separately because it requires a model path on the device; run it when a suitable installed GGUF path is discoverable without changing user data.

## Potential Risks and Gotchas

- Tasks 1.1, 2.1, 2.2, and 3.1 touch `GgufRuntime.kt`; integrate them sequentially and resolve conflicts by preserving the newest typed runtime contract.
- Native cancellation must not free a session while JNI work still owns it.
- Effective context may only be known after native creation; the profile contract must support a conservative pre-load value and an authoritative loaded value.
- CI must not initialize submodules for the PC job unnecessarily.
- The current app version may be unchanged, so installation verification must use `lastUpdateTime` as well as version fields.

## Rollback Plan

- Each issue is an independent commit and can be reverted separately.
- The original dirty checkout remains untouched.
- Device rollback uses the previous APK with `adb install -r`; no uninstall or app-data wipe is permitted.
