import { randomUUID } from "node:crypto";
import { readHermesConfigSummary, type HermesConfigSummary } from "../hermes/HermesConfigReader.js";
import type { ChatAttachment } from "../protocol/messages.js";
import type {
  FetchLike,
  HermesRunCreateOptions,
  HermesRunCreateResult,
  HermesRunTransport,
  HermesRunStatus,
  HermesSseEvent
} from "./HermesApiClient.js";

export interface HermesConfigProvider {
  provider: string;
  model: string;
  baseUrl: string;
  apiKey?: string;
  contextWindow?: number;
}

interface StoredRun {
  options: HermesRunCreateOptions;
  sessionId: string;
  status: "queued" | "running" | "completed" | "failed" | "cancelled";
  output: string;
  error?: string;
  usage?: unknown;
  controller?: AbortController;
  createdAt: number;
  updatedAt: number;
}

const DEFAULT_HEADERS = {
  "Content-Type": "application/json"
};
const PROVIDER_PROBE_TIMEOUT_MS = 10_000;

export function createHermesConfigRunsClient(defaultModel: string, fetchFn: FetchLike = fetch): HermesConfigRunsClient | undefined {
  const provider = readHermesConfigProvider(readHermesConfigSummary(), defaultModel);
  return provider ? new HermesConfigRunsClient(provider, fetchFn) : undefined;
}

export class HermesConfigRunsClient implements HermesRunTransport {
  private readonly runs = new Map<string, StoredRun>();

  constructor(
    private readonly provider: HermesConfigProvider,
    private readonly fetchFn: FetchLike = fetch
  ) {}

  async createRun(options: HermesRunCreateOptions): Promise<HermesRunCreateResult> {
    const runId = options.idempotencyKey?.trim() || `hermes_local_${randomUUID()}`;
    const now = Date.now();
    this.runs.set(runId, {
      options,
      sessionId: options.sessionId,
      status: "queued",
      output: "",
      createdAt: now,
      updatedAt: now
    });
    return { runId, sessionId: options.sessionId, status: "queued" };
  }

  async getRun(runId: string): Promise<HermesRunStatus> {
    const run = this.requireRun(runId);
    return {
      runId,
      status: run.status,
      sessionId: run.sessionId,
      output: run.output ? { text: run.output } : undefined,
      error: run.error ? { message: run.error } : undefined,
      raw: {
        run_id: runId,
        status: run.status,
        session_id: run.sessionId,
        output: run.output,
        error: run.error,
        usage: run.usage
      }
    };
  }

  async health(): Promise<unknown> {
    const response = await this.fetchWithTimeout(this.url("/models"), {
      method: "GET",
      headers: this.headers()
    }, PROVIDER_PROBE_TIMEOUT_MS);
    if (!response.ok) {
      const body = await response.text().catch(() => "");
      throw new Error(`Hermes local provider health failed: ${response.status} ${response.statusText}${body ? `: ${body}` : ""}`);
    }
    return {
      ok: true,
      status: "ok",
      mode: "local-openai-stream",
      provider: this.provider.provider,
      model: this.provider.model,
      message: "Using Hermes config OpenAI-compatible provider for streaming runs."
    };
  }

  supportsModel(model: string | undefined): boolean {
    const selected = model?.trim();
    if (!selected) {
      return true;
    }
    const prefix = `${this.provider.provider}:`;
    if (selected.startsWith(prefix)) {
      return true;
    }
    if (selected.includes(":")) {
      return false;
    }
    return selected === this.provider.model;
  }

  async streamRunEvents(runId: string, onEvent: (event: HermesSseEvent) => void, signal?: AbortSignal): Promise<void> {
    const run = this.requireRun(runId);
    const controller = new AbortController();
    run.controller = controller;
    run.status = "running";
    run.updatedAt = Date.now();
    signal?.addEventListener("abort", () => controller.abort(), { once: true });
    onEvent(this.event("status", { status: "running", run_id: runId }));

    try {
      const response = await this.fetchFn(this.url("/chat/completions"), {
        method: "POST",
        headers: this.headers(),
        body: JSON.stringify({
          model: this.bareModel(run.options.model),
          messages: this.messagesForRun(run.options),
          stream: true,
          ...(run.options.serviceTier ? { service_tier: run.options.serviceTier } : {})
        }),
        signal: controller.signal
      });
      if (!response.ok) {
        const body = await response.text().catch(() => "");
        throw new Error(`Hermes local provider request failed: ${response.status} ${response.statusText}${body ? `: ${body}` : ""}`);
      }
      if (!response.body) {
        throw new Error("Hermes local provider response did not include a stream body.");
      }
      await this.readOpenAiStream(response.body, run, onEvent);
      if (run.status === "running") {
        run.status = "completed";
        run.updatedAt = Date.now();
        onEvent(this.event("status", { status: "completed", run_id: runId, output: run.output }));
      }
    } catch (error) {
      if (controller.signal.aborted || signal?.aborted) {
        run.status = "cancelled";
        run.updatedAt = Date.now();
        onEvent(this.event("status", { status: "cancelled", run_id: runId }));
        return;
      }
      run.status = "failed";
      run.error = error instanceof Error ? error.message : String(error);
      run.updatedAt = Date.now();
      onEvent(this.event("error", { status: "failed", run_id: runId, error: run.error }));
      throw error;
    }
  }

  async stopRun(runId: string): Promise<void> {
    const run = this.runs.get(runId);
    run?.controller?.abort();
    if (run) {
      run.status = "cancelled";
      run.updatedAt = Date.now();
    }
  }

  private async readOpenAiStream(
    body: ReadableStream<Uint8Array>,
    run: StoredRun,
    onEvent: (event: HermesSseEvent) => void
  ): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split(/\r?\n\r?\n/);
      buffer = parts.pop() ?? "";
      for (const part of parts) {
        this.handleOpenAiSseBlock(part, run, onEvent);
      }
    }
    buffer += decoder.decode();
    if (buffer.trim()) {
      this.handleOpenAiSseBlock(buffer, run, onEvent);
    }
  }

  private handleOpenAiSseBlock(block: string, run: StoredRun, onEvent: (event: HermesSseEvent) => void): void {
    const dataLines = block
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice("data:".length).trim());
    for (const raw of dataLines) {
      if (!raw || raw === "[DONE]") {
        continue;
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(raw);
      } catch {
        continue;
      }
      const delta = openAiDeltaText(parsed);
      if (delta) {
        run.output += delta;
        run.updatedAt = Date.now();
        onEvent(this.event("message.delta", { delta, run_id: this.runIdFor(run), data: parsed }, raw));
      }
      const usage = (parsed as { usage?: unknown })?.usage;
      if (usage) {
        run.usage = usage;
      }
    }
  }

  private messagesForRun(options: HermesRunCreateOptions): Array<Record<string, unknown>> {
    const messages: Array<Record<string, unknown>> = [];
    if (options.instructions?.trim()) {
      messages.push({ role: "system", content: options.instructions.trim() });
    }
    messages.push({ role: "user", content: userContent(options.input, options.attachments) });
    return messages;
  }

  private bareModel(model: string | undefined): string {
    const selected = model?.trim() || this.provider.model;
    const prefix = `${this.provider.provider}:`;
    return selected.startsWith(prefix) ? selected.slice(prefix.length) : selected;
  }

  private requireRun(runId: string): StoredRun {
    const run = this.runs.get(runId);
    if (!run) {
      throw new Error(`Unknown Hermes local run: ${runId}`);
    }
    return run;
  }

  private runIdFor(run: StoredRun): string {
    for (const [runId, candidate] of this.runs) {
      if (candidate === run) {
        return runId;
      }
    }
    return "";
  }

  private event(event: string, data: unknown, raw = JSON.stringify(data)): HermesSseEvent {
    return { event, data, raw };
  }

  private headers(): HeadersInit {
    return {
      ...DEFAULT_HEADERS,
      ...(this.provider.apiKey ? { Authorization: `Bearer ${this.provider.apiKey}` } : {})
    };
  }

  private url(path: string): string {
    return `${this.provider.baseUrl.replace(/\/+$/, "")}${path}`;
  }

  private async fetchWithTimeout(input: string, init: RequestInit, timeoutMs: number): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await this.fetchFn(input, {
        ...init,
        signal: controller.signal
      });
    } finally {
      clearTimeout(timer);
    }
  }
}

function readHermesConfigProvider(config: HermesConfigSummary, defaultModel: string): HermesConfigProvider | undefined {
  const providerId = config.modelProvider?.trim();
  const providerConfig = providerId ? config.providers.get(providerId) : undefined;
  const apiMode = providerConfig?.apiMode?.trim();
  const baseUrl = providerConfig?.baseUrl ?? config.modelBaseUrl;
  if (!providerId || !baseUrl || (apiMode && apiMode !== "chat_completions")) {
    return undefined;
  }
  const model = config.modelDefault?.trim()
    || providerConfig?.defaultModel?.trim()
    || providerConfig?.models[0]?.id
    || defaultModel;
  const configuredModelContext = providerConfig?.models.find((entry) => entry.id === model)?.contextWindow;
  return {
    provider: providerId,
    model,
    baseUrl,
    apiKey: providerConfig?.apiKey ?? config.modelApiKey,
    contextWindow: config.modelContextLength ?? configuredModelContext
  };
}

function openAiDeltaText(value: unknown): string | undefined {
  const firstChoice = Array.isArray((value as { choices?: unknown })?.choices)
    ? ((value as { choices: unknown[] }).choices[0] as Record<string, unknown> | undefined)
    : undefined;
  const delta = asRecord(firstChoice?.delta);
  const message = asRecord(firstChoice?.message);
  const content = delta?.content ?? message?.content;
  return typeof content === "string" && content.length > 0 ? content : undefined;
}

function userContent(input: string, attachments: ChatAttachment[] | undefined): unknown {
  if (!attachments?.length) {
    return input;
  }
  const parts: Array<Record<string, unknown>> = [{ type: "text", text: input }];
  for (const attachment of attachments) {
    if (attachment.kind !== "image" || !attachment.contentBase64) {
      continue;
    }
    parts.push({
      type: "image_url",
      image_url: {
        url: `data:${attachment.mimeType};base64,${attachment.contentBase64}`
      }
    });
  }
  return parts;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}
