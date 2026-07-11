import type { SessionNotification } from "@agentclientprotocol/sdk";
import type { ChatToolSummary } from "../../protocol/messages.js";
import type { DevinAcpClient } from "./DevinAcpClient.js";
import { DevinAcpEventNormalizer } from "./DevinAcpEventNormalizer.js";
import type { DevinAcpError } from "./DevinAcpTypes.js";
import type { DevinPermissionBroker, DevinPermissionRun } from "./DevinPermissionBroker.js";
import { HarnessRunLifecycle, type HarnessActiveRun } from "../harness/HarnessRunLifecycle.js";
import type { HarnessStoredSession, InMemoryHarnessSessionStore } from "../harness/InMemoryHarnessSessionStore.js";

type EmitGatewayEvent = (event: string, payload: unknown) => void;

interface DevinRunState {
  normalizer: DevinAcpEventNormalizer;
  text: string;
  cancelled: boolean;
  terminal: boolean;
}

const MAX_TOKEN_CONTINUATIONS = 2;
const CONTINUE_TRUNCATED_RESPONSE =
  "Continue exactly where the previous response stopped. Do not repeat prior content. Finish the original request.";

export class DevinRunDriver {
  private readonly runs: HarnessRunLifecycle<DevinRunState>;

  constructor(
    private readonly client: DevinAcpClient,
    private readonly sessions: InMemoryHarnessSessionStore,
    private readonly emit: EmitGatewayEvent,
    private readonly permissions: () => DevinPermissionBroker,
    private readonly onTool: (sessionKey: string, tool: ChatToolSummary) => void
  ) {
    this.runs = new HarnessRunLifecycle(sessions, {
      concurrency: "per-session",
      busyMessage: "A Devin task is already running in this session."
    });
  }

  startRun(session: HarnessStoredSession, runId: string, text: string): void {
    const state: DevinRunState = {
      normalizer: new DevinAcpEventNormalizer(this.emit),
      text: "",
      cancelled: false,
      terminal: false
    };
    const active = this.runs.start(session, runId, state);
    void this.processRun(active, session, text);
  }

  assertCanStart(sessionKey: string): void {
    this.runs.assertCanStart(sessionKey);
  }

  handleUpdate(notification: SessionNotification): void {
    const active = this.activeForSessionId(notification.sessionId);
    if (!active) return;
    const session = this.sessions.ensureSession(active.sessionKey);
    const result = active.resource.normalizer.handle(session, active.runId, notification);
    if (result?.textDelta !== undefined) {
      active.resource.text += result.textDelta;
      this.sessions.upsertAssistantMessage(session, active.runId, active.resource.text, { persist: false });
    }
    if (result?.usage) this.sessions.setUsage(session, result.usage);
    if (result?.tool) this.onTool(session.key, result.tool);
  }

  permissionRun(sessionId: string): DevinPermissionRun | undefined {
    const active = this.activeForSessionId(sessionId);
    if (!active) return undefined;
    return {
      sessionKey: active.sessionKey,
      sessionId,
      runId: active.runId
    };
  }

  async abort(sessionKey: string, runId?: string): Promise<{ status: "stopping" | "idle" }> {
    const active = this.runs.activeFor(sessionKey, runId);
    if (!active) return { status: "idle" };
    if (!active.resource.cancelled) {
      active.resource.cancelled = true;
      const session = this.sessions.ensureSession(active.sessionKey);
      active.resource.normalizer.cancelUnfinished(session, active.runId);
      this.permissions().cancelRun(active.sessionKey, active.runId);
      await this.client.sessionCancel({ sessionId: session.sessionId });
    }
    return { status: "stopping" };
  }

  failAll(error: DevinAcpError): void {
    this.permissions().cancelAll();
    for (const active of this.runs.active()) {
      this.terminal(active, "error", `Devin ACP stopped: ${error.message}`);
    }
  }

  close(): void {
    this.permissions().cancelAll();
    this.runs.close();
  }

  private activeForSessionId(sessionId: string): HarnessActiveRun<DevinRunState> | undefined {
    return this.runs.active().find((active) => this.sessions.ensureSession(active.sessionKey).sessionId === sessionId);
  }

  private async processRun(
    active: HarnessActiveRun<DevinRunState>,
    session: HarnessStoredSession,
    text: string
  ): Promise<void> {
    try {
      let promptText = text;
      for (let continuation = 0; continuation <= MAX_TOKEN_CONTINUATIONS; continuation += 1) {
        const response = await this.client.sessionPrompt({
          sessionId: session.sessionId,
          prompt: [{ type: "text", text: promptText }]
        });
        if (response.usage) {
          this.sessions.setUsage(session, {
            ...session.usage,
            totalTokens: response.usage.totalTokens,
            inputTokens: response.usage.inputTokens,
            outputTokens: response.usage.outputTokens,
            ...(response.usage.thoughtTokens != null ? { thoughtTokens: response.usage.thoughtTokens } : {}),
            ...(response.usage.cachedReadTokens != null ? { cachedReadTokens: response.usage.cachedReadTokens } : {}),
            ...(response.usage.cachedWriteTokens != null ? { cachedWriteTokens: response.usage.cachedWriteTokens } : {})
          });
        }
        if (response.stopReason === "end_turn") {
          this.terminal(active, "final");
          return;
        }
        if (response.stopReason === "cancelled" || active.resource.cancelled) {
          this.terminal(active, "error", "Devin run cancelled.");
          return;
        }
        if (response.stopReason === "max_tokens" && continuation < MAX_TOKEN_CONTINUATIONS) {
          promptText = CONTINUE_TRUNCATED_RESPONSE;
          continue;
        }
        this.terminal(active, "error", `Devin stopped: ${response.stopReason.replaceAll("_", " ")}.`);
        return;
      }
    } catch (error) {
      this.terminal(active, "error", error instanceof Error ? error.message : String(error));
    }
  }

  private terminal(
    active: HarnessActiveRun<DevinRunState>,
    kind: "final" | "error",
    error?: string
  ): void {
    if (active.resource.terminal) return;
    active.resource.terminal = true;
    const session = this.sessions.ensureSession(active.sessionKey);
    if (kind === "final") {
      this.sessions.upsertAssistantMessage(session, active.runId, active.resource.text);
      this.emit("chat", {
        sessionKey: active.sessionKey,
        runId: active.runId,
        state: "final",
        message: active.resource.text,
        usage: session.usage
      });
    } else {
      this.emit("chat", {
        sessionKey: active.sessionKey,
        runId: active.runId,
        state: "error",
        error: error ?? "Devin run failed."
      });
    }
    this.permissions().cancelRun(active.sessionKey, active.runId);
    this.runs.clear(active);
  }
}
