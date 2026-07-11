import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "./InMemoryHarnessSessionStore.js";

export type HarnessRunConcurrency = "single" | "per-session";
export type HarnessRunPhase = "idle" | "starting" | "running" | "stopping";

export interface HarnessRunReservation<Metadata = undefined> {
  reservationId: string;
  sessionKey: string;
  metadata?: Metadata;
}

export interface HarnessIdleRun {
  phase: "idle";
  sessionKey: string;
}

export interface HarnessStartingRun<Metadata = undefined> extends HarnessRunReservation<Metadata> {
  phase: "starting";
}

export interface HarnessActiveRun<Runtime, Metadata = undefined> extends HarnessRunReservation<Metadata> {
  phase: "running";
  runId: string;
  resource: Runtime;
  cleanup?: () => void;
}

export interface HarnessStoppingBeforeStart<Metadata = undefined> extends HarnessRunReservation<Metadata> {
  phase: "stopping";
  runId: null;
  resource: null;
  reason: string;
}

export interface HarnessStoppingRun<Runtime, Metadata = undefined> extends HarnessRunReservation<Metadata> {
  phase: "stopping";
  runId: string;
  resource: Runtime;
  cleanup?: () => void;
  reason: string;
}

export type HarnessOwnedRun<Runtime, Metadata = undefined> =
  | HarnessActiveRun<Runtime, Metadata>
  | HarnessStoppingRun<Runtime, Metadata>;

export type HarnessRunState<Runtime, Metadata = undefined> =
  | HarnessIdleRun
  | HarnessStartingRun<Metadata>
  | HarnessActiveRun<Runtime, Metadata>
  | HarnessStoppingBeforeStart<Metadata>
  | HarnessStoppingRun<Runtime, Metadata>;

export interface HarnessRunPromotion<Runtime, Metadata = undefined> {
  run: HarnessOwnedRun<Runtime, Metadata>;
  stopRequested: boolean;
}

export class HarnessRunBusyError<Runtime, Metadata = undefined> extends Error {
  constructor(readonly state: Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun>, message: string) {
    super(message);
    this.name = "HarnessRunBusyError";
  }
}

export class HarnessRunLifecycle<Runtime, Metadata = undefined> {
  private readonly statesBySession = new Map<string, Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun>>();
  private readonly sessionByReservation = new Map<string, string>();
  private reservationSequence = 0;

  constructor(
    private readonly sessions: InMemoryHarnessSessionStore | undefined,
    private readonly options: {
      concurrency: HarnessRunConcurrency;
      busyMessage: string;
    }
  ) {}

  stateFor(sessionKey: string): HarnessRunState<Runtime, Metadata> {
    return this.statesBySession.get(sessionKey) ?? { phase: "idle", sessionKey };
  }

  assertCanStart(sessionKey: string): void {
    const busy = this.busyState(sessionKey);
    if (busy) {
      throw new HarnessRunBusyError(busy, this.options.busyMessage);
    }
  }

  reserve(sessionKey: string, metadata?: Metadata): HarnessRunReservation<Metadata> {
    this.assertCanStart(sessionKey);
    const reservation: HarnessRunReservation<Metadata> = {
      reservationId: `reservation-${++this.reservationSequence}`,
      sessionKey,
      ...(metadata === undefined ? {} : { metadata })
    };
    const starting: HarnessStartingRun<Metadata> = {
      phase: "starting",
      ...reservation
    };
    this.statesBySession.set(sessionKey, starting);
    this.sessionByReservation.set(reservation.reservationId, sessionKey);
    return reservation;
  }

  canEnterHarness(reservation: HarnessRunReservation<Metadata>): boolean {
    return this.stateForReservation(reservation)?.phase === "starting";
  }

  stateForReservation(reservation: HarnessRunReservation<Metadata>): HarnessRunState<Runtime, Metadata> | undefined {
    const sessionKey = this.sessionByReservation.get(reservation.reservationId);
    if (!sessionKey) {
      return undefined;
    }
    const state = this.statesBySession.get(sessionKey);
    return state?.reservationId === reservation.reservationId ? state : undefined;
  }

  promote(
    reservation: HarnessRunReservation<Metadata>,
    sessionKey: string,
    runId: string,
    resource: Runtime,
    cleanup?: () => void
  ): HarnessRunPromotion<Runtime, Metadata> {
    const current = this.stateForReservation(reservation);
    if (!current || (current.phase !== "starting" && !isStoppingBeforeStart(current))) {
      throw new Error(`Harness run reservation ${reservation.reservationId} is no longer startable.`);
    }
    this.moveReservation(current, sessionKey);
    const shared = {
      reservationId: reservation.reservationId,
      sessionKey,
      runId,
      resource,
      ...(current.metadata === undefined ? {} : { metadata: current.metadata }),
      ...(cleanup ? { cleanup } : {})
    };
    const run: HarnessOwnedRun<Runtime, Metadata> = current.phase === "stopping"
      ? { phase: "stopping", ...shared, reason: current.reason }
      : { phase: "running", ...shared };
    this.statesBySession.set(sessionKey, run);
    this.sessions?.setActiveRun(this.sessions.ensureSession(sessionKey), runId);
    return { run, stopRequested: run.phase === "stopping" };
  }

  start(
    session: HarnessStoredSession,
    runId: string,
    resource: Runtime,
    cleanup?: () => void
  ): HarnessActiveRun<Runtime, Metadata> {
    this.assertCanStart(session.key);
    const active: HarnessActiveRun<Runtime, Metadata> = {
      phase: "running",
      reservationId: `reservation-${++this.reservationSequence}`,
      sessionKey: session.key,
      runId,
      resource,
      ...(cleanup ? { cleanup } : {})
    };
    this.statesBySession.set(session.key, active);
    this.sessionByReservation.set(active.reservationId, session.key);
    this.sessions?.setActiveRun(session, runId);
    return active;
  }

  requestStop(sessionKey: string, reason: string, runId?: string): HarnessRunState<Runtime, Metadata> {
    const current = this.statesBySession.get(sessionKey);
    if (!current || (runId && (!isOwnedRun(current) || current.runId !== runId))) {
      return { phase: "idle", sessionKey };
    }
    if (current.phase === "stopping") {
      return current;
    }
    const stopping: HarnessStoppingBeforeStart<Metadata> | HarnessStoppingRun<Runtime, Metadata> = current.phase === "starting"
      ? {
          phase: "stopping",
          reservationId: current.reservationId,
          sessionKey: current.sessionKey,
          runId: null,
          resource: null,
          reason,
          ...(current.metadata === undefined ? {} : { metadata: current.metadata })
        }
      : {
          ...current,
          phase: "stopping",
          reason
        };
    this.statesBySession.set(sessionKey, stopping);
    return stopping;
  }

  rollback(reservation: HarnessRunReservation<Metadata>): boolean {
    const current = this.stateForReservation(reservation);
    if (!current || (current.phase !== "starting" && !isStoppingBeforeStart(current))) {
      return false;
    }
    this.removeState(current);
    return true;
  }

  settle(sessionKey: string, runId?: string): boolean {
    const current = this.statesBySession.get(sessionKey);
    if (!current || !isOwnedRun(current) || (runId && current.runId !== runId)) {
      return false;
    }
    this.removeState(current);
    return true;
  }

  activeFor(sessionKey: string, runId?: string): HarnessActiveRun<Runtime, Metadata> | undefined {
    const state = this.statesBySession.get(sessionKey);
    if (!state || state.phase !== "running" || (runId && state.runId !== runId)) {
      return undefined;
    }
    return state;
  }

  activeByRun(runId?: string): HarnessActiveRun<Runtime, Metadata> | undefined {
    const active = this.active();
    if (runId) {
      const matches = active.filter((run) => run.runId === runId);
      return matches.length === 1 ? matches[0] : undefined;
    }
    return this.options.concurrency === "single" && active.length === 1 ? active[0] : undefined;
  }

  active(): HarnessActiveRun<Runtime, Metadata>[] {
    return [...this.statesBySession.values()].filter(isActiveRun);
  }

  clear(active: HarnessActiveRun<Runtime, Metadata>): void {
    const current = this.statesBySession.get(active.sessionKey);
    if (!current || !isOwnedRun(current) || current.reservationId !== active.reservationId) {
      return;
    }
    this.removeState(current);
  }

  close(): void {
    for (const state of [...this.statesBySession.values()]) {
      this.removeState(state);
    }
  }

  private busyState(sessionKey: string): Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun> | undefined {
    if (this.options.concurrency === "single") {
      return this.statesBySession.values().next().value;
    }
    return this.statesBySession.get(sessionKey);
  }

  private moveReservation(
    current: HarnessStartingRun<Metadata> | HarnessStoppingBeforeStart<Metadata>,
    sessionKey: string
  ): void {
    if (current.sessionKey === sessionKey) {
      return;
    }
    const target = this.statesBySession.get(sessionKey);
    if (target && target.reservationId !== current.reservationId) {
      throw new HarnessRunBusyError(target, this.options.busyMessage);
    }
    this.statesBySession.delete(current.sessionKey);
    this.sessionByReservation.set(current.reservationId, sessionKey);
  }

  private removeState(state: Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun>): void {
    this.statesBySession.delete(state.sessionKey);
    this.sessionByReservation.delete(state.reservationId);
    if (!isOwnedRun(state)) {
      return;
    }
    this.sessions?.clearActiveRun(this.sessions.ensureSession(state.sessionKey), state.runId);
    state.cleanup?.();
  }
}

function isStoppingBeforeStart<Runtime, Metadata>(
  state: HarnessRunState<Runtime, Metadata>
): state is HarnessStoppingBeforeStart<Metadata> {
  return state.phase === "stopping" && state.runId === null;
}

function isOwnedRun<Runtime, Metadata>(
  state: Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun>
): state is HarnessOwnedRun<Runtime, Metadata> {
  return state.phase === "running" || (state.phase === "stopping" && state.runId !== null);
}

function isActiveRun<Runtime, Metadata>(
  state: Exclude<HarnessRunState<Runtime, Metadata>, HarnessIdleRun>
): state is HarnessActiveRun<Runtime, Metadata> {
  return state.phase === "running";
}
