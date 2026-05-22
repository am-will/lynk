import type { PhoneHub } from "../bridge/PhoneHub.js";
import type { AuditLog } from "../bridge/AuditLog.js";
import type { UserRequestMessage } from "../protocol/messages.js";
import type { AgentClient, AgentRequestOptions, AgentRunResult, AgentStatusSink } from "./AgentClient.js";
import { CodexAppServerClient } from "./CodexAppServerClient.js";
import { FallbackAgentClient } from "./FallbackAgentClient.js";
import { HermesSessionClient } from "./HermesSessionClient.js";
import { OpenClawSessionClient } from "./OpenClawSessionClient.js";

export type DispatcherKind = "openclaw" | "hermes" | "codex" | "fallback";

function dispatcherKindFromEnv(): DispatcherKind {
  if (process.env.PHONE_AGENT_USE_FALLBACK === "1") {
    return "fallback";
  }
  const raw = (process.env.PHONE_AGENT_DISPATCHER ?? "openclaw").trim().toLowerCase();
  if (raw === "openclaw" || raw === "hermes" || raw === "codex" || raw === "fallback") {
    return raw;
  }
  throw new Error(`Unsupported PHONE_AGENT_DISPATCHER "${raw}". Expected openclaw, hermes, codex, or fallback.`);
}

export function createAgentClient(kind: DispatcherKind, audit?: AuditLog): AgentClient {
  switch (kind) {
    case "openclaw":
      return new OpenClawSessionClient(audit);
    case "hermes":
      return new HermesSessionClient(undefined, audit);
    case "codex":
      return new CodexAppServerClient(audit);
    case "fallback":
      return new FallbackAgentClient();
  }
}

export class Dispatcher {
  private readonly client: AgentClient;
  private readonly kind: DispatcherKind;

  constructor(
    private readonly hub: PhoneHub,
    private readonly audit?: AuditLog,
    client?: AgentClient,
    kind?: DispatcherKind
  ) {
    this.kind = kind ?? dispatcherKindFromEnv();
    this.client = client ?? createAgentClient(this.kind, audit);
  }

  async handleUserRequest(request: UserRequestMessage, options: Pick<AgentRequestOptions, "taskKind"> = {}): Promise<AgentRunResult> {
    this.audit?.startTurn(request.deviceId, request.text);
    const sink = this.statusSink(request.deviceId);
    try {
      sink.working(`Received: ${request.text}`);
      const result = await this.client.submitUserRequest(request.text, sink, {
        deviceId: request.deviceId,
        systemPrompt: request.systemPrompt,
        model: request.model,
        reasoningEffort: request.reasoningEffort,
        taskKind: options.taskKind ?? "general"
      });
      this.audit?.endTurn(request.deviceId, { result });
      return result;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      sink.error(message);
      this.audit?.endTurn(request.deviceId, { error: message });
      return { finalMessage: message, error: message };
    }
  }

  async stopActiveTurn(deviceId: string, reason = "Stopped by user"): Promise<void> {
    const sink = this.statusSink(deviceId);
    this.audit?.record("turn_stop_requested", deviceId, { reason });
    sink.working("Stopping active Open Claw task");
    this.hub.cancelPendingCommands(deviceId, reason);
    if (this.client.interrupt) {
      await this.client.interrupt(reason);
      sink.done("Stopped active task");
      return;
    }
    await this.client.close();
    sink.done("Stopped agent client");
  }

  async steerActiveTurn(deviceId: string, guidance: string): Promise<void> {
    const sink = this.statusSink(deviceId);
    const text = guidance.trim();
    if (!text) {
      throw new Error("Steering guidance is required");
    }
    if (!this.client.steer) {
      throw new Error("Active agent client does not support steering");
    }

    this.audit?.record("turn_steer_requested", deviceId, { guidance: text });
    await this.client.steer(text);
    sink.working(`Steered active task: ${text}`);
  }

  private statusSink(deviceId: string): AgentStatusSink {
    return {
      info: (text) => this.status(deviceId, "info", text),
      working: (text) => this.status(deviceId, "working", text),
      tool: (text) => this.status(deviceId, "tool", text),
      done: (text) => this.status(deviceId, "done", text),
      error: (text) => this.status(deviceId, "error", text)
    };
  }

  private status(deviceId: string, status: "info" | "working" | "tool" | "done" | "error", text: string): void {
    console.log(`[${status}] ${deviceId}: ${text}`);
    this.audit?.record("agent_status", deviceId, { status, text });
    this.hub.sendStatus(deviceId, { deviceId, status, text });
  }
}
