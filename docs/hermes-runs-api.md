# Hermes Runs API Contract

Lynk can use Hermes in two modes:

- **Default setup**: if the `hermes` CLI is on `PATH`, Lynk can answer chat turns through `hermes -z` without any extra server.
- **Runs API setup**: for richer session history, active-turn steering, SSE deltas, and adapter-controlled cancellation, point Lynk at a Hermes-compatible runs/SSE API.

A generic OpenAI-compatible `/chat/completions` proxy is not the runs API. If you want Lynk to use an HTTP adapter instead of CLI fallback, expose the endpoints below.

Set `HERMES_API_BASE_URL` to the API root, usually `http://127.0.0.1:8642/v1`, and set `HERMES_API_KEY`. Every runs API request uses `Authorization: Bearer <HERMES_API_KEY>` and JSON bodies unless noted.

## Required Endpoints

- `GET /health` returns JSON with `ok: true` when the backend is usable. `{"status":"ok"}` is not sufficient for Lynk status rendering.
- `GET /models` returns `{ "models": [...] }` or `{ "data": [...] }`. Each model should include `id` or `name`; `provider`, `context_window` or `contextWindow`, and display labels are optional but recommended.
- `POST /runs` creates a run and returns `run_id` or `id`. The request body includes:

```json
{
  "input": "User text",
  "session_id": "hermes-agent-phone",
  "model": "provider:model-or-model",
  "instructions": "Optional previous conversation context",
  "attachments": [],
  "service_tier": "priority"
}
```

`attachments` uses Lynk's `ChatAttachment` shape from `docs/protocol.md`. `service_tier` is omitted unless fast mode is enabled; the only value Lynk currently sends is `"priority"`.

- `GET /runs/{run_id}` returns run status for diagnostics and recovery.
- `GET /runs/{run_id}/events` streams Server-Sent Events. Lynk accepts JSON event payloads for text deltas, final output, errors, cancellation, and tool events.
- `POST /runs/{run_id}/stop` requests cancellation.
- `GET /api/sessions` lists previous sessions for the Android session picker.
- `GET /api/sessions/{session_id}/messages` returns message history for a selected session.
- `GET /capabilities` is optional but used to populate tool/capability metadata when available.

## Model IDs And Providers

Android selects Hermes models as `hermes:<model>`. The bridge strips the `hermes:` harness prefix before calling `POST /runs`.

For multi-provider Hermes deployments, return provider metadata from `/models`. If a model row contains `provider: "xai"` and `id: "grok-4.3"`, Lynk presents and sends the raw Hermes model id as `xai:grok-4.3`. A shim can split that value before forwarding to the underlying provider. If the model name alone is globally routable in your backend, you can omit `provider`.

The bridge merges live `/models` results with local `~/.hermes/config.yaml` discovery. The API list determines what is available; local config only enriches context windows, reasoning options, and fallback entries.

If `/health` is unreachable but the `hermes` CLI is installed, Lynk reports Hermes health as CLI fallback mode and uses local sessions. In that mode, active-turn steering and remote session sync are limited because the standard CLI does not expose Lynk's run lifecycle endpoints.

## Local Config And Profiles

`lynk-bridge-host mcp` writes Hermes MCP config to `HERMES_CONFIG_PATH` when set, otherwise `$HERMES_HOME/config.yaml` or `~/.hermes/config.yaml`. Multi-profile Hermes installs commonly load `~/.hermes/profiles/<name>/config.yaml`; set `HERMES_CONFIG_PATH` to that active profile before running MCP registration.

Global npm installs generate MCP config that launches the shipped `lynk-bridge-mcp` dist entrypoint. Source checkouts fall back to `tsx src/mcp/androidPhoneServer.ts` when `dist/` is not built.
