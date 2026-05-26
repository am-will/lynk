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

export interface BridgeHttpDependencies {
  config: Pick<BridgeConfig, "defaultDeviceId" | "token" | "port">;
  hub: Pick<PhoneHub, "listPhones" | "sendCommand">;
  audit: Pick<AuditLog, "recent" | "active">;
  dispatcher: Pick<Dispatcher, "handleUserRequest">;
  chatBridge: Pick<OpenClawChatBridge, "backendReadiness" | "health">;
  stopAgentWork: (deviceId: string, reason: string) => Promise<void>;
  petsDir?: string;
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
    console.log(`[command] ${body.deviceId ?? deps.config.defaultDeviceId}: ${command}`);
    const result = await deps.hub.sendCommand({
      deviceId: typeof body.deviceId === "string" ? body.deviceId : undefined,
      command,
      args: typeof body.args === "object" && body.args ? (body.args as Record<string, unknown>) : {},
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
