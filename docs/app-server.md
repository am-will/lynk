# Legacy Codex App-Server Dispatcher

This document describes the hand-written Codex app-server compatibility adapter that is still present in the prototype. It is useful for legacy testing, but OpenAgent's default path is the adapter that talks to an installed OpenClaw session on the remote PC. See `docs/open-claw-migration-plan.md`.

The dispatcher starts Codex app-server with:

```bash
codex app-server --listen stdio://
```

Override it with:

```bash
export CODEX_APP_SERVER_COMMAND="codex app-server --listen stdio://"
export CODEX_AGENT_CWD="$(pwd)"
```

The client performs:

1. `initialize`
2. `initialized`
3. `thread/start`
4. `turn/start` with the Android user request and safety instructions

If app-server integration fails at runtime, the dispatcher reports the exact error to the Android bubble. Set `PHONE_AGENT_USE_FALLBACK=1` to test the isolated fallback adapter deliberately.

`npm run codex:schemas` writes generated bindings under `pc/src/generated/codex-app-server`. That tree is local, gitignored, and excluded from `tsconfig.json`; runtime code must remain hand-written and must not import generated Codex schemas.
