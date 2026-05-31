import assert from "node:assert/strict";
import test from "node:test";
import { HarnessDeviceStateStore } from "./HarnessDeviceStateStore.js";

const config = {
  openClawChatAgentId: "main",
  openClawChatSessionKey: "agent:main:explicit:open-claw-agent",
  hermesDefaultSessionId: "hermes-agent",
  hermesApiKey: "hermes-key"
};

test("new Hermes-configured device state defaults to Hermes", () => {
  const store = new HarnessDeviceStateStore(config);

  const state = store.stateFor("Pixel XL");

  assert.equal(state.harnessId, "hermes");
  assert.equal(state.sessionKey, "hermes:hermes-agent-pixel-xl");
  assert.equal(state.sessionKeysByHarness.get("hermes"), "hermes:hermes-agent-pixel-xl");
  assert.equal(
    state.sessionKeysByHarness.get("openclaw"),
    "agent:main:explicit:open-claw-agent-pixel-xl"
  );
});

test("explicit OpenClaw selection persists across later state lookups", () => {
  const store = new HarnessDeviceStateStore(config);
  const state = store.stateFor("pixel");

  store.switchHarness("pixel", state, "openclaw");
  const openClawSessionKey = state.sessionKey;

  const sameState = store.stateFor("pixel");

  assert.equal(sameState.harnessId, "openclaw");
  assert.equal(sameState.sessionKey, openClawSessionKey);
});

test("active session memory stays separate per harness", () => {
  const store = new HarnessDeviceStateStore(config);
  const state = store.stateFor("pixel");
  state.sessionKey = "hermes:custom-hermes";
  store.rememberActiveSession(state);

  store.switchHarness("pixel", state, "openclaw");
  state.sessionKey = "agent:main:explicit:custom-openclaw";
  store.rememberActiveSession(state);

  store.switchHarness("pixel", state, "hermes");
  assert.equal(state.sessionKey, "hermes:custom-hermes");

  store.switchHarness("pixel", state, "openclaw");
  assert.equal(state.sessionKey, "agent:main:explicit:custom-openclaw");
});
