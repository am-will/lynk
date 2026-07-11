import { randomUUID } from "node:crypto";
import type {
  PermissionOption,
  RequestPermissionRequest,
  RequestPermissionResponse,
  ToolCallContent
} from "@agentclientprotocol/sdk";
import type { HarnessPermissionReplyOptions } from "../chat/ChatTransportTypes.js";

type EmitGatewayEvent = (event: string, payload: unknown) => void;

export interface DevinPermissionRun {
  sessionKey: string;
  sessionId: string;
  runId: string;
}

interface PendingPermission extends DevinPermissionRun {
  permissionId: string;
  request: RequestPermissionRequest;
  options: Map<string, PermissionOption>;
  resolve: (response: RequestPermissionResponse) => void;
  settled: boolean;
}

export class DevinPermissionBroker {
  private readonly pending = new Map<string, PendingPermission>();

  constructor(
    private readonly activeForSessionId: (sessionId: string) => DevinPermissionRun | undefined,
    private readonly emit: EmitGatewayEvent
  ) {}

  request(request: RequestPermissionRequest): Promise<RequestPermissionResponse> {
    const active = this.activeForSessionId(request.sessionId);
    if (!active || request.options.length === 0) {
      return Promise.resolve({ outcome: { outcome: "cancelled" } });
    }
    const permissionId = `devin_permission_${randomUUID()}`;
    return new Promise<RequestPermissionResponse>((resolve) => {
      const pending: PendingPermission = {
        ...active,
        permissionId,
        request,
        options: new Map(request.options.map((option) => [option.optionId, option])),
        resolve,
        settled: false
      };
      this.pending.set(permissionId, pending);
      this.emit("agent", permissionEvent(pending));
    });
  }

  respond(options: HarnessPermissionReplyOptions): { status: "selected"; optionId: string } {
    const pending = this.pending.get(options.permissionId);
    if (!pending || pending.settled) throw new Error("Devin permission reply is stale or already answered.");
    if (pending.sessionKey !== options.sessionKey) throw new Error("Devin permission reply belongs to another session.");
    if (typeof options.response === "string" || options.response.kind !== "acp_option") {
      throw new Error("Devin permission reply requires an ACP option.");
    }
    const option = pending.options.get(options.response.optionId);
    if (!option) throw new Error("Devin permission option was not offered for this request.");
    this.settle(pending, { outcome: { outcome: "selected", optionId: option.optionId } });
    this.emit("agent", {
      type: "chat.tool_event",
      sessionKey: pending.sessionKey,
      runId: pending.runId,
      eventId: pending.permissionId,
      toolName: "permission",
      title: "Devin permission answered",
      status: option.kind.startsWith("reject") ? "failed" : "completed",
      summary: option.name
    });
    return { status: "selected", optionId: option.optionId };
  }

  cancelRun(sessionKey: string, runId: string): void {
    this.cancelWhere((pending) => pending.sessionKey === sessionKey && pending.runId === runId);
  }

  cancelAll(): void {
    this.cancelWhere(() => true);
  }

  private cancelWhere(predicate: (pending: PendingPermission) => boolean): void {
    for (const pending of [...this.pending.values()]) {
      if (!predicate(pending)) continue;
      this.settle(pending, { outcome: { outcome: "cancelled" } });
      this.emit("agent", {
        type: "chat.tool_event",
        sessionKey: pending.sessionKey,
        runId: pending.runId,
        eventId: pending.permissionId,
        toolName: "permission",
        title: "Devin permission cancelled",
        status: "failed",
        error: "Cancelled"
      });
    }
  }

  private settle(pending: PendingPermission, response: RequestPermissionResponse): void {
    if (pending.settled) return;
    pending.settled = true;
    this.pending.delete(pending.permissionId);
    pending.resolve(response);
  }
}

function permissionEvent(pending: PendingPermission): Record<string, unknown> {
  const call = pending.request.toolCall;
  return {
    type: "chat.tool_event",
    sessionKey: pending.sessionKey,
    runId: pending.runId,
    eventId: pending.permissionId,
    toolName: call.kind ?? "permission",
    title: call.title ?? "Devin permission request",
    status: "blocked",
    summary: toolSummary(call.content) ?? "Devin is waiting for permission.",
    args: safeValue(call.rawInput) ?? null,
    actions: pending.request.options.map((option) => ({
      id: option.optionId,
      label: option.name,
      command: "devin.permission",
      args: { permissionId: pending.permissionId, optionId: option.optionId },
      style: option.kind.startsWith("reject") ? "danger" : option.kind === "allow_once" ? "primary" : "secondary"
    }))
  };
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

function toolSummary(content: ToolCallContent[] | null | undefined): string | undefined {
  for (const item of content ?? []) {
    if (item.type === "content" && item.content.type === "text" && item.content.text.trim()) {
      return item.content.text;
    }
    if (item.type === "diff") return `Modify ${item.path}`;
  }
  return undefined;
}
