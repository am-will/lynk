import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import test from "node:test";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";

import { createBridgeHttpHandler } from "./bridgeHttp.js";
import { getPetSpritesheet, spritesheetEtag } from "./PetCatalog.js";
import type { PhoneCommandRequest } from "../protocol/messages.js";
import { HostBlobStore } from "./blob/HostBlobStore.js";

const token = "test-token";

class FakeHub {
  readonly commands: PhoneCommandRequest[] = [];

  listPhones(): unknown[] {
    return [{ deviceId: "phone", capabilities: [], connectedAt: 1 }];
  }

  async sendCommand(request: PhoneCommandRequest): Promise<{ id: string; deviceId: string; ok: boolean }> {
    this.commands.push(request);
    return { id: "cmd_1", deviceId: request.deviceId ?? "phone", ok: true };
  }
}

class FakeAudit {
  recent(limit: number): unknown[] {
    return [{ id: "event", limit }];
  }

  active(): unknown[] {
    return [{ deviceId: "phone" }];
  }
}

class FakeDispatcher {
  readonly requests: unknown[] = [];

  async handleUserRequest(request: unknown): Promise<{ finalMessage: string }> {
    this.requests.push(request);
    return { finalMessage: "done" };
  }
}

class FakeChatBridge {
  async health(): Promise<unknown> {
    return {
      harnesses: {
        openclaw: { ok: true },
        hermes: { ok: false, error: "missing HERMES_API_KEY" }
      }
    };
  }

  async backendReadiness(): Promise<unknown> {
    return {
      harnesses: {
        openclaw: { ok: false, configured: true, label: "OpenClaw", modelCount: 0, message: "OpenClaw is configured on the PC bridge, but no live models are available yet." },
        hermes: { ok: false, configured: false, label: "Hermes", modelCount: 0, message: "Hermes is not configured on the PC bridge." },
        codex: { ok: true, configured: true, label: "Codex", modelCount: 5 }
      }
    };
  }
}

async function withHttpServer<T>(
  options: {
    petsDir?: string;
    blobs?: HostBlobStore;
    stopAgentWork?: (deviceId: string, reason: string) => Promise<void>;
  },
  fn: (baseUrl: string, fakes: { hub: FakeHub; audit: FakeAudit; dispatcher: FakeDispatcher }) => Promise<T>
): Promise<T> {
  const hub = new FakeHub();
  const audit = new FakeAudit();
  const dispatcher = new FakeDispatcher();
  const chatBridge = new FakeChatBridge();
  const handler = createBridgeHttpHandler({
    config: { token, defaultDeviceId: "phone", port: 8788 },
    hub: hub as never,
    audit: audit as never,
    dispatcher: dispatcher as never,
    chatBridge: chatBridge as never,
    stopAgentWork: options.stopAgentWork ?? (async () => {}),
    petsDir: options.petsDir,
    blobs: options.blobs
  });
  const server = createServer((req, res) => {
    handler(req, res);
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const address = server.address();
    assert.ok(address && typeof address === "object");
    return await fn(`http://127.0.0.1:${address.port}`, { hub, audit, dispatcher });
  } finally {
    await closeServer(server);
  }
}

function closeServer(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  });
}

function authHeaders(): Record<string, string> {
  return { authorization: `Bearer ${token}` };
}

async function makePetsDir(): Promise<string> {
  return await mkdtemp(join(tmpdir(), "open-claw-http-pets-"));
}

async function writePet(root: string, id: string): Promise<void> {
  const dir = join(root, id);
  await mkdir(dir, { recursive: true });
  await writeFile(join(dir, "pet.json"), JSON.stringify({
    id,
    displayName: id,
    description: `${id} description`,
    spritesheetPath: "spritesheet.webp"
  }));
  await writeFile(join(dir, "spritesheet.webp"), Buffer.from("RIFFFAKEWEBPDATA"));
}

test("bridge HTTP serves health without auth and protects api routes", async () => {
  await withHttpServer({}, async (baseUrl) => {
    const health = await fetch(`${baseUrl}/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), {
      ok: true,
      phones: [{ deviceId: "phone", capabilities: [], connectedAt: 1 }]
    });

    const protectedResponse = await fetch(`${baseUrl}/api/phones`);
    assert.equal(protectedResponse.status, 401);
    assert.equal(protectedResponse.headers.get("www-authenticate"), "Bearer");
  });
});

test("bridge HTTP serves authenticated harness health", async () => {
  await withHttpServer({}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/harnesses/health`, {
      headers: authHeaders()
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      ok: true,
      harnesses: {
        openclaw: { ok: true },
        hermes: { ok: false, error: "missing HERMES_API_KEY" }
      }
    });
  });
});

test("bridge HTTP serves authenticated harness readiness", async () => {
  await withHttpServer({}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/harnesses/readiness`, {
      headers: authHeaders()
    });

    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      ok: true,
      harnesses: {
        openclaw: { ok: false, configured: true, label: "OpenClaw", modelCount: 0, message: "OpenClaw is configured on the PC bridge, but no live models are available yet." },
        hermes: { ok: false, configured: false, label: "Hermes", modelCount: 0, message: "Hermes is not configured on the PC bridge." },
        codex: { ok: true, configured: true, label: "Codex", modelCount: 5 }
      }
    });
  });
});

test("bridge HTTP serves authenticated pairing payload", async () => {
  await withHttpServer({}, async (baseUrl) => {
    const response = await fetch(`${baseUrl}/api/pairing`, {
      headers: authHeaders()
    });

    assert.equal(response.status, 200);
    const payload = await response.json() as Record<string, unknown>;
    assert.equal(payload.product, "android-agent-bridge");
    assert.equal(payload.deviceId, "phone");
    assert.equal(payload.token, token);
    assert.ok(Array.isArray(payload.endpoints));
    if (payload.deepLink === null) {
      assert.ok(Array.isArray(payload.warnings));
      assert.match(String((payload.warnings as string[])[0]), /No usable phone endpoint/);
    } else {
      assert.match(String(payload.deepLink), /^android-agent:\/\/pair\?/);
    }
  });
});

test("bridge HTTP streams authenticated device/session-owned blobs", async () => {
  const root = await mkdtemp(join(tmpdir(), "lynk-http-blobs-"));
  const blobs = new HostBlobStore(root, {
    maxItemBytes: 64,
    maxAggregateBytes: 256,
    freeSpaceReserveBytes: 0,
    usableSpaceBytes: () => Number.MAX_SAFE_INTEGER
  });
  const bytes = Buffer.from("owned attachment");
  const sha256 = createHash("sha256").update(bytes).digest("hex");
  const query = new URLSearchParams({
    displayName: "note.txt",
    mimeType: "text/plain",
    kind: "file",
    sha256
  });
  try {
    await withHttpServer({ blobs }, async (baseUrl) => {
      const unauthenticated = await fetch(`${baseUrl}/api/blobs/blob_http-owned?${query}`, {
        method: "PUT",
        body: bytes
      });
      assert.equal(unauthenticated.status, 401);

      const upload = await fetch(`${baseUrl}/api/blobs/blob_http-owned?${query}`, {
        method: "PUT",
        headers: {
          ...authHeaders(),
          "x-lynk-device-id": "phone",
          "x-lynk-session-key": "codex:session"
        },
        body: bytes
      });
      assert.equal(upload.status, 201);
      assert.equal((await upload.json() as { blob: { sha256: string } }).blob.sha256, sha256);

      const wrongOwner = await fetch(`${baseUrl}/api/blobs/blob_http-owned`, {
        headers: {
          ...authHeaders(),
          "x-lynk-device-id": "phone",
          "x-lynk-session-key": "codex:other"
        }
      });
      assert.equal(wrongOwner.status, 404);

      const download = await fetch(`${baseUrl}/api/blobs/blob_http-owned`, {
        headers: {
          ...authHeaders(),
          "x-lynk-device-id": "phone",
          "x-lynk-session-key": "codex:session"
        }
      });
      assert.equal(download.status, 200);
      assert.equal(Buffer.from(await download.arrayBuffer()).toString("utf8"), bytes.toString("utf8"));
      assert.equal(download.headers.get("x-lynk-blob-sha256"), sha256);
    });
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("bridge HTTP validates user requests and dispatches valid text", async () => {
  await withHttpServer({}, async (baseUrl, { dispatcher }) => {
    const empty = await fetch(`${baseUrl}/api/user_request`, {
      method: "POST",
      headers: { ...authHeaders(), "content-type": "application/json" },
      body: JSON.stringify({ text: "   " })
    });
    assert.equal(empty.status, 400);

    const ok = await fetch(`${baseUrl}/api/user_request`, {
      method: "POST",
      headers: { ...authHeaders(), "content-type": "application/json" },
      body: JSON.stringify({ text: "Open Settings" })
    });
    assert.equal(ok.status, 200);
    assert.deepEqual(dispatcher.requests, [{
      type: "user_request",
      inputType: "text",
      deviceId: "phone",
      text: "Open Settings"
    }]);
  });
});

test("bridge HTTP routes commands, stop, unknown paths, and pet spritesheets", async () => {
  const petsDir = await makePetsDir();
  const stops: Array<{ deviceId: string; reason: string }> = [];
  try {
    await writePet(petsDir, "buddy");
    await withHttpServer({
      petsDir,
      stopAgentWork: async (deviceId, reason) => {
        stops.push({ deviceId, reason });
      }
    }, async (baseUrl, { hub }) => {
      const command = await fetch(`${baseUrl}/api/phone/default/command`, {
        method: "POST",
        headers: { ...authHeaders(), "content-type": "application/json" },
        body: JSON.stringify({ command: "press_home", requestOwner: "test-client" })
      });
      assert.equal(command.status, 200);
      assert.equal(hub.commands[0].command, "press_home");
      assert.equal(hub.commands[0].requestOwner, "test-client");

      const missingOwner = await fetch(`${baseUrl}/api/phone/default/command`, {
        method: "POST",
        headers: { ...authHeaders(), "content-type": "application/json" },
        body: JSON.stringify({ command: "press_home" })
      });
      assert.equal(missingOwner.status, 400);
      assert.equal(hub.commands.length, 1);

      const stop = await fetch(`${baseUrl}/api/agent/stop`, {
        method: "POST",
        headers: { ...authHeaders(), "content-type": "application/json" },
        body: JSON.stringify({ reason: "test stop" })
      });
      assert.equal(stop.status, 200);
      assert.deepEqual(stops, [{ deviceId: "phone", reason: "test stop" }]);

      const missingPet = await fetch(`${baseUrl}/api/pets/missing/spritesheet`, { headers: authHeaders() });
      assert.equal(missingPet.status, 404);

      const info = await getPetSpritesheet("buddy", petsDir);
      assert.ok(info);
      const notModified = await fetch(`${baseUrl}/api/pets/buddy/spritesheet`, {
        headers: { ...authHeaders(), "if-none-match": spritesheetEtag(info) }
      });
      assert.equal(notModified.status, 304);

      const unknown = await fetch(`${baseUrl}/api/nope`, { headers: authHeaders() });
      assert.equal(unknown.status, 404);
    });
  } finally {
    await rm(petsDir, { recursive: true, force: true });
  }
});
