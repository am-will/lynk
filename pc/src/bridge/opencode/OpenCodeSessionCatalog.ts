import { randomUUID } from "node:crypto";
import { InMemoryHarnessSessionStore, type HarnessStoredSession } from "../harness/InMemoryHarnessSessionStore.js";
import { listOpenCodeStoredSessions } from "./OpenCodeSessionDiscovery.js";
import type { OpenCodeServerClient } from "./OpenCodeServerClient.js";
import {
  asRecord,
  numberField,
  payloadHasUserMessage,
  secondsToMillis,
  sessionDirectory,
  sessionTitle,
  stringField,
  workspaceNameFromPath
} from "./OpenCodeNormalizers.js";

const OPENCODE_SESSION_PREFIX = "opencode:";

export const OPENCODE_REMOTE_SESSION_KEY = "opencodeRemoteSession";
export const OPENCODE_SESSION_DIRECTORY_KEY = "opencodeDirectory";

export class OpenCodeSessionCatalog {
  constructor(
    private readonly sessions: InMemoryHarnessSessionStore,
    private readonly client: OpenCodeServerClient,
    private readonly defaultModel: string,
    private readonly storageDataDir?: string
  ) {}

  async listSessions(limit = 50): Promise<Record<string, unknown>[]> {
    const directory = this.client.defaultDirectory();
    const storageSessions = listOpenCodeStoredSessions({ dataDir: this.storageDataDir });
    const storageSessionIds = new Set(storageSessions.map((session) => session.id));
    const payload = await this.client.listAllSessions()
      .catch(() => this.client.listSessions(directory).catch(() => undefined));
    const remoteSessions = Array.isArray(payload) ? payload.map(asRecord).filter(Boolean) as Record<string, unknown>[] : [];
    const filteredRemoteSessions = await this.filterRemoteSessionsWithUserMessages(remoteSessions, storageSessionIds);
    const summaries = [
      ...storageSessions.map((session) => this.sessionToSummary(session as unknown as Record<string, unknown>)),
      ...filteredRemoteSessions.map((session) => this.sessionToSummary(session)),
      ...this.localUserSessionSummaries()
    ];
    return mergeSessionSummaries(summaries)
      .sort((left, right) => (numberField(left, "updatedAt") ?? 0) - (numberField(right, "updatedAt") ?? 0))
      .reverse()
      .slice(0, Math.max(1, limit));
  }

  private sessionToSummary(session: Record<string, unknown>): Record<string, unknown> {
    const id = stringField(session, "id") ?? randomUUID();
    const directory = sessionDirectory(session, this.client.defaultDirectory());
    const key = `${OPENCODE_SESSION_PREFIX}${id}`;
    const local = this.sessions.ensureSession(key, id);
    this.sessions.setSessionId(local, id);
    this.sessions.setMetadata(local, OPENCODE_REMOTE_SESSION_KEY, true);
    if (directory) {
      this.sessions.setMetadata(local, OPENCODE_SESSION_DIRECTORY_KEY, directory);
    }
    const model = stringField(session, "model") ?? local.model ?? this.defaultModel;
    return {
      key,
      sessionId: id,
      label: sessionTitle(session),
      displayName: sessionTitle(session),
      workspacePath: directory,
      workspaceName: workspaceNameFromPath(directory),
      source: "opencode",
      model,
      modelProvider: "opencode",
      updatedAt: secondsToMillis(numberField(asRecord(session.time), "updated") ?? numberField(asRecord(session.time), "created")),
      hasActiveRun: false,
      thinkingLevel: null,
      inputTokens: numberField(session, "inputTokens"),
      outputTokens: numberField(session, "outputTokens"),
      totalTokens: numberField(session, "totalTokens"),
      estimatedCostUsd: numberField(session, "estimatedCostUsd")
    };
  }

  private localUserSessionSummaries(): Record<string, unknown>[] {
    return this.sessions.listStoredSessions(500)
      .filter((session) => this.sessions.hasUserMessage(session))
      .map((session) => {
        const directory = directoryForSession(session) ?? this.client.defaultDirectory();
        return {
          key: session.key,
          sessionId: session.sessionId,
          label: session.label,
          displayName: session.displayName ?? session.label,
          workspacePath: directory,
          workspaceName: workspaceNameFromPath(directory),
          source: "opencode",
          model: session.model ?? this.defaultModel,
          modelProvider: "opencode",
          updatedAt: session.updatedAt,
          hasActiveRun: Boolean(session.activeRunId),
          thinkingLevel: session.thinkingLevel ?? null
        };
      });
  }

  private async filterRemoteSessionsWithUserMessages(
    sessions: Record<string, unknown>[],
    storageSessionIds: Set<string>
  ): Promise<Record<string, unknown>[]> {
    if (storageSessionIds.size > 0) {
      return sessions.filter((session) => {
        const id = stringField(session, "id");
        return Boolean(id && storageSessionIds.has(id));
      });
    }
    const results = await Promise.all(sessions.map(async (session) => {
      const id = stringField(session, "id");
      if (!id) {
        return undefined;
      }
      const directory = sessionDirectory(session, this.client.defaultDirectory()) ?? undefined;
      const payload = await this.client.messages(id, directory).catch(() => undefined);
      return payloadHasUserMessage(payload) ? session : undefined;
    }));
    return results.filter((session): session is Record<string, unknown> => session !== undefined);
  }
}

export function directoryForSession(session: HarnessStoredSession): string | undefined {
  return stringField(session.metadata, OPENCODE_SESSION_DIRECTORY_KEY);
}

function mergeSessionSummaries(summaries: Record<string, unknown>[]): Record<string, unknown>[] {
  const byKey = new Map<string, Record<string, unknown>>();
  for (const summary of summaries) {
    const key = stringField(summary, "key");
    if (!key) {
      continue;
    }
    const existing = byKey.get(key);
    if (!existing || (numberField(summary, "updatedAt") ?? 0) >= (numberField(existing, "updatedAt") ?? 0)) {
      byKey.set(key, summary);
    }
  }
  return [...byKey.values()];
}
