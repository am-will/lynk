import type { AuditLog } from "../AuditLog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import { HarnessRunLifecycle, type HarnessActiveRun } from "../harness/HarnessRunLifecycle.js";
import type { ChatAttachment } from "../../protocol/messages.js";
import { OpenCodeEventNormalizer, type OpenCodeRunEventResult } from "./OpenCodeEventNormalizer.js";
import type { OpenCodeServerClient } from "./OpenCodeServerClient.js";
import { directoryForSession } from "./OpenCodeSessionCatalog.js";
import {
  isIdleStatus,
  latestAssistantText,
  parseModelRef,
  usageFromMessages
} from "./OpenCodeNormalizers.js";
import { AdapterFailure, withAdapterDeadline } from "../harness/AdapterFailure.js";

type EmitGatewayEvent = (event: string, payload: unknown) => void;

export class OpenCodeRunDriver {
  private readonly eventNormalizer: OpenCodeEventNormalizer;
  private readonly runs: HarnessRunLifecycle<AbortController>;

  constructor(
    private readonly client: OpenCodeServerClient,
    private readonly sessions: InMemoryHarnessSessionStore,
    private readonly emit: EmitGatewayEvent,
    private readonly audit?: AuditLog,
    private readonly timeoutMs = 600_000
  ) {
    this.eventNormalizer = new OpenCodeEventNormalizer(emit);
    this.runs = new HarnessRunLifecycle(sessions, {
      concurrency: "single",
      busyMessage: "An OpenCode task is already running"
    });
  }

  assertIdle(): void {
    this.runs.assertCanStart("");
  }

  startRun(
    session: HarnessStoredSession,
    options: {
      runId: string;
      text: string;
      model?: string;
      attachments?: ChatAttachment[];
    }
  ): void {
    this.assertIdle();
    const abortController = new AbortController();
    const active = this.runs.start(session, options.runId, abortController, () => abortController.abort());
    void this.processRun(active, session, options.text, options.model, options.attachments);
  }

  async abort(runId?: string): Promise<unknown> {
    const active = this.runs.activeByRun(runId);
    if (!active) {
      return {};
    }
    const session = this.sessions.ensureSession(active.sessionKey);
    active.resource.abort();
    await withAdapterDeadline(
      this.client.abort(session.sessionId, directoryForSession(session)),
      { timeoutMs: Math.min(this.timeoutMs, 5_000), harnessId: "opencode", operation: "abort" }
    );
    return { status: "stopping" };
  }

  close(): void {
    this.runs.close();
  }

  private async processRun(
    active: HarnessActiveRun<AbortController>,
    session: HarnessStoredSession,
    text: string,
    model: string | undefined,
    attachments: ChatAttachment[] | undefined
  ): Promise<void> {
    const directory = directoryForSession(session) ?? this.client.defaultDirectory();
    let lastText = "";
    let eventError: string | undefined;
    const abortController = active.resource;
    const startedAt = Date.now();
    try {
      const eventStream = this.consumeEvents(session, active.runId, directory, abortController.signal, (result) => {
        if (result.textDelta !== undefined) {
          const nextText = result.textReplace ? result.textDelta : `${lastText}${result.textDelta}`;
          lastText = nextText;
          this.sessions.upsertAssistantMessage(session, active.runId, lastText, { persist: false });
        }
        if (result.textFinal !== undefined) {
          lastText = result.textFinal;
          this.sessions.upsertAssistantMessage(session, active.runId, lastText, { persist: false });
        }
        if (result.usage) {
          this.sessions.setUsage(session, result.usage);
        }
        if (result.error) {
          eventError = result.error;
        }
      }).catch((error) => {
        if (!abortController.signal.aborted) eventError = error instanceof Error ? error.message : String(error);
      });
      await withAdapterDeadline(
        this.client.promptAsync({
          sessionId: session.sessionId,
          directory,
          text,
          attachments,
          model: parseModelRef(model),
          agent: this.client.defaultAgentName()
        }),
        { timeoutMs: remainingTime(startedAt, this.timeoutMs), harnessId: "opencode", operation: "prompt", signal: abortController.signal }
      );

      let completed = false;
      while (Date.now() - startedAt < this.timeoutMs) {
        if (abortController.signal.aborted) {
          throw new AdapterFailure("cancelled", "OpenCode run stopped", { harnessId: "opencode", operation: "run" });
        }
        const remainingMs = this.timeoutMs - (Date.now() - startedAt);
        const operationTimeoutMs = Math.max(1, Math.min(5_000, remainingMs));
        const messages = await withAdapterDeadline(
          this.client.messages(session.sessionId, directory),
          { timeoutMs: operationTimeoutMs, harnessId: "opencode", operation: "messages", signal: abortController.signal }
        );
        if (messages) {
          const nextText = latestAssistantText(messages);
          if (nextText && nextText !== lastText) {
            const delta = nextText.startsWith(lastText) ? nextText.slice(lastText.length) : nextText;
            const replace = !nextText.startsWith(lastText);
            lastText = nextText;
            this.sessions.upsertAssistantMessage(session, active.runId, lastText, { persist: false });
            this.emit("chat", { sessionKey: session.key, runId: active.runId, state: "delta", delta, replace });
          }
          this.sessions.setUsage(session, usageFromMessages(messages));
        }
        const status = await withAdapterDeadline(
          this.client.status(directory),
          { timeoutMs: operationTimeoutMs, harnessId: "opencode", operation: "status", signal: abortController.signal }
        );
        if (status && isIdleStatus(status, session.sessionId) && lastText) {
          completed = true;
          break;
        }
        if (eventError) {
          throw new Error(eventError);
        }
        await new Promise((resolve) => setTimeout(resolve, Math.min(400, remainingTime(startedAt, this.timeoutMs))));
      }
      if (!completed) {
        throw new AdapterFailure("timeout", `OpenCode run timed out after ${this.timeoutMs}ms`, {
          harnessId: "opencode",
          operation: "run"
        });
      }
      abortController.abort();
      await withAdapterDeadline(eventStream, {
        timeoutMs: Math.min(1_000, this.timeoutMs),
        harnessId: "opencode",
        operation: "event-stream-close"
      });
      if (eventError) {
        throw new Error(eventError);
      }
      this.sessions.upsertAssistantMessage(session, active.runId, lastText);
      this.emit("chat", {
        sessionKey: session.key,
        runId: active.runId,
        state: "final",
        message: lastText
      });
    } catch (error) {
      abortController.abort();
      if (!(error instanceof AdapterFailure && error.code === "cancelled")) {
        await withAdapterDeadline(
          this.client.abort(session.sessionId, directory),
          { timeoutMs: Math.min(this.timeoutMs, 5_000), harnessId: "opencode", operation: "abort" }
        ).catch(() => undefined);
      }
      this.emit("chat", {
        sessionKey: session.key,
        runId: active.runId,
        state: "error",
        error: error instanceof Error ? error.message : String(error),
        ...(error instanceof AdapterFailure ? { code: error.code } : {})
      });
    } finally {
      this.runs.clear(active);
    }
  }

  private async consumeEvents(
    session: HarnessStoredSession,
    runId: string,
    directory: string,
    signal: AbortSignal,
    onResult: (result: OpenCodeRunEventResult) => void
  ): Promise<void> {
    try {
      const stream = await this.client.subscribe(directory, { signal });
      for await (const event of stream) {
        if (signal.aborted) {
          break;
        }
        const result = this.eventNormalizer.handle(session, runId, event);
        if (result) {
          onResult(result);
        }
        if (result?.done) {
          break;
        }
      }
    } catch (error) {
      if (!signal.aborted) {
        this.audit?.record("opencode_event_stream_error", session.key, {
          error: error instanceof Error ? error.message : String(error)
        });
      }
      throw error;
    }
  }

}

function remainingTime(startedAt: number, timeoutMs: number): number {
  return Math.max(1, timeoutMs - (Date.now() - startedAt));
}
