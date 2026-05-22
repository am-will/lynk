import type { AuditLog } from "../bridge/AuditLog.js";
import type { BridgeConfig } from "../bridge/config.js";
import type { AgentClient, AgentRequestOptions, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { HermesApiClient, type HermesApiClientConfig, type HermesSseEvent } from "./HermesApiClient.js";
import { buildHermesPrompt } from "./hermesPrompt.js";

interface ActiveRun {
  runId: string;
  controller: AbortController;
}

export interface HermesSessionClientConfig extends HermesApiClientConfig {
  defaultSessionId: string;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const nested = firstStringField(item, keys);
      if (nested) {
        return nested;
      }
    }
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
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

function outputText(value: unknown): string | undefined {
  return firstStringField(value, ["output", "final_output", "finalMessage", "message", "text", "content", "delta"]);
}

function eventStatus(event: HermesSseEvent): string | undefined {
  const record = asRecord(event.data);
  const value = record?.status ?? record?.state ?? record?.phase;
  return typeof value === "string" ? value.toLowerCase() : undefined;
}

function eventToolName(event: HermesSseEvent): string | undefined {
  const record = asRecord(event.data);
  const nested = asRecord(record?.data) ?? record;
  const value = nested?.toolName ?? nested?.tool ?? nested?.name ?? nested?.function_name;
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function eventDelta(event: HermesSseEvent): string | undefined {
  return firstStringField(event.data, ["delta", "text_delta", "output_text", "text"]);
}

function sanitizeSessionSegment(value: string): string {
  return value.trim().replace(/[^a-zA-Z0-9_.:-]+/g, "-").replace(/^-+|-+$/g, "") || "default";
}

export function hermesSessionConfigFromBridgeConfig(config: BridgeConfig): HermesSessionClientConfig {
  if (!config.hermesApiKey) {
    throw new Error("HERMES_API_KEY is required to use the Hermes harness.");
  }
  return {
    apiBaseUrl: config.hermesApiBaseUrl,
    apiKey: config.hermesApiKey,
    model: config.hermesModel,
    defaultSessionId: config.hermesDefaultSessionId,
    runTimeoutMs: config.hermesRunTimeoutMs
  };
}

export function hermesSessionConfigFromEnv(): HermesSessionClientConfig {
  const apiKey = process.env.HERMES_API_KEY?.trim();
  if (!apiKey) {
    throw new Error("HERMES_API_KEY is required to use PHONE_AGENT_DISPATCHER=hermes.");
  }
  const timeoutSeconds = Number.parseInt(process.env.HERMES_RUN_TIMEOUT_SECONDS ?? "600", 10);
  return {
    apiBaseUrl: (process.env.HERMES_API_BASE_URL ?? "http://127.0.0.1:8642/v1").replace(/\/+$/, ""),
    apiKey,
    model: process.env.HERMES_MODEL?.trim() || "hermes-agent",
    defaultSessionId: process.env.HERMES_DEFAULT_SESSION_ID?.trim() || "hermes-agent",
    runTimeoutMs: (Number.isFinite(timeoutSeconds) && timeoutSeconds > 0 ? timeoutSeconds : 600) * 1000
  };
}

export class HermesSessionClient implements AgentClient {
  private readonly config: HermesSessionClientConfig;
  private readonly api: HermesApiClient;
  private active?: ActiveRun;

  constructor(
    config: HermesSessionClientConfig = hermesSessionConfigFromEnv(),
    private readonly audit?: AuditLog,
    api?: HermesApiClient
  ) {
    this.config = config;
    this.api = api ?? new HermesApiClient(config);
  }

  async submitUserRequest(
    text: string,
    sink: AgentStatusSink,
    options: AgentRequestOptions = {}
  ): Promise<AgentRunResult> {
    if (this.active) {
      throw new Error("A Hermes task is already running");
    }

    const prompt = buildHermesPrompt(text, options);
    const sessionId = this.sessionIdFor(options);
    const controller = new AbortController();
    let runId: string | undefined;
    let latestOutput: string | undefined;

    try {
      this.audit?.record("hermes_task_starting", options.deviceId, {
        taskKind: options.taskKind ?? "general",
        sessionId
      });
      sink.working(options.taskKind === "phone" ? "Sending phone task to Hermes" : "Sending task to Hermes");
      const created = await this.api.createRun({
        input: prompt,
        sessionId,
        idempotencyKey: options.deviceId ? `openclaw-hermes-${options.deviceId}-${Date.now()}` : undefined
      });
      runId = created.runId;
      this.active = { runId, controller };
      await this.withTimeout(
        this.api.streamRunEvents(runId, (event) => {
          latestOutput = this.handleEvent(event, sink, latestOutput);
        }, controller.signal),
        this.config.runTimeoutMs
      );

      const finalStatus = await this.api.getRun(runId);
      const error = outputText(finalStatus.error);
      if (error) {
        throw new Error(error);
      }
      const finalMessage = outputText(finalStatus.output) ?? outputText(finalStatus.raw) ?? latestOutput ?? "";
      const result: AgentRunResult = {
        threadId: finalStatus.sessionId ?? sessionId,
        turnId: finalStatus.runId,
        finalMessage
      };
      sink.done(finalMessage || "Hermes task completed");
      return result;
    } catch (error) {
      if (controller.signal.aborted) {
        throw new Error("Hermes task was interrupted");
      }
      throw error;
    } finally {
      if (this.active?.runId === runId) {
        this.active = undefined;
      }
    }
  }

  async interrupt(reason = "Stopped by user"): Promise<void> {
    const active = this.active;
    if (!active) {
      return;
    }
    active.controller.abort();
    await this.api.stopRun(active.runId).catch((error) => {
      this.audit?.record("hermes_task_stop_failed", undefined, {
        runId: active.runId,
        reason,
        error: error instanceof Error ? error.message : String(error)
      });
    });
    this.audit?.record("hermes_task_interrupted", undefined, { runId: active.runId, reason });
  }

  async steer(text: string): Promise<void> {
    const active = this.active;
    if (!active) {
      throw new Error("No active Hermes task is running");
    }
    await this.api.createRun({
      input: `Additional user guidance for the active Hermes task:\n${text.trim()}`,
      sessionId: this.config.defaultSessionId,
      idempotencyKey: `hermes-steer-${active.runId}-${Date.now()}`
    });
  }

  async close(): Promise<void> {
    await this.interrupt("Agent client closed");
  }

  private handleEvent(event: HermesSseEvent, sink: AgentStatusSink, latestOutput: string | undefined): string | undefined {
    const toolName = eventToolName(event);
    const status = eventStatus(event);
    if (toolName || event.event.toLowerCase().includes("tool")) {
      sink.tool(toolName ? `Using ${toolName}` : "Hermes tool activity");
    } else if (status && !["completed", "failed", "cancelled"].includes(status)) {
      sink.working(`Hermes ${status}`);
    }
    return eventDelta(event) ?? latestOutput;
  }

  private sessionIdFor(options: AgentRequestOptions): string {
    const base = sanitizeSessionSegment(this.config.defaultSessionId);
    const device = options.deviceId ? sanitizeSessionSegment(options.deviceId) : undefined;
    return device ? `${base}-${device}` : base;
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<T>((_, reject) => {
          timer = setTimeout(() => reject(new Error(`Hermes task timed out after ${Math.round(timeoutMs / 1000)} seconds`)), timeoutMs);
        })
      ]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
  }
}
