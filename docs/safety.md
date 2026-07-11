# Safety

Safety policy ownership depends on the path:

- Primary `chat.*` messages stream through the installed OpenClaw Gateway session. That session and its configured tools own system-level policy enforcement for ordinary Gateway chat; the bridge does not currently inject the Android default system prompt into this path.
- Explicit phone tasks, legacy `user_request` requests, and realtime delegated tasks are wrapped by the bridge/dispatcher with the OpenAgent phone-safety context before reaching the active session adapter.
- The Android **System prompt** setting is saved locally and sent with legacy/realtime request metadata. It mirrors the canonical phone-control policy for paths that consume that field.

The active session adapter must call `phone_ask_user_confirmation` before:

- Purchases or final order placement
- Payments or money movement
- Crypto transactions
- Account, security, or privacy changes
- App installs or deletions
- Deleting data
- Sharing credentials
- Sending chat, SMS, social, or email messages unless the user explicitly requested the exact send

The Android app displays a confirmation overlay above the current app. If the user cancels, the command result has `ok: false` and `error: "User did not confirm"`.

## Devin ACP permissions

Devin runs as a local stdio child of the bridge. The ACP process, CLI credentials, and its stdio transport are never exposed to Android or opened as a network listener. Only the token-authenticated Lynk `/phone` bridge is phone-facing; for remote use, expose that bridge through a trusted private network such as Tailscale and keep Devin host-local.

Lynk leaves the ACP session in Devin's normal mode and does not request an unrestricted/bypass mode. The tested CLI's process-level permission default is `auto`; Lynk does not add `--permission-mode dangerous`. When Devin requests permission, the bridge shows the request through the existing Android tool-action UI and forwards only the exact option the user selected. Option IDs are bound to the originating run and session, and stale, cross-session, or fabricated replies are rejected. Cancelling or ending a run cancels outstanding permission requests.

The UI may offer ACP choices such as allow once, allow always, or reject when Devin itself supplies them. Lynk preserves those labels and semantics rather than silently upgrading an allow-once choice or treating absence of a handler as approval. If no active matching run or no options exist, the request is cancelled.

Biometric, fingerprint, passkey, password-manager, and OS credential prompts are always manual. The agent should stop and ask the user to handle them.
