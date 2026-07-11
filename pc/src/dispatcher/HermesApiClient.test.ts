import assert from "node:assert/strict";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import { HermesApiClient } from "./HermesApiClient.js";

test("Hermes runs API materializes bounded attachment payloads without host paths", async () => {
  const root = mkdtempSync(join(tmpdir(), "lynk-hermes-api-attachment-"));
  const localPath = join(root, "payload.blob");
  writeFileSync(localPath, "hello");
  let body: string | undefined;
  const client = new HermesApiClient({
    apiBaseUrl: "https://hermes.example/v1",
    apiKey: "test",
    model: "hermes-agent",
    runTimeoutMs: 10_000
  }, async (_input, init) => {
    body = typeof init?.body === "string" ? init.body : undefined;
    return new Response(JSON.stringify({ run_id: "run-1" }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  });
  try {
    await client.createRun({
      input: "inspect",
      sessionId: "session-1",
      attachments: [{
        id: "blob_attachment-1",
        kind: "image",
        displayName: "photo.png",
        mimeType: "image/png",
        sizeBytes: 5,
        sha256: "a".repeat(64),
        localPath
      }]
    });
    assert.ok(body);
    assert.equal(body.includes(localPath), false);
    assert.equal(body.includes("aGVsbG8="), true);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
