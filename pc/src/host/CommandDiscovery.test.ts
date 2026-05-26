import assert from "node:assert/strict";
import test from "node:test";

import { commandExecutable } from "./CommandDiscovery.js";

test("commandExecutable handles bare, quoted, and empty commands", () => {
  assert.equal(commandExecutable("codex app-server --listen stdio://"), "codex");
  assert.equal(commandExecutable("\"/Applications/Codex CLI/codex\" app-server"), "/Applications/Codex CLI/codex");
  assert.equal(commandExecutable("'/opt/hermes bin/hermes' serve"), "/opt/hermes bin/hermes");
  assert.equal(commandExecutable("   "), "");
});
