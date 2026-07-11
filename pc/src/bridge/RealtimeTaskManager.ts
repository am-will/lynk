import type { AgentRunResult, AgentTaskKind } from "../dispatcher/AgentClient.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import { REALTIME_TOOL_NAMES } from "../protocol/messages.js";
import type { PhoneLocation, RealtimeOutboundMessage, RealtimeToolCallMessage, RealtimeToolResultMessage, UserRequestMessage } from "../protocol/messages.js";
import type { AuditLog } from "./AuditLog.js";

interface RealtimeTaskDelegate {
  handleRealtimeRequest(request: UserRequestMessage, options: RealtimeTaskDelegateOptions): Promise<AgentRunResult>;
  stopRealtimeTurn(deviceId: string, reason?: string): Promise<void>;
  steerRealtimeTurn(deviceId: string, guidance: string, options: RealtimeTaskDelegateOptions): Promise<void>;
}

interface RealtimeTaskManagerOptions {
  dispatcher?: Pick<Dispatcher, "handleUserRequest" | "stopActiveTurn" | "steerActiveTurn">;
  taskDelegate?: RealtimeTaskDelegate;
  sendRealtime: (deviceId: string, message: RealtimeOutboundMessage) => void;
  webSearch?: {
    search(options: { deviceId: string; query: string; apiKey?: string; location?: PhoneLocation }): Promise<string>;
  };
  getRealtimeLocation?: (deviceId: string, voiceSessionId: string) => PhoneLocation | undefined;
  getRealtimeApiKey?: (deviceId: string, voiceSessionId: string) => string | undefined;
  isVoiceSessionActive?: (deviceId: string, voiceSessionId: string) => boolean;
  audit?: AuditLog;
  maxQueueSize?: number;
  taskTimeoutMs?: number;
  completedResultTtlMs?: number;
  maxCompletedResults?: number;
  now?: () => number;
}

interface QueuedTask {
  deviceId: string;
  voiceSessionId: string;
  callId: string;
  instruction: string;
  urgency: "normal" | "interrupt";
  kind: "general" | "phone";
  model?: string;
  reasoningEffort?: string;
}

interface RealtimeTaskRoutingContext {
  model?: string;
  reasoningEffort?: string;
}

interface RealtimeTaskDelegateOptions extends RealtimeTaskRoutingContext {
  taskKind: AgentTaskKind;
  callId: string;
}

interface DeviceTaskState {
  deviceId: string;
  voiceSessionId: string;
  detached?: boolean;
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
    if (this.options.isVoiceSessionActive && !this.options.isVoiceSessionActive(message.deviceId, message.voiceSessionId)) {
      this.sendUntrackedResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId, ok: false, status: "failed", error: "Realtime voice session is no longer active."
      });
      return;
    }
    const duplicate = this.findAcceptedCall(message.deviceId, message.voiceSessionId, message.callId);
    if (duplicate === "active" || duplicate === "queued") {
      this.sendStatus(message.deviceId, message.voiceSessionId);
      return;
    }
    if (duplicate && typeof duplicate !== "string") {
      this.options.sendRealtime(message.deviceId, duplicate);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.stopPhoneTask || message.name === REALTIME_TOOL_NAMES.stopOpenClawTask || message.name === REALTIME_TOOL_NAMES.stopAgentTask) {
      await this.handleStopToolCall(message);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.steerPhoneTask || message.name === REALTIME_TOOL_NAMES.steerOpenClawTask || message.name === REALTIME_TOOL_NAMES.steerAgentTask) {
      await this.handleSteerToolCall(message);
      return;
    }

    if (message.name === REALTIME_TOOL_NAMES.webSearch) {
      await this.handleWebSearchToolCall(message);
      return;
    }

    const validated = this.validate(message);
    if (!validated.ok) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: validated.error
      });
      return;
    }

    const routingContext = this.routingContextFor(message);
    const task: QueuedTask = {
      deviceId: message.deviceId,
      voiceSessionId: message.voiceSessionId,
      callId: message.callId,
      instruction: validated.instruction,
      urgency: validated.urgency,
      kind: validated.kind,
      model: routingContext?.model,
      reasoningEffort: routingContext?.reasoningEffort
    };

    const state = this.stateFor(message.deviceId, message.voiceSessionId);
    if (task.urgency === "interrupt") {
      await this.interruptActiveTask(state, task);
      state.queue.unshift(task);
      this.sendStatus(message.deviceId, message.voiceSessionId);
      this.processNext(message.deviceId, message.voiceSessionId);
      return;
    }

    if (state.active) {
      if (state.queue.length >= this.maxQueueSize) {
        this.sendResult(message.deviceId, message.voiceSessionId, {
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
      this.sendStatus(message.deviceId, message.voiceSessionId);
      return;
    }

    state.queue.push(task);
    this.sendStatus(message.deviceId, message.voiceSessionId);
    this.processNext(message.deviceId, message.voiceSessionId);
  }

  private async handleStopToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const reason = typeof message.arguments.reason === "string" && message.arguments.reason.trim()
      ? message.arguments.reason.trim()
      : "Stopped by realtime voice";
    await this.cancelSession(message.deviceId, message.voiceSessionId, reason);
    this.sendResult(message.deviceId, message.voiceSessionId, {
      callId: message.callId,
      ok: true,
      status: "completed",
      output: message.name === REALTIME_TOOL_NAMES.stopOpenClawTask || message.name === REALTIME_TOOL_NAMES.stopAgentTask
        ? "Stopped the active agent task and cleared queued realtime tasks."
        : "Stopped the active phone task and cleared queued realtime phone tasks.",
      createResponse: false
    });
  }

  private async handleSteerToolCall(message: RealtimeToolCallMessage): Promise<void> {
    const guidance = typeof message.arguments.guidance === "string"
      ? message.arguments.guidance.trim()
      : "";
    if (!guidance) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: `${message.name} requires non-empty guidance.`
      });
      return;
    }

    const state = this.stateFor(message.deviceId, message.voiceSessionId);
    const routingContext = this.routingContextFor(message);
    if (!state.active) {
      const task: QueuedTask = {
        deviceId: message.deviceId,
        voiceSessionId: message.voiceSessionId,
        callId: message.callId,
        instruction: guidance,
        urgency: "normal",
        kind: message.name === REALTIME_TOOL_NAMES.steerOpenClawTask || message.name === REALTIME_TOOL_NAMES.steerAgentTask ? "general" : "phone",
        model: routingContext?.model,
        reasoningEffort: routingContext?.reasoningEffort
      };
      state.queue.unshift(task);
      this.sendStatus(message.deviceId, message.voiceSessionId);
      this.processNext(message.deviceId, message.voiceSessionId);
      return;
    }

    try {
      await this.steerActiveTurn(
        message.deviceId,
        guidance,
        message.name === REALTIME_TOOL_NAMES.steerOpenClawTask || message.name === REALTIME_TOOL_NAMES.steerAgentTask ? "general" : "phone",
        message.callId,
        routingContext
      );
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: true,
        status: "completed",
      output: message.name === REALTIME_TOOL_NAMES.steerOpenClawTask || message.name === REALTIME_TOOL_NAMES.steerAgentTask ? "Steered the active agent task." : "Steered the active phone task.",
        createResponse: false
      });
      this.sendStatus(message.deviceId, message.voiceSessionId);
    } catch (error) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
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
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: "web_search requires a non-empty query."
      });
      return;
    }
    if (query.length > MAX_WEB_SEARCH_QUERY_LENGTH) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: `web_search query is too long (${query.length}/${MAX_WEB_SEARCH_QUERY_LENGTH}).`
      });
      return;
    }
    if (!this.options.webSearch) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
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
        apiKey: this.options.getRealtimeApiKey?.(message.deviceId, message.voiceSessionId),
        location: this.options.getRealtimeLocation?.(message.deviceId, message.voiceSessionId)
      });
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: true,
        status: "completed",
        output
      });
    } catch (error) {
      this.sendResult(message.deviceId, message.voiceSessionId, {
        callId: message.callId,
        ok: false,
        status: "failed",
        error: error instanceof Error ? error.message : String(error)
      });
    }
  }

  async cancelDevice(deviceId: string, reason = "Realtime phone task cancelled"): Promise<void> {
    const states = [...this.states.values()].filter((state) => state.deviceId === deviceId);
    await Promise.all(states.map((state) => this.cancelSession(deviceId, state.voiceSessionId, reason)));
    if (states.length === 0) await this.stopActiveTurn(deviceId, reason);
  }

  async cancelSession(deviceId: string, voiceSessionId: string, reason = "Realtime phone task cancelled"): Promise<void> {
    const state = this.states.get(this.stateKey(deviceId, voiceSessionId));
    if (!state) return;
    for (const task of state.queue.splice(0)) {
      this.sendResult(deviceId, voiceSessionId, {
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
      this.sendResult(deviceId, voiceSessionId, {
        callId: active.callId,
        ok: false,
        status: "cancelled",
        error: reason
      });
    }
    this.sendStatus(deviceId, voiceSessionId);
  }

  detachSession(deviceId: string, voiceSessionId: string): void {
    const key = this.stateKey(deviceId, voiceSessionId);
    const state = this.states.get(key);
    if (!state) return;
    state.detached = true;
    state.active = undefined;
    state.queue = [];
    this.states.delete(key);
  }

  async failDevice(deviceId: string, reason: string): Promise<void> {
    const states = [...this.states.values()].filter((state) => state.deviceId === deviceId);
    await Promise.all(states.map((state) => this.failSession(state, reason)));
  }

  private async failSession(state: DeviceTaskState, reason: string): Promise<void> {
    const { deviceId, voiceSessionId } = state;
    const active = state.active;
    const tasks = [...(active ? [active] : []), ...state.queue];
    state.active = undefined;
    state.queue = [];
    for (const task of tasks) {
      this.options.audit?.record("realtime_task_failed", deviceId, {
        callId: task.callId,
        reason
      });
      this.sendResult(deviceId, voiceSessionId, {
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
    this.sendStatus(deviceId, voiceSessionId);
  }

  private async interruptActiveTask(state: DeviceTaskState, nextTask: QueuedTask): Promise<void> {
    const active = state.active;
    if (!active) {
      return;
    }
    state.active = undefined;
    await this.stopActiveTurn(nextTask.deviceId, `Interrupted by newer realtime ${nextTask.kind === "phone" ? "phone" : "Open Claw"} task`);
    this.sendResult(nextTask.deviceId, nextTask.voiceSessionId, {
      callId: active.callId,
      ok: false,
      status: "cancelled",
      error: `Interrupted by newer realtime ${nextTask.kind === "phone" ? "phone" : "Open Claw"} task.`
    });
  }

  private processNext(deviceId: string, voiceSessionId: string): void {
    const state = this.stateFor(deviceId, voiceSessionId);
    if (state.detached || state.active) {
      return;
    }
    const task = state.queue.shift();
    if (!task) {
      this.sendStatus(deviceId, voiceSessionId);
      return;
    }
    state.active = task;
    this.sendStatus(deviceId, voiceSessionId);
    void this.runTask(task);
  }

  private async runTask(task: QueuedTask): Promise<void> {
    const state = this.stateFor(task.deviceId, task.voiceSessionId);
    this.options.audit?.record("realtime_task_started", task.deviceId, {
      callId: task.callId,
      instruction: task.instruction
    });

    try {
      const result = await this.withTimeout(
        this.handleUserRequest(task),
        this.taskTimeoutMs
      );
      if (state.detached) return;
      if (state.completedResults.has(task.callId)) {
        return;
      }
      const error = result.error?.trim();
      this.sendResult(task.deviceId, task.voiceSessionId, {
        callId: task.callId,
        ok: !error,
        status: error ? "failed" : "completed",
        output: result.finalMessage?.trim() || (error ? undefined : task.kind === "phone" ? "Phone task completed." : "Open Claw task completed."),
        error
      });
    } catch (error) {
      if (state.detached) return;
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
        this.sendResult(task.deviceId, task.voiceSessionId, {
          callId: task.callId,
          ok: false,
          status: "timeout",
          error: `${task.kind === "phone" ? "Phone" : "Open Claw"} task timed out after ${Math.round(this.taskTimeoutMs / 1000)} seconds.`
        });
      } else {
        this.sendResult(task.deviceId, task.voiceSessionId, {
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
      if (!state.detached) this.processNext(task.deviceId, task.voiceSessionId);
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
        callId: task.callId,
        model: task.model,
        reasoningEffort: task.reasoningEffort
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

  private async steerActiveTurn(
    deviceId: string,
    guidance: string,
    taskKind: AgentTaskKind,
    callId: string,
    routingContext?: RealtimeTaskRoutingContext
  ): Promise<void> {
    if (this.options.taskDelegate) {
      await this.options.taskDelegate.steerRealtimeTurn(deviceId, guidance, {
        taskKind,
        callId,
        model: routingContext?.model,
        reasoningEffort: routingContext?.reasoningEffort
      });
      return;
    }
    if (!this.options.dispatcher) {
      throw new Error("Realtime task manager is missing a task delegate");
    }
    await this.options.dispatcher.steerActiveTurn(deviceId, guidance);
  }

  private validate(message: RealtimeToolCallMessage): { ok: true; instruction: string; urgency: "normal" | "interrupt"; kind: "general" | "phone" } | { ok: false; error: string } {
    const kind = message.name === REALTIME_TOOL_NAMES.delegateOpenClawTask || message.name === REALTIME_TOOL_NAMES.delegateAgentTask
      ? "general"
      : message.name === REALTIME_TOOL_NAMES.runPhoneTask ? "phone" : undefined;
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

  private findAcceptedCall(deviceId: string, voiceSessionId: string, callId: string): "active" | "queued" | RealtimeToolResultMessage | undefined {
    const state = this.states.get(this.stateKey(deviceId, voiceSessionId));
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

  private sendResult(deviceId: string, voiceSessionId: string, result: Omit<RealtimeToolResultMessage, "type" | "deviceId" | "voiceSessionId">): void {
    const state = this.stateFor(deviceId, voiceSessionId);
    if (result.ok) {
      state.completed += 1;
    } else {
      state.failed += 1;
    }
    const message: RealtimeToolResultMessage = {
      type: "realtime.tool_result",
      deviceId,
      voiceSessionId,
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
    this.sendStatus(deviceId, voiceSessionId);
  }

  private sendUntrackedResult(deviceId: string, voiceSessionId: string, result: Omit<RealtimeToolResultMessage, "type" | "deviceId" | "voiceSessionId">): void {
    this.options.sendRealtime(deviceId, { type: "realtime.tool_result", deviceId, voiceSessionId, ...result });
  }

  private sendStatus(deviceId: string, voiceSessionId: string): void {
    const state = this.stateFor(deviceId, voiceSessionId);
    this.options.sendRealtime(deviceId, {
      type: "realtime.task_status",
      deviceId,
      voiceSessionId,
      running: Boolean(state.active),
      queued: state.queue.length,
      currentTask: state.active?.instruction ?? null,
      completed: state.completed,
      failed: state.failed
    });
  }

  private routingContextFor(message: Pick<RealtimeToolCallMessage, "model" | "reasoningEffort">): RealtimeTaskRoutingContext | undefined {
    const model = message.model?.trim();
    const reasoningEffort = message.reasoningEffort?.trim();
    if (!model && !reasoningEffort) {
      return undefined;
    }
    return {
      ...(model ? { model } : {}),
      ...(reasoningEffort ? { reasoningEffort } : {})
    };
  }

  private stateFor(deviceId: string, voiceSessionId: string): DeviceTaskState {
    const key = this.stateKey(deviceId, voiceSessionId);
    let state = this.states.get(key);
    if (!state) {
      state = {
        deviceId,
        voiceSessionId,
        queue: [],
        completed: 0,
        failed: 0,
        completedResults: new Map()
      };
      this.states.set(key, state);
    }
    return state;
  }

  private stateKey(deviceId: string, voiceSessionId: string): string {
    return `${deviceId}\u0000${voiceSessionId}`;
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
