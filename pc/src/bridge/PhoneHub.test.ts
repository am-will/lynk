import assert from "node:assert/strict";
import test from "node:test";
import { WebSocket } from "ws";
import { PhoneHub } from "./PhoneHub.js";

test("command results resolve only from the owning registered device", async () => {
  const sent: string[] = [];
  const socket = {
    readyState: WebSocket.OPEN,
    close() {},
    send(data: string, callback?: (error?: Error) => void) {
      sent.push(data);
      callback?.();
    }
  } as unknown as WebSocket;
  const hub = new PhoneHub("phone-a");
  hub.register({ type: "register", deviceId: "phone-a", token: "token", capabilities: [] }, socket);

  let settled = false;
  const resultPromise = hub.sendCommand({
    requestOwner: "test:phone-hub",
    command: "press_home"
  }).finally(() => { settled = true; });
  const id = JSON.parse(sent[0]!).id as string;
  hub.handleResult("phone-b", { type: "result", id, ok: true });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(settled, false);

  hub.handleResult("phone-a", { type: "result", id, ok: true });
  assert.equal((await resultPromise).deviceId, "phone-a");
});

test("command timeout sends an owner-bound cancellation to Android", async () => {
  const sent: string[] = [];
  const socket = fakeSocket(sent);
  const hub = new PhoneHub("phone-a");
  hub.register({ type: "register", deviceId: "phone-a", token: "token", capabilities: [] }, socket);

  await assert.rejects(hub.sendCommand({
    requestOwner: "test:timeout",
    command: "wait",
    args: { ms: 60_000 },
    timeoutMs: 5
  }), /Timed out/);

  const command = JSON.parse(sent[0]!) as { id: string; requestOwner: string };
  const cancellation = JSON.parse(sent[1]!);
  assert.deepEqual(cancellation, {
    type: "command.cancel",
    commandId: command.id,
    requestOwner: command.requestOwner,
    reason: "Timed out after 5ms"
  });
});

test("task cancellation rejects pending result and notifies Android", async () => {
  const sent: string[] = [];
  const socket = fakeSocket(sent);
  const hub = new PhoneHub("phone-a");
  hub.register({ type: "register", deviceId: "phone-a", token: "token", capabilities: [] }, socket);
  const result = hub.sendCommand({ requestOwner: "test:stop", command: "wait", args: { ms: 60_000 } });
  const rejection = assert.rejects(result, /Stopped by test/);

  hub.cancelPendingCommands("phone-a", "Stopped by test");

  await rejection;
  const command = JSON.parse(sent[0]!) as { id: string; requestOwner: string };
  assert.deepEqual(JSON.parse(sent[1]!), {
    type: "command.cancel",
    commandId: command.id,
    requestOwner: command.requestOwner,
    reason: "Stopped by test"
  });
});

function fakeSocket(sent: string[]): WebSocket {
  return {
    readyState: WebSocket.OPEN,
    close() {},
    send(data: string, callback?: (error?: Error) => void) {
      sent.push(data);
      callback?.();
    }
  } as unknown as WebSocket;
}
