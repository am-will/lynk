import type { AgentRunResult } from "../dispatcher/AgentClient.js";
import type { ChatOutboundMessage } from "../protocol/messages.js";

interface RunWaiter {
  deviceId: string;
  sessionKey: string;
  runId: string;
  resolve: (result: AgentRunResult) => void;
  reject: (error: Error) => void;
  timer: NodeJS.Timeout;
}

const REALTIME_CHAT_RUN_TIMEOUT_MS = 10 * 60 * 1000;

export class OpenClawRunWaiters {
  private readonly waiters = new Map<string, RunWaiter>();

  waitForRun(deviceId: string, sessionKey: string, runId: string): Promise<AgentRunResult> {
    return new Promise<AgentRunResult>((resolve, reject) => {
      const key = runWaiterKey(deviceId, runId);
      const timer = setTimeout(() => {
        this.waiters.delete(key);
        reject(new Error(`OpenClaw chat run ${runId} timed out`));
      }, REALTIME_CHAT_RUN_TIMEOUT_MS);
      this.waiters.set(key, {
        deviceId,
        sessionKey,
        runId,
        resolve,
        reject,
        timer
      });
    });
  }

  settleRun(message: Extract<ChatOutboundMessage, { type: "chat.final" | "chat.error" }>): void {
    const runId = message.runId;
    if (!runId) {
      return;
    }
    const key = runWaiterKey(message.deviceId, runId);
    const waiter = this.waiters.get(key);
    if (!waiter || ("sessionKey" in message && message.sessionKey && message.sessionKey !== waiter.sessionKey)) {
      return;
    }

    clearTimeout(waiter.timer);
    this.waiters.delete(key);
    if (message.type === "chat.final") {
      waiter.resolve({ finalMessage: message.text });
    } else {
      waiter.reject(new Error(message.message));
    }
  }

  rejectForDevice(deviceId: string, error: Error): void {
    for (const [key, waiter] of this.waiters) {
      if (waiter.deviceId !== deviceId) {
        continue;
      }
      clearTimeout(waiter.timer);
      this.waiters.delete(key);
      waiter.reject(error);
    }
  }

  close(error = new Error("OpenClaw chat bridge closed")): void {
    for (const [key, waiter] of this.waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
      this.waiters.delete(key);
    }
  }
}

function runWaiterKey(deviceId: string, runId: string): string {
  return `${deviceId}:${runId}`;
}
