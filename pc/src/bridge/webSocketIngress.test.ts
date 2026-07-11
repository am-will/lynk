import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";

import {
  WebSocketAdmissionBudget,
  WebSocketHeartbeat,
  resolvePhoneWebSocketIngressOptions
} from "./webSocketIngress.js";

class HeartbeatSocket extends EventEmitter {
  pings = 0;
  terminated = false;
  readyState = WebSocket.OPEN;

  ping(): void {
    this.pings += 1;
  }

  terminate(): void {
    this.terminated = true;
  }
}

test("upgrade admission enforces active connection and per-address rate budgets", () => {
  let now = 0;
  const options = resolvePhoneWebSocketIngressOptions({
    maxConnections: 2,
    upgradeRateCapacity: 2,
    upgradeRateRefillMs: 1_000,
    now: () => now
  });
  const budget = new WebSocketAdmissionBudget(options);

  assert.equal(budget.allow("127.0.0.1", 0), true);
  assert.equal(budget.allow("127.0.0.1", 0), true);
  assert.equal(budget.allow("127.0.0.1", 0), false);
  assert.equal(budget.allow("127.0.0.2", 2), false);

  now = 1_000;
  assert.equal(budget.allow("127.0.0.1", 0), true);
});

test("upgrade admission keeps its source-address tracking bounded", () => {
  let now = 0;
  const options = resolvePhoneWebSocketIngressOptions({
    maxTrackedAddresses: 2,
    upgradeRateCapacity: 1,
    upgradeRateRefillMs: 10_000,
    now: () => now
  });
  const budget = new WebSocketAdmissionBudget(options);

  assert.equal(budget.allow("address-1", 0), true);
  now += 1;
  assert.equal(budget.allow("address-2", 0), true);
  now += 1;
  assert.equal(budget.allow("address-3", 0), true);
  assert.equal(budget.allow("address-1", 0), true);
});

test("heartbeat terminates clients that do not answer the previous ping", () => {
  const socket = new HeartbeatSocket();
  const heartbeat = new WebSocketHeartbeat();
  heartbeat.attach(socket as unknown as WebSocket);

  heartbeat.sweep([socket as unknown as WebSocket]);
  assert.equal(socket.pings, 1);
  assert.equal(socket.terminated, false);

  socket.emit("pong");
  heartbeat.sweep([socket as unknown as WebSocket]);
  assert.equal(socket.pings, 2);
  assert.equal(socket.terminated, false);

  heartbeat.sweep([socket as unknown as WebSocket]);
  assert.equal(socket.terminated, true);
});

test("ingress option validation rejects incoherent payload limits", () => {
  assert.throws(
    () => resolvePhoneWebSocketIngressOptions({
      maxPayloadBytes: 64,
      registrationMaxBytes: 64,
      controlFrameMaxBytes: 65
    }),
    /controlFrameMaxBytes cannot exceed maxPayloadBytes/
  );
  assert.throws(
    () => resolvePhoneWebSocketIngressOptions({ registrationTimeoutMs: 0 }),
    /registrationTimeoutMs must be a positive integer/
  );
});
