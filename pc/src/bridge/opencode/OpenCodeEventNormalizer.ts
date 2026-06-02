import { randomUUID } from "node:crypto";
import type { ChatToolEventMessage } from "../../protocol/messages.js";
import type { HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import {
  asRecord,
  errorText,
  eventPayload,
  eventProperties,
  eventType,
  numberField,
  permissionPreview,
  sessionIdFromEvent,
  stringField,
  toolContentText
} from "./OpenCodeNormalizers.js";

export interface OpenCodeRunEventResult {
  textDelta?: string;
  textReplace?: boolean;
  textFinal?: string;
  usage?: Record<string, unknown>;
  error?: string;
  done?: boolean;
}

type EmitGatewayEvent = (event: string, payload: unknown) => void;

type ParsedOpenCodeEvent =
  | { kind: "text_delta"; sessionId?: string; delta: string; partId?: string; source: "part" | "next" }
  | { kind: "text_final"; sessionId?: string; text: string }
  | { kind: "reasoning_delta"; sessionId?: string; delta: string }
  | { kind: "part_updated"; sessionId?: string; part?: Record<string, unknown> }
  | { kind: "permission_requested"; sessionId?: string; permission: Record<string, unknown> }
  | { kind: "permission_replied"; sessionId?: string; permissionId?: string; summary?: string; raw: unknown }
  | { kind: "next_tool"; sessionId?: string; type: string; properties: Record<string, unknown>; raw: unknown }
  | { kind: "command_executed"; sessionId?: string; properties: Record<string, unknown>; payload?: Record<string, unknown>; raw: unknown }
  | { kind: "session_diff"; sessionId?: string; properties: Record<string, unknown>; payload?: Record<string, unknown>; raw: unknown }
  | { kind: "run_error"; sessionId?: string; error: string }
  | { kind: "usage"; sessionId?: string; usage: Record<string, unknown> }
  | { kind: "idle"; sessionId?: string }
  | { kind: "unknown"; sessionId?: string };

export class OpenCodeEventNormalizer {
  private readonly partTypes = new Map<string, string>();

  constructor(private readonly emit: EmitGatewayEvent) {}

  handle(session: HarnessStoredSession, runId: string, event: unknown): OpenCodeRunEventResult | undefined {
    const parsed = parseOpenCodeEvent(event);
    if (parsed.sessionId && parsed.sessionId !== session.sessionId) {
      return undefined;
    }

    switch (parsed.kind) {
      case "text_delta": {
        const partType = parsed.partId ? this.partTypes.get(`${session.sessionId}:${parsed.partId}`) : undefined;
        if (parsed.source === "part" && partType === "reasoning") {
          this.emitReasoningDelta(session, runId, parsed.delta);
          return undefined;
        }
        this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta: parsed.delta, replace: false });
        return { textDelta: parsed.delta };
      }
      case "text_final":
        this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta: parsed.text, replace: true });
        return { textFinal: parsed.text };
      case "reasoning_delta":
        if (parsed.delta) {
          this.emitReasoningDelta(session, runId, parsed.delta);
        }
        return undefined;
      case "part_updated":
        return this.handlePartUpdated(session, runId, parsed.part);
      case "permission_requested":
        this.emitToolEvent(this.permissionToolEvent(session, runId, parsed.permission));
        return undefined;
      case "permission_replied":
        if (parsed.permissionId) {
          this.emitToolEvent({
            type: "chat.tool_event",
            sessionKey: session.key,
            runId,
            eventId: `opencode_permission_${parsed.permissionId}`,
            toolName: "permission",
            title: "OpenCode permission answered",
            status: "completed",
            summary: parsed.summary ?? null,
            raw: parsed.raw
          });
        }
        return undefined;
      case "next_tool":
        this.emitToolEvent(this.nextToolEvent(session, runId, parsed.raw, parsed.type, parsed.properties));
        return undefined;
      case "command_executed":
        this.emitToolEvent({
          type: "chat.tool_event",
          sessionKey: session.key,
          runId,
          eventId: `opencode_command_${stringField(parsed.properties, "messageID") ?? stringField(parsed.payload, "id") ?? randomUUID()}`,
          toolName: "command",
          title: `OpenCode command: ${stringField(parsed.properties, "name") ?? "command"}`,
          status: "completed",
          args: stringField(parsed.properties, "arguments") ?? null,
          raw: parsed.raw
        });
        return undefined;
      case "session_diff":
        this.emitToolEvent({
          type: "chat.tool_event",
          sessionKey: session.key,
          runId,
          eventId: `opencode_diff_${stringField(parsed.payload, "id") ?? randomUUID()}`,
          toolName: "patch",
          title: "OpenCode patch updated",
          status: "info",
          output: parsed.properties.diff ?? null,
          raw: parsed.raw
        });
        return undefined;
      case "run_error":
        return { done: true, error: parsed.error };
      case "usage":
        return { usage: parsed.usage };
      case "idle":
        return { done: true };
      case "unknown":
        return undefined;
      default: {
        const exhaustive: never = parsed;
        return exhaustive;
      }
    }
  }

  private emitReasoningDelta(session: HarnessStoredSession, runId: string, delta: string, replace?: boolean): void {
    this.emit("agent", {
      sessionKey: session.key,
      runId,
      type: "reasoning.delta",
      data: { delta, state: "reasoning", ...(replace ? { replace: true } : {}) }
    });
  }

  private handlePartUpdated(session: HarnessStoredSession, runId: string, part: Record<string, unknown> | undefined): OpenCodeRunEventResult | undefined {
    if (!part) {
      return undefined;
    }
    const type = stringField(part, "type");
    const partId = stringField(part, "id");
    if (type && partId) {
      this.partTypes.set(`${session.sessionId}:${partId}`, type);
    }
    if (type === "text") {
      const text = stringField(part, "text") ?? "";
      this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta: text, replace: true });
      return { textFinal: text };
    }
    if (type === "reasoning") {
      const delta = stringField(part, "text") ?? "";
      if (delta) {
        this.emitReasoningDelta(session, runId, delta, true);
      }
      return undefined;
    }
    if (type === "tool") {
      this.emitToolEvent(this.toolPartEvent(session, runId, part));
      return undefined;
    }
    if (type === "patch") {
      this.emitToolEvent({
        type: "chat.tool_event",
        sessionKey: session.key,
        runId,
        eventId: `opencode_patch_${stringField(part, "id") ?? randomUUID()}`,
        toolName: "patch",
        title: "OpenCode patch",
        status: "info",
        output: part.files ?? null,
        raw: part
      });
    }
    return undefined;
  }

  private toolPartEvent(session: HarnessStoredSession, runId: string, part: Record<string, unknown>): Omit<ChatToolEventMessage, "deviceId"> {
    const state = asRecord(part.state);
    const status = stringField(state, "status");
    const mappedStatus = status === "error" ? "failed" : status === "completed" ? "completed" : status === "running" || status === "pending" ? "running" : "info";
    const toolName = stringField(part, "tool") ?? "tool";
    return {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: `opencode_tool_${stringField(part, "callID") ?? stringField(part, "id") ?? randomUUID()}`,
      toolName,
      title: stringField(state, "title") ?? `OpenCode ${toolName}`,
      status: mappedStatus,
      args: state?.input ?? null,
      output: stringField(state, "output") ?? null,
      error: stringField(state, "error") ?? null,
      raw: part
    };
  }

  private nextToolEvent(
    session: HarnessStoredSession,
    runId: string,
    event: unknown,
    type: string,
    properties: Record<string, unknown>
  ): Omit<ChatToolEventMessage, "deviceId"> {
    const callId = stringField(properties, "callID") ?? stringField(asRecord(eventPayload(event)), "id") ?? randomUUID();
    const toolName = stringField(properties, "tool") ?? stringField(properties, "name") ?? "tool";
    const status = type.endsWith(".failed")
      ? "failed"
      : type.endsWith(".success")
        ? "completed"
        : type.endsWith(".progress")
          ? "info"
          : "running";
    return {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: `opencode_tool_${callId}`,
      toolName,
      title: stringField(properties, "command") ?? `OpenCode ${toolName}`,
      status,
      args: properties.input ?? stringField(properties, "text") ?? null,
      output: toolContentText(properties.content) ?? stringField(properties, "output") ?? null,
      error: errorText(properties.error) ?? null,
      raw: event
    };
  }

  private permissionToolEvent(
    session: HarnessStoredSession,
    runId: string,
    permission: Record<string, unknown>
  ): Omit<ChatToolEventMessage, "deviceId"> {
    const permissionId = stringField(permission, "id") ?? stringField(permission, "requestID") ?? randomUUID();
    return {
      type: "chat.tool_event",
      sessionKey: session.key,
      runId,
      eventId: `opencode_permission_${permissionId}`,
      toolName: "permission",
      title: stringField(permission, "title") ?? "OpenCode permission request",
      status: "blocked",
      summary: permissionPreview(permission) ?? "OpenCode is waiting for permission.",
      args: permission,
      actions: [
        {
          id: "once",
          label: "Allow Once",
          command: "opencode.permission",
          args: { permissionId, response: "once" },
          style: "primary"
        },
        {
          id: "always",
          label: "Always Allow",
          command: "opencode.permission",
          args: { permissionId, response: "always" },
          style: "secondary"
        },
        {
          id: "reject",
          label: "Reject",
          command: "opencode.permission",
          args: { permissionId, response: "reject" },
          style: "danger"
        }
      ],
      raw: permission
    };
  }

  private emitToolEvent(message: Omit<ChatToolEventMessage, "deviceId">): void {
    this.emit("agent", message);
  }
}

function parseOpenCodeEvent(event: unknown): ParsedOpenCodeEvent {
  const type = eventType(event);
  const properties = eventProperties(event) ?? {};
  const sessionId = sessionIdFromEvent(event);
  if (type === "message.part.delta") {
    const delta = stringField(properties, "delta") ?? "";
    return delta
      ? {
        kind: "text_delta",
        sessionId,
        delta,
        partId: stringField(properties, "partID") ?? stringField(properties, "partId"),
        source: "part"
      }
      : { kind: "unknown", sessionId };
  }
  if (type === "session.next.text.delta") {
    const delta = stringField(properties, "delta") ?? "";
    return delta ? { kind: "text_delta", sessionId, delta, source: "next" } : { kind: "unknown", sessionId };
  }
  if (type === "session.next.text.ended") {
    return { kind: "text_final", sessionId, text: stringField(properties, "text") ?? "" };
  }
  if (type === "session.next.reasoning.delta") {
    return { kind: "reasoning_delta", sessionId, delta: stringField(properties, "delta") ?? "" };
  }
  if (type === "message.part.updated") {
    return { kind: "part_updated", sessionId, part: asRecord(properties.part) };
  }
  if (type === "permission.asked" || type === "permission.updated") {
    return {
      kind: "permission_requested",
      sessionId,
      permission: type === "permission.updated" ? properties : asRecord(properties) ?? {}
    };
  }
  if (type === "permission.replied") {
    return {
      kind: "permission_replied",
      sessionId,
      permissionId: stringField(properties, "permissionID") ?? stringField(properties, "requestID"),
      summary: stringField(properties, "response") ?? stringField(properties, "reply"),
      raw: event
    };
  }
  if (type.startsWith("session.next.tool.")) {
    return { kind: "next_tool", sessionId, type, properties, raw: event };
  }
  if (type === "command.executed") {
    return { kind: "command_executed", sessionId, properties, payload: asRecord(eventPayload(event)), raw: event };
  }
  if (type === "session.diff") {
    return { kind: "session_diff", sessionId, properties, payload: asRecord(eventPayload(event)), raw: event };
  }
  if (type === "session.error" || type === "session.next.step.failed") {
    return { kind: "run_error", sessionId, error: errorText(properties.error) ?? "OpenCode run failed" };
  }
  if (type === "session.next.step.ended") {
    const tokens = asRecord(properties.tokens);
    const cache = asRecord(tokens?.cache);
    const inputTokens = numberField(tokens, "input");
    const outputTokens = numberField(tokens, "output");
    const reasoningTokens = numberField(tokens, "reasoning");
    return {
      kind: "usage",
      sessionId,
      usage: {
        inputTokens,
        outputTokens,
        reasoningTokens,
        totalTokens: numberField(tokens, "total") ??
          [inputTokens, outputTokens, reasoningTokens, numberField(cache, "read"), numberField(cache, "write")]
            .filter((value): value is number => value !== undefined)
            .reduce((sum, value) => sum + value, 0),
        estimatedCostUsd: numberField(properties, "cost")
      }
    };
  }
  if (type === "session.idle") {
    return { kind: "idle", sessionId };
  }
  return { kind: "unknown", sessionId };
}
