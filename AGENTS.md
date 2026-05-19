# AGENTS.md

## Project Shape
- This repo is **Open Claw Agent**: an Android bubble endpoint for delegating chat and voice tasks directly to an installed Open Claw session on the user's remote PC. Phone control is a secondary tool capability, not the default purpose of the product.
- `pc/` is the TypeScript PC bridge, HTTP API, dispatcher layer, and `android-phone` MCP server. It uses Node.js 24+, ESM, strict TypeScript, `zod`, `ws`, and the official MCP SDK.
- `pc/src/bridge/` owns phone registration, command dispatch, audit/status APIs, Realtime session creation, Realtime task queueing, and OpenAI web search for voice-mode lookups.
- `pc/src/dispatcher/` owns the agent-session adapter boundary. `OpenClawSessionClient` is the default adapter and delegates to `openclaw agent --json`; the hand-written Codex app-server client remains legacy-selectable with `PHONE_AGENT_DISPATCHER=codex`. `PHONE_AGENT_USE_FALLBACK=1` deliberately exercises the fallback path.
- `pc/src/protocol/messages.ts` is the source of truth for bridge message validation and shared message types.
- `android/` is the Kotlin Android app. It connects to the PC bridge over WebSocket, runs the overlay/foreground service, executes phone commands through `AccessibilityService`, supports realtime voice over WebRTC, and supports composer transcription through OpenAI audio transcription.
- `android/app/src/main/java/dev/androidagent/voice/` owns realtime voice state, WebRTC transport, function-call accumulation, transcript normalization, and transcription helpers.
- `docs/` contains setup, protocol, pairing, safety, legacy Codex dispatcher notes, OpenClaw implementation notes, limitations, and demo notes. Check these before changing cross-device behavior.

## Commands
- PC install: `cd pc && npm install`
- PC type check: `cd pc && npm run check`
- PC build: `cd pc && npm run build`
- PC tests: `cd pc && npm test`
- PC bridge: `cd pc && npm run bridge` (auto-loads `pc/.env.local` via `tsx --env-file-if-exists`; command-line env vars still win for one-off overrides)
- PC MCP server: `cd pc && npm run mcp`
- Register phone MCP with OpenClaw: `cd pc && npm run openclaw:mcp`
- PC bridge health: `cd pc && npm run phone:health`
- USB test setup: `cd pc && npm run phone:usb`
- Demo text request: `cd pc && npm run demo:agent -- "Open Settings"`
- Demo direct command: `cd pc && npm run demo:open-settings`
- Legacy Codex schemas: `cd pc && npm run codex:schemas`
- Android build/tests: open `android/` in Android Studio, or run local Gradle tasks from `android/` if available, especially `:app:assembleDebug` and `:app:testDebugUnitTest`.

## Working Rules
- Keep WebSocket message shapes aligned across `pc/src/protocol/messages.ts`, Android JSON handling in `PhoneWebSocketClient.kt` / voice controllers, and `docs/protocol.md`.
- Keep phone command names aligned across `PHONE_COMMANDS`, `pc/src/mcp/tools.ts`, Android `AccessibilityCommandExecutor.kt`, and the docs for whichever session adapter exposes the phone tools.
- Keep realtime voice message names and tool names aligned across `OpenAiRealtimeClient.ts`, `RealtimeTaskManager.ts`, Android voice parsing/accumulation, and `docs/protocol.md`.
- Keep model and reasoning options aligned across `pc/src/protocol/messages.ts` and `android/app/src/main/java/dev/androidagent/AgentModelOptions.kt`.
- Keep the default phone-agent safety/operating prompt aligned between `pc/src/dispatcher/safetyPrompt.ts` and `android/app/src/main/java/dev/androidagent/DefaultSystemPrompt.kt`.
- `pc/src/generated/codex-app-server/` is local, gitignored output for inspecting Codex app-server schemas. Do not hand-edit or commit it; while the legacy Codex adapter remains, regenerate it with `cd pc && npm run codex:schemas`. Runtime code must stay hand-written and must not import generated schemas.
- Treat `PHONE_AGENT_TOKEN`, `OPENAI_API_KEY`, saved Android API keys, LAN URLs, device IDs, and other device-specific values as local config. Do not commit real tokens, keys, or machine-specific secrets. The PC's persistent config lives in `pc/.env.local` (gitignored via `.env.*`); `pc/.env.example` documents the shape. The Android side stores its token under `dev.openclawagent`'s `shared_prefs/open_claw_agent_config.xml`; both ends must match exactly or the bridge closes the WebSocket with code `4001 invalid token`.
- Phone↔bridge handshake is two-step: TCP/WS open is not enough. `PhoneWebSocketClient` only flips `connected = true` after receiving an `agent_status` whose text starts with `"Registered "` (sent by `server.ts` after `hub.register`). A 5 s register-ack watchdog will `cancel()` the socket and reconnect if the ack never arrives. When changing the register path on either side, preserve that ack text and behavior, or update both ends together — silent token mismatches and pre-ack sends are the bug class this guards against.
- Realtime voice depends on an OpenAI API key supplied either through `OPENAI_API_KEY` on the PC bridge or the Android app settings. `OPENAI_REALTIME_MODEL`, `OPENAI_REALTIME_VOICE`, and `OPENAI_WEB_SEARCH_MODEL` are bridge-side runtime config.
- Screenshots and coordinate taps use full-screen physical pixels, including system bars. Use normalized taps for coordinates derived from scaled screenshots, and preserve screenshot width/height metadata through the protocol.
- Android agent chrome may temporarily hide during taps, swipes, and screenshots; this should not stop active turns, voice sessions, or the foreground service.
- When changing the current Codex app-server compatibility path, update `docs/app-server.md` and keep fallback behavior explicit. When adding or changing the Open Claw path, update `docs/open-claw-migration-plan.md` until it is replaced by final architecture docs.
- Add or update focused tests for queueing, interrupts, steering, realtime tool output handling, transcript normalization, and transcription audio helpers when touching those areas.
- Keep changes narrowly scoped; this is a prototype, so prefer clear local code over broad abstractions.

## Safety
- Preserve user confirmation before purchases, payments, money movement, crypto transactions, account/security/privacy changes, deleting data, installing apps, sharing credentials, or other hard-to-undo actions.
- Do not send chat, SMS, social, or email messages unless the user explicitly requested that exact send. Ask for confirmation when the recipient, content, account, or intent is ambiguous or sensitive.
- Use `phone_ask_user_confirmation` for high-risk phone actions and keep the Android confirmation UI in the loop.
- Biometric, fingerprint, passkey, password-manager, credential, and other sensitive OS prompts must remain manual; the agent should stop and ask the user to handle them.
