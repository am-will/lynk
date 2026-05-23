# AGENTS.md

## Product Shape
- This repo is currently **Android Agent**: an Android bubble/chat/voice endpoint that can route work to host-side agents or an on-device local model. Do not describe the product as OpenClaw-only even though many classes and docs still carry OpenClaw migration names.
- The supported backends are **OpenClaw**, **Hermes**, **Codex**, and **Local LiteRT-LM**. Android phone control is an optional tool target across these paths, not the default purpose of every request.
- The app has two user modes:
  - **Host bridge**: Android connects to the PC bridge over `/phone`; the bridge exposes a harness router for OpenClaw, Hermes, and Codex chat sessions.
  - **Local phone**: Android runs an imported `.litertlm` model on-device, emits the same `chat.*` timeline events locally, and can call Android/local app-private tools.
- OpenClaw is currently the default host harness and has the most Gateway-specific code. Hermes and Codex are real supported harnesses, not merely documentation footnotes. `PHONE_AGENT_USE_FALLBACK=1` is a deliberate bridge fallback path for testing.
- Local phone mode uses `local-litertlm`, supports Android phone tools and app-private workspace tools, and gates write/Termux developer tools behind settings. It is not yet a full desktop shell/git/build environment.

## Repo Map
- `pc/`: Node 24+, ESM, strict TypeScript bridge. Uses `zod`, `ws`, `tsx`, and the MCP SDK.
- `pc/src/bridge/`: WebSocket registration, HTTP APIs, host chat bridge, harness routing, audit/status, realtime session setup, task queueing, web search, pet catalog. Some files are still named `OpenClaw*` because OpenClaw was the first host path.
- `pc/src/bridge/harness/` and `pc/src/bridge/AgentHarness.ts`: host harness router for OpenClaw, Hermes, and Codex. This is the key source for model/session namespacing.
- `pc/src/dispatcher/`: adapter boundary for legacy `user_request` and realtime delegated tasks. `OpenClawSessionClient`, `HermesSessionClient`, and `CodexAppServerClient` all exist behind this boundary.
- `pc/src/mcp/`: `android-phone` MCP server and phone tool schemas. Keep these aligned with Android command execution.
- `pc/src/protocol/messages.ts`: canonical TypeScript source for WebSocket message validation, phone commands, MCP tool-name mapping, realtime tool names, model IDs, and reasoning options.
- `android/`: Kotlin Android app. Package/application id is `dev.openclawagent`; source namespace is `dev.androidagent`.
- `android/app/src/main/java/dev/androidagent/net/`: bridge WebSocket client and inbound JSON parsing.
- `android/app/src/main/java/dev/androidagent/accessibility/`: Android command executor and screen observation.
- `android/app/src/main/java/dev/androidagent/overlay/`, `chat/`, `agentchat/`, `ui/`: bubble, panel, timeline, model/session controls, markdown/status rendering.
- `android/app/src/main/java/dev/androidagent/voice/`: OpenAI Realtime WebRTC state, tool-call accumulation, transcript normalization, tool-result events, transcription helpers.
- `android/app/src/main/java/dev/androidagent/localmodel/`: LiteRT-LM local mode, local sessions, local tool specs, app-private workspace tooling, Termux placeholder policy.
- `docs/`: setup, pairing, protocol, safety, OpenClaw migration notes, Codex docs, demo notes, and limitations. Some docs are still OpenClaw-skewed; verify source before copying that framing into new agent guidance.

## Commands
- PC install: `cd pc && npm install`
- PC type check: `cd pc && npm run check`
- PC build: `cd pc && npm run build`
- PC tests: `cd pc && npm test`
- PC bridge: `cd pc && npm run bridge` loads `pc/.env.local` via `tsx --env-file-if-exists`; shell env vars override it.
- PC MCP server: `cd pc && npm run mcp`
- Register phone MCP with OpenClaw: `cd pc && npm run openclaw:mcp`
- Register phone MCP with Hermes: `cd pc && npm run hermes:mcp`
- Bridge health: `cd pc && npm run phone:health`
- USB test setup: `cd pc && npm run phone:usb`
- Tailscale pairing URL: `cd pc && npm run phone:tailscale`
- Demo text request: `cd pc && npm run demo:agent -- "Open Settings"`
- Demo direct command: `cd pc && npm run demo:open-settings`
- Legacy Codex schemas: `cd pc && npm run codex:schemas`
- Android build/test from repo root when Gradle is available: `cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Android Studio remains acceptable for build/install/debug because local Gradle availability can vary.

## Runtime Configuration
- `pc/.env.local` is gitignored and is the normal persistent PC config. `pc/.env.example` documents the shape.
- Required bridge secret: `PHONE_AGENT_TOKEN`. Generate a strong value, save the exact same value in Android settings, and never commit real tokens.
- Bridge defaults: `PHONE_AGENT_HOST=0.0.0.0`, `PHONE_AGENT_PORT=8788`, `PHONE_AGENT_DEFAULT_DEVICE=openclaw-agent`, `PHONE_AGENT_BRIDGE_URL=http://127.0.0.1:8788`.
- OpenClaw Gateway chat defaults: `OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789`, `OPENCLAW_CHAT_AGENT_ID=main`, `OPENCLAW_CHAT_SESSION_KEY=agent:main:explicit:open-claw-agent`. Start Gateway with `openclaw gateway start`.
- Gateway auth can come from `OPENCLAW_GATEWAY_TOKEN`, `OPENCLAW_GATEWAY_PASSWORD`, `OPENCLAW_CONFIG_PATH`, or `~/.openclaw/openclaw.json`.
- Hermes appears in Android model selection only when `HERMES_API_KEY` is set. Relevant env: `HERMES_API_BASE_URL`, `HERMES_MODEL`, `HERMES_DEFAULT_SESSION_ID`, `HERMES_RUN_TIMEOUT_SECONDS`.
- Codex appears as a host harness through the bundled app-server adapter. Relevant env: `CODEX_APP_SERVER_COMMAND`, `CODEX_AGENT_CWD`; generated schemas remain optional inspection output only.
- Local models are Android-side settings, not PC env. Import a `.litertlm` file, choose CPU/GPU/NPU, and switch **Run on** to **Local phone**. The local model id is `local-litertlm`.
- Realtime voice needs an OpenAI key from either PC `OPENAI_API_KEY` or Android settings. Bridge-side knobs: `OPENAI_REALTIME_MODEL`, `OPENAI_REALTIME_VOICE`, `OPENAI_WEB_SEARCH_MODEL`.
- Android stores config in `dev.openclawagent` shared prefs named `open_claw_agent_config`; the saved token must match `PHONE_AGENT_TOKEN` exactly.

## Protocol And Contract Rules
- Keep WebSocket shapes aligned across `pc/src/protocol/messages.ts`, Android handling in `PhoneWebSocketClient.kt`, realtime/voice controllers, and `docs/protocol.md`.
- Host and local chat both use `chat.*` messages. Normal overlay submissions use `chat.send`, not legacy `user_request`. Keep `chat.models`, `chat.sessions`, `chat.history`, `chat.state`, `chat.delta`, `chat.final`, `chat.error`, `chat.tool_event`, `chat.tools`, `chat.commands`, and `chat.usage` compatible on both sides.
- Harness model IDs are selected by Android. OpenClaw can use bare model IDs for backward compatibility; Hermes and Codex use namespaced IDs such as `hermes:<model>` and `codex:<model>`. Local mode uses `local-litertlm` and local session keys. Preserve `harnessId`, `harnessLabel`, and `modelId` metadata where emitted.
- Phone command names must stay aligned across `PHONE_COMMANDS`, `MCP_PHONE_TOOL_NAMES`, `pc/src/mcp/tools.ts`, Android `AccessibilityCommandExecutor.kt`, and docs.
- Realtime tool names must stay aligned across `REALTIME_TOOL_NAMES`, `OpenAiRealtimeClient.ts`, `RealtimeTaskManager.ts`, Android voice parsing/accumulation, and `docs/protocol.md`.
- Model and reasoning options must stay aligned across `pc/src/protocol/messages.ts` and `android/app/src/main/java/dev/androidagent/AgentModelOptions.kt`.
- The default phone-control safety prompt must stay aligned between `pc/src/dispatcher/safetyPrompt.ts` and `android/app/src/main/java/dev/androidagent/DefaultSystemPrompt.kt`.
- `pc/src/generated/codex-app-server/` is local, gitignored inspection output. Do not hand-edit or commit it. Regenerate with `cd pc && npm run codex:schemas` only while the legacy adapter remains.

## Bridge, HTTP, And Pairing Invariants
- Android registration is two-step. TCP/WS open is not connected. `PhoneWebSocketClient` marks `connected=true` only after an `agent_status` text that starts with `"Registered "`. The 5 s watchdog cancels and reconnects if that ack does not arrive. Preserve this behavior or update both ends together.
- Token failure closes with `4001 invalid token`; Android backs off and tells the user to re-pair.
- The bridge exposes `/health` without auth and protected `/api/*` routes with `Authorization: Bearer $PHONE_AGENT_TOKEN` or `X-Phone-Agent-Token: $PHONE_AGENT_TOKEN`.
- Current protected routes include phones, audit recent/active, default phone command dispatch, legacy user request, pets, pet spritesheets, and agent stop. Keep tests updated when adding routes.
- For off-LAN use, expose only the phone-facing bridge through Tailscale. Keep OpenClaw Gateway, Hermes API, Codex app-server, and similar host-agent transports on localhost or trusted private networks; do not suggest public tunnels as the default.

## Android Command Rules
- Screenshots and coordinate taps use full-screen physical pixels, including system bars. For points chosen from scaled screenshots, use `tap_normalized` and preserve screenshot width/height metadata through the protocol.
- Observation node IDs are ephemeral and valid only until the next observation. Prefer `viewIdResourceName`, visible text, or content description as stable selectors.
- `open_app` supports package names and fuzzy visible-label matching; package names are more reliable.
- `type_text` tries Accessibility `ACTION_SET_TEXT`, then clipboard paste, and restores or clears the previous clip afterward.
- `submit_text` tries IME enter and then a normalized keyboard fallback tap.
- Agent chrome may hide during taps, swipes, and screenshots outside the Android Agent app. This must not stop active turns, realtime voice, foreground service state, or notification stop actions.
- When automating the Android Agent app itself, canonical resource IDs currently live under `dev.openclawagent:id/...`; see `docs/protocol.md`. Names may lag the product rename.

## Safety
- Preserve user confirmation before purchases, final order placement, payments, money movement, crypto transactions, account/security/privacy changes, app installs/deletions, deleting data, sharing credentials, or other hard-to-undo actions.
- Do not send chat, SMS, social, or email messages unless the user explicitly requested that exact send. Ask for confirmation when recipient, content, account, or intent is ambiguous or sensitive.
- Use `phone_ask_user_confirmation` for high-risk phone actions and keep Android's confirmation overlay in the loop.
- Biometric, fingerprint, passkey, password-manager, and OS credential prompts must remain manual. Stop and ask the user to handle them.
- Primary host `chat.*` messages rely on the selected harness and its configured tools for ordinary session policy. Explicit phone tasks, legacy `user_request`, and realtime delegated tasks are wrapped with the bridge/dispatcher phone-safety context.


## Testing Expectations
- For PC bridge/protocol/dispatcher changes, run `cd pc && npm run check && npm test`.
- For Android protocol, chat reducer, overlay, local model, voice, or transcription changes, run `cd android && ./gradlew :app:testDebugUnitTest` when Gradle is available.
- For Android build-impacting changes, run `cd android && ./gradlew :app:assembleDebug` when possible.
- Add or update focused tests when touching queueing, interrupts/stops, steering, harness routing, `chat.*` rendering, realtime tool output, transcript normalization, transcription audio, local tool parsing, or phone command mappings.
- Manually verify pairing/registration, `/health`, accessibility permission, overlay behavior, screenshot metadata, and confirmation overlay for cross-device behavior changes.

## Change Discipline
- Keep changes narrowly scoped. Prefer clear local code over broad abstractions; this is still a prototype.
- Do not commit real secrets, LAN URLs, device IDs, saved Android API keys, or machine-specific config.
- Do not regress Host mode while working on Local phone mode, do not regress one host harness while editing another, and do not force general agent tasks into phone-control prompts.
- Update `docs/protocol.md` for message or command-shape changes, `docs/setup.md`/`docs/pairing.md` for setup changes, `docs/safety.md` for policy-path changes, and `docs/open-claw-migration-plan.md` for OpenClaw architecture changes until final architecture docs replace it.
