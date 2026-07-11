import type { InitializeResponse } from "@agentclientprotocol/sdk";
import type { DevinAcpAuthMethod, DevinAcpCapabilities } from "./DevinAcpTypes.js";

export function normalizeDevinCapabilities(response: InitializeResponse): DevinAcpCapabilities {
  const agent = response.agentCapabilities ?? {};
  const session = agent.sessionCapabilities ?? {};
  const prompt = agent.promptCapabilities ?? {};
  const auth = agent.auth ?? {};

  const authMethods: DevinAcpAuthMethod[] = response.authMethods
    ? response.authMethods
        .filter((m): m is { readonly id: string; readonly name: string } => typeof m.id === "string" && typeof m.name === "string")
        .map((m) => Object.freeze({ id: m.id, name: m.name }))
    : [];

  return Object.freeze({
    protocolVersion: response.protocolVersion,
    agentName: response.agentInfo?.name ?? null,
    agentVersion: response.agentInfo?.version ?? null,
    loadSession: agent.loadSession === true,
    listSessions: isPresent(session.list),
    deleteSessions: isPresent(session.delete),
    additionalDirectories: isPresent(session.additionalDirectories),
    resumeSession: isPresent(session.resume),
    closeSession: isPresent(session.close),
    forkSession: isPresent(session.fork),
    promptImage: prompt.image === true,
    promptAudio: prompt.audio === true,
    promptEmbeddedContext: prompt.embeddedContext === true,
    authLogout: isPresent(auth.logout),
    providers: isPresent(agent.providers),
    authMethods: Object.freeze(authMethods)
  });
}

function isPresent(value: unknown): boolean {
  return value !== undefined && value !== null;
}
