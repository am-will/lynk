import assert from "node:assert/strict";
import { describe, it } from "node:test";
import type { RequestPermissionRequest } from "@agentclientprotocol/sdk";
import { DevinPermissionBroker } from "./DevinPermissionBroker.js";

function request(options = [
  { optionId: "allow-opaque", name: "Allow it", kind: "allow_once" as const },
  { optionId: "reject_always", name: "Never allow", kind: "reject_always" as const }
]): RequestPermissionRequest {
  return {
    sessionId: "s1",
    toolCall: { toolCallId: "tool1", title: "Run safe command", kind: "execute", rawInput: { command: "pwd" } },
    options
  };
}

describe("DevinPermissionBroker", () => {
  it("preserves exact option labels and opaque IDs", async () => {
    const events: Record<string, unknown>[] = [];
    const broker = new DevinPermissionBroker(
      () => ({ sessionKey: "devin:s1", sessionId: "s1", runId: "r1" }),
      (_event, payload) => events.push(payload as Record<string, unknown>)
    );
    const responsePromise = broker.request(request());
    const actions = events[0]?.actions as Array<Record<string, unknown>>;
    assert.deepEqual(actions.map((action) => [action.id, action.label, action.args]), [
      ["allow-opaque", "Allow it", { permissionId: events[0]?.eventId, optionId: "allow-opaque" }],
      ["reject_always", "Never allow", { permissionId: events[0]?.eventId, optionId: "reject_always" }]
    ]);
    broker.respond({
      sessionKey: "devin:s1",
      permissionId: String(events[0]?.eventId),
      response: { kind: "acp_option", optionId: "reject_always" }
    });
    assert.deepEqual(await responsePromise, { outcome: { outcome: "selected", optionId: "reject_always" } });
  });

  it("rejects invalid, stale, duplicate, and cross-session replies", async () => {
    const events: Record<string, unknown>[] = [];
    const broker = new DevinPermissionBroker(
      () => ({ sessionKey: "devin:s1", sessionId: "s1", runId: "r1" }),
      (_event, payload) => events.push(payload as Record<string, unknown>)
    );
    const pending = broker.request(request());
    const permissionId = String(events[0]?.eventId);
    assert.throws(() => broker.respond({
      sessionKey: "devin:s2",
      permissionId,
      response: { kind: "acp_option", optionId: "allow-opaque" }
    }), /another session/);
    assert.throws(() => broker.respond({
      sessionKey: "devin:s1",
      permissionId,
      response: { kind: "acp_option", optionId: "not-offered" }
    }), /not offered/);
    broker.respond({ sessionKey: "devin:s1", permissionId, response: { kind: "acp_option", optionId: "allow-opaque" } });
    assert.throws(() => broker.respond({
      sessionKey: "devin:s1",
      permissionId,
      response: { kind: "acp_option", optionId: "allow-opaque" }
    }), /stale|already answered/);
    assert.deepEqual(await pending, { outcome: { outcome: "selected", optionId: "allow-opaque" } });
  });

  it("cancels permissions when the exact run is stopped", async () => {
    const events: Record<string, unknown>[] = [];
    const broker = new DevinPermissionBroker(
      () => ({ sessionKey: "devin:s1", sessionId: "s1", runId: "r1" }),
      (_event, payload) => events.push(payload as Record<string, unknown>)
    );
    const pending = broker.request(request());
    broker.cancelRun("devin:s1", "other");
    broker.cancelRun("devin:s1", "r1");
    assert.deepEqual(await pending, { outcome: { outcome: "cancelled" } });
    assert.equal(events.at(-1)?.status, "failed");
  });

  it("cancels requests when there is no matching active run", async () => {
    const broker = new DevinPermissionBroker(() => undefined, () => undefined);
    assert.deepEqual(await broker.request(request()), { outcome: { outcome: "cancelled" } });
  });
});
