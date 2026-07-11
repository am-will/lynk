import { isAbsolute } from "node:path";
import type { SessionInfo } from "@agentclientprotocol/sdk";
import type { ChatSessionSummary } from "../../protocol/messages.js";
import type { HarnessStoredSession, InMemoryHarnessSessionStore } from "../harness/InMemoryHarnessSessionStore.js";
import type { DevinAcpClient } from "./DevinAcpClient.js";

export interface DevinSessionCatalogOptions {
  client: DevinAcpClient;
  store: InMemoryHarnessSessionStore;
  toSummary(session: HarnessStoredSession): ChatSessionSummary;
}

export interface DevinSessionCatalogResult {
  sessions: ChatSessionSummary[];
  status?: "list_unavailable";
}

const MAX_LIST_PAGES = 100;

export class DevinSessionCatalog {
  constructor(private readonly options: DevinSessionCatalogOptions) {}

  async listSessions(limit = 50): Promise<DevinSessionCatalogResult> {
    const capabilities = await this.options.client.ensureStarted();
    if (!capabilities.listSessions) {
      return {
        sessions: this.localLynkSummaries(limit),
        status: "list_unavailable"
      };
    }

    const seenCursors = new Set<string | null | undefined>();
    const remoteById = new Map<string, SessionInfo>();
    let cursor: string | null | undefined;
    let pages = 0;
    while (true) {
      const response = await this.options.client.sessionList(cursor ? { cursor } : {});
      for (const info of response.sessions) {
        remoteById.set(info.sessionId, info);
        if (remoteById.size >= limit) {
          break;
        }
      }
      cursor = response.nextCursor;
      pages += 1;
      if (remoteById.size >= limit) {
        break;
      }
      if (!cursor || seenCursors.has(cursor)) {
        break;
      }
      seenCursors.add(cursor);
      if (pages >= MAX_LIST_PAGES) {
        break;
      }
    }

    const summaries: ChatSessionSummary[] = [];
    for (const info of remoteById.values()) {
      summaries.push(this.mergeRemoteSession(info));
    }

    const remoteKeys = new Set([...remoteById.keys()].map((id) => `devin:${id}`));
    const localOnly = this.options.store
      .listStoredSessions(limit)
      .filter((session) => !remoteKeys.has(session.key) && session.metadata?.createdByLynk === true)
      .map((session) => this.options.toSummary(session));

    summaries.push(...localOnly);
    summaries.sort((left, right) => (right.updatedAt ?? 0) - (left.updatedAt ?? 0));

    return { sessions: summaries.slice(0, limit) };
  }

  private localLynkSummaries(limit: number): ChatSessionSummary[] {
    return this.options.store
      .listStoredSessions(limit)
      .filter((session) => session.metadata?.createdByLynk === true)
      .map((session) => this.options.toSummary(session));
  }

  private mergeRemoteSession(info: SessionInfo): ChatSessionSummary {
    const key = `devin:${info.sessionId}`;
    const store = this.options.store;
    const session = store.ensureSession(key, info.sessionId);
    const patch: Record<string, unknown> = {};
    if (info.title) {
      patch.displayName = info.title;
    }
    if (Object.keys(patch).length > 0) {
      store.patchSession(key, patch);
    }
    if (info.cwd && isAbsolute(info.cwd)) {
      store.setMetadata(session, "workspacePath", info.cwd);
    }
    if (info.updatedAt) {
      const updatedAtMs = new Date(info.updatedAt).getTime();
      if (Number.isFinite(updatedAtMs)) {
        store.setMetadata(session, "acpUpdatedAt", updatedAtMs);
      }
    }
    return this.options.toSummary(store.ensureSession(key, info.sessionId));
  }
}
