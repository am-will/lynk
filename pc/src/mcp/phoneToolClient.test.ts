import assert from "node:assert/strict";
import test from "node:test";

import { bridgeAuthHeaders, PhoneToolClient } from "./phoneToolClient.js";

test("bridgeAuthHeaders sends bearer auth for protected bridge APIs", () => {
  assert.deepEqual(bridgeAuthHeaders("local-token"), { authorization: "Bearer local-token" });
  assert.deepEqual(bridgeAuthHeaders("  local-token  "), { authorization: "Bearer local-token" });
});

test("bridgeAuthHeaders requires a token", () => {
  assert.throws(
    () => bridgeAuthHeaders(""),
    /PHONE_AGENT_TOKEN is required/
  );
});

test("PhoneToolClient keeps capability scoped to its stable request owner", async () => {
  const originalFetch = globalThis.fetch;
  const originalToken = process.env.PHONE_AGENT_TOKEN;
  const bodies: Array<Record<string, unknown>> = [];
  process.env.PHONE_AGENT_TOKEN = "test-token";
  globalThis.fetch = (async (_input, init) => {
    bodies.push(JSON.parse(String(init?.body)) as Record<string, unknown>);
    return new Response(JSON.stringify({ id: "cmd", deviceId: "pixel", ok: true }), {
      status: 200,
      headers: { "content-type": "application/json" }
    });
  }) as typeof fetch;
  try {
    const client = new PhoneToolClient("http://bridge.test", "test-session");
    const target = { observationId: "123e4567-e89b-12d3-a456-426614174000", nodeId: "n1" };
    await client.command("ask_user_confirmation", { command: "tap_node", args: target });
    await client.command("tap_node", target, 30_000, "capability-secret-value");

    assert.deepEqual(bodies.map((body) => body.requestOwner), ["test-session", "test-session"]);
    assert.equal(bodies[0].approvalCapability, undefined);
    assert.equal(bodies[1].approvalCapability, "capability-secret-value");
  } finally {
    globalThis.fetch = originalFetch;
    if (originalToken === undefined) delete process.env.PHONE_AGENT_TOKEN;
    else process.env.PHONE_AGENT_TOKEN = originalToken;
  }
});
