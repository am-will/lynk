import { randomUUID } from "node:crypto";
import type { AuditLog } from "../AuditLog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import type { ChatAttachment, ChatToolEventMessage } from "../../protocol/messages.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler, HarnessPermissionReplyOptions } from "../chat/ChatTransportTypes.js";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import { OpenCodeServerClient } from "./OpenCodeServerClient.js";
import {
  directoryForSession,
  OpenCodeSessionCatalog,
  OPENCODE_REMOTE_SESSION_KEY,
  OPENCODE_SESSION_DIRECTORY_KEY
} from "./OpenCodeSessionCatalog.js";
import { prepareOpenCodeWorkspace } from "./OpenCodeWorkspace.js";
import {
  arrayField,
  asRecord,
  booleanField,
  errorText,
  eventPayload,
  eventProperties,
  eventType,
  isIdleStatus,
  latestAssistantText,
  messagesFromOpenCode,
  modelIdFromRef,
  normalizeOpenCodeModels,
  normalizeTools,
  numberField,
  opencodeSessionIdFromKey,
  parseModelRef,
  permissionPreview,
  sessionIdFromEvent,
  sessionTitle,
  stringField,
  toolContentText,
  usageFromMessages,
  workspaceNameFromPath
} from "./OpenCodeNormalizers.js";

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
const OPENCODE_DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

export class OpenCodeChatClient {
  private readonly client: OpenCodeServerClient;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly sessionCatalog: OpenCodeSessionCatalog;
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
      storageDataDir?: string;
    } = {}
  ) {
    this.client = client ?? new OpenCodeServerClient(audit, options);
    this.sessions = new InMemoryHarnessSessionStore("opencode", {
      defaultModel: OPENCODE_DEFAULT_MODEL,
      modelProvider: "opencode",
      storagePath: sessionStoragePath ?? undefined,
      persistEmptySessions: false
    });
    this.sessionCatalog = new OpenCodeSessionCatalog(this.sessions, this.client, OPENCODE_DEFAULT_MODEL, options.storageDataDir);
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
    const sessions = await this.sessionCatalog.listSessions(limit);
    if (sessions.length > 0) {
      return {
        sessions,
        defaults: {
          thinkingLevels: DEFAULT_REASONING_OPTIONS.map((option) => option.id)
        }
      };
    }
    return {
      sessions: [],
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

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}
