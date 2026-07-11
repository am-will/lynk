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
