import type { IncomingMessage, ServerResponse } from "node:http";
import type { AuditLog } from "./AuditLog.js";
import type { Dispatcher } from "../dispatcher/dispatcher.js";
import {
  getPetSpritesheet,
  listPets,
  openSpritesheetStream,
  resolvePetsDir,
  spritesheetEtag
} from "./PetCatalog.js";
import { phoneCommandSchema, type PhoneCommandRequest } from "../protocol/messages.js";
import type { BridgeConfig } from "./config.js";
import type { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import type { PhoneHub } from "./PhoneHub.js";
import { isAuthorizedHttpRequest } from "./httpAuth.js";
import { BodyTooLargeError, json, readJson } from "./httpUtils.js";
import { createHostPairingPayload } from "../host/PairingPayload.js";
import { buildDiagnosticsBundle } from "../host/Diagnostics.js";
import { detectIntegrations, refreshHostIntegrations } from "../host/IntegrationManager.js";
import { HostBlobStoreError, type HostBlobStore, type HostBlobOwner } from "./blob/HostBlobStore.js";

export interface BridgeHttpDependencies {
  config: Pick<BridgeConfig, "defaultDeviceId" | "token" | "port">;
  hub: Pick<PhoneHub, "listPhones" | "sendCommand">;
  audit: Pick<AuditLog, "recent" | "active">;
  dispatcher: Pick<Dispatcher, "handleUserRequest">;
  chatBridge: Pick<OpenClawChatBridge, "backendReadiness" | "health">;
  stopAgentWork: (deviceId: string, reason: string) => Promise<void>;
  petsDir?: string;
  blobs?: Pick<HostBlobStore, "upload" | "openDownload" | "delete">;
}

export type BridgeHttpHandler = (req: IncomingMessage, res: ServerResponse) => Promise<void>;

export function createBridgeHttpHandler(deps: BridgeHttpDependencies): BridgeHttpHandler {
  return async function handleHttp(req, res): Promise<void> {
    try {
      await routeHttp(req, res, deps);
    } catch (error) {
      if (error instanceof BodyTooLargeError) {
        json(res, 413, { ok: false, error: error.message });
        return;
      }
      if (error instanceof SyntaxError) {
        json(res, 400, { ok: false, error: "invalid json" });
        return;
      }
      if (error instanceof HostBlobStoreError) {
        json(res, error.statusCode, { ok: false, error: error.message });
        return;
      }
      json(res, 500, { ok: false, error: error instanceof Error ? error.message : String(error) });
    }
  };
}

async function routeHttp(req: IncomingMessage, res: ServerResponse, deps: BridgeHttpDependencies): Promise<void> {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);

  if (req.method === "GET" && url.pathname === "/health") {
    json(res, 200, { ok: true, phones: deps.hub.listPhones() });
    return;
  }

  if (url.pathname.startsWith("/api/") && !isAuthorizedHttpRequest(req.headers, deps.config.token)) {
    res.setHeader("www-authenticate", "Bearer");
    json(res, 401, { ok: false, error: "unauthorized" });
    return;
  }

  const blobMatch = url.pathname.match(/^\/api\/blobs\/([^/]+)$/u);
  if (blobMatch) {
    await handleBlobRequest(req, res, decodePathSegment(blobMatch[1] ?? ""), url, deps);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/phones") {
    json(res, 200, { phones: deps.hub.listPhones(), defaultDeviceId: deps.config.defaultDeviceId });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/pairing") {
    json(res, 200, await createHostPairingPayload(deps.config));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/diagnostics") {
    json(res, 200, await buildDiagnosticsBundle());
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/integrations") {
    json(res, 200, { integrations: await detectIntegrations() });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/integrations/refresh") {
    json(res, 200, await refreshHostIntegrations({ configureMcp: url.searchParams.get("configureMcp") === "1" }));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/harnesses/health") {
    const health = await deps.chatBridge.health();
    json(res, 200, { ok: true, ...recordPayload(health) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/harnesses/readiness") {
    const readiness = await deps.chatBridge.backendReadiness();
    json(res, 200, { ok: true, ...recordPayload(readiness) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/audit/recent") {
    const limit = Number.parseInt(url.searchParams.get("limit") ?? "100", 10);
    json(res, 200, { events: deps.audit.recent(Number.isFinite(limit) ? limit : 100) });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/audit/active") {
    json(res, 200, { activeTurns: deps.audit.active() });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/phone/default/command") {
    const body = (await readJson(req)) as Partial<PhoneCommandRequest>;
    const command = phoneCommandSchema.parse(body.command);
    const requestOwner = typeof body.requestOwner === "string" ? body.requestOwner.trim() : "";
    if (!requestOwner || requestOwner.length > 240) {
      json(res, 400, { ok: false, error: "requestOwner must be 1-240 characters" });
      return;
    }
    console.log(`[command] ${body.deviceId ?? deps.config.defaultDeviceId}: ${command}`);
    const result = await deps.hub.sendCommand({
      deviceId: typeof body.deviceId === "string" ? body.deviceId : undefined,
      requestOwner,
      command,
      args: typeof body.args === "object" && body.args ? (body.args as Record<string, unknown>) : {},
      approvalCapability: typeof body.approvalCapability === "string" ? body.approvalCapability : undefined,
      timeoutMs: typeof body.timeoutMs === "number" ? body.timeoutMs : undefined
    });
    console.log(`[result] ${result.deviceId}: ${command} ok=${result.ok}${result.error ? ` error=${result.error}` : ""}`);
    json(res, result.ok ? 200 : 502, result);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/user_request") {
    const body = (await readJson(req)) as { deviceId?: unknown; text?: unknown };
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : deps.config.defaultDeviceId;
    const text = typeof body.text === "string" ? body.text.trim() : "";
    if (!text) {
      json(res, 400, { ok: false, error: "text is required" });
      return;
    }

    const result = await deps.dispatcher.handleUserRequest({
      type: "user_request",
      inputType: "text",
      deviceId,
      text
    });
    json(res, result.error ? 502 : 200, { ok: !result.error, result });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/pets") {
    const pets = await listPets(petsDir(deps));
    json(res, 200, { pets });
    return;
  }

  const petsSpritesheetMatch = url.pathname.match(/^\/api\/pets\/([^/]+)\/spritesheet$/);
  if (req.method === "GET" && petsSpritesheetMatch) {
    await handlePetSpritesheet(req, res, decodeURIComponent(petsSpritesheetMatch[1] ?? ""), deps);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/agent/stop") {
    const body = (await readJson(req)) as { deviceId?: unknown; reason?: unknown };
    const deviceId = typeof body.deviceId === "string" ? body.deviceId : deps.config.defaultDeviceId;
    const reason = typeof body.reason === "string" ? body.reason : "Stopped by user";
    await deps.stopAgentWork(deviceId, reason);
    json(res, 200, { ok: true });
    return;
  }

  json(res, 404, { ok: false, error: "not found" });
}

async function handleBlobRequest(
  req: IncomingMessage,
  res: ServerResponse,
  blobId: string,
  url: URL,
  deps: BridgeHttpDependencies
): Promise<void> {
  if (!deps.blobs) {
    json(res, 404, { ok: false, error: "blob storage is unavailable" });
    return;
  }
  const owner = blobOwner(req);
  if (req.method === "PUT") {
    const kind = url.searchParams.get("kind");
    if (kind !== "image" && kind !== "file") {
      throw new HostBlobStoreError("kind must be image or file", 400);
    }
    const metadata = await deps.blobs.upload(req, {
      id: blobId,
      ...owner,
      displayName: url.searchParams.get("displayName") ?? "",
      mimeType: url.searchParams.get("mimeType") ?? "",
      kind,
      sha256: url.searchParams.get("sha256") ?? "",
      declaredSizeBytes: contentLength(req)
    });
    json(res, 201, {
      ok: true,
      blob: {
        id: metadata.id,
        sizeBytes: metadata.sizeBytes,
        sha256: metadata.sha256
      }
    });
    return;
  }
  if (req.method === "GET") {
    const download = deps.blobs.openDownload(blobId, owner);
    if (!download) {
      json(res, 404, { ok: false, error: "blob not found" });
      return;
    }
    res.writeHead(200, {
      "content-type": download.metadata.mimeType,
      "content-length": download.metadata.sizeBytes,
      "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(download.metadata.displayName)}`,
      "x-content-type-options": "nosniff",
      "x-lynk-blob-sha256": download.metadata.sha256,
      "cache-control": "private, no-store"
    });
    download.stream.on("error", (error) => res.destroy(error instanceof Error ? error : new Error(String(error))));
    download.stream.pipe(res);
    return;
  }
  if (req.method === "DELETE") {
    if (!deps.blobs.delete(blobId, owner)) {
      json(res, 404, { ok: false, error: "blob not found" });
      return;
    }
    json(res, 200, { ok: true });
    return;
  }
  res.setHeader("allow", "PUT, GET, DELETE");
  json(res, 405, { ok: false, error: "method not allowed" });
}

function blobOwner(req: IncomingMessage): HostBlobOwner {
  return {
    deviceId: singleHeader(req, "x-lynk-device-id"),
    sessionKey: singleHeader(req, "x-lynk-session-key")
  };
}

function singleHeader(req: IncomingMessage, name: string): string {
  const value = req.headers[name];
  if (Array.isArray(value)) throw new HostBlobStoreError(`${name} must be sent once`, 400);
  return value ?? "";
}

function contentLength(req: IncomingMessage): number | undefined {
  const raw = singleHeader(req, "content-length");
  if (!raw) return undefined;
  if (!/^\d+$/u.test(raw)) throw new HostBlobStoreError("Invalid Content-Length", 400);
  const parsed = Number(raw);
  if (!Number.isSafeInteger(parsed)) throw new HostBlobStoreError("Invalid Content-Length", 400);
  return parsed;
}

function decodePathSegment(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    throw new HostBlobStoreError("Invalid blob path", 400);
  }
}

async function handlePetSpritesheet(req: IncomingMessage, res: ServerResponse, petId: string, deps: BridgeHttpDependencies): Promise<void> {
  const info = await getPetSpritesheet(petId, petsDir(deps));
  if (!info) {
    json(res, 404, { ok: false, error: "pet not found" });
    return;
  }
  const etag = spritesheetEtag(info);
  if (req.headers["if-none-match"] === etag) {
    res.writeHead(304, { etag });
    res.end();
    return;
  }
  res.writeHead(200, {
    "content-type": "image/webp",
    "content-length": info.sizeBytes,
    "cache-control": "no-cache",
    etag
  });
  const stream = openSpritesheetStream(info);
  stream.on("error", (error) => {
    console.warn(`[pets] failed to stream spritesheet for ${petId}: ${error instanceof Error ? error.message : String(error)}`);
    res.destroy(error instanceof Error ? error : new Error(String(error)));
  });
  stream.pipe(res);
}

function petsDir(deps: BridgeHttpDependencies): string {
  return deps.petsDir ?? resolvePetsDir();
}

function recordPayload(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return { health: value };
}
