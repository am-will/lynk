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
    this.activeRuns.set(runId, active);
    this.activeRunBySession.set(session.key, runId);
    this.sessions.setActiveRun(session, runId);
    return active;
  }

  activeFor(sessionKey: string, runId?: string): HarnessActiveRun<Runtime> | undefined {
    if (runId) {
      const active = this.activeRuns.get(runId);
      return active?.sessionKey === sessionKey ? active : undefined;
    }
    const activeRunId = this.activeRunBySession.get(sessionKey);
    return activeRunId ? this.activeRuns.get(activeRunId) : undefined;
  }

  activeByRun(runId?: string): HarnessActiveRun<Runtime> | undefined {
    if (runId) {
      return this.activeRuns.get(runId);
    }
    if (this.options.concurrency === "single" && this.activeRuns.size === 1) {
      return this.activeRuns.values().next().value;
    }
    return undefined;
  }

  clear(active: HarnessActiveRun<Runtime>): void {
    if (this.activeRuns.get(active.runId) !== active) {
      return;
    }
    active.cleanup?.();
    this.activeRuns.delete(active.runId);
    if (this.activeRunBySession.get(active.sessionKey) === active.runId) {
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
