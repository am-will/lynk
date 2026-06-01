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

export class OpenCodeEventNormalizer {
  private readonly partTypes = new Map<string, string>();

  constructor(private readonly emit: EmitGatewayEvent) {}

  handle(session: HarnessStoredSession, runId: string, event: unknown): OpenCodeRunEventResult | undefined {
    const type = eventType(event);
    const properties = eventProperties(event) ?? {};
    const eventSessionId = sessionIdFromEvent(event);
    if (eventSessionId && eventSessionId !== session.sessionId) {
      return undefined;
    }

    if (type === "message.part.delta") {
      const delta = stringField(properties, "delta") ?? "";
      if (!delta) {
        return undefined;
      }
      const partId = stringField(properties, "partID") ?? stringField(properties, "partId");
      const partType = partId ? this.partTypes.get(`${session.sessionId}:${partId}`) : undefined;
      if (partType === "reasoning") {
        this.emit("agent", {
          sessionKey: session.key,
          runId,
          type: "reasoning.delta",
          data: { delta, state: "reasoning" }
        });
        return undefined;
      }
      this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta, replace: false });
      return { textDelta: delta };
    }

    if (type === "session.next.text.delta") {
      const delta = stringField(properties, "delta") ?? "";
      if (!delta) {
        return undefined;
      }
      this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta, replace: false });
      return { textDelta: delta };
    }

    if (type === "session.next.text.ended") {
      const text = stringField(properties, "text") ?? "";
      this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta: text, replace: true });
      return { textFinal: text };
    }

    if (type === "session.next.reasoning.delta") {
      const delta = stringField(properties, "delta") ?? "";
      if (delta) {
        this.emit("agent", {
          sessionKey: session.key,
          runId,
          type: "reasoning.delta",
          data: { delta, state: "reasoning" }
        });
      }
      return undefined;
    }

    if (type === "message.part.updated") {
      return this.handlePartUpdated(session, runId, asRecord(properties.part));
    }

    if (
      type === "permission.asked" ||
      type === "permission.updated"
    ) {
      const permission = type === "permission.updated" ? properties : asRecord(properties) ?? {};
      this.emitToolEvent(this.permissionToolEvent(session, runId, permission));
      return undefined;
    }

    if (type === "permission.replied") {
      const permissionId = stringField(properties, "permissionID") ?? stringField(properties, "requestID");
      if (permissionId) {
        this.emitToolEvent({
          type: "chat.tool_event",
          sessionKey: session.key,
          runId,
          eventId: `opencode_permission_${permissionId}`,
          toolName: "permission",
          title: "OpenCode permission answered",
          status: "completed",
          summary: stringField(properties, "response") ?? stringField(properties, "reply") ?? null,
          raw: event
        });
      }
      return undefined;
    }

    if (type.startsWith("session.next.tool.")) {
      this.emitToolEvent(this.nextToolEvent(session, runId, event, type, properties));
      return undefined;
    }

    if (type === "command.executed") {
      this.emitToolEvent({
        type: "chat.tool_event",
        sessionKey: session.key,
        runId,
        eventId: `opencode_command_${stringField(properties, "messageID") ?? stringField(asRecord(eventPayload(event)), "id") ?? randomUUID()}`,
        toolName: "command",
        title: `OpenCode command: ${stringField(properties, "name") ?? "command"}`,
        status: "completed",
        args: stringField(properties, "arguments") ?? null,
        raw: event
      });
      return undefined;
    }

    if (type === "session.diff") {
      this.emitToolEvent({
        type: "chat.tool_event",
        sessionKey: session.key,
        runId,
        eventId: `opencode_diff_${stringField(asRecord(eventPayload(event)), "id") ?? randomUUID()}`,
        toolName: "patch",
        title: "OpenCode patch updated",
        status: "info",
        output: properties.diff ?? null,
        raw: event
      });
      return undefined;
    }

    if (type === "session.error" || type === "session.next.step.failed") {
      const message = errorText(properties.error) ?? "OpenCode run failed";
      return { done: true, error: message };
    }

    if (type === "session.next.step.ended") {
      const tokens = asRecord(properties.tokens);
      const cache = asRecord(tokens?.cache);
      return {
        usage: {
          inputTokens: numberField(tokens, "input"),
          outputTokens: numberField(tokens, "output"),
          reasoningTokens: numberField(tokens, "reasoning"),
          totalTokens: numberField(tokens, "total") ??
            [numberField(tokens, "input"), numberField(tokens, "output"), numberField(tokens, "reasoning"), numberField(cache, "read"), numberField(cache, "write")]
              .filter((value): value is number => value !== undefined)
              .reduce((sum, value) => sum + value, 0),
          estimatedCostUsd: numberField(properties, "cost")
        }
      };
    }

    if (type === "session.idle") {
      return { done: true };
    }

    return undefined;
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
        this.emit("agent", {
          sessionKey: session.key,
          runId,
          type: "reasoning.delta",
          data: { delta, state: "reasoning", replace: true }
        });
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
