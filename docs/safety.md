# Safety

Safety policy ownership depends on the path:

- Primary `chat.*` messages stream through the installed OpenClaw Gateway session. That session and its configured tools own system-level policy enforcement for ordinary Gateway chat; the bridge does not currently inject the Android default system prompt into this path.
- Explicit phone tasks, legacy `user_request` requests, and realtime delegated tasks are wrapped by the bridge/dispatcher with the OpenAgent phone-safety context before reaching the active session adapter.
- The Android **System prompt** setting is saved locally and sent with legacy/realtime request metadata. It mirrors the canonical phone-control policy for paths that consume that field.
- Network bridge endpoints require platform-verified TLS. Android sends a saved provider API key only over `wss://`, while the host-owned `OPENAI_API_KEY` is preferred and never traverses the phone bridge.

## Enforced phone-action approval

Android enforces approval below host and local model dispatch. The following commands are classified as sensitive and cannot execute without a capability, even when the model skips `phone_ask_user_confirmation`:

- Tap by node, pixels, or normalized coordinates
- Long press
- Type text or submit the focused field
- Capture a screenshot

To authorize one action, call `phone_ask_user_confirmation` with its exact `command` and exact `args`. The user sees a deterministic action summary rather than a model-controlled generic prompt. Approval returns an opaque capability that must be passed unchanged to the target tool. Capabilities are held only in Android memory, expire after 60 seconds, are scoped to one host client or local session/run, optionally bind the current observation, and are consumed before the action runs.

Missing, denied, expired, replayed, cross-owner, changed-command/arguments, and changed-observation approvals fail closed with distinct `authorization_*` errors. Observing or navigating after approval changes the observation and requires a new approval. Bridge disconnect, service destruction, and request-owner cancellation revoke applicable capabilities. Capability tokens are not written to host audit records.

All phone commands pass through one bounded serialized actor. A local stop, host task cancellation/timeout, bridge disconnect, or service shutdown cancels matching queued and active work and settles callers with `command_*` errors. Node actions additionally require the exact observation UUID and ordinal node ID returned together; stale generations are rejected instead of being refreshed into a different target.

Observation, wait, opening apps, scrolling, swiping, Back, Home, and Recents remain available without approval because they are deliberately classified as reversible observation/navigation. Biometric, fingerprint, passkey, password-manager, and OS credential prompts remain manual and cannot be approved through this capability flow.

Higher-level policy should still explain why an action is needed, especially for:

- Purchases or final order placement
- Payments or money movement
- Crypto transactions
- Account, security, or privacy changes
- App installs or deletions
- Deleting data
- Sharing credentials
- Sending chat, SMS, social, or email messages unless the user explicitly requested the exact send

The Android app displays the normalized action and optional rationale in a confirmation overlay above the current app. Cancellation issues no capability and returns `ok: false`.

## Devin ACP permissions

Devin runs as a local stdio child of the bridge. The ACP process, CLI credentials, and its stdio transport are never exposed to Android or opened as a network listener. Only the token-authenticated Lynk `/phone` bridge is phone-facing; for remote use, expose that bridge through a trusted private network such as Tailscale and keep Devin host-local.

Lynk leaves the ACP session in Devin's normal mode and does not request an unrestricted/bypass mode. The tested CLI's process-level permission default is `auto`; Lynk does not add `--permission-mode dangerous`. When Devin requests permission, the bridge shows the request through the existing Android tool-action UI and forwards only the exact option the user selected. Option IDs are bound to the originating run and session, and stale, cross-session, or fabricated replies are rejected. Cancelling or ending a run cancels outstanding permission requests.

The UI may offer ACP choices such as allow once, allow always, or reject when Devin itself supplies them. Lynk preserves those labels and semantics rather than silently upgrading an allow-once choice or treating absence of a handler as approval. If no active matching run or no options exist, the request is cancelled.
