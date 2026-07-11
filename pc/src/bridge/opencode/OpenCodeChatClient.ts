import { randomUUID } from "node:crypto";
import type { AuditLog } from "../AuditLog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";
import type { GatewayChatSendResult, GatewayEvent, GatewayEventHandler, HarnessPermissionReplyOptions } from "../chat/ChatTransportTypes.js";
import { DEFAULT_REASONING_OPTIONS } from "../chat/ModelCatalog.js";
import { OpenCodeServerClient } from "./OpenCodeServerClient.js";
import { OpenCodeRunDriver } from "./OpenCodeRunDriver.js";
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
  messagesFromOpenCode,
  modelIdFromRef,
  normalizeOpenCodeModels,
  normalizeTools,
  opencodeSessionIdFromKey,
  parseModelRef,
  sessionTitle,
  stringField,
  workspaceNameFromPath
} from "./OpenCodeNormalizers.js";

const OPENCODE_SESSION_PREFIX = "opencode:";
const OPENCODE_DEFAULT_MODEL = "anthropic/claude-sonnet-4-5";

export class OpenCodeChatClient {
  private readonly client: OpenCodeServerClient;
  private readonly sessions: InMemoryHarnessSessionStore;
  private readonly sessionCatalog: OpenCodeSessionCatalog;
  private readonly runDriver: OpenCodeRunDriver;
  private readonly handlers = new Set<GatewayEventHandler>();

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
    this.runDriver = new OpenCodeRunDriver(this.client, this.sessions, (event, payload) => this.emit(event, payload), audit);
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
    attachments?: ResolvedChatAttachment[];
    thinking?: string;
    idempotencyKey?: string;
  }): Promise<GatewayChatSendResult> {
    this.runDriver.assertIdle();
    const session = this.sessions.ensureSession(options.sessionKey, options.sessionId ?? opencodeSessionIdFromKey(options.sessionKey));
    this.sessions.setThinkingLevel(session, options.thinking);
    await this.ensureOpenCodeSession(session);
    this.sessions.appendUserMessage(session, options.message, options.idempotencyKey, options.attachments);

    const runId = options.idempotencyKey ?? `opencode_${randomUUID()}`;
    this.runDriver.startRun(session, {
      runId,
      text: options.message,
      model: session.model,
      attachments: options.attachments
    });
    return { runId, sessionKey: session.key };
  }

  async abort(_sessionKey: string, runId?: string): Promise<unknown> {
    return await this.runDriver.abort(runId);
  }

  async respondToPermission(options: HarnessPermissionReplyOptions): Promise<unknown> {
    if (typeof options.response !== "string") {
      throw new Error("OpenCode permission reply requires an OpenCode decision.");
    }
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
      title: options.label
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
    this.runDriver.close();
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
      title: session.displayName ?? session.label
    }));
    const sessionId = stringField(created, "id");
    if (!sessionId) {
      throw new Error("OpenCode session/create returned no session id");
    }
    this.sessions.setSessionId(session, sessionId);
    this.sessions.setMetadata(session, OPENCODE_REMOTE_SESSION_KEY, true);
    this.sessions.setMetadata(session, OPENCODE_SESSION_DIRECTORY_KEY, directory);
  }

  private emit(event: string, payload: unknown): void {
    const gatewayEvent: GatewayEvent = { event, payload };
    for (const handler of this.handlers) {
      handler(gatewayEvent);
    }
  }
}
