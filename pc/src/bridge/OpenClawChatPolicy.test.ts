import assert from "node:assert/strict";
import test from "node:test";

import { isExplicitPhoneTask, messageForGateway } from "./OpenClawChatPolicy.js";
import { GENERIC_AGENT_SYSTEM_PROMPT, PHONE_AGENT_SYSTEM_PROMPT } from "../dispatcher/promptPolicy.js";

test("isExplicitPhoneTask requires actionable phone context", () => {
  assert.equal(isExplicitPhoneTask("Open the Settings app on my phone"), true);
  assert.equal(isExplicitPhoneTask("What is on my Android screen?"), true);
  assert.equal(isExplicitPhoneTask("Tap the Allow button"), true);

  assert.equal(isExplicitPhoneTask("Reply exactly: lynk-opencode-phone-e2e-ok"), false);
  assert.equal(isExplicitPhoneTask("Summarize this phone SDK in the repo"), false);
  assert.equal(isExplicitPhoneTask("Make the tap target bigger in the codebase"), false);
});

test("client-owned prompts remain platform-specific", () => {
  const iosMessage = messageForGateway("Review this code", "general", GENERIC_AGENT_SYSTEM_PROMPT);
  assert.match(iosMessage, /accessed through the Lynk client/);
  assert.doesNotMatch(iosMessage, /Android|Phone Control MCP|phone-control|phone tools/i);

  const androidMessage = messageForGateway("Open Settings on my phone", "phone", PHONE_AGENT_SYSTEM_PROMPT);
  assert.match(androidMessage, /Android phone/);
  assert.match(androidMessage, /android-phone MCP tools/);
  assert.match(androidMessage, /Phone-control turn hint/);
});
