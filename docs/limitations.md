# Known Limitations

- Overlay and accessibility permissions must be granted manually.
- On the tested Samsung Galaxy device, adb writes to `enabled_accessibility_services` were reset by the OS after process restarts. Enabling the service through Settings worked, but bridge-only `open_app` is also supported so the MVP can still launch apps before Accessibility is bound.
- Screenshot capture requires Android API 30+ and may be rate-limited by the OS.
- Android `take_screenshot` captures through `AccessibilityService`, so it does not require adb after the app is paired and accessibility is enabled. The MCP tool saves the PNG under `captures/` and returns `screenshotPath`; the raw WebSocket result still carries base64 internally.
- Node IDs are per-observation and should be refreshed with `phone_observe` after navigation.
- Text input relies on `ACTION_SET_TEXT`, which some custom controls may reject.
- App-name launch matching is fuzzy. Prefer `packageName` for reliable automation.
- The bridge uses a shared token suitable for a local prototype, not production auth.
- Host bridge session persistence is harness-specific. OpenClaw history lives in OpenClaw Gateway. Hermes CLI fallback and Codex completed session history can be cached to disk by the bridge, but active in-flight runs are not resumed after a bridge restart and SSE clients do not receive a synthetic terminal event from the restarted process.
- Hermes CLI fallback supports normal send/final chat turns, but not active-turn steering or remote session sync. Use the Hermes runs API contract for those features.
- Harness session catalogs use the stable private host data root and survive package upgrades, but remain a bounded local cache rather than the source of truth for backend runs. Writes are debounced briefly and flushed during graceful shutdown; an abrupt process or machine loss can discard the final not-yet-flushed update while preserving the last atomic generation.
- The legacy Codex adapter depends on app-server protocol details that may change by installed version. Generate local, gitignored schemas with `npm run codex:schemas` for inspection while that path remains; runtime code stays hand-written.
- The OpenClaw CLI adapter currently runs one `openclaw agent --json` task at a time through the bridge. Mid-task steering depends on OpenClaw support and may require stopping and sending a follow-up.
- Devin support is based on the tested authenticated CLI `3000.1.27` ACP surface. That runtime advertises session listing/loading and additional directories, but not `session/resume`; Lynk deliberately uses `session/list` plus `session/load` instead of claiming resume support.
- Devin active-turn cancellation is supported, but active-turn steering is not advertised or implemented. Stop the turn and send a follow-up. Devin also currently rejects Android attachments before sending a prompt.
- Host attachments use authenticated streaming blobs, but backend support is not uniform. Codex and Pi consume images only; Devin rejects attachments; OpenClaw, Hermes, OpenCode, and Pi's image path use a runtime compatibility conversion capped at 8 MiB per item even though Lynk's blob transport accepts up to 50 MiB. Payloads above a selected harness's compatibility limit fail before the harness turn starts.
- Devin sessions and replay depend on the CLI's ACP catalog. Lynk stores workspace/model associations in its private session root, but does not fabricate backend history when ACP list/load is unavailable. Session IDs observed in authenticated restart tests are ephemeral and should not be used as durable user-facing identifiers.
- Node file modes provide verified `0700`/`0600` privacy on Unix-like systems. On Windows the same private data layout and best-effort modes are used, but Node mode bits do not establish a complete DACL; managed deployments should apply account-appropriate Windows ACL policy to the data root.
- The authenticated Devin smoke test is opt-in because it starts a real remote-backed turn. `cd pc && npm run test:devin:real` passed against CLI `3000.1.27` for process restart, list, load, history replay, and retained context; it is not a claim of feature parity with every Devin CLI version or ACP extension.
- Local phone mode requires a user-imported `.litertlm` model and device resources vary widely by RAM, GPU/NPU support, and thermals.
- Local phone mode can control Android and use app-private workspace tools. It does not provide a full desktop-class shell/git/build environment. `termux_command` requires a compatible F-Droid/GitHub Termux installation and remains fail-closed until Lynk verifies its [tracked process-group cancellation protocol](termux-cancellation.md) on the device.

## Host harness reliability boundaries

- A chat send goes directly to its selected harness. Aggregate model and health diagnostics run concurrently and give each harness 3 seconds; a slow unrelated backend is reported as timed out without delaying the selected send.
- Hermes local model discovery runs in an isolated worker, is single-flight, and caches successful results for 5 minutes. A refresh has a 30-second outer deadline; its authenticated and curated CLI probes are limited to 15 and 10 seconds respectively with 1 MiB output caps. Failed refreshes expose a typed stale result when a prior catalog exists, otherwise unavailable. Restarting the bridge clears the cache.
- Codex app-server RPCs default to a 30-second deadline (`CODEX_RPC_TIMEOUT_MS` can override it), JSON-lines are capped at 1 MiB, and turns retain the existing 10-minute default deadline. A failed generation rejects all owned pending work. Teardown sends `SIGTERM`, waits 1.5 seconds, then sends `SIGKILL` and waits another 0.5 seconds; only the child process spawned by Lynk is signalled.
- OpenCode uses `OPENCODE_RUN_TIMEOUT_MS` for prompt, polling, and overall run completion. Timeout, cancellation, and hung-stream paths emit an error terminal and never a successful final response.

## Local model and GGUF limitations

- Local phone mode requires a user-imported `.litertlm` or `.gguf` model and device resources vary widely by RAM, GPU/NPU/Vulkan support, and thermals.
- `.gguf` support uses a pinned `llama.cpp` submodule. Initialize it with `git submodule update --init --recursive` before building the Android app.
- The first GGUF path is text-only; multimodal projection (`mmproj`) files are not supported yet.
- GGUF inference falls back to ARM CPU when GPU acceleration is unavailable. Vulkan is compiled in but is disabled for Snapdragon SM8850 because Q1_0 generation reproducibly caused `VK_ERROR_DEVICE_LOST` on the Adreno 840; other GPUs still fall back after a Vulkan error.
- Context up to 262K is requested, not guaranteed, and the runtime reduces it based on available memory. The 262K allocation was verified with Bonsai 27B on a 16 GB SM-S948U, but sustained generation has a larger working-set and thermal cost.
- Model files can be large. `Bonsai-27B-Q1_0.gguf` is 3,803,452,480 bytes and its SHA-256 is `17ef842e47450caeb8eaa3ebfbbab5d2f2278b62b79be107985fb69a2f819aa0`; verify device storage and download bandwidth before importing.
- CPU inference is functional but slow for Bonsai 27B on the tested SM-S948U: a short eight-token native smoke generation took about 10.5 minutes. Bonsai 1.7B Q1_0 completed a normal Lynk `hi` turn in about 3.3 seconds after direct prompts were compacted.
