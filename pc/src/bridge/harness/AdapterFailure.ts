export type AdapterFailureCode =
  | "not_found"
  | "auth"
  | "unavailable"
  | "timeout"
  | "protocol"
  | "rejected"
  | "cancelled";

export class AdapterFailure extends Error {
  constructor(
    readonly code: AdapterFailureCode,
    message: string,
    options: { cause?: unknown; harnessId?: string; operation?: string } = {}
  ) {
    super(message, options.cause === undefined ? undefined : { cause: options.cause });
    this.name = "AdapterFailure";
    this.harnessId = options.harnessId;
    this.operation = options.operation;
  }

  readonly harnessId?: string;
  readonly operation?: string;
}

export function adapterFailure(
  error: unknown,
  fallback: { code: AdapterFailureCode; message: string; harnessId?: string; operation?: string }
): AdapterFailure {
  if (error instanceof AdapterFailure) {
    return error;
  }
  return new AdapterFailure(fallback.code, fallback.message, {
    cause: error,
    harnessId: fallback.harnessId,
    operation: fallback.operation
  });
}

export function isAdapterFailure(error: unknown, code?: AdapterFailureCode): error is AdapterFailure {
  return error instanceof AdapterFailure && (code === undefined || error.code === code);
}

export function translateAdapterError(
  error: unknown,
  options: { harnessId: string; operation: string; fallbackCode?: AdapterFailureCode }
): AdapterFailure {
  if (error instanceof AdapterFailure) return error;
  const record = error && typeof error === "object" ? error as Record<string, unknown> : undefined;
  const rawCode = record?.code;
  const status = numberValue(record?.status)
    ?? numberValue(record?.statusCode)
    ?? numberValue((record?.response as Record<string, unknown> | undefined)?.status);
  const message = error instanceof Error ? error.message : String(error);
  const normalized = message.toLowerCase();
  const code: AdapterFailureCode = status === 404 || rawCode === "not_found" || /\b(not found|does not exist|unknown session)\b/.test(normalized)
    ? "not_found"
    : status === 401 || status === 403 || /\b(unauthorized|forbidden|authentication failed|permission denied)\b/.test(normalized)
      ? "auth"
      : status === 408 || status === 504 || /\b(timed out|timeout)\b/.test(normalized)
        ? "timeout"
        : (error as { name?: unknown })?.name === "AbortError" || /\bcancelled\b/.test(normalized)
          ? "cancelled"
          : (status !== undefined && status >= 500) || /\b(econnrefused|econnreset|enotfound|socket hang up|unavailable)\b/.test(normalized)
            ? "unavailable"
            : options.fallbackCode ?? "protocol";
  return new AdapterFailure(code, message, { cause: error, ...options });
}

function numberValue(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) ? value : undefined;
}

export async function withAdapterDeadline<T>(
  operation: Promise<T> | (() => Promise<T>),
  options: { timeoutMs: number; harnessId?: string; operation?: string; signal?: AbortSignal }
): Promise<T> {
  if (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0) {
    throw new RangeError("Adapter deadline must be a positive finite number");
  }
  if (options.signal?.aborted) {
    throw new AdapterFailure("cancelled", `${options.operation ?? "Adapter operation"} was cancelled`, options);
  }

  let timer: NodeJS.Timeout | undefined;
  let abortListener: (() => void) | undefined;
  const timeout = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new AdapterFailure(
      "timeout",
      `${options.operation ?? "Adapter operation"} timed out after ${options.timeoutMs}ms`,
      options
    )), options.timeoutMs);
    timer.unref?.();
  });
  const cancelled = options.signal
    ? new Promise<never>((_, reject) => {
        abortListener = () => reject(new AdapterFailure(
          "cancelled",
          `${options.operation ?? "Adapter operation"} was cancelled`,
          options
        ));
        options.signal?.addEventListener("abort", abortListener, { once: true });
      })
    : undefined;

  try {
    const pending = typeof operation === "function" ? operation() : operation;
    return await Promise.race(cancelled ? [pending, timeout, cancelled] : [pending, timeout]);
  } finally {
    if (timer) clearTimeout(timer);
    if (abortListener) options.signal?.removeEventListener("abort", abortListener);
  }
}
