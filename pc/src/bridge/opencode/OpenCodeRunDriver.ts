import type { AuditLog } from "../AuditLog.js";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
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

interface ActiveRun {
  sessionKey: string;
  runId: string;
  abortController?: AbortController;
}

type EmitGatewayEvent = (event: string, payload: unknown) => void;

export class OpenCodeRunDriver {
  private readonly eventNormalizer: OpenCodeEventNormalizer;
  private active?: ActiveRun;

  constructor(
    private readonly client: OpenCodeServerClient,
    private readonly sessions: InMemoryHarnessSessionStore,
    private readonly emit: EmitGatewayEvent,
    private readonly audit?: AuditLog
  ) {
    this.eventNormalizer = new OpenCodeEventNormalizer(emit);
  }

  assertIdle(): void {
    if (this.active) {
      throw new Error("An OpenCode task is already running");
    }
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
    this.sessions.setActiveRun(session, options.runId);
    this.active = { sessionKey: session.key, runId: options.runId };
    void this.processRun(session, options.runId, options.text, options.model, options.attachments);
  }

  async abort(runId?: string): Promise<unknown> {
    if (!this.active || (runId && this.active.runId !== runId)) {
      return {};
    }
    const session = this.sessions.ensureSession(this.active.sessionKey);
    this.active.abortController?.abort();
    await this.client.abort(session.sessionId, directoryForSession(session));
    this.emit("chat", {
      sessionKey: this.active.sessionKey,
      runId: this.active.runId,
      state: "error",
      error: "OpenCode run stopped."
    });
    return { status: "stopping" };
  }

  close(): void {
    this.active?.abortController?.abort();
    this.active = undefined;
  }

  private async processRun(
    session: HarnessStoredSession,
    runId: string,
    text: string,
    model: string | undefined,
    attachments: ChatAttachment[] | undefined
  ): Promise<void> {
    const directory = directoryForSession(session) ?? this.client.defaultDirectory();
    let lastText = "";
    let eventError: string | undefined;
    const abortController = new AbortController();
    if (this.active?.runId === runId) {
      this.active.abortController = abortController;
    }
    try {
      const eventStream = this.consumeEvents(session, runId, directory, abortController.signal, (result) => {
        if (result.textDelta !== undefined) {
          const nextText = result.textReplace ? result.textDelta : `${lastText}${result.textDelta}`;
          lastText = nextText;
          this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
        }
        if (result.textFinal !== undefined) {
          lastText = result.textFinal;
          this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
        }
        if (result.usage) {
          this.sessions.setUsage(session, result.usage);
        }
        if (result.error) {
          eventError = result.error;
        }
      });
      await this.client.promptAsync({
        sessionId: session.sessionId,
        directory,
        text,
        attachments,
        model: parseModelRef(model),
        agent: this.client.defaultAgentName()
      });

      const startedAt = Date.now();
      while (Date.now() - startedAt < 600_000) {
        const messages = await this.client.messages(session.sessionId, directory).catch(() => undefined);
        if (messages) {
          const nextText = latestAssistantText(messages);
          if (nextText && nextText !== lastText) {
            const delta = nextText.startsWith(lastText) ? nextText.slice(lastText.length) : nextText;
            const replace = !nextText.startsWith(lastText);
            lastText = nextText;
            this.sessions.upsertAssistantMessage(session, runId, lastText, { persist: false });
            this.emit("chat", { sessionKey: session.key, runId, state: "delta", delta, replace });
          }
          this.sessions.setUsage(session, usageFromMessages(messages));
        }
        const status = await this.client.status(directory).catch(() => undefined);
        if (status && isIdleStatus(status, session.sessionId) && lastText) {
          break;
        }
        if (eventError) {
          throw new Error(eventError);
        }
        await new Promise((resolve) => setTimeout(resolve, 400));
      }
      abortController.abort();
      await eventStream.catch(() => undefined);
      if (eventError) {
        throw new Error(eventError);
      }
      this.sessions.upsertAssistantMessage(session, runId, lastText);
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "final",
        message: lastText
      });
    } catch (error) {
      this.emit("chat", {
        sessionKey: session.key,
        runId,
        state: "error",
        error: error instanceof Error ? error.message : String(error)
      });
    } finally {
      abortController.abort();
      if (this.active?.runId === runId) {
        this.active = undefined;
      }
      this.sessions.clearActiveRun(session, runId);
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
    }
  }

}
