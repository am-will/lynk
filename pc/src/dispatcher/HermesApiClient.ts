import type { ChatAttachment } from "../protocol/messages.js";

export interface HermesApiClientConfig {
  apiBaseUrl: string;
  apiKey: string;
  model: string;
  runTimeoutMs: number;
}

export interface HermesRunCreateOptions {
  input: string;
  sessionId: string;
  model?: string;
  instructions?: string;
  idempotencyKey?: string;
  attachments?: ChatAttachment[];
  serviceTier?: "priority" | null;
}

export interface HermesRunCreateResult {
  runId: string;
  status?: string;
  sessionId?: string;
}

export interface HermesRunStatus {
  runId: string;
  status: string;
  sessionId?: string;
  output?: unknown;
  error?: unknown;
  raw: unknown;
}

export interface HermesSseEvent {
  event: string;
  data: unknown;
  raw: string;
}

export type FetchLike = (input: string | URL, init?: RequestInit) => Promise<Response>;

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function stringField(record: Record<string, unknown> | undefined, key: string): string | undefined {
  const value = record?.[key];
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function normalizeBaseUrl(value: string): string {
  return value.trim().replace(/\/+$/, "");
}

function parseSseBlock(block: string): HermesSseEvent | undefined {
  const lines = block.split(/\r?\n/);
  let event = "message";
  const dataLines: string[] = [];
  for (const line of lines) {
    if (!line || line.startsWith(":")) {
      continue;
    }
    const separatorIndex = line.indexOf(":");
    const field = separatorIndex === -1 ? line : line.slice(0, separatorIndex);
    const rawValue = separatorIndex === -1 ? "" : line.slice(separatorIndex + 1);
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    if (field === "event") {
      event = value || "message";
    } else if (field === "data") {
      dataLines.push(value);
    }
  }
  if (dataLines.length === 0) {
    return undefined;
  }
  const raw = dataLines.join("\n");
  if (raw === "[DONE]") {
    return { event, data: raw, raw };
  }
  try {
    return { event, data: JSON.parse(raw), raw };
  } catch {
    return { event, data: raw, raw };
  }
}

export function parseSseEvents(text: string): HermesSseEvent[] {
  return text
    .split(/\r?\n\r?\n/)
    .map((block) => parseSseBlock(block.trim()))
    .filter((event): event is HermesSseEvent => Boolean(event));
}

export class HermesApiClient {
  private readonly apiBaseUrl: string;
  private readonly dashboardBaseUrl: string;

  constructor(
    private readonly config: HermesApiClientConfig,
    private readonly fetchFn: FetchLike = fetch
  ) {
    this.apiBaseUrl = normalizeBaseUrl(config.apiBaseUrl);
    this.dashboardBaseUrl = this.apiBaseUrl.replace(/\/v1$/i, "");
  }

  async createRun(options: HermesRunCreateOptions): Promise<HermesRunCreateResult> {
    const payload = await this.requestJson("/runs", {
      method: "POST",
      headers: this.headers(options.idempotencyKey),
      body: JSON.stringify({
        input: options.input,
        session_id: options.sessionId,
        model: options.model ?? this.config.model,
        ...(options.instructions ? { instructions: options.instructions } : {}),
        ...(options.attachments?.length ? { attachments: options.attachments } : {}),
        ...(options.serviceTier ? { service_tier: options.serviceTier } : {})
      })
    });
    const record = asRecord(payload);
    const runId = stringField(record, "run_id") ?? stringField(record, "runId") ?? stringField(record, "id");
    if (!runId) {
      throw new Error("Hermes run creation response did not include a run_id.");
    }
    return {
      runId,
      status: stringField(record, "status"),
      sessionId: stringField(record, "session_id") ?? stringField(record, "sessionId")
    };
  }

  async getRun(runId: string): Promise<HermesRunStatus> {
    const payload = await this.requestJson(`/runs/${encodeURIComponent(runId)}`, {
      method: "GET",
      headers: this.headers()
    });
    const record = asRecord(payload);
    return {
      runId: stringField(record, "run_id") ?? stringField(record, "runId") ?? stringField(record, "id") ?? runId,
      status: stringField(record, "status") ?? "unknown",
      sessionId: stringField(record, "session_id") ?? stringField(record, "sessionId"),
      output: record?.output,
      error: record?.error,
      raw: payload
    };
  }

  async listModels(): Promise<unknown> {
    return await this.requestJson("/models", {
      method: "GET",
      headers: this.headers()
    });
  }

  async listSessions(): Promise<unknown> {
    return await this.requestJson("/api/sessions", {
      method: "GET",
      headers: this.headers()
    });
  }

  async listSessionMessages(sessionId: string): Promise<unknown> {
    return await this.requestJson(`/api/sessions/${encodeURIComponent(sessionId)}/messages`, {
      method: "GET",
      headers: this.headers()
    });
  }

  async capabilities(): Promise<unknown> {
    return await this.requestJson("/capabilities", {
      method: "GET",
      headers: this.headers()
    });
  }

  async health(): Promise<unknown> {
    return await this.requestJson("/health", {
      method: "GET",
      headers: this.headers()
    });
  }

  async streamRunEvents(runId: string, onEvent: (event: HermesSseEvent) => void, signal?: AbortSignal): Promise<void> {
    const response = await this.fetchFn(this.url(`/runs/${encodeURIComponent(runId)}/events`), {
      method: "GET",
      headers: this.headers(),
      signal
    });
    await this.assertOk(response, "Hermes run events request failed");
    if (!response.body) {
      return;
    }

    const reader = response.body.getReader();
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
        const event = parseSseBlock(part.trim());
        if (event) {
          onEvent(event);
        }
      }
    }
    buffer += decoder.decode();
    const finalEvent = parseSseBlock(buffer.trim());
    if (finalEvent) {
      onEvent(finalEvent);
    }
  }

  async stopRun(runId: string): Promise<void> {
    await this.requestJson(`/runs/${encodeURIComponent(runId)}/stop`, {
      method: "POST",
      headers: this.headers()
    });
  }

  private async requestJson(path: string, init: RequestInit): Promise<unknown> {
    const response = await this.fetchFn(this.url(path), init);
    await this.assertOk(response, "Hermes API request failed");
    const text = await response.text();
    return text ? JSON.parse(text) : {};
  }

  private async assertOk(response: Response, prefix: string): Promise<void> {
    if (response.ok) {
      return;
    }
    const body = await response.text().catch(() => "");
    throw new Error(`${prefix}: ${response.status} ${response.statusText}${body ? `: ${body}` : ""}`);
  }

  private headers(idempotencyKey?: string): HeadersInit {
    return {
      Authorization: `Bearer ${this.config.apiKey}`,
      "Content-Type": "application/json",
      ...(idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {})
    };
  }

  private url(path: string): string {
    if (path.startsWith("/api/")) {
      return `${this.dashboardBaseUrl}${path}`;
    }
    return `${this.apiBaseUrl}${path}`;
  }
}
