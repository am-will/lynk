import type { AuditLog } from "../bridge/AuditLog.js";
import type { BridgeConfig } from "../bridge/config.js";
import type { AgentClient, AgentRequestOptions, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { HermesApiClient, type HermesApiClientConfig } from "./HermesApiClient.js";
import { buildHermesPrompt } from "./hermesPrompt.js";
import { HermesRunDriver, type HermesActiveRun, type HermesRunDriverEvent } from "./HermesRunDriver.js";

export interface HermesSessionClientConfig extends HermesApiClientConfig {
  defaultSessionId: string;
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
  private readonly driver: HermesRunDriver;
  private active?: HermesActiveRun;

  constructor(
    config: HermesSessionClientConfig = hermesSessionConfigFromEnv(),
    private readonly audit?: AuditLog,
    api?: HermesApiClient
  ) {
    this.config = config;
    this.driver = new HermesRunDriver(api ?? new HermesApiClient(config), config.runTimeoutMs);
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
    let active: HermesActiveRun | undefined;

    try {
      this.audit?.record("hermes_task_starting", options.deviceId, {
        taskKind: options.taskKind ?? "general",
        sessionId
      });
      sink.working(options.taskKind === "phone" ? "Sending phone task to Hermes" : "Sending task to Hermes");
      active = await this.driver.createRun({
        input: prompt,
        sessionId,
        idempotencyKey: options.deviceId ? `openclaw-hermes-${options.deviceId}-${Date.now()}` : undefined
      });
      this.active = active;
      const completed = await this.driver.streamRun(active, (event) => this.handleEvent(event, sink));
      const finalMessage = completed.finalText;
      const result: AgentRunResult = {
        threadId: completed.status.sessionId ?? sessionId,
        turnId: completed.status.runId,
        finalMessage
      };
      sink.done(finalMessage || "Hermes task completed");
      return result;
    } catch (error) {
      if (active?.controller.signal.aborted) {
        throw new Error("Hermes task was interrupted");
      }
      throw error;
    } finally {
      if (active && this.active?.runId === active.runId) {
        this.active = undefined;
      }
    }
  }

  async interrupt(reason = "Stopped by user"): Promise<void> {
    const active = this.active;
    if (!active) {
      return;
    }
    await this.driver.stopRun(active).catch((error) => {
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
    await this.driver.steerRun(active, text);
  }

  async close(): Promise<void> {
    await this.interrupt("Agent client closed");
  }

  private handleEvent(event: HermesRunDriverEvent, sink: AgentStatusSink): void {
    if (event.type === "tool") {
      sink.tool(event.toolName ? `Using ${event.toolName}` : "Hermes tool activity");
    } else if (event.type === "status") {
      sink.working(`Hermes ${event.status}`);
    }
  }

  private sessionIdFor(options: AgentRequestOptions): string {
    const base = sanitizeSessionSegment(this.config.defaultSessionId);
    const device = options.deviceId ? sanitizeSessionSegment(options.deviceId) : undefined;
    return device ? `${base}-${device}` : base;
  }

}
