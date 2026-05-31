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
- The default Hermes/Codex session cache path is tied to the bridge process working directory unless a packaged/service install configures a stable location. Treat completed history as helpful cache, not the source of truth for backend runs.
- The legacy Codex adapter depends on app-server protocol details that may change by installed version. Generate local, gitignored schemas with `npm run codex:schemas` for inspection while that path remains; runtime code stays hand-written.
- The OpenClaw CLI adapter currently runs one `openclaw agent --json` task at a time through the bridge. Mid-task steering depends on OpenClaw support and may require stopping and sending a follow-up.
- Local phone mode requires a user-imported `.litertlm` model and device resources vary widely by RAM, GPU/NPU support, and thermals.
- Local phone mode can control Android and use app-private workspace tools. It does not yet provide a full desktop-class shell/git/build environment; `termux_command` is a placeholder until a dedicated Termux helper is configured.
