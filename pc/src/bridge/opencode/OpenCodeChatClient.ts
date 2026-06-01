import { randomUUID } from "node:crypto";
import { basename } from "node:path";
import type { AuditLog } from "../AuditLog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import type { ChatAttachment, ChatToolEventMessage } from "../../protocol/messages.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler, HarnessPermissionReplyOptions } from "../chat/ChatTransportTypes.js";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import { OpenCodeServerClient, type OpenCodeModelRef } from "./OpenCodeServerClient.js";
import { prepareOpenCodeWorkspace } from "./OpenCodeWorkspace.js";

interface ActiveRun {
  sessionKey: string;
  runId: string;
  abortController?: AbortController;
}

interface OpenCodeRunEventResult {
  textDelta?: string;
  textReplace?: boolean;
  textFinal?: string;
  usage?: Record<string, unknown>;
  error?: string;
  done?: boolean;
}

const OPENCODE_SESSION_PREFIX = "opencode:";
const OPENCODE_REMOTE_SESSION_KEY = "opencodeRemoteSession";
const OPENCODE_SESSION_DIRECTORY_KEY = "opencodeDirectory";
const OPENCODE_DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function numberField(record: Record<string, unknown> | undefined, key: string): number | undefined {
  const value = record?.[key];
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

function arrayField(record: Record<string, unknown> | undefined, key: string): unknown[] {
  const value = record?.[key];
  return Array.isArray(value) ? value : [];
}

function objectValues(record: Record<string, unknown> | undefined): unknown[] {
  return record ? Object.values(record) : [];
}

function booleanField(record: Record<string, unknown> | undefined, key: string): boolean | undefined {
  const value = record?.[key];
  return typeof value === "boolean" ? value : undefined;
}

function opencodeSessionIdFromKey(sessionKey: string | undefined | null): string | undefined {
  const value = sessionKey?.startsWith(OPENCODE_SESSION_PREFIX)
    ? sessionKey.slice(OPENCODE_SESSION_PREFIX.length).trim()
    : undefined;
  return value || undefined;
}

function workspaceNameFromPath(path: string | null | undefined): string | null {
  if (!path) {
    return null;
  }
  return basename(path) || path;
}

function secondsToMillis(value: number | null | undefined): number | null {
  if (value === null || value === undefined) {
    return null;
  }
  return value < 10_000_000_000 ? value * 1000 : value;
}

function parseModelRef(model: string | undefined | null): OpenCodeModelRef | undefined {
  const clean = model?.trim();
  if (!clean) {
    return undefined;
  }
  const separator = clean.indexOf("/");
  if (separator <= 0 || separator === clean.length - 1) {
    return undefined;
  }
  return {
    providerID: clean.slice(0, separator),
    modelID: clean.slice(separator + 1)
  };
}

function modelIdFromRef(model: OpenCodeModelRef): string {
  return `${model.providerID}/${model.modelID}`;
}

function textFromParts(parts: unknown[], role: string): string {
  return parts
    .map((part) => {
      const record = asRecord(part);
      const type = stringField(record, "type");
      if (type === "text") {
        return stringField(record, "text") ?? "";
      }
      if (role === "assistant" && type === "reasoning") {
        return "";
      }
      return "";
    })
    .filter(Boolean)
    .join("");
}

function messagesFromOpenCode(payload: unknown): Array<Record<string, unknown>> {
  const records = Array.isArray(payload) ? payload : arrayField(asRecord(payload), "messages");
  return records
    .map((entry, index) => {
      const record = asRecord(entry);
      const info = asRecord(record?.info) ?? record;
      const role = stringField(info, "role") ?? "assistant";
      const parts = arrayField(record, "parts");
      const text = textFromParts(parts, role);
      if (!text.trim()) {
        return undefined;
      }
      return {
        id: stringField(info, "id") ?? `opencode_message_${index}`,
        role,
        text,
        timestamp: secondsToMillis(numberField(asRecord(info?.time), "created"))
      } as Record<string, unknown>;
    })
    .filter((message): message is Record<string, unknown> => message !== undefined);
}

function latestAssistantText(payload: unknown): string {
  const messages = messagesFromOpenCode(payload);
  return [...messages].reverse().find((message) => message.role === "assistant")?.text as string | undefined ?? "";
}

function usageFromMessages(payload: unknown): Record<string, unknown> | undefined {
  const records = Array.isArray(payload) ? payload : arrayField(asRecord(payload), "messages");
  for (const entry of [...records].reverse()) {
    const info = asRecord(asRecord(entry)?.info);
    if (stringField(info, "role") !== "assistant") {
      continue;
    }
    const tokens = asRecord(info?.tokens);
    if (!tokens) {
      continue;
    }
    const cache = asRecord(tokens.cache);
    const inputTokens = numberField(tokens, "input");
    const outputTokens = numberField(tokens, "output");
    const reasoningTokens = numberField(tokens, "reasoning");
    const totalTokens = [inputTokens, outputTokens, reasoningTokens, numberField(cache, "read"), numberField(cache, "write")]
      .filter((value): value is number => value !== undefined)
      .reduce((sum, value) => sum + value, 0);
    return {
      inputTokens,
      outputTokens,
      reasoningTokens,
      totalTokens: totalTokens || undefined,
      estimatedCostUsd: numberField(info, "cost")
    };
  }
  return undefined;
}

function sessionTitle(session: Record<string, unknown>): string {
  return stringField(session, "title") ?? stringField(session, "id") ?? "OpenCode session";
}

function sessionDirectory(session: Record<string, unknown> | undefined, fallback?: string): string | null {
  return stringField(session, "directory") ?? fallback ?? null;
}

function isIdleStatus(payload: unknown, sessionId: string): boolean {
  const payloadRecord = asRecord(payload);
  if (payloadRecord && Object.keys(payloadRecord).length === 0) {
    return true;
  }
  const status = payloadRecord?.[sessionId];
  const record = asRecord(status);
  const type = typeof status === "string" ? status : stringField(record, "type");
  return type === "idle";
}

function eventPayload(value: unknown): Record<string, unknown> | undefined {
  const record = asRecord(value);
  return asRecord(record?.payload) ?? asRecord(record?.data) ?? record;
}

function eventProperties(value: unknown): Record<string, unknown> | undefined {
  return asRecord(eventPayload(value)?.properties);
}

function eventType(value: unknown): string {
  return stringField(eventPayload(value), "type") ?? "";
}

function sessionIdFromEvent(value: unknown): string | undefined {
  return stringField(eventProperties(value), "sessionID") ?? stringField(eventProperties(value), "sessionId");
}

function toolContentText(content: unknown): string | undefined {
  const values = Array.isArray(content) ? content : [];
  const text = values
    .map((item) => {
      const record = asRecord(item);
      if (stringField(record, "type") === "text") {
        return stringField(record, "text") ?? "";
      }
      const uri = stringField(record, "uri");
      const name = stringField(record, "name");
      return uri ? [name, uri].filter(Boolean).join(": ") : "";
    })
    .filter(Boolean)
    .join("\n");
  return text || undefined;
}

function errorText(error: unknown): string | undefined {
  const record = asRecord(error);
  return stringField(record, "message") ?? stringField(record, "error") ?? (typeof error === "string" ? error : undefined);
}

function permissionPreview(permission: Record<string, unknown>): string | undefined {
  const pattern = permission.pattern;
  const metadata = asRecord(permission.metadata);
  const parts = [
    stringField(permission, "type"),
    Array.isArray(pattern) ? pattern.join(", ") : typeof pattern === "string" ? pattern : undefined,
    stringField(metadata, "command") ?? stringField(metadata, "file") ?? stringField(metadata, "path")
  ].filter(Boolean);
  return parts.join("\n") || undefined;
}

export class OpenCodeChatClient {
  private readonly client: OpenCodeServerClient;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly handlers = new Set<GatewayEventHandler>();
  private readonly partTypes = new Map<string, string>();
  private active?: ActiveRun;

  constructor(
    private readonly audit?: AuditLog,
    client?: OpenCodeServerClient,
    sessionStoragePath: string | null = "state/opencode-sessions.json",
    options: {
      serverUrl?: string;
      command?: string;
      cwd?: string;
      username?: string;
      password?: string;
      defaultAgent?: string;
      timeoutMs?: number;
    } = {}
  ) {
    this.client = client ?? new OpenCodeServerClient(audit, options);
    this.sessions = new InMemoryHarnessSessionStore("opencode", {
      defaultModel: OPENCODE_DEFAULT_MODEL,
      modelProvider: "opencode",
      storagePath: sessionStoragePath ?? undefined
    });
  }

  addEventListener(handler: GatewayEventHandler): () => void {
    this.handlers.add(handler);
    return () => this.handlers.delete(handler);
  }

  async history(sessionKey: string): Promise<unknown> {
    const session = this.sessions.ensureSession(sessionKey, opencodeSessionIdFromKey(sessionKey));
    const directory = directoryForSession(session);
    try {
      const payload = await this.client.messages(session.sessionId, directory);
      return {
        sessionId: session.sessionId,
        messages: messagesFromOpenCode(payload)
      };
    } catch {
      return this.sessions.history(sessionKey);
    }
  }

  async sendChat(options: {
    sessionKey: string;
    sessionId?: string;
    message: string;
    attachments?: ChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    if (this.active) {
      throw new Error("An OpenCode task is already running");
    }
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId ?? opencodeSessionIdFromKey(options.sessionKey));
    this.sessions.setThinkingLevel(session, options.thinking);
    await this.ensureOpenCodeSession(session);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey, options.attachments);

    const runId = options.idempotencyKey ?? `opencode_${randomUUID()}`;
    this.sessions.setActiveRun(session, runId);
    this.active = { sessionKey: session.key, runId };
    void this.processRun(session, runId, options.message, session.model, options.attachments);
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    if (!this.active || (runId && this.active.runId !== runId)) {
      return {};
    }
    const session = this.sessions.ensureSession(this.active.sessionKey);
    this.active.abortController?.abort();
    await this.client.abort(session.sessionId, directoryForSession(session));
    this.emit("chat", {
      sessionKey: this.active.sessionKey,
      runId: this.active.runId,
      state: "error",
      error: "OpenCode run stopped."
    });
    return { status: "stopping" };
  }

  async respondToPermission(options: HarnessPermissionReplyOptions): Promise<unknown> {
    const session = this.sessions.ensureSession(options.sessionKey, opencodeSessionIdFromKey(options.sessionKey));
    return await this.client.respondToPermission({
      sessionId: session.sessionId,
      directory: directoryForSession(session),
      permissionId: options.permissionId,
      response: options.response
    });
  }

  async listModels(): Promise<unknown> {
    const payload = await this.client.providers(this.client.defaultDirectory())
      .catch(() => this.client.configProviders(this.client.defaultDirectory()));
    return { models: normalizeOpenCodeModels(payload) };
  }

  async listSessions(limit = 50): Promise<unknown> {
    const directory = this.client.defaultDirectory();
    const payload = await this.client.listSessions(directory).catch(() => undefined);
    const remoteSessions = Array.isArray(payload) ? payload.map(asRecord).filter(Boolean) as Record<string, unknown>[] : [];
    if (remoteSessions.length > 0) {
      return {
        sessions: remoteSessions
          .slice(0, Math.max(1, limit))
          .map((session) => this.sessionToSummary(session)),
        defaults: {
          thinkingLevels: DEFAULT_REASONING_OPTIONS.map((option) => option.id)
        }
      };
    }
    return {
      sessions: this.sessions.listSessions(limit),
      defaults: {
        thinkingLevels: DEFAULT_REASONING_OPTIONS.map((option) => option.id)
      }
    };
  }

  async createSession(options: { key?: string; label?: string; model?: string; workspacePath?: string; createWorkspaceIfMissing?: boolean }): Promise<unknown> {
    const directory = prepareOpenCodeWorkspace(options.workspacePath, options.createWorkspaceIfMissing === true) ?? this.client.defaultDirectory();
    const createdPayload = await this.client.createSession({
      directory,
      title: options.label,
      agent: this.client.defaultAgentName(),
      model: parseModelRef(options.model)
    });
    const created = asRecord(createdPayload) ?? {};
    const sessionId = stringField(created, "id");
    if (!sessionId) {
      throw new Error("OpenCode session/create returned no session id");
    }
    const key = `${OPENCODE_SESSION_PREFIX}${sessionId}`;
    const local = this.sessions.ensureSession(key, sessionId);
    local.label = options.label?.trim() || sessionTitle(created);
    local.displayName = local.label;
    local.model = options.model?.trim() || local.model;
    this.sessions.setSessionId(local, sessionId);
    this.sessions.setMetadata(local, OPENCODE_REMOTE_SESSION_KEY, true);
    this.sessions.setMetadata(local, OPENCODE_SESSION_DIRECTORY_KEY, directory);
    return {
      key,
      sessionId,
      label: local.label,
      displayName: local.displayName,
      workspacePath: directory,
      workspaceName: workspaceNameFromPath(directory)
    };
  }

  async patchSession(sessionKey: string, patch: Record<string, unknown>): Promise<unknown> {
    this.sessions.patchSession(sessionKey, patch);
    return { ok: true };
  }

  async listCommands(): Promise<unknown> {
    const payload = await this.client.listCommands(this.client.defaultDirectory()).catch(() => []);
    const commands = (Array.isArray(payload) ? payload : arrayField(asRecord(payload), "commands"))
      .map((command) => {
        const record = asRecord(command);
        const name = stringField(record, "name");
        if (!name) {
          return undefined;
        }
        return {
          name,
          description: stringField(record, "description") ?? `Run OpenCode /${name}`,
          textAliases: [`/${name}`],
          acceptsArgs: true,
          source: "opencode"
        };
      })
      .filter(Boolean);
    return { commands };
  }

  async effectiveTools(sessionKey: string): Promise<unknown> {
    const session = this.sessions.ensureSession(sessionKey);
    const model = parseModelRef(session.model);
    if (model) {
      const payload = await this.client.listTools({
        directory: directoryForSession(session),
        providerID: model.providerID,
        modelID: model.modelID
      }).catch(() => undefined);
      return { tools: normalizeTools(payload) };
    }
    const payload = await this.client.listToolIds(directoryForSession(session)).catch(() => []);
    return {
      tools: (Array.isArray(payload) ? payload : [])
        .map((name) => typeof name === "string" ? { name, description: `OpenCode ${name}` } : undefined)
        .filter(Boolean)
    };
  }

  async health(): Promise<unknown> {
    return await this.client.health(this.client.defaultDirectory());
  }

  close(): void {
    void this.client.close();
  }

  private async ensureOpenCodeSession(session: HarnessStoredSession): Promise<void> {
    const directory = directoryForSession(session) ?? this.client.defaultDirectory();
    if (session.metadata?.[OPENCODE_REMOTE_SESSION_KEY] === true) {
      return;
    }
    try {
      await this.client.getSession(session.sessionId, directory);
      this.sessions.setMetadata(session, OPENCODE_REMOTE_SESSION_KEY, true);
      this.sessions.setMetadata(session, OPENCODE_SESSION_DIRECTORY_KEY, directory);
      return;
    } catch {
      // Fall through and create the remote session.
    }
    const created = asRecord(await this.client.createSession({
      directory,
      title: session.displayName ?? session.label,
      agent: this.client.defaultAgentName(),
      model: parseModelRef(session.model)
    }));
    const sessionId = stringField(created, "id");
    if (!sessionId) {
      throw new Error("OpenCode session/create returned no session id");
    }
    this.sessions.setSessionId(session, sessionId);
    this.sessions.setMetadata(session, OPENCODE_REMOTE_SESSION_KEY, true);
    this.sessions.setMetadata(session, OPENCODE_SESSION_DIRECTORY_KEY, directory);
  }

  private async processRun(
    session: HarnessStoredSession,
    runId: string,
    text: string,
    model: string | undefined,
    attachments: ChatAttachment[] | undefined
  ): Promise<void> {
    const directory = directoryForSession(session) ?? this.client.defaultDirectory();
    let lastText = "";
    let eventError: string | undefined;
    const abortController = new AbortController();
    if (this.active?.runId === runId) {
      this.active.abortController = abortController;
    }
    try {
      const eventStream = this.consumeEvents(session, runId, directory, abortController.signal, (result) => {
        if (result.textDelta !== undefined) {
          const nextText = result.textReplace ? result.textDelta : `${lastText}${result.textDelta}`;
          lastText = nextText;
          this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
        }
        if (result.textFinal !== undefined) {
          lastText = result.textFinal;
          this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
        }
        if (result.usage) {
          this.sessions.setUsage(session, result.usage);
        }
        if (result.error) {
          eventError = result.error;
        }
      });
      await this.client.promptAsync({
        sessionId: session.sessionId,
        directory,
        text,
        attachments,
        model: parseModelRef(model),
        agent: this.client.defaultAgentName()
      });

      const startedAt = Date.now();
      while (Date.now() - startedAt < 600_000) {
        const messages = await this.client.messages(session.sessionId, directory).catch(() => undefined);
        if (messages) {
          const nextText = latestAssistantText(messages);
          if (nextText && nextText !== lastText) {
            const delta = nextText.startsWith(lastText) ? nextText.slice(lastText.length) : nextText;
            const replace = !nextText.startsWith(lastText);
            lastText = nextText;
            this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
            this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta, replace });
          }
          this.sessions.setUsage(session, usageFromMessages(messages));
        }
        const status = await this.client.status(directory).catch(() => undefined);
        if (status && isIdleStatus(status, session.sessionId) && lastText) {
          break;
        }
        if (eventError) {
          throw new Error(eventError);
        }
        await new Promise((resolve) => setTimeout(resolve, 400));
      }
      abortController.abort();
      await eventStream.catch(() => undefined);
      if (eventError) {
        throw new Error(eventError);
      }
      this.sessions.upsertAssistantMessage(session, runId, lastText);
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "final",
        message: lastText
      });
    } catch (error) {
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "error",
        error: error instanceof Error ? error.message : String(error)
      });
    } finally {
      abortController.abort();
      if (this.active?.runId === runId) {
        this.active = undefined;
      }
      this.sessions.clearActiveRun(session, runId);
    }
  }

  private async consumeEvents(
    session: HarnessStoredSession,
    runId: string,
    directory: string,
    signal: AbortSignal,
    onResult: (result: OpenCodeRunEventResult) => void
  ): Promise<void> {
    try {
      const stream = await this.client.subscribe(directory, { signal });
      for await (const event of stream) {
        if (signal.aborted) {
          break;
        }
        const result = this.handleOpenCodeEvent(session, runId, event);
        if (result) {
          onResult(result);
        }
        if (result?.done) {
          break;
        }
      }
    } catch (error) {
      if (!signal.aborted) {
        this.audit?.record("opencode_event_stream_error", session.key, {
          error: error instanceof Error ? error.message : String(error)
        });
      }
    }
  }

  private handleOpenCodeEvent(session: HarnessStoredSession, runId: string, event: unknown): OpenCodeRunEventResult | undefined {
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

  private sessionToSummary(session: Record<string, unknown>): Record<string, unknown> {
    const id = stringField(session, "id") ?? randomUUID();
    const directory = sessionDirectory(session, this.client.defaultDirectory());
    const key = `${OPENCODE_SESSION_PREFIX}${id}`;
    const local = this.sessions.ensureSession(key, id);
    this.sessions.setSessionId(local, id);
    this.sessions.setMetadata(local, OPENCODE_REMOTE_SESSION_KEY, true);
    if (directory) {
      this.sessions.setMetadata(local, OPENCODE_SESSION_DIRECTORY_KEY, directory);
    }
    return {
      key,
      sessionId: id,
      label: sessionTitle(session),
      displayName: sessionTitle(session),
      workspacePath: directory,
      workspaceName: workspaceNameFromPath(directory),
      source: "opencode",
      model: local.model ?? OPENCODE_DEFAULT_MODEL,
      modelProvider: "opencode",
      updatedAt: secondsToMillis(numberField(asRecord(session.time), "updated") ?? numberField(asRecord(session.time), "created")),
      hasActiveRun: false,
      thinkingLevel: null
    };
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}

function directoryForSession(session: HarnessStoredSession): string | undefined {
  return stringField(session.metadata, OPENCODE_SESSION_DIRECTORY_KEY);
}

function normalizeTools(payload: unknown): Array<Record<string, unknown>> {
  const records = Array.isArray(payload) ? payload : objectValues(asRecord(payload));
  return records
    .map((tool) => {
      const record = asRecord(tool);
      const name = stringField(record, "name") ?? stringField(record, "id");
      return name ? { name, description: stringField(record, "description") ?? `OpenCode ${name}` } as Record<string, unknown> : undefined;
    })
    .filter((tool): tool is Record<string, unknown> => tool !== undefined);
}

export function normalizeOpenCodeModels(payload: unknown): Array<Record<string, unknown>> {
  const record = asRecord(payload);
  const connected = new Set(arrayField(record, "connected").filter((value): value is string => typeof value === "string"));
  const providers = arrayField(record, "all").length > 0
    ? arrayField(record, "all")
    : arrayField(record, "providers");
  const models: Array<Record<string, unknown>> = [];
  for (const providerValue of providers) {
    const provider = asRecord(providerValue);
    if (!provider) {
      continue;
    }
    const providerID = stringField(provider, "id");
    if (!providerID) {
      continue;
    }
    const providerModels = asRecord(provider.models);
    for (const [key, value] of Object.entries(providerModels ?? {})) {
      const model = asRecord(value);
      const modelID = stringField(model, "id") ?? key;
      const id = `${providerID}/${modelID}`;
      models.push({
        id,
        key: id,
        name: stringField(model, "name") ?? modelID,
        provider: "opencode",
        contextWindow: numberField(asRecord(model?.limit), "context"),
        available: connected.size === 0 || connected.has(providerID),
        reasoningOptions: DEFAULT_REASONING_OPTIONS.map((option) => option.id),
        defaultReasoningEffort: "medium"
      });
    }
  }
  return models;
}
