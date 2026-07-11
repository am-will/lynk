import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { createOpencodeClient, type OpencodeClient } from "@opencode-ai/sdk";
import type { ResolvedChatAttachment } from "../../attachments/AttachmentTypes.js";
import { inlineCompatibilityAttachments } from "../../attachments/AttachmentCompatibility.js";
import type { AuditLog } from "../AuditLog.js";

export interface OpenCodeModelRef {
  providerID: string;
  modelID: string;
  variant?: string;
}

export interface OpenCodeServerClientOptions {
  serverUrl?: string;
  command?: string;
  cwd?: string;
  username?: string;
  password?: string;
  defaultAgent?: string;
  timeoutMs?: number;
}

export interface OpenCodeSessionPromptOptions {
  sessionId: string;
  directory?: string;
  text: string;
  attachments?: ResolvedChatAttachment[];
  model?: OpenCodeModelRef;
  agent?: string;
  system?: string;
  messageId?: string;
}

export interface OpenCodeSessionCreateOptions {
  directory?: string;
  title?: string;
}

export interface OpenCodeSessionCommandOptions {
  sessionId: string;
  directory?: string;
  command: string;
  arguments?: string;
  model?: string;
  agent?: string;
}

interface RequestResult<T> {
  data?: T;
  error?: unknown;
  response?: Response;
}

type UnsafeOpenCodeSdkRequest = any;

interface OpenCodeDirectoryQuery {
  directory?: string;
}

interface OpenCodeSessionCreateBody {
  title?: string;
}

interface OpenCodePromptBody {
  messageID?: string;
  model?: OpenCodeModelRef;
  agent?: string;
  system?: string;
  parts: unknown[];
}

interface OpenCodeCommandBody {
  command: string;
  arguments: string;
  model?: string;
  agent?: string;
}

interface OpenCodePermissionReplyBody {
  response: "once" | "always" | "reject";
}

export interface OpenCodeStreamEvent {
  data: unknown;
  event?: string;
  id?: string;
  retry?: number;
}

const DEFAULT_OPENCODE_COMMAND = "opencode serve --hostname 127.0.0.1 --port 4096";
const DEFAULT_TIMEOUT_MS = 600_000;

function commandParts(command: string): string[] {
  return command.match(/(?:[^\s"]+|"[^"]*")+/g)?.map((part) => part.replace(/^"|"$/g, "")) ?? [];
}

function query(directory: string | undefined): OpenCodeDirectoryQuery | undefined {
  return directory ? { directory } : undefined;
}

function authHeader(username: string | undefined, password: string | undefined): string | undefined {
  if (!password) {
    return undefined;
  }
  return `Basic ${Buffer.from(`${username || "opencode"}:${password}`).toString("base64")}`;
}

function serverUrlFromCommand(command: string): string {
  const parts = commandParts(command);
  const port = flagValue(parts, "--port") ?? "4096";
  const rawHost = flagValue(parts, "--hostname") ?? flagValue(parts, "--host") ?? "127.0.0.1";
  const host = rawHost === "0.0.0.0" ? "127.0.0.1" : rawHost;
  return `http://${host}:${port}`;
}

function flagValue(parts: string[], flag: string): string | undefined {
  const withEquals = parts.find((part) => part.startsWith(`${flag}=`));
  if (withEquals) {
    return withEquals.slice(flag.length + 1);
  }
  const index = parts.indexOf(flag);
  return index >= 0 ? parts[index + 1] : undefined;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message;
  }
  if (error && typeof error === "object") {
    const record = error as Record<string, unknown>;
    const data = record.data && typeof record.data === "object" ? record.data as Record<string, unknown> : undefined;
    const message = data?.message ?? data?.error ?? record.message ?? record.error;
    if (typeof message === "string") {
      return message;
    }
    if (record.error && typeof record.error === "object") {
      const nested = record.error as Record<string, unknown>;
      const nestedMessage = nested.message ?? nested.error;
      if (typeof nestedMessage === "string") {
        return nestedMessage;
      }
    }
    try {
      return JSON.stringify(record);
    } catch {
      return String(error);
    }
  }
  return String(error);
}

function unwrap<T>(result: RequestResult<T>): T {
  if (result.error !== undefined) {
    throw new Error(errorMessage(result.error));
  }
  if (result.data === undefined) {
    throw new Error(`OpenCode request returned no data${result.response ? ` (${result.response.status})` : ""}`);
  }
  return result.data;
}

function sdkRequest(value: unknown): UnsafeOpenCodeSdkRequest {
  return value;
}

export function buildOpenCodePromptParts(text: string, attachments: ResolvedChatAttachment[] | undefined): unknown[] {
  const parts: unknown[] = [{ type: "text", text }];
  for (const attachment of inlineCompatibilityAttachments(attachments)) {
    parts.push({
      type: "file",
      mime: attachment.mimeType,
      filename: attachment.displayName,
      url: `data:${attachment.mimeType};base64,${attachment.contentBase64}`
    });
  }
  return parts;
}

function promptBody(options: OpenCodeSessionPromptOptions, defaultAgent: string | undefined): OpenCodePromptBody {
  return {
    ...(options.messageId ? { messageID: options.messageId } : {}),
    ...(options.model ? { model: { providerID: options.model.providerID, modelID: options.model.modelID, ...(options.model.variant ? { variant: options.model.variant } : {}) } } : {}),
    ...(options.agent ?? defaultAgent ? { agent: options.agent ?? defaultAgent } : {}),
    ...(options.system ? { system: options.system } : {}),
    parts: buildOpenCodePromptParts(options.text, options.attachments)
  };
}

export class OpenCodeServerClient {
  private client?: OpencodeClient;
  private child?: ChildProcessWithoutNullStreams;
  private readonly serverUrl: string;
  private readonly serverCommand: string;
  private readonly cwd: string;
  private readonly username: string;
  private readonly password?: string;
  private readonly defaultAgent?: string;
  private readonly timeoutMs: number;
  private readonly manageServer: boolean;
  private serverStartError?: Error;

  constructor(
    private readonly audit?: AuditLog,
    options: OpenCodeServerClientOptions = {}
  ) {
    this.serverCommand = options.command?.trim() || process.env.OPENCODE_SERVER_COMMAND?.trim() || DEFAULT_OPENCODE_COMMAND;
    this.cwd = options.cwd?.trim() || process.env.OPENCODE_AGENT_CWD?.trim() || process.cwd();
    const configuredServerUrl = options.serverUrl?.trim() || process.env.OPENCODE_SERVER_URL?.trim();
    this.serverUrl = (configuredServerUrl || serverUrlFromCommand(this.serverCommand)).replace(/\/+$/, "");
    this.manageServer = !configuredServerUrl;
    this.username = options.username?.trim() || process.env.OPENCODE_SERVER_USERNAME?.trim() || "opencode";
    this.password = options.password?.trim() || process.env.OPENCODE_SERVER_PASSWORD?.trim() || undefined;
    this.defaultAgent = options.defaultAgent?.trim() || process.env.OPENCODE_DEFAULT_AGENT?.trim() || undefined;
    const envTimeoutMs = Number.parseInt(process.env.OPENCODE_RUN_TIMEOUT_MS ?? "", 10);
    this.timeoutMs = options.timeoutMs ?? (Number.isFinite(envTimeoutMs) && envTimeoutMs > 0 ? envTimeoutMs : DEFAULT_TIMEOUT_MS);
  }

  defaultDirectory(): string {
    return this.cwd;
  }

  defaultAgentName(): string | undefined {
    return this.defaultAgent;
  }

  async health(directory = this.cwd): Promise<Record<string, unknown>> {
    try {
      await this.path(directory);
      return { ok: true, harness: "opencode", url: this.serverUrl };
    } catch (error) {
      return { ok: false, harness: "opencode", url: this.serverUrl, error: errorMessage(error) };
    }
  }

  async path(directory = this.cwd): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.path.get({ query: query(directory) }) as RequestResult<unknown>);
  }

  async project(directory = this.cwd): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.project.current({ query: query(directory) }) as RequestResult<unknown>);
  }

  async providers(directory = this.cwd): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.provider.list({ query: query(directory) }) as RequestResult<unknown>);
  }

  async configProviders(directory = this.cwd): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.config.providers({ query: query(directory) }) as RequestResult<unknown>);
  }

  async listSessions(directory = this.cwd): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.list({ query: query(directory) }) as RequestResult<unknown>);
  }

  async listAllSessions(): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.list() as RequestResult<unknown>);
  }

  async createSession(options: OpenCodeSessionCreateOptions = {}): Promise<unknown> {
    const client = await this.ensureStarted();
    const body: OpenCodeSessionCreateBody = {
      ...(options.title ? { title: options.title } : {})
    };
    return unwrap(await client.session.create({
      query: query(options.directory ?? this.cwd),
      body: sdkRequest(body)
    }) as RequestResult<unknown>);
  }

  async getSession(sessionId: string, directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.get({
      path: { id: sessionId },
      query: query(directory)
    }) as RequestResult<unknown>);
  }

  async messages(sessionId: string, directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.messages({
      path: { id: sessionId },
      query: query(directory)
    }) as RequestResult<unknown>);
  }

  async status(directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.status({ query: query(directory) }) as RequestResult<unknown>);
  }

  async promptAsync(options: OpenCodeSessionPromptOptions): Promise<unknown> {
    const client = await this.ensureStarted();
    const body = promptBody(options, this.defaultAgent);
    return unwrap(await client.session.promptAsync({
      path: { id: options.sessionId },
      query: query(options.directory),
      body: sdkRequest(body)
    }) as RequestResult<unknown>);
  }

  async prompt(options: OpenCodeSessionPromptOptions): Promise<unknown> {
    const client = await this.ensureStarted();
    const body = promptBody(options, this.defaultAgent);
    return unwrap(await client.session.prompt({
      path: { id: options.sessionId },
      query: query(options.directory),
      body: sdkRequest(body)
    }) as RequestResult<unknown>);
  }

  async command(options: OpenCodeSessionCommandOptions): Promise<unknown> {
    const client = await this.ensureStarted();
    const body: OpenCodeCommandBody = {
      command: options.command,
      arguments: options.arguments ?? "",
      ...(options.agent ?? this.defaultAgent ? { agent: options.agent ?? this.defaultAgent } : {}),
      ...(options.model ? { model: options.model } : {})
    };
    return unwrap(await client.session.command({
      path: { id: options.sessionId },
      query: query(options.directory),
      body: sdkRequest(body)
    }) as RequestResult<unknown>);
  }

  async abort(sessionId: string, directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.session.abort({
      path: { id: sessionId },
      query: query(directory)
    }) as RequestResult<unknown>);
  }

  async listCommands(directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.command.list({ query: query(directory) }) as RequestResult<unknown>);
  }

  async listToolIds(directory?: string): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.tool.ids(sdkRequest({ query: query(directory) })) as RequestResult<unknown>);
  }

  async listTools(options: { directory?: string; providerID: string; modelID: string }): Promise<unknown> {
    const client = await this.ensureStarted();
    return unwrap(await client.tool.list(sdkRequest({
      query: {
        ...query(options.directory),
        provider: options.providerID,
        model: options.modelID
      }
    })) as RequestResult<unknown>);
  }

  async respondToPermission(options: { sessionId: string; permissionId: string; directory?: string; response: "once" | "always" | "reject" }): Promise<unknown> {
    const client = await this.ensureStarted();
    const body: OpenCodePermissionReplyBody = { response: options.response };
    return unwrap(await client.postSessionIdPermissionsPermissionId({
      path: { id: options.sessionId, permissionID: options.permissionId },
      query: query(options.directory),
      body
    }) as RequestResult<unknown>);
  }

  async subscribe(directory?: string, options: { signal?: AbortSignal; onEvent?: (event: OpenCodeStreamEvent) => void } = {}): Promise<AsyncGenerator<unknown>> {
    const client = await this.ensureStarted();
    const result = await client.event.subscribe(sdkRequest({
      query: query(directory),
      signal: options.signal,
      sseMaxRetryAttempts: 0,
      onSseEvent: options.onEvent
    }));
    return result.stream as AsyncGenerator<unknown>;
  }

  async close(): Promise<void> {
    if (this.child) {
      this.child.kill();
      this.child = undefined;
    }
    this.client = undefined;
  }

  private async ensureStarted(): Promise<OpencodeClient> {
    if (this.client) {
      return this.client;
    }
    if (this.manageServer) {
      this.startManagedServer();
    }
    this.client = createOpencodeClient({
      baseUrl: this.serverUrl,
      directory: this.cwd,
      fetch: (request) => this.fetchWithDefaults(request)
    });
    await this.waitForServer();
    return this.client;
  }

  private startManagedServer(): void {
    if (this.child) {
      return;
    }
    this.serverStartError = undefined;
    const [bin, ...args] = commandParts(this.serverCommand);
    if (!bin) {
      throw new Error("OPENCODE_SERVER_COMMAND is empty");
    }
    this.child = spawn(bin, args, {
      cwd: this.cwd,
      env: process.env
    });
    this.child.stderr.on("data", (chunk) => {
      this.audit?.record("opencode_server_stderr", undefined, { message: chunk.toString().trim() });
    });
    this.child.once("error", (error) => {
      this.serverStartError = error;
      this.audit?.record("opencode_server_error", undefined, { message: error.message, code: (error as NodeJS.ErrnoException).code });
      this.child = undefined;
      this.client = undefined;
    });
    this.child.on("exit", (code, signal) => {
      this.audit?.record("opencode_server_exit", undefined, { code, signal });
      this.child = undefined;
      this.client = undefined;
    });
  }

  private async waitForServer(): Promise<void> {
    const startedAt = Date.now();
    let lastError: unknown;
    while (Date.now() - startedAt < 10_000) {
      if (this.serverStartError) {
        throw new Error(`OpenCode server failed to start with "${this.serverCommand}": ${this.serverStartError.message}`);
      }
      try {
        await this.path(this.cwd);
        return;
      } catch (error) {
        lastError = error;
        await delay(200);
      }
    }
    throw new Error(`OpenCode server did not become ready at ${this.serverUrl}: ${errorMessage(lastError)}`);
  }

  private async fetchWithDefaults(request: Request): Promise<Response> {
    const headers = new Headers(request.headers);
    const auth = authHeader(this.username, this.password);
    if (auth && !headers.has("Authorization")) {
      headers.set("Authorization", auth);
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await fetch(new Request(request, { headers, signal: controller.signal }));
    } finally {
      clearTimeout(timer);
    }
  }
}
