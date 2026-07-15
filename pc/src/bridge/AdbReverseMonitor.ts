import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);
const DEFAULT_INTERVAL_MS = 10_000;

export interface AdbReverseMonitor {
  stop(): void;
}

export interface AdbReverseMonitorOptions {
  port: number;
  enabled?: boolean;
  intervalMs?: number;
  adbPath?: string;
}

export function startAdbReverseMonitor(options: AdbReverseMonitorOptions): AdbReverseMonitor {
  const enabled = options.enabled ?? process.env.PHONE_AGENT_ADB_REVERSE === "1";
  if (!enabled) {
    return { stop: () => undefined };
  }

  const adbPath = options.adbPath ?? process.env.ADB ?? "adb";
  const configuredInterval = Number.parseInt(process.env.PHONE_AGENT_ADB_REVERSE_INTERVAL_MS ?? "", 10);
  const intervalMs = options.intervalMs ?? (Number.isFinite(configuredInterval) && configuredInterval > 0 ? configuredInterval : DEFAULT_INTERVAL_MS);
  let stopped = false;
  let timer: NodeJS.Timeout | undefined;
  let warnedUnavailable = false;

  const run = async () => {
    if (stopped) {
      return;
    }
    try {
      const { stdout } = await execFileAsync(adbPath, ["devices"], { timeout: 5_000 });
      const devices = parseAdbDevices(stdout);
      await Promise.all(devices.map((serial) => ensureReverse(adbPath, serial, options.port)));
      warnedUnavailable = false;
    } catch (error) {
      if (!warnedUnavailable) {
        warnedUnavailable = true;
        console.warn(`[adb] reverse monitor unavailable: ${error instanceof Error ? error.message : String(error)}`);
      }
    } finally {
      if (!stopped) {
        timer = setTimeout(run, intervalMs);
      }
    }
  };

  void run();
  return {
    stop() {
      stopped = true;
      if (timer) {
        clearTimeout(timer);
      }
    }
  };
}

export function parseAdbDevices(output: string): string[] {
  return output
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line && !line.startsWith("List of devices"))
    .map((line) => line.split(/\s+/))
    .filter((parts) => parts[1] === "device")
    .map((parts) => parts[0])
    .filter((serial): serial is string => Boolean(serial));
}

async function ensureReverse(adbPath: string, serial: string, port: number): Promise<void> {
  await execFileAsync(adbPath, ["-s", serial, "reverse", `tcp:${port}`, `tcp:${port}`], { timeout: 5_000 });
}
