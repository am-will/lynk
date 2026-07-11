import assert from "node:assert/strict";
import test from "node:test";
import type { OpenAiRealtimeSession } from "./OpenAiRealtimeClient.js";
import type { RealtimeOutboundMessage, RealtimeStartMessage } from "../protocol/messages.js";
import { BridgeRealtime } from "./bridgeRealtime.js";

const VOICE_A = "11111111-1111-4111-8111-111111111111";
const VOICE_B = "22222222-2222-4222-8222-222222222222";

class Deferred<T> {
  promise: Promise<T>;
  resolve!: (value: T) => void;
  reject!: (error: Error) => void;
  constructor() {
    this.promise = new Promise<T>((resolve, reject) => { this.resolve = resolve; this.reject = reject; });
  }
}

function startMessage(voiceSessionId: string): RealtimeStartMessage {
  return { type: "realtime.start", deviceId: "pixel", voiceSessionId, sdp: `offer-${voiceSessionId}` };
}

function harness() {
  const starts: Array<Deferred<{ answerSdp: string; session: OpenAiRealtimeSession }>> = [];
  const stopped: OpenAiRealtimeSession[] = [];
  const outbound: RealtimeOutboundMessage[] = [];
  const cancelled: string[] = [];
  const detached: string[] = [];
  const realtime = new BridgeRealtime({
    config: { openAiRealtimeModel: "realtime", openAiRealtimeVoice: "voice" },
    hub: { sendRealtime: (_deviceId, message) => outbound.push(message) },
    audit: { record: () => undefined },
    realtimeClient: {
      start: () => {
        const deferred = new Deferred<{ answerSdp: string; session: OpenAiRealtimeSession }>();
        starts.push(deferred);
        return deferred.promise;
      },
      stop: async (session) => { stopped.push(session); }
    },
    stopAgentWork: async (_deviceId, voiceSessionId) => { cancelled.push(voiceSessionId); },
    detachAgentWork: (_deviceId, voiceSessionId) => { detached.push(voiceSessionId); }
  });
  return { realtime, starts, stopped, outbound, cancelled, detached };
}

test("overlapping starts resolving out of order publish only the latest owner", async () => {
  const h = harness();
  const first = h.realtime.startRealtimeSession(startMessage(VOICE_A), "pixel");
  const second = h.realtime.startRealtimeSession(startMessage(VOICE_B), "pixel");
  await Promise.resolve();
  h.starts[1]!.resolve({ answerSdp: "answer-b", session: { deviceId: "pixel", callId: "b" } });
  await second;
  h.starts[0]!.resolve({ answerSdp: "answer-a", session: { deviceId: "pixel", callId: "a" } });
  await first;

  assert.equal(h.realtime.owns("pixel", VOICE_B), true);
  assert.deepEqual(h.outbound.filter((message) => message.type === "realtime.sdp").map((message) => message.voiceSessionId), [VOICE_B]);
  assert.deepEqual(h.stopped.map((session) => session.callId), ["a"]);
  assert.deepEqual(h.cancelled, [VOICE_A]);
  assert.deepEqual(h.detached, [VOICE_A]);
});

test("a third start invalidating cleanup prevents creation of the stale middle transport", async () => {
  const h = harness();
  const first = h.realtime.startRealtimeSession(startMessage(VOICE_A), "pixel");
  const middle = h.realtime.startRealtimeSession(startMessage(VOICE_B), "pixel");
  const latestId = "33333333-3333-4333-8333-333333333333";
  const latest = h.realtime.startRealtimeSession(startMessage(latestId), "pixel");
  await Promise.resolve();

  assert.equal(h.starts.length, 2, "only the first and still-current third generations create transports");
  h.starts[1]!.resolve({ answerSdp: "answer-latest", session: { deviceId: "pixel", callId: "latest" } });
  await latest;
  await middle;
  h.starts[0]!.resolve({ answerSdp: "answer-a", session: { deviceId: "pixel", callId: "a" } });
  await first;

  assert.equal(h.realtime.owns("pixel", latestId), true);
  assert.deepEqual(h.outbound.filter((message) => message.type === "realtime.sdp").map((message) => message.voiceSessionId), [latestId]);
  assert.deepEqual(h.stopped.map((session) => session.callId), ["a"]);
});

test("late old start errors cannot clear or error a newer successful owner", async () => {
  const h = harness();
  const first = h.realtime.startRealtimeSession(startMessage(VOICE_A), "pixel");
  const second = h.realtime.startRealtimeSession(startMessage(VOICE_B), "pixel");
  await Promise.resolve();
  h.starts[1]!.resolve({ answerSdp: "answer-b", session: { deviceId: "pixel", callId: "b" } });
  await second;
  h.starts[0]!.reject(new Error("old failed"));
  await first;
  assert.equal(h.realtime.owns("pixel", VOICE_B), true);
  assert.equal(h.outbound.some((message) => message.type === "realtime.error" && message.voiceSessionId === VOICE_A), false);
});

test("mismatched stop fails closed while exact stop closes only its owner", async () => {
  const h = harness();
  const started = h.realtime.startRealtimeSession(startMessage(VOICE_B), "pixel");
  h.starts[0]!.resolve({ answerSdp: "answer-b", session: { deviceId: "pixel", callId: "b" } });
  await started;
  await h.realtime.handleRealtimeStop({ type: "realtime.stop", deviceId: "pixel", voiceSessionId: VOICE_A }, "pixel");
  assert.equal(h.realtime.owns("pixel", VOICE_B), true);
  await h.realtime.handleRealtimeStop({ type: "realtime.stop", deviceId: "pixel", voiceSessionId: VOICE_B }, "pixel");
  assert.equal(h.realtime.owns("pixel", VOICE_B), false);
  assert.deepEqual(h.stopped.map((session) => session.callId), ["b"]);
  assert.deepEqual(h.detached, [VOICE_B]);
});

test("disconnect invalidates ownership and stops only the published transport", async () => {
  const h = harness();
  const started = h.realtime.startRealtimeSession(startMessage(VOICE_A), "pixel");
  h.starts[0]!.resolve({ answerSdp: "answer-a", session: { deviceId: "pixel", callId: "a" } });
  await started;
  h.realtime.disconnectDevice("pixel");
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(h.realtime.owns("pixel", VOICE_A), false);
  assert.deepEqual(h.stopped.map((session) => session.callId), ["a"]);
  assert.deepEqual(h.detached, [VOICE_A]);
});
