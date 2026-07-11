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
