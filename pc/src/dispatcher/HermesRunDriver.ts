import { HermesApiClient, type HermesRunStatus, type HermesSseEvent } from "./HermesApiClient.js";

export interface HermesActiveRun {
  runId: string;
  sessionId: string;
  controller: AbortController;
}

export type HermesRunDriverEvent =
  | { type: "delta"; delta: string; accumulated: string; raw: HermesSseEvent }
  | { type: "tool"; toolName?: string; status?: string; raw: HermesSseEvent }
  | { type: "status"; status: string; raw: HermesSseEvent };

export interface HermesRunDriverResult {
  active: HermesActiveRun;
  status: HermesRunStatus;
  finalText: string;
}

export class HermesRunDriver {
  constructor(
    private readonly api: HermesApiClient,
    private readonly runTimeoutMs: number
  ) {}

  async createRun(options: {
    input: string;
    sessionId: string;
    model?: string;
    idempotencyKey?: string;
  }): Promise<HermesActiveRun> {
    const created = await this.api.createRun(options);
    return {
      runId: created.runId,
      sessionId: created.sessionId ?? options.sessionId,
      controller: new AbortController()
    };
  }

  async streamRun(
    active: HermesActiveRun,
    onEvent: (event: HermesRunDriverEvent) => void
  ): Promise<HermesRunDriverResult> {
    let latestOutput = "";
    await this.withTimeout(
      this.api.streamRunEvents(active.runId, (event) => {
        latestOutput = this.handleEvent(event, latestOutput, onEvent);
      }, active.controller.signal),
      this.runTimeoutMs
    );

    const status = await this.api.getRun(active.runId);
    const error = outputText(status.error);
    if (error) {
      throw new Error(error);
    }
    return {
      active,
      status,
      finalText: outputText(status.output) ?? latestOutput
    };
  }

  async stopRun(active: HermesActiveRun): Promise<void> {
    active.controller.abort();
    await this.api.stopRun(active.runId);
  }

  async steerRun(active: HermesActiveRun, text: string): Promise<void> {
    await this.api.createRun({
      input: `Additional user guidance for the active Hermes task:\n${text.trim()}`,
      sessionId: active.sessionId,
      idempotencyKey: `hermes-steer-${active.runId}-${Date.now()}`
    });
  }

  private handleEvent(
    event: HermesSseEvent,
    latestOutput: string,
    onEvent: (event: HermesRunDriverEvent) => void
  ): string {
    const toolName = eventToolName(event);
    const status = eventStatus(event);
    if (toolName || event.event.toLowerCase().includes("tool")) {
      onEvent({ type: "tool", toolName, status, raw: event });
    } else if (status && !["completed", "failed", "cancelled"].includes(status)) {
      onEvent({ type: "status", status, raw: event });
    }

    const delta = eventDelta(event);
    if (!delta) {
      return latestOutput;
    }
    const accumulated = latestOutput + delta;
    onEvent({ type: "delta", delta, accumulated, raw: event });
    return accumulated;
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<T>((_, reject) => {
          timer = setTimeout(() => reject(new Error(`Hermes task timed out after ${Math.round(timeoutMs / 1000)} seconds`)), timeoutMs);
        })
      ]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
  }
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? value as Record<string, unknown> : undefined;
}

function firstStringField(value: unknown, keys: string[]): string | undefined {
  if (typeof value === "string" && value.trim()) {
    return value.trim();
  }
  if (Array.isArray(value)) {
    for (const item of value) {
      const nested = firstStringField(item, keys);
      if (nested) {
        return nested;
      }
    }
    return undefined;
  }
  const record = asRecord(value);
  if (!record) {
    return undefined;
  }
  for (const key of keys) {
    const field = record[key];
    if (typeof field === "string" && field.trim()) {
      return field.trim();
    }
  }
  for (const field of Object.values(record)) {
    const nested = firstStringField(field, keys);
    if (nested) {
      return nested;
    }
  }
  return undefined;
}

function outputText(value: unknown): string | undefined {
  return firstStringField(value, ["output", "final_output", "finalMessage", "message", "text", "content", "delta"]);
}

function eventStatus(event: HermesSseEvent): string | undefined {
  const record = asRecord(event.data);
  const value = record?.status ?? record?.state ?? record?.phase;
  return typeof value === "string" ? value.toLowerCase() : undefined;
}

function eventToolName(event: HermesSseEvent): string | undefined {
  const record = asRecord(event.data);
  const nested = asRecord(record?.data) ?? record;
  const value = nested?.toolName ?? nested?.tool ?? nested?.name ?? nested?.function_name;
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function eventDelta(event: HermesSseEvent): string | undefined {
  const record = asRecord(event.data);
  const nested = asRecord(record?.data) ?? record;
  for (const key of ["delta", "text_delta", "output_text", "text"]) {
    const value = nested?.[key];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
  }
  return undefined;
}
