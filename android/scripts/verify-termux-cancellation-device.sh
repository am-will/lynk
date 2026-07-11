#!/usr/bin/env bash
set -euo pipefail

adb_command=(adb)
if [[ -n "${ADB_SERIAL:-}" ]]; then
  adb_command+=( -s "$ADB_SERIAL" )
fi

if ! command -v "${adb_command[0]}" >/dev/null 2>&1; then
  echo "adb is required." >&2
  exit 2
fi

"${adb_command[@]}" get-state >/dev/null

probe_id="$(date +%s)-$$"
remote_file="/sdcard/Download/lynk-termux-cancel-probe-${probe_id}.txt"
probe_command="umask 077; : > '$remote_file'; while :; do date +%s%N >> '$remote_file'; sleep 0.1; done"

echo "Safe Lynk Termux cancellation probe"
echo
echo "1. On the connected phone, open Lynk and switch Run on to Local phone."
echo "2. Enable Local developer tools."
echo "3. Send this request exactly:"
echo
echo "   Use termux_command to run exactly this command: $probe_command"
echo
echo "Lynk will first run its harmless STOP-gated kill preflight. If preflight cannot"
echo "verify process-group termination, the command must be blocked before this file exists."
echo
read -r -p "Press Enter after Lynk reports the Termux command is running... "

size_of_probe() {
  "${adb_command[@]}" shell stat -c %s "$remote_file" 2>/dev/null | tr -d '\r'
}

first_size="$(size_of_probe || true)"
if [[ ! "$first_size" =~ ^[0-9]+$ ]]; then
  echo "Probe file was not created. Check Lynk's truthful failure status and Termux setup." >&2
  exit 1
fi
sleep 1
growing_size="$(size_of_probe || true)"
if [[ ! "$growing_size" =~ ^[0-9]+$ ]] || (( growing_size <= first_size )); then
  echo "Probe file did not grow; the test command was not observed running." >&2
  exit 1
fi

echo
echo "4. Press Stop Turn in Lynk. Wait for Lynk to report whether termination was verified."
read -r -p "Press Enter after the stop result is visible... "

stable_size="$(size_of_probe)"
for _ in {1..10}; do
  sleep 0.5
  next_size="$(size_of_probe)"
  if [[ "$next_size" != "$stable_size" ]]; then
    echo "FAIL: the probe file continued growing after stop ($stable_size -> $next_size)." >&2
    echo "Leave Local developer tools disabled and capture Lynk/Termux logs." >&2
    exit 1
  fi
done

echo "PASS: the probe file remained stable at $stable_size bytes for 5 seconds after stop."
echo "Confirm Lynk reported cancellationVerified=true / verified termination."

if [[ "${KEEP_PROBE_FILE:-0}" == "1" ]]; then
  echo "Kept $remote_file"
else
  "${adb_command[@]}" shell rm -f "$remote_file"
  echo "Removed $remote_file"
fi
