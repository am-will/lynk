import { spawn, type ChildProcess } from "node:child_process";
import { createInterface } from "node:readline";
import type { AuditLog } from "../bridge/AuditLog.js";
import { defaultWorkspaceRoot } from "../host/HostPaths.js";
import type { AgentClient, AgentRequestOptions, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { buildOpenClawPrompt } from "./openClawPrompt.js";

interface ActiveRun {
  child: ChildProcess;
  interrupted: boolean;
}

export interface OpenClawCommandConfig {
  command: string;
  args: string[];
  cwd: string;
}

const DEFAULT_OPENCLAW_TIMEOUT_SECONDS = "600";
const DEFAULT_OPENCLAW_SESSION_ID = "open-claw-agent";

function envFlag(name: string): boolean {
  return ["1", "true", "yes", "on"].includes((process.env[name] ?? "").trim().toLowerCase());
}

function pushOptional(args: string[], flag: string, value: string | undefined): void {
  const trimmed = value?.trim();
  if (trimmed) {
    args.push(flag, trimmed);
  }
}

export function buildOpenClawCommandConfig(message: string, options: AgentRequestOptions = {}): OpenClawCommandConfig {
  const command = process.env.OPENCLAW_AGENT_COMMAND?.trim() || "openclaw";
  const args: string[] = [];

  pushOptional(args, "--profile", process.env.OPENCLAW_AGENT_PROFILE);
  pushOptional(args, "--container", process.env.OPENCLAW_AGENT_CONTAINER);
  if (envFlag("OPENCLAW_AGENT_DEV")) {
    args.push("--dev");
  }
  args.push("--no-color", "agent", "--json");

  const timeoutSeconds = process.env.OPENCLAW_AGENT_TIMEOUT_SECONDS?.trim() || DEFAULT_OPENCLAW_TIMEOUT_SECONDS;
  pushOptional(args, "--timeout", timeoutSeconds);
  pushOptional(args, "--session-id", process.env.OPENCLAW_AGENT_SESSION_ID?.trim() || DEFAULT_OPENCLAW_SESSION_ID);
  pushOptional(args, "--agent", process.env.OPENCLAW_AGENT_ID);
  pushOptional(args, "--channel", process.env.OPENCLAW_AGENT_CHANNEL);
  pushOptional(args, "--to", process.env.OPENCLAW_AGENT_TO);
  pushOptional(args, "--reply-channel", process.env.OPENCLAW_AGENT_REPLY_CHANNEL);
  pushOptional(args, "--reply-to", process.env.OPENCLAW_AGENT_REPLY_TO);
  pushOptional(args, "--reply-account", process.env.OPENCLAW_AGENT_REPLY_ACCOUNT);
  pushOptional(args, "--model", options.model ?? process.env.OPENCLAW_AGENT_MODEL);
  pushOptional(args, "--thinking", options.reasoningEffort ?? process.env.OPENCLAW_AGENT_THINKING);

  if (envFlag("OPENCLAW_AGENT_LOCAL")) {
    args.push("--local");
  }
  if (envFlag("OPENCLAW_AGENT_DELIVER")) {
    args.push("--deliver");
  }

  args.push("--message", message);

  return {
    command,
    args,
    cwd: process.env.OPENCLAW_AGENT_CWD?.trim() || defaultWorkspaceRoot()
  };
}

function parseJsonFromOutput(output: string): unknown | undefined {
  const trimmed = output.trim();
  if (!trimmed) {
    return undefined;
  }

  try {
    return JSON.parse(trimmed);
  } catch {
    // OpenClaw may print progress before the JSON result. Prefer the last JSON-looking line.
  }

  for (const line of trimmed.split(/\r?\n/).reverse()) {
    const candidate = line.trim();
    if (!candidate.startsWith("{") && !candidate.startsWith("[")) {
      continue;
    }
    try {
      return JSON.parse(candidate);
    } catch {
      continue;
    }
  }
  return undefined;
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (!value || typeof value !== "object") {
    return undefined;
  }
  const record = value as Record<string, unknown>;
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  for (const field of Object.values(record)) {
    const nested = firstStringField(field, keys);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function finalMessageFromResult(parsed: unknown, fallbackOutput: string): string {
  return firstStringField(parsed, [
    "finalMessage",
    "reply",
    "message",
    "text",
    "output",
    "answer",
    "content",
    "result"
  ]) ?? fallbackOutput.trim();
}

export class OpenClawSessionClient implements AgentClient {
  private active?: ActiveRun;

  constructor(private readonly audit?: AuditLog) {}

  async submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options: AgentRequestOptions = {}
  ): Promise<AgentRunResult> {
    if (this.active) {
      throw new Error("An Open Claw task is already running");
    }

    const prompt = buildOpenClawPrompt(text, options);
    const config = buildOpenClawCommandConfig(prompt, options);
    this.audit?.record("openclaw_task_starting", options.deviceId, {
      command: config.command,
      args: config.args.filter((arg) => arg !== prompt),
      taskKind: options.taskKind ?? "general"
    });
    sink.working(options.taskKind === "phone" ? "Sending phone task to Open Claw" : "Sending task to Open Claw");

    return await new Promise<AgentRunResult>((resolve, reject) => {
      const child = spawn(config.command, config.args, {
        cwd: config.cwd,
        env: process.env,
        stdio: ["ignore", "pipe", "pipe"]
      });
      this.active = { child, interrupted: false };

      const stdout: string[] = [];
      const stderr: string[] = [];
      const stdoutLines = createInterface({ input: child.stdout });
      const stderrLines = createInterface({ input: child.stderr });

      stdoutLines.on("line", (line) => {
        stdout.push(line);
        const trimmed = line.trim();
        if (trimmed && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
          sink.info(trimmed);
        }
      });
      stderrLines.on("line", (line) => {
        stderr.push(line);
      });

      child.on("error", (error) => {
        this.active = undefined;
        reject(new Error(`Failed to start Open Claw command "${config.command}": ${error.message}`));
      });

      child.on("close", (code, signal) => {
        const active = this.active;
        this.active = undefined;
        stdoutLines.close();
        stderrLines.close();

        const stdoutText = stdout.join("\n");
        const stderrText = stderr.join("\n").trim();
        if (active?.interrupted) {
          reject(new Error("Open Claw task was interrupted"));
          return;
        }
        if (code !== 0) {
          reject(new Error(`Open Claw exited with code ${code ?? "null"} signal ${signal ?? "null"}${stderrText ? `: ${stderrText}` : ""}`));
          return;
        }

        const parsed = parseJsonFromOutput(stdoutText);
        const finalMessage = finalMessageFromResult(parsed, stdoutText);
        const result: AgentRunResult = { finalMessage };
        this.audit?.record("openclaw_task_completed", options.deviceId, {
          taskKind: options.taskKind ?? "general",
          hasJson: parsed !== undefined
        });
        sink.done(finalMessage || "Open Claw task completed");
        resolve(result);
      });
    });
  }

  async interrupt(reason = "Stopped by user"): Promise<void> {
    const active = this.active;
    if (!active) {
      return;
    }
    active.interrupted = true;
    active.child.kill("SIGINT");
    setTimeout(() => {
      if (!active.child.killed) {
        active.child.kill("SIGTERM");
      }
    }, 2_000).unref();
    this.audit?.record("openclaw_task_interrupted", undefined, { reason });
  }

  async steer(): Promise<void> {
    throw new Error("The Open Claw CLI adapter does not support mid-task steering yet. Stop the task and send a follow-up request.");
  }

  async close(): Promise<void> {
    await this.interrupt("Agent client closed");
  }
}
