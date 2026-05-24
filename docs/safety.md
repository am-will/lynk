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

Biometric, fingerprint, passkey, password-manager, and OS credential prompts are always manual. The agent should stop and ask the user to handle them.
