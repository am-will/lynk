import type {
  ContentBlock,
  SessionNotification,
  SessionUpdate,
  ToolCallContent,
  ToolCallLocation,
  ToolCallStatus
} from "@agentclientprotocol/sdk";
import type { ChatToolEventMessage, ChatToolSummary } from "../../protocol/messages.js";
import type { HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";

type EmitGatewayEvent = (event: string, payload: unknown) => void;

export interface DevinNormalizedUpdate {
  textDelta?: string;
  usage?: Record<string, unknown>;
  tool?: ChatToolSummary;
}

interface TrackedTool {
  eventId: string;
  toolName: string;
  title: string;
  status: ChatToolEventMessage["status"];
  args?: unknown;
  output?: unknown;
  error?: string | null;
}

export class DevinAcpEventNormalizer {
  private readonly tools = new Map<string, TrackedTool>();

  constructor(private readonly emit: EmitGatewayEvent) {}

  handle(
    session: HarnessStoredSession,
    runId: string,
    notification: SessionNotification
  ): DevinNormalizedUpdate | undefined {
    if (notification.sessionId !== session.sessionId) return undefined;
    const update = notification.update;
    switch (update.sessionUpdate) {
      case "user_message_chunk":
        return undefined;
      case "agent_message_chunk": {
        const delta = textContent(update.content);
        if (!delta) return undefined;
        this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta, replace: false });
        return { textDelta: delta };
      }
      case "agent_thought_chunk": {
        const delta = textContent(update.content);
        if (delta) {
          this.emit("agent", {
            sessionKey: session.key,
            runId,
            type: "reasoning.delta",
            data: { state: "reasoning", delta }
          });
        }
        return undefined;
      }
      case "tool_call":
      case "tool_call_update": {
        const tool = this.emitTool(session, runId, update);
        return { tool: { id: tool.toolName, label: tool.title, source: "devin" } };
      }
      case "plan":
        this.emitPlan(session, runId, update.entries);
        return undefined;
      case "plan_update":
        this.emitExperimentalPlan(session, runId, update.plan);
        return undefined;
      case "plan_removed":
        this.emit("agent", {
          type: "chat.tool_event",
          sessionKey: session.key,
          runId,
          eventId: `devin_plan_${planIdentifier(update)}`,
          toolName: "plan",
          title: "Devin plan",
          status: "info",
          output: null
        });
        return undefined;
      case "usage_update": {
        const usage = normalizeUsage(update.used, update.size, update.cost);
        return { usage };
      }
      case "available_commands_update":
      case "current_mode_update":
      case "config_option_update":
      case "session_info_update":
        return undefined;
      default: {
        const exhaustive: never = update;
        return exhaustive;
      }
    }
  }

  cancelUnfinished(session: HarnessStoredSession, runId: string): void {
    for (const tool of this.tools.values()) {
      if (tool.status !== "running" && tool.status !== "blocked") continue;
      tool.status = "failed";
      tool.error = "Cancelled";
      this.emit("agent", this.toolMessage(session, runId, tool));
    }
  }

  private emitTool(
    session: HarnessStoredSession,
    runId: string,
    update: Extract<SessionUpdate, { sessionUpdate: "tool_call" | "tool_call_update" }>
  ): TrackedTool {
    const existing = this.tools.get(update.toolCallId);
    const title = update.title ?? existing?.title ?? "Devin tool";
    const next: TrackedTool = {
      eventId: `devin_tool_${update.toolCallId}`,
      toolName: update.kind ?? existing?.toolName ?? "tool",
      title,
      status: toolStatus(update.status ?? undefined, existing?.status),
      args: update.rawInput !== undefined ? safeValue(update.rawInput) : existing?.args,
      output: toolOutput(update.content, update.locations, update.rawOutput, existing?.output),
      error: update.status === "failed"
        ? toolError(update.rawOutput, update.content)
        : update.status === "completed"
          ? null
          : existing?.error
    };
    this.tools.set(update.toolCallId, next);
    this.emit("agent", this.toolMessage(session, runId, next));
    return next;
  }

  private toolMessage(
    session: HarnessStoredSession,
    runId: string,
    tool: TrackedTool
  ): Omit<ChatToolEventMessage, "deviceId"> {
    return {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: tool.eventId,
      toolName: tool.toolName,
      title: tool.title,
      status: tool.status,
      args: tool.args ?? null,
      output: tool.output ?? null,
      error: tool.error ?? null
    };
  }

  private emitPlan(
    session: HarnessStoredSession,
    runId: string,
    entries: Extract<SessionUpdate, { sessionUpdate: "plan" }>["entries"]
  ): void {
    this.emit("agent", {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: `devin_plan_${session.sessionId}`,
      toolName: "plan",
      title: "Devin plan",
      status: "info",
      output: entries.map(({ content, priority, status }) => ({ content, priority, status }))
    });
  }

  private emitExperimentalPlan(
    session: HarnessStoredSession,
    runId: string,
    plan: Extract<SessionUpdate, { sessionUpdate: "plan_update" }>["plan"]
  ): void {
    const output = plan.type === "items"
      ? plan.entries.map(({ content, priority, status }) => ({ content, priority, status }))
      : plan.type === "markdown"
        ? plan.content
        : { uri: plan.uri };
    this.emit("agent", {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: `devin_plan_${planIdentifier(plan)}`,
      toolName: "plan",
      title: "Devin plan",
      status: "info",
      output
    });
  }
}

function planIdentifier(value: unknown): string {
  if (value && typeof value === "object") {
    const record = value as { id?: unknown; planId?: unknown };
    if (typeof record.planId === "string" && record.planId) return record.planId;
    if (typeof record.id === "string" && record.id) return record.id;
  }
  return "unknown";
}

function textContent(content: ContentBlock): string | undefined {
  return content.type === "text" ? content.text : undefined;
}

function toolStatus(status: ToolCallStatus | undefined, previous?: ChatToolEventMessage["status"]): ChatToolEventMessage["status"] {
  if (status === "completed") return "completed";
  if (status === "failed") return "failed";
  if (status === "pending" || status === "in_progress") return "running";
  return previous ?? "running";
}

function toolOutput(
  content: ToolCallContent[] | null | undefined,
  locations: ToolCallLocation[] | null | undefined,
  rawOutput: unknown,
  previous: unknown
): unknown {
  const result: Record<string, unknown> = {};
  if (content !== undefined) result.content = content?.map(safeToolContent) ?? null;
  if (locations !== undefined) {
    result.locations = locations?.map(({ path, line }) => ({ path, ...(line != null ? { line } : {}) })) ?? null;
  }
  if (rawOutput !== undefined) result.rawOutput = safeValue(rawOutput);
  return Object.keys(result).length > 0 ? result : previous;
}

function safeToolContent(item: ToolCallContent): unknown {
  if (item.type === "diff") return { type: "diff", path: item.path, oldText: item.oldText ?? null, newText: item.newText };
  if (item.type === "terminal") return { type: "terminal", terminalId: item.terminalId };
  const content = item.content;
  if (content.type === "text") return { type: "text", text: content.text };
  if (content.type === "resource_link") {
    return { type: "resource_link", name: content.name, uri: content.uri, title: content.title ?? null };
  }
  if (content.type === "resource") {
    const resource = content.resource;
    return "text" in resource
      ? { type: "resource", uri: resource.uri, text: resource.text, mimeType: resource.mimeType ?? null }
      : { type: "resource", uri: resource.uri, mimeType: resource.mimeType ?? null };
  }
  return { type: content.type, mimeType: content.mimeType, uri: "uri" in content ? content.uri ?? null : null };
}

function safeValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(safeValue);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .filter(([key]) => key !== "_meta")
      .map(([key, nested]) => [key, safeValue(nested)])
  );
}

function toolError(rawOutput: unknown, content: ToolCallContent[] | null | undefined): string {
  if (typeof rawOutput === "string" && rawOutput.trim()) return rawOutput;
  const text = content?.find((item) => item.type === "content" && item.content.type === "text");
  return text?.type === "content" && text.content.type === "text" ? text.content.text : "Devin tool failed.";
}

function normalizeUsage(
  used: number,
  size: number,
  cost: { amount: number; currency: string } | null | undefined
): Record<string, unknown> {
  return {
    contextTokens: used,
    contextWindowTokens: size,
    ...(cost?.currency.toUpperCase() === "USD" ? { estimatedCostUsd: cost.amount } : {})
  };
}
