import assert from "node:assert/strict";
import test from "node:test";

import { isExplicitPhoneTask } from "./OpenClawChatPolicy.js";

test("isExplicitPhoneTask requires actionable phone context", () => {
  assert.equal(isExplicitPhoneTask("Open the Settings app on my phone"), true);
  assert.equal(isExplicitPhoneTask("What is on my Android screen?"), true);
  assert.equal(isExplicitPhoneTask("Tap the Allow button"), true);

  assert.equal(isExplicitPhoneTask("Reply exactly: lynk-opencode-phone-e2e-ok"), false);
  assert.equal(isExplicitPhoneTask("Summarize this phone SDK in the repo"), false);
  assert.equal(isExplicitPhoneTask("Make the tap target bigger in the codebase"), false);
});
