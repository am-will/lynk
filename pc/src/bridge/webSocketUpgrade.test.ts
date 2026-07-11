import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { connect } from "node:net";
import test from "node:test";
import { WebSocketServer } from "ws";

import { createPhoneUpgradeHandler, validatePhoneUpgradeRequest } from "./webSocketUpgrade.js";

test("upgrade validation accepts only an origin-form /phone target with one valid Host", () => {
  assert.deepEqual(validatePhoneUpgradeRequest({
    headers: { host: "127.0.0.1:8788" },
    rawHeaders: ["Host", "127.0.0.1:8788"],
    url: "/phone?client=android"
  }), { accepted: true });

  for (const request of [
    { headers: { host: "[malformed" }, rawHeaders: ["Host", "[malformed"], url: "/phone" },
    { headers: { host: "localhost" }, rawHeaders: [], url: "/phone" },
    { headers: { host: "localhost" }, rawHeaders: ["Host", "localhost", "HOST", "other"], url: "/phone" },
    { headers: { host: "localhost" }, rawHeaders: ["Host", "localhost"], url: "//example.com/phone" }
  ]) {
    assert.equal(validatePhoneUpgradeRequest(request).accepted, false);
  }
});

test("a raw upgrade with a malformed Host is rejected without terminating the server", async (t) => {
  const wss = new WebSocketServer({ noServer: true });
  const server = createServer((_request, response) => {
    response.writeHead(200, { "content-type": "text/plain" });
    response.end("healthy");
  });
  server.on("upgrade", createPhoneUpgradeHandler(wss));
  await listen(server);
  t.after(async () => {
    wss.close();
    await close(server);
  });

  const address = server.address();
  assert(address && typeof address === "object");
  const response = await rawRequest(address.port, [
    "GET /phone HTTP/1.1",
    "Host: [malformed",
    "Connection: Upgrade",
    "Upgrade: websocket",
    "Sec-WebSocket-Version: 13",
    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==",
    "",
    ""
  ].join("\r\n"));

  assert.match(response, /^HTTP\/1\.1 400 Bad Request/);
  const health = await fetch(`http://127.0.0.1:${address.port}/health`);
  assert.equal(health.status, 200);
  assert.equal(await health.text(), "healthy");
});

function listen(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      server.off("error", reject);
      resolve();
    });
  });
}

function close(server: Server): Promise<void> {
  return new Promise((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  });
}

function rawRequest(port: number, request: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const socket = connect(port, "127.0.0.1");
    const chunks: Buffer[] = [];
    socket.setTimeout(2_000, () => socket.destroy(new Error("raw request timed out")));
    socket.on("connect", () => socket.end(request));
    socket.on("data", (chunk) => chunks.push(chunk));
    socket.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    socket.on("error", reject);
  });
}
