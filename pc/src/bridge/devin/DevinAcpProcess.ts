import { spawn, type ChildProcess } from "node:child_process";
import { Readable, Writable } from "node:stream";
import { resolveCommand, commandArgs } from "../../host/CommandDiscovery.js";
import { DevinAcpError } from "./DevinAcpTypes.js";
import type { DevinAcpProcess, DevinAcpProcessExit, DevinAcpProcessFactory } from "./DevinAcpTypes.js";
import { sanitizeDevinCommand } from "./DevinAcpClientSupport.js";

export interface DevinAcpCommandResolution {
  readonly command: string;
  readonly executable: string;
  readonly resolvedPath: string;
  readonly args: readonly string[];
  readonly available: boolean;
}

export function resolveDevinAcpCommand(command: string): DevinAcpCommandResolution {
  const resolution = resolveCommand(command);
  const args = commandArgs(command);
  if (!resolution.available || !resolution.resolvedPath) {
    throw new DevinAcpError("missing_executable", `Devin ACP command not found: ${sanitizeDevinCommand(command)}`);
  }
  return {
    command: resolution.command,
    executable: resolution.executable,
    resolvedPath: resolution.resolvedPath,
    args,
    available: resolution.available
  };
}

export function createDefaultDevinAcpProcessFactory(): DevinAcpProcessFactory {
  return {
    create({ command, cwd }): DevinAcpProcess {
      const resolution = resolveDevinAcpCommand(command);
      return new NodeDevinAcpProcess(command, resolution.resolvedPath, resolution.args, cwd);
    }
  };
}

class NodeDevinAcpProcess implements DevinAcpProcess {
  private readonly child: ChildProcess;
  readonly stdin: WritableStream<Uint8Array>;
  readonly stdout: ReadableStream<Uint8Array>;
  readonly stderr: ReadableStream<Uint8Array>;
  readonly exited: Promise<DevinAcpProcessExit>;

  constructor(
    readonly command: string,
    readonly executable: string,
    readonly args: readonly string[],
    readonly cwd: string
  ) {
    const child = spawn(executable, [...args], {
      cwd,
      env: process.env,
      shell: false,
      stdio: ["pipe", "pipe", "pipe"]
    });

    if (!child.stdin || !child.stdout || !child.stderr) {
      throw new DevinAcpError("spawn_failure", "Devin ACP child process could not be created with piped stdio.");
    }

    this.child = child;
    this.stdin = Writable.toWeb(child.stdin) as WritableStream<Uint8Array>;
    this.stdout = Readable.toWeb(child.stdout) as ReadableStream<Uint8Array>;
    this.stderr = Readable.toWeb(child.stderr) as ReadableStream<Uint8Array>;
    this.exited = waitForExit(child);
  }

  kill(signal?: NodeJS.Signals): void {
    try {
      this.child.kill(signal);
    } catch {
      // Best-effort; the process may already be gone.
    }
  }
}

function waitForExit(child: ChildProcess): Promise<DevinAcpProcessExit> {
  return new Promise((resolve) => {
    let settled = false;
    let spawnError: Error | undefined;

    const finish = (code: number | null, signal: NodeJS.Signals | null): void => {
      if (settled) {
        return;
      }
      settled = true;
      resolve({ code, signal, spawnError });
    };

    if (child.exitCode !== null || child.signalCode !== null) {
      finish(child.exitCode ?? null, child.signalCode ?? null);
      return;
    }

    child.on("error", (error: Error) => {
      if (!spawnError) {
        spawnError = error;
      }
      finish(null, null);
    });

    child.on("close", (code, signal) => {
      finish(code ?? null, signal ?? null);
    });
  });
}
