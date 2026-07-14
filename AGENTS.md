# AGENTS.md

## Product Shape

- This repo is currently **Lynk**: an Android bubble/chat/voice endpoint that can route work to host-side agents or an on-device local model. Do not describe the product as OpenAgent or OpenClaw-only even though many classes and docs still carry older migration names.
- The supported backends are **OpenClaw**, **Hermes**, **Codex**, **Local LiteRT-LM**, and **Local GGUF** (via `llama.cpp`). Android phone control is an optional tool target across these paths, not the default purpose of every request.
- The app has two user modes:
  - **Host bridge**: Android connects to the PC bridge over `/phone`; the bridge exposes a harness router for OpenClaw, Hermes, and Codex chat sessions.
  - **Local phone**: Android runs an imported `.litertlm` or `.gguf` model on-device, emits the same `chat.*` timeline events locally, and can call Android/local app-private tools.
- OpenClaw is currently the default host harness and has the most Gateway-specific code. Hermes and Codex are real supported harnesses, not merely documentation footnotes. `PHONE_AGENT_USE_FALLBACK=1` is a deliberate bridge fallback path for testing.
- Local phone mode uses `local-litertlm` for LiteRT-LM imports and `llama.cpp` for GGUF imports, supports Android phone tools and app-private workspace tools, and gates write/Termux developer tools behind settings. It is not yet a full desktop shell/git/build environment.

## Repo Map

- `pc/`: Node 24+, ESM, strict TypeScript bridge. Uses `zod`, `ws`, `tsx`, and the MCP SDK.
- `pc/src/bridge/`: WebSocket registration, HTTP APIs, host chat bridge, harness routing, audit/status, realtime session setup, task queueing, web search, pet catalog. Some files are still named `OpenClaw*` because OpenClaw was the first host path.
- `pc/src/bridge/harness/` and `pc/src/bridge/AgentHarness.ts`: host harness router for OpenClaw, Hermes, and Codex. This is the key source for model/session namespacing.
- `pc/src/dispatcher/`: adapter boundary for legacy `user_request` and realtime delegated tasks. `OpenClawSessionClient`, `HermesSessionClient`, and `CodexAppServerClient` all exist behind this boundary.
- `pc/src/mcp/`: `android-phone` MCP server and phone tool schemas. Keep these aligned with Android command execution.
- `pc/src/protocol/messages.ts`: canonical TypeScript source for WebSocket message validation, phone commands, MCP tool-name mapping, realtime tool names, model IDs, and reasoning options.
- `android/`: Kotlin Android app. Package/application id is `app.lynk`; source namespace is `dev.androidagent`.
- `android/app/src/main/java/dev/androidagent/net/`: bridge WebSocket client and inbound JSON parsing.
- `android/app/src/main/java/dev/androidagent/accessibility/`: Android command executor and screen observation.
- `android/app/src/main/java/dev/androidagent/overlay/`, `chat/`, `agentchat/`, `ui/`: bubble, panel, timeline, model/session controls, markdown/status rendering.
- `android/app/src/main/java/dev/androidagent/voice/`: OpenAI Realtime WebRTC state, tool-call accumulation, transcript normalization, tool-result events, transcription helpers.
- `android/app/src/main/java/dev/androidagent/localmodel/`: LiteRT-LM and GGUF local mode, local sessions, local tool specs, app-private workspace tooling, Termux placeholder policy.
- `docs/`: setup, pairing, protocol, safety, OpenClaw migration notes, Codex docs, demo notes, and limitations. Some docs are still OpenClaw-skewed; verify source before copying that framing into new agent guidance.

## Commands

- PC install: `cd pc && npm install`
- Global bridge install after npm publish: `npm install -g lynk-bridge`
- Global bridge command: `lynk-bridge`
- Global host CLI: `lynk-bridge-host pairing --qr`, `lynk-bridge-host refresh`, `lynk-bridge-host mcp`, `lynk-bridge-host install-service`, `lynk-bridge-host uninstall-service`, `lynk-bridge-host service-status`, `lynk-bridge-host diagnostics`
- Global MCP server command: `lynk-bridge-mcp`
- PC host integration refresh: `cd pc && npm run host:refresh`
- PC host pairing payload: `cd pc && npm run host:pairing`
- PC host pairing QR: `cd pc && npm run host:pairing:qr`
- PC host service plan: `cd pc && npm run host:service-plan`
- PC host install/startup service: `cd pc && npm run host:install-service`
- PC host uninstall startup service: `cd pc && npm run host:uninstall-service`
- PC host service status: `cd pc && npm run host:service-status`
- PC host diagnostics: `cd pc && npm run host:diagnostics`
- PC type check: `cd pc && npm run check`
- PC build: `cd pc && npm run build`
- PC tests: `cd pc && npm test`
- PC bridge: `cd pc && npm run bridge` loads `pc/.env.local` via `tsx --env-file-if-exists`; shell env vars override it.
- PC MCP server: `cd pc && npm run mcp`
- Register available phone MCP integrations: `cd pc && npm run host:mcp`
- Register phone MCP with OpenClaw: `cd pc && npm run openclaw:mcp`
- Register phone MCP with Hermes: `cd pc && npm run hermes:mcp`
- Register phone MCP with Codex: `cd pc && npm run codex:mcp`
- Bridge health: `cd pc && npm run phone:health`
- USB test setup: `cd pc && npm run phone:usb`
- Tailscale pairing URL: `cd pc && npm run phone:tailscale`
- Demo text request: `cd pc && npm run demo:agent -- "Open Settings"`
- Demo direct command: `cd pc && npm run demo:open-settings`
- Legacy Codex schemas: `cd pc && npm run codex:schemas`
- Android build/test from repo root when Gradle is available: `git submodule update --init --recursive && cd android && ./gradlew :app:assembleDebug :app:testDebugUnitTest`
- Android Studio remains acceptable for build/install/debug because local Gradle availability can vary.
- npm package metadata for the host bridge lives in `pc/package.json`; keep it publishable as `lynk-bridge` with Node 24+ engines, built `dist/` files, installer scaffolding, bin entries for bridge/host CLI/MCP server, and a guarded global-install `postinstall` that registers the bridge to start at login.

## Runtime Configuration

- The host bridge auto-creates a persistent config with a strong token on first run. Config paths are macOS `~/Library/Application Support/Android Agent Bridge/config.json`, Windows `%ProgramData%\\AndroidAgentBridge\\config.json`, and Linux `~/.config/android-agent-bridge/config.json`.
- `pc/.env.local` is gitignored and is the normal development override file. `pc/.env.example` documents the shape. Shell env vars override both the persistent host config and `.env.local`.
- Required bridge secret: `PHONE_AGENT_TOKEN` or the generated host config `phoneAgentToken`. Save the exact same value in Android settings or use `npm run host:pairing:qr`, and never commit real tokens.
- Bridge defaults: `PHONE_AGENT_HOST=0.0.0.0`, `PHONE_AGENT_PORT=8788`, `PHONE_AGENT_DEFAULT_DEVICE=openclaw-agent`, `PHONE_AGENT_BRIDGE_URL=http://127.0.0.1:8788`.
- OpenClaw Gateway chat defaults: `OPENCLAW_GATEWAY_URL=ws://127.0.0.1:18789`, `OPENCLAW_CHAT_AGENT_ID=main`, `OPENCLAW_CHAT_SESSION_KEY=agent:main:explicit:open-claw-agent`. Start Gateway with `openclaw gateway start`.
- Gateway auth can come from `OPENCLAW_GATEWAY_TOKEN`, `OPENCLAW_GATEWAY_PASSWORD`, `OPENCLAW_CONFIG_PATH`, or `~/.openclaw/openclaw.json`.
- Hermes appears in Android model selection only when `HERMES_API_KEY` is set. Relevant env: `HERMES_API_BASE_URL`, `HERMES_MODEL`, `HERMES_DEFAULT_SESSION_ID`, `HERMES_RUN_TIMEOUT_SECONDS`.
- Codex appears as a host harness through the bundled app-server adapter. Relevant env: `CODEX_APP_SERVER_COMMAND`, `CODEX_AGENT_CWD`; generated schemas remain optional inspection output only.
- Local models are Android-side settings, not PC env. Import a `.litertlm` or `.gguf` file and switch **Run on** to **Local phone**. LiteRT-LM uses CPU/GPU/NPU; GGUF uses `llama.cpp` with ARM CPU fallback and optional Vulkan. Both preserve the shipped `local-litertlm` protocol model id. Vulkan is disabled for Snapdragon SM8850 after reproducible Q1_0 device-loss errors on Adreno 840.
- Realtime voice needs an OpenAI key from either PC `OPENAI_API_KEY` or Android settings. Bridge-side knobs: `OPENAI_REALTIME_MODEL`, `OPENAI_REALTIME_VOICE`, `OPENAI_WEB_SEARCH_MODEL`.
- Android stores config in `app.lynk` shared prefs named `open_claw_agent_config`; the saved token must match `PHONE_AGENT_TOKEN` exactly.

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
- Current protected routes include phones, pairing, diagnostics, integrations, harness health/readiness, audit recent/active, default phone command dispatch, legacy user request, pets, pet spritesheets, and agent stop. Keep tests updated when adding routes.
- Host bridge installer/source flows should use `npm run host:refresh` for integration discovery, `npm run host:install-service` to register/start the bridge at login, `npm run host:service-status` to verify startup, `npm run host:pairing` or `npm run host:pairing:qr` for Android setup, `npm run host:service-plan` only when showing commands instead of applying them, and `npm run host:mcp` only for optional phone-control MCP registration.
- For off-LAN use, expose only the phone-facing bridge through Tailscale. Keep OpenClaw Gateway, Hermes API, Codex app-server, and similar host-agent transports on localhost or trusted private networks; do not suggest public tunnels as the default.

## Code Quality Bar

- Start changes by finding the canonical owner for the behavior. Protocol shapes belong in `pc/src/protocol/messages.ts` and matching Android parsers/renderers; phone commands belong in the phone command schema/executor path; harness routing belongs in `pc/src/bridge/harness/` and `AgentHarness.ts`; local model behavior belongs under `android/app/src/main/java/dev/androidagent/localmodel/`.
- Prefer the structural move that deletes concepts, branches, or helpers over one that merely rearranges them. If a cleaner framing can make special cases disappear, take that path instead of layering another flag or mode into an already busy flow.
- Do not scatter feature-specific conditionals through shared code. Repeated `if (harness/local/realtime/voice/legacy)` checks usually mean a missing model, policy helper, dispatcher, or state boundary.
- Keep abstractions direct and earned. Avoid thin wrappers, pass-through services, speculative generic helpers, and "magic" inference that hides simple data-shape assumptions.
- Keep type boundaries explicit. In TypeScript, prefer `zod`-validated contracts, discriminated unions, exhaustive switches, and narrow interfaces over `any`, broad `unknown`, casts, nullable modes, or optional fields that are not truly optional. In Kotlin, prefer sealed models, data classes, and explicit state over nullable flags or loosely-shaped maps.
- Keep orchestration legible and state changes coherent. Independent async work can run in parallel when that makes the flow clearer, but related state updates should be applied atomically enough that failures do not leave chat, session, voice, or phone-command state half-updated.
- Avoid file sprawl. Treat any file approaching 1,000 lines as a decomposition candidate, and do not push a file from under 1,000 lines to over 1,000 lines without a strong structural reason. Extract focused modules, reducers, render helpers, or command/policy objects before adding another large section to a busy file.
- Make compatibility choices deliberately. Preserve compatibility for shipped behavior, persisted data, public HTTP/WebSocket contracts, and Android app settings; do not add shims for unshipped branch-local experiments when replacing the model is cleaner.
- Tests should protect the boundary being changed. For protocol, routing, reducer, queueing, local tool parsing, realtime transcript/tool output, and phone command mappings, add focused tests around the contract rather than only testing the new branch.
- Before finalizing a non-trivial change, re-read the diff as a code quality review: did it reduce or increase the number of concepts a maintainer must hold in their head; did it add ad-hoc branching; did it put logic in the owning layer; and is there a simpler restructuring that keeps behavior the same?

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
