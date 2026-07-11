# Host Path Ownership Inventory

Host files belong to one of three explicit owners. Packaged code and assets are immutable under `HostPaths.installRoot`; bridge-owned mutable state is private under `HostPaths.dataRoot`; user-selected agent working directories are explicit workspaces and are never inferred as persistence roots.

| Data | Previous owner | Canonical owner |
| --- | --- | --- |
| Package entrypoints, generated prompt, MCP launch scripts, pet assets | package/install tree | `installRoot` (read-only) |
| Bridge config and generated pairing token | platform config directory or `PHONE_AGENT_CONFIG_PATH` | `dataRoot/config.json` (private) |
| Codex, Hermes, OpenCode, Pi, and Devin local session catalogs | CWD `state/` or config sibling | `sessionsRoot/<harness>-sessions.json` |
| Audit JSONL | config sibling `audit/` | `auditRoot` with rotation and retention |
| Host attachment/blob payloads | feature-specific | `blobRoot` (the canonical root consumed by the blob store) |
| Adapter discovery/model cache owned by Lynk | feature-specific | `cacheRoot` |
| Atomic-write and transient bridge files | destination sibling or OS temp | `tempRoot` or a private destination-sibling temporary file |
| LaunchAgent/systemd/autostart registration | OS service registry directory | OS-owned service path; points at immutable entrypoint and private config |
| Agent repository/project directory | prior package/CWD fallback | explicit configured workspace (`workspaceRoot`/harness `*AgentCwd`) |
| Screenshots explicitly requested by the MCP CLI | `../captures` from CWD | caller override or private cache/capture root |

Known legacy session sources are `state/{codex,hermes,opencode,pi}-sessions.json` relative to the old launch CWD and `devin-sessions.json` beside the config. Migration copies and durably verifies these files before marking migration complete; legacy sources are retained as backups.

On Unix-like systems bridge directories are enforced as `0700` and sensitive files as `0600`. Windows applies the same owner-oriented layout and file modes on a best-effort basis; Node's mode bits do not configure a complete Windows DACL, so administrators must use an appropriately private account/profile or managed ACL policy.
