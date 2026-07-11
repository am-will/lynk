import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./InMemoryHarnessSessionStore.js";

export type HarnessRunConcurrency = "single" | "per-session";

export interface HarnessActiveRun<Runtime> {
  sessionKey: string;
  runId: string;
  resource: Runtime;
  cleanup?: () => void;
}

export class HarnessRunLifecycle<Runtime> {
  private readonly activeRuns = new Map<string, HarnessActiveRun<Runtime>>();
  private readonly activeRunBySession = new Map<string, string>();

  constructor(
    private readonly sessions: InMemoryHarnessSessionStore,
    private readonly options: {
      concurrency: HarnessRunConcurrency;
      busyMessage: string;
    }
  ) {}

  assertCanStart(sessionKey: string): void {
    if (this.options.concurrency === "single" && this.activeRuns.size > 0) {
      throw new Error(this.options.busyMessage);
    }
    if (this.options.concurrency === "per-session" && this.activeRunBySession.has(sessionKey)) {
      throw new Error(this.options.busyMessage);
    }
  }

  start(session: HarnessStoredSession, runId: string, resource: Runtime, cleanup?: () => void): HarnessActiveRun<Runtime> {
    this.assertCanStart(session.key);
    const active: HarnessActiveRun<Runtime> = {
      sessionKey: session.key,
      runId,
      resource,
      cleanup
    };
    const key = activeRunKey(session.key, runId);
    this.activeRuns.set(key, active);
    this.activeRunBySession.set(session.key, key);
    this.sessions.setActiveRun(session, runId);
    return active;
  }

  activeFor(sessionKey: string, runId?: string): HarnessActiveRun<Runtime> | undefined {
    if (runId) {
      const active = this.activeRuns.get(activeRunKey(sessionKey, runId));
      return active?.sessionKey === sessionKey ? active : undefined;
    }
    const activeKey = this.activeRunBySession.get(sessionKey);
    return activeKey ? this.activeRuns.get(activeKey) : undefined;
  }

  activeByRun(runId?: string): HarnessActiveRun<Runtime> | undefined {
    if (runId) {
      const matches = [...this.activeRuns.values()].filter((active) => active.runId === runId);
      return matches.length === 1 ? matches[0] : undefined;
    }
    if (this.options.concurrency === "single" && this.activeRuns.size === 1) {
      return this.activeRuns.values().next().value;
    }
    return undefined;
  }

  active(): HarnessActiveRun<Runtime>[] {
    return [...this.activeRuns.values()];
  }

  clear(active: HarnessActiveRun<Runtime>): void {
    const key = activeRunKey(active.sessionKey, active.runId);
    if (this.activeRuns.get(key) !== active) {
      return;
    }
    active.cleanup?.();
    this.activeRuns.delete(key);
    if (this.activeRunBySession.get(active.sessionKey) === key) {
      this.activeRunBySession.delete(active.sessionKey);
    }
    const session = this.sessions.ensureSession(active.sessionKey);
    this.sessions.clearActiveRun(session, active.runId);
  }

  close(): void {
    for (const active of [...this.activeRuns.values()]) {
      this.clear(active);
    }
  }
}

function activeRunKey(sessionKey: string, runId: string): string {
  return `${sessionKey.length}:${sessionKey}${runId}`;
}
