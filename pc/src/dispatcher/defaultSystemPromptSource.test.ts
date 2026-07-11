import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { GENERATED_DEFAULT_SYSTEM_PROMPT } from "./defaultSystemPrompt.generated.js";

test("generated default system prompt matches the neutral source", async () => {
  const testDirectory = dirname(fileURLToPath(import.meta.url));
  const sourcePath = resolve(testDirectory, "../../../shared/default-system-prompt.txt");
  const source = (await readFile(sourcePath, "utf8")).replace(/\r\n/g, "\n").trim();

  assert.equal(GENERATED_DEFAULT_SYSTEM_PROMPT, source);
});
