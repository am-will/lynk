import { spawn } from "node:child_process";
import { resolveCommand, type CommandResolution } from "./CommandDiscovery.js";

export type DevinAuthStatus = "not_installed" | "not_authenticated" | "authenticated" | "timeout" | "spawn_error";

export interface DevinAuthProbeRunner {
  run(executable: string, args: string[], options: { timeoutMs: number }): Promise<{ exitCode: number | null; signal: NodeJS.Signals | null }>;
}

export interface ProbeDevinAuthStatusOptions {
  command?: string;
  timeoutMs?: number;
  runner?: DevinAuthProbeRunner;
  resolveCommand?: (command: string) => CommandResolution;
}

export interface DevinAuthProbeResult {
  status: DevinAuthStatus;
  path?: string;
}

export function createDefaultDevinAuthProbeRunner(): DevinAuthProbeRunner {
  return {
    run(executable, args, options) {
      return new Promise((resolve, reject) => {
        const child = spawn(executable, args, {
          stdio: "ignore"
        });
        let settled = false;
        const timer = setTimeout(() => {
          if (settled) {
            return;
          }
          settled = true;
          child.kill("SIGTERM");
          reject(new Error("timeout"));
        }, options.timeoutMs);

        const cleanup = (): void => {
          clearTimeout(timer);
        };

        child.on("error", (error) => {
          if (settled) {
            return;
          }
          settled = true;
          cleanup();
          reject(error);
        });

        child.on("close", (exitCode, signal) => {
          if (settled) {
            return;
          }
          settled = true;
          cleanup();
          if (signal) {
            reject(new Error(signal));
            return;
          }
          resolve({ exitCode, signal });
        });
      });
    }
  };
}

export async function probeDevinAuthStatus(options: ProbeDevinAuthStatusOptions = {}): Promise<DevinAuthProbeResult> {
  const command = options.command?.trim() || "devin acp";
  const resolve = options.resolveCommand ?? resolveCommand;
  const resolution = resolve(command);
  if (!resolution.available || !resolution.resolvedPath) {
    return { status: "not_installed" };
  }

  const runner = options.runner ?? createDefaultDevinAuthProbeRunner();
  const timeoutMs = options.timeoutMs ?? 10_000;
  try {
    const { exitCode, signal } = await runner.run(resolution.resolvedPath, ["auth", "status"], { timeoutMs });
    if (signal) {
      return { status: "spawn_error", path: resolution.resolvedPath };
    }
    if (exitCode === 0) {
      return { status: "authenticated", path: resolution.resolvedPath };
    }
    return { status: "not_authenticated", path: resolution.resolvedPath };
  } catch (error) {
    if (error instanceof Error && error.message === "timeout") {
      return { status: "timeout", path: resolution.resolvedPath };
    }
    return { status: "spawn_error", path: resolution.resolvedPath };
  }
}
