import type { AgentRunResult, AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import { REALTIME_TOOL_NAMES } from "../protocol/messages.js";
import type { PhoneLocation, RealtimeOutboundMessage, RealtimeToolCallMessage, RealtimeToolResultMessage, UserRequestMessage } from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";

interface RealtimeTaskDelegate {
  handleRealtimeRequest(request: UserRequestMessage, options: { taskKind: AgentTaskKind; callId: string }): Promise<AgentRunResult>;
  stopRealtimeTurn(deviceId: string, reason?: string): Promise<void>;
  steerRealtimeTurn(deviceId: string, guidance: string, options: { taskKind: AgentTaskKind; callId: string }): Promise<void>;
}

interface RealtimeTaskManagerOptions {
  dispatcher?: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn" | "steerActiveTurn">;
  taskDelegate?: RealtimeTaskDelegate;
  sendRealtime: (deviceId: string, message: RealtimeOutboundMessage) => void;
  webSearch?: {
    search(options: { deviceId: string; query: string; apiKey?: string; location?: PhoneLocation }): Promise<string>;
  };
  getRealtimeApiKey?: (deviceId: string) => string | undefined;
  getRealtimeLocation?: (deviceId: string) => PhoneLocation | undefined;
  audit?: AuditLog;
  maxQueueSize?: number;
  taskTimeoutMs?: number;
  completedResultTtlMs?: number;
  maxCompletedResults?: number;
  now?: () => number;
}

interface QueuedTask {
  deviceId: string;
  callId: string;
  instruction: string;
  urgency: "normal" | "interrupt";
  kind: "general" | "phone";
}

interface DeviceTaskState {
  active?: QueuedTask;
  queue: QueuedTask[];
  completed: number;
  failed: number;
  completedResults: Map<string, CompletedResult>;
}

interface CompletedResult {
  message: RealtimeToolResultMessage;
  completedAtMs: number;
}

const DEFAULT_MAX_QUEUE_SIZE = 3;
const DEFAULT_TASK_TIMEOUT_MS = 120_000;
const DEFAULT_COMPLETED_RESULT_TTL_MS = 15 * 60 * 1000;
const DEFAULT_MAX_COMPLETED_RESULTS = 100;
const MAX_INSTRUCTION_LENGTH = 4_000;
const MAX_WEB_SEARCH_QUERY_LENGTH = 1_000;

export class RealtimeTaskManager {
  private readonly states = new Map<string, DeviceTaskState>();
  private readonly maxQueueSize: number;
  private readonly taskTimeoutMs: number;
  private readonly completedResultTtlMs: number;
  private readonly maxCompletedResults: number;
  private readonly now: () => number;

  constructor(private readonly options: RealtimeTaskManagerOptions) {
    this.maxQueueSize = options.maxQueueSize ?? DEFAULT_MAX_QUEUE_SIZE;
    this.taskTimeoutMs = options.taskTimeoutMs ?? DEFAULT_TASK_TIMEOUT_MS;
    this.completedResultTtlMs = options.completedResultTtlMs ?? DEFAULT_COMPLETED_RESULT_TTL_MS;
    this.maxCompletedResults = options.maxCompletedResults ?? DEFAULT_MAX_COMPLETED_RESULTS;
    this.now = options.now ?? Date.now;
  }

  async handleToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const duplicate = this.findAcceptedCall(message.deviceId, message.callId);
    if (duplicate === "active" || duplicate === "queued") {
      this.sendStatus(message.deviceId);
      return;
    }
    if (duplicate && typeof duplicate !== "string") {
      this.options.sendRealtime(message.deviceId, duplicate);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.stopPhoneTask || message.name === REALTIME_TOOL_NAMES.stopOpenClawTask) {
      await this.handleStopToolCall(message);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.steerPhoneTask || message.name === REALTIME_TOOL_NAMES.steerOpenClawTask) {
      await this.handleSteerToolCall(message);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.webSearch) {
      await this.handleWebSearchToolCall(message);
      return;
    }

    const validated = this.validate(message);
    if (!validated.ok) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: validated.error
      });
      return;
    }

    const task: QueuedTask = {
      deviceId: message.deviceId,
      callId: message.callId,
      instruction: validated.instruction,
      urgency: validated.urgency,
      kind: validated.kind
    };

    const state = this.stateFor(message.deviceId);
    if (task.urgency === "interrupt") {
      await this.interruptActiveTask(state, task);
      state.queue.unshift(task);
      this.sendStatus(message.deviceId);
      this.processNext(message.deviceId);
      return;
    }

    if (state.active) {
      if (state.queue.length >= this.maxQueueSize) {
        this.sendResult(message.deviceId, {
          callId: message.callId,
          ok: false,
          status: "failed",
          error: `Realtime phone task queue is full (${this.maxQueueSize}).`
        });
        return;
      }
      state.queue.push(task);
      this.options.audit?.record("realtime_task_queued", message.deviceId, {
        callId: message.callId,
        queued: state.queue.length
      });
      this.sendStatus(message.deviceId);
      return;
    }

    state.queue.push(task);
    this.sendStatus(message.deviceId);
    this.processNext(message.deviceId);
  }

  private async handleStopToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
      ? message.arguments.reason.trim()
      : "Stopped by realtime voice";
    await this.cancelDevice(message.deviceId, reason);
    this.sendResult(message.deviceId, {
      callId: message.callId,
      ok: true,
      status: "completed",
      output: message.name === REALTIME_TOOL_NAMES.stopOpenClawTask
        ? "Stopped the active Open Claw task and cleared queued realtime tasks."
        : "Stopped the active phone task and cleared queued realtime phone tasks.",
      createResponse: false
    });
  }

  private async handleSteerToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const guidance = typeof message.arguments.guidance === "string"
      ? message.arguments.guidance.trim()
      : "";
    if (!guidance) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: `${message.name} requires non-empty guidance.`
      });
      return;
    }

    const state = this.stateFor(message.deviceId);
    if (!state.active) {
      const task: QueuedTask = {
        deviceId: message.deviceId,
        callId: message.callId,
        instruction: guidance,
        urgency: "normal",
        kind: message.name === REALTIME_TOOL_NAMES.steerOpenClawTask ? "general" : "phone"
      };
      state.queue.unshift(task);
      this.sendStatus(message.deviceId);
      this.processNext(message.deviceId);
      return;
    }

    try {
      await this.steerActiveTurn(
        message.deviceId,
        guidance,
        message.name === REALTIME_TOOL_NAMES.steerOpenClawTask ? "general" : "phone",
        message.callId
      );
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: true,
        status: "completed",
        output: message.name === REALTIME_TOOL_NAMES.steerOpenClawTask ? "Steered the active Open Claw task." : "Steered the active phone task.",
        createResponse: false
      });
      this.sendStatus(message.deviceId);
    } catch (error) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  private async handleWebSearchToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const query = typeof message.arguments.query === "string"
      ? message.arguments.query.trim()
      : "";
    if (!query) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "web_search requires a non-empty query."
      });
      return;
    }
    if (query.length > MAX_WEB_SEARCH_QUERY_LENGTH) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: `web_search query is too long (${query.length}/${MAX_WEB_SEARCH_QUERY_LENGTH}).`
      });
      return;
    }
    if (!this.options.webSearch) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "Realtime web search is not configured."
      });
      return;
    }

    try {
      const output = await this.options.webSearch.search({
        deviceId: message.deviceId,
        query,
        apiKey: this.options.getRealtimeApiKey?.(message.deviceId),
        location: this.options.getRealtimeLocation?.(message.deviceId)
      });
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: true,
        status: "completed",
        output
      });
    } catch (error) {
      this.sendResult(message.deviceId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  async cancelDevice(deviceId: string, reason = "Realtime phone task cancelled"): Promise<void> {
    const state = this.states.get(deviceId);
    if (!state?.active && (!state || state.queue.length === 0)) {
      await this.stopActiveTurn(deviceId, reason);
      return;
    }

    for (const task of state.queue.splice(0)) {
      this.sendResult(deviceId, {
        callId: task.callId,
        ok: false,
        status: "cancelled",
        error: reason
      });
    }

    const active = state.active;
    if (active) {
      state.active = undefined;
      await this.stopActiveTurn(deviceId, reason);
      this.sendResult(deviceId, {
        callId: active.callId,
        ok: false,
        status: "cancelled",
        error: reason
      });
    }
    this.sendStatus(deviceId);
  }

  async failDevice(deviceId: string, reason: string): Promise<void> {
    const state = this.states.get(deviceId);
    if (!state) {
      return;
    }
    const active = state.active;
    const tasks = [...(active ? [active] : []), ...state.queue];
    state.active = undefined;
    state.queue = [];
    for (const task of tasks) {
      this.options.audit?.record("realtime_task_failed", deviceId, {
        callId: task.callId,
        reason
      });
      this.sendResult(deviceId, {
        callId: task.callId,
        ok: false,
        status: "failed",
        error: reason
      });
    }
    if (active) {
      try {
        await this.stopActiveTurn(deviceId, reason);
      } catch (error) {
        this.options.audit?.record("realtime_task_stop_failed", deviceId, {
          callId: active.callId,
          error: error instanceof Error ? error.message : String(error)
        });
      }
    }
    this.sendStatus(deviceId);
  }

  private async interruptActiveTask(state: DeviceTaskState, nextTask: QueuedTask): Promise<void> {
    const active = state.active;
    if (!active) {
      return;
    }
    state.active = undefined;
    await this.stopActiveTurn(nextTask.deviceId, `Interrupted by newer realtime ${nextTask.kind === "phone" ? "phone" : "Open Claw"} task`);
    this.sendResult(nextTask.deviceId, {
      callId: active.callId,
      ok: false,
      status: "cancelled",
      error: `Interrupted by newer realtime ${nextTask.kind === "phone" ? "phone" : "Open Claw"} task.`
    });
  }

  private processNext(deviceId: string): void {
    const state = this.stateFor(deviceId);
    if (state.active) {
      return;
    }
    const task = state.queue.shift();
    if (!task) {
      this.sendStatus(deviceId);
      return;
    }
    state.active = task;
    this.sendStatus(deviceId);
    void this.runTask(task);
  }

  private async runTask(task: QueuedTask): Promise<void> {
    const state = this.stateFor(task.deviceId);
    this.options.audit?.record("realtime_task_started", task.deviceId, {
      callId: task.callId,
      instruction: task.instruction
    });

    try {
      const result = await this.withTimeout(
        this.handleUserRequest(task),
        this.taskTimeoutMs
      );
      if (state.completedResults.has(task.callId)) {
        return;
      }
      const error = result.error?.trim();
      this.sendResult(task.deviceId, {
        callId: task.callId,
        ok: !error,
        status: error ? "failed" : "completed",
        output: result.finalMessage?.trim() || (error ? undefined : task.kind === "phone" ? "Phone task completed." : "Open Claw task completed."),
        error
      });
    } catch (error) {
      if (state.completedResults.has(task.callId)) {
        return;
      }
      const message = error instanceof Error ? error.message : String(error);
      if (message === "realtime task timeout") {
        try {
          await this.stopActiveTurn(task.deviceId, `Realtime ${task.kind === "phone" ? "phone" : "Open Claw"} task timed out after ${this.taskTimeoutMs}ms`);
        } catch (stopError) {
          this.options.audit?.record("realtime_task_stop_failed", task.deviceId, {
            callId: task.callId,
            error: stopError instanceof Error ? stopError.message : String(stopError)
          });
        }
        this.sendResult(task.deviceId, {
          callId: task.callId,
          ok: false,
          status: "timeout",
          error: `${task.kind === "phone" ? "Phone" : "Open Claw"} task timed out after ${Math.round(this.taskTimeoutMs / 1000)} seconds.`
        });
      } else {
        this.sendResult(task.deviceId, {
          callId: task.callId,
          ok: false,
          status: "failed",
          error: message
        });
      }
    } finally {
      if (state.active?.callId === task.callId) {
        state.active = undefined;
      }
      this.processNext(task.deviceId);
    }
  }

  private async handleUserRequest(task: QueuedTask): Promise<AgentRunResult> {
    const request: UserRequestMessage = {
      type: "user_request",
      deviceId: task.deviceId,
      inputType: "text",
      text: task.instruction
    };
    if (this.options.taskDelegate) {
      return await this.options.taskDelegate.handleRealtimeRequest(request, {
        taskKind: task.kind,
        callId: task.callId
      });
    }
    if (!this.options.dispatcher) {
      throw new Error("Realtime task manager is missing a task delegate");
    }
    return await this.options.dispatcher.handleUserRequest(request, {
      taskKind: task.kind
    });
  }

  private async stopActiveTurn(deviceId: string, reason: string): Promise<void> {
    if (this.options.taskDelegate) {
      await this.options.taskDelegate.stopRealtimeTurn(deviceId, reason);
      return;
    }
    if (!this.options.dispatcher) {
      throw new Error("Realtime task manager is missing a task delegate");
    }
    await this.options.dispatcher.stopActiveTurn(deviceId, reason);
  }

  private async steerActiveTurn(deviceId: string, guidance: string, taskKind: AgentTaskKind, callId: string): Promise<void> {
    if (this.options.taskDelegate) {
      await this.options.taskDelegate.steerRealtimeTurn(deviceId, guidance, { taskKind, callId });
      return;
    }
    if (!this.options.dispatcher) {
      throw new Error("Realtime task manager is missing a task delegate");
    }
    await this.options.dispatcher.steerActiveTurn(deviceId, guidance);
  }

  private validate(message: RealtimeToolCallMessage): { ok: true; instruction: string; urgency: "normal" | "interrupt"; kind: "general" | "phone" } | { ok: false; error: string } {
    const kind = message.name === REALTIME_TOOL_NAMES.delegateOpenClawTask ? "general" : message.name === REALTIME_TOOL_NAMES.runPhoneTask ? "phone" : undefined;
    if (!kind) {
      return { ok: false, error: `Unsupported realtime tool ${message.name}.` };
    }

    const instruction = typeof message.arguments.instruction === "string"
      ? message.arguments.instruction.trim()
      : typeof message.arguments.task === "string"
      ? message.arguments.task.trim()
      : "";
    if (!instruction) {
      return { ok: false, error: `${message.name} requires a non-empty instruction.` };
    }
    if (instruction.length > MAX_INSTRUCTION_LENGTH) {
      return { ok: false, error: `${message.name} instruction is too long (${instruction.length}/${MAX_INSTRUCTION_LENGTH}).` };
    }

    const urgency = message.arguments.urgency === "interrupt" ? "interrupt" : "normal";
    return { ok: true, instruction, urgency, kind };
  }

  private findAcceptedCall(deviceId: string, callId: string): "active" | "queued" | RealtimeToolResultMessage | undefined {
    const state = this.states.get(deviceId);
    if (!state) {
      return undefined;
    }
    if (state.active?.callId === callId) {
      return "active";
    }
    if (state.queue.some((task) => task.callId === callId)) {
      return "queued";
    }
    this.pruneCompletedResults(state);
    return state.completedResults.get(callId)?.message;
  }

  private sendResult(deviceId: string, result: Omit<RealtimeToolResultMessage, "type" | "deviceId">): void {
    const state = this.stateFor(deviceId);
    if (result.ok) {
      state.completed += 1;
    } else {
      state.failed += 1;
    }
    const message: RealtimeToolResultMessage = {
      type: "realtime.tool_result",
      deviceId,
      ...result
    };
    this.pruneCompletedResults(state);
    state.completedResults.set(result.callId, {
      message,
      completedAtMs: this.now()
    });
    this.pruneCompletedResults(state);
    this.options.audit?.record("realtime_task_result", deviceId, message);
    this.options.sendRealtime(deviceId, message);
    this.sendStatus(deviceId);
  }

  private sendStatus(deviceId: string): void {
    const state = this.stateFor(deviceId);
    this.options.sendRealtime(deviceId, {
      type: "realtime.task_status",
      deviceId,
      running: Boolean(state.active),
      queued: state.queue.length,
      currentTask: state.active?.instruction ?? null,
      completed: state.completed,
      failed: state.failed
    });
  }

  private stateFor(deviceId: string): DeviceTaskState {
    let state = this.states.get(deviceId);
    if (!state) {
      state = {
        queue: [],
        completed: 0,
        failed: 0,
        completedResults: new Map()
      };
      this.states.set(deviceId, state);
    }
    return state;
  }

  private pruneCompletedResults(state: DeviceTaskState): void {
    const cutoff = this.now() - this.completedResultTtlMs;
    for (const [callId, result] of state.completedResults) {
      if (result.completedAtMs < cutoff) {
        state.completedResults.delete(callId);
      }
    }
    while (state.completedResults.size > this.maxCompletedResults) {
      const oldest = state.completedResults.keys().next().value;
      if (!oldest) {
        break;
      }
      state.completedResults.delete(oldest);
    }
  }

  private async withTimeout<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
    let timer: NodeJS.Timeout | undefined;
    try {
      return await Promise.race([
        promise,
        new Promise<T>((_, reject) => {
          timer = setTimeout(() => reject(new Error("realtime task timeout")), timeoutMs);
        })
      ]);
    } finally {
      if (timer) {
        clearTimeout(timer);
      }
    }
  }
}
