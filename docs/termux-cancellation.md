# Termux command cancellation

Lynk treats Termux as an external execution service. Cancelling the Android coroutine or dropping its result `PendingIntent` does not stop a command already running under the Termux UID. Lynk therefore blocks `termux_command` until it has proved that the tracked cancellation protocol works on the current Termux installation.

## Requirements

- Install Termux from F-Droid or the official Termux GitHub releases, not the divergent Play Store build.
- Open Termux once and let it install its bootstrap packages.
- Set `allow-external-apps=true` in `~/.termux/termux.properties`.
- Grant Lynk the `com.termux.permission.RUN_COMMAND` additional permission.
- Exempt Termux from device-specific background restrictions if RUN_COMMAND startup is unreliable.

Termux documents the supported command/result extras and the result `PendingIntent` contract in its official [RUN_COMMAND Intent documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent). That contract does not expose a remote cancellation handle, so Lynk uses a second RUN_COMMAND request for control.

## Protocol

For every command, Lynk generates a random 128-bit execution ID and a separate random 128-bit nonce. Both are strict lowercase hexadecimal values and are passed as separate `String[]` arguments; user command text is also a separate argument and is never interpolated into control shell source.

1. A constant wrapper launches `setsid bash -lc <user-command>` as a new process group. Its child stops itself with `SIGSTOP` before executing user text.
2. While the child is stopped, the wrapper atomically records `nonce PID PGID /proc-start-time` under Termux-private `$PREFIX/tmp/lynk-executions`.
3. Lynk sends a start-control RUN_COMMAND. It verifies the nonce, PID start time, and current PGID before writing the nonce-bound continue marker. Only then does the wrapper send `SIGCONT`.
4. Cancellation writes a nonce-bound cancel marker and verifies the same PID start time and PGID before sending `SIGKILL` to `-PGID`. It polls until the process group no longer exists and returns a strict `LYNK_KILL` marker.
5. Cancel-before-start is safe: the cancel marker is written before a child can be continued, and the wrapper consumes it without running user text.

PID start-time verification prevents a stale coordination file from signalling a reused PID. A private coordination directory, strict tokens, opaque argv, and fixed scripts prevent path and shell injection through execution metadata.

## Fail-closed preflight

The first Termux developer command in a runner launches a harmless `sleep 30` child in the stopped state and requests `kill-running`. Lynk enables commands only after receiving a successful, well-formed:

```text
LYNK_KILL verified <execution-id> <pid> <pgid> <start-time>
```

The verified capability is cached and shared by concurrent commands. Timeout, malformed output, nonzero Termux result, identity mismatch, or kill failure leaves the capability unavailable. Lynk then launches no user command and returns:

- `status: cancellation_unavailable`
- `cancellationVerified: false`
- a truthful failure detail

Timeout results likewise distinguish verified termination from an unverified command that may still be running. Session stop, new-session replacement, realtime stop, and service destruction all request the same idempotent kill operation. Late Termux results cannot settle the caller again.

## Device verification

Run from the repository root with one Android device connected:

```bash
android/scripts/verify-termux-cancellation-device.sh
```

Set `ADB_SERIAL=<serial>` when multiple devices are attached. The script uses a unique file in `Download`, asks you to start and stop a safe heartbeat command through Lynk, and verifies that its size remains unchanged for five seconds after stop. It removes only that unique probe file after success; set `KEEP_PROBE_FILE=1` to retain it.

This manual probe remains required before claiming on-device acceptance because JVM fake-service tests cannot validate a particular Termux build, OEM background-service behavior, or Android permission state. If the probe fails, keep Local developer tools disabled and collect both Lynk and Termux logs.

## Scope

The protocol guarantees cancellation for the tracked process group. Commands deliberately written to evade ownership—for example by clearing identity state and escaping into an unrelated daemon—are outside the supported `termux_command` contract. Do not use this tool as a general daemon supervisor.
