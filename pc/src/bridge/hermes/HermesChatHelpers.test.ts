import assert from "node:assert/strict";
import test from "node:test";
import { hermesCliModelArgs } from "./HermesChatHelpers.js";

test("Hermes CLI model args pass provider for provider/model ids", () => {
  assert.deepEqual(
    hermesCliModelArgs("anthropic/claude-sonnet-4.6"),
    ["--provider", "anthropic", "--model", "anthropic/claude-sonnet-4.6"]
  );
});

test("Hermes CLI model args keep provider:model ids compatible", () => {
  assert.deepEqual(
    hermesCliModelArgs("local-minimax:MiniMax-M2.7"),
    ["--provider", "local-minimax", "--model", "MiniMax-M2.7"]
  );
});
