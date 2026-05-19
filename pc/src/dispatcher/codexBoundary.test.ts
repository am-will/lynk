import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { dirname, join, relative } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const srcDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const pcDir = join(srcDir, "..");

async function typescriptFiles(root: string): Promise<string[]> {
  const entries = await readdir(root, { withFileTypes: true });
  const files = await Promise.all(entries.map(async (entry) => {
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      return entry.name === "generated" ? [] : await typescriptFiles(path);
    }
    return entry.isFile() && entry.name.endsWith(".ts") ? [path] : [];
  }));
  return files.flat();
}

test("hand-written pc source does not import generated Codex schemas", async () => {
  const offenders: string[] = [];
  const generatedSegment = "generated";
  const generatedImportMarkers = [`/${generatedSegment}/`, `../${generatedSegment}`, `./${generatedSegment}`];
  for (const file of await typescriptFiles(srcDir)) {
    if (file.endsWith("codexBoundary.test.ts")) {
      continue;
    }
    const source = await readFile(file, "utf8");
    if (generatedImportMarkers.some((marker) => source.includes(marker))) {
      offenders.push(relative(pcDir, file));
    }
  }

  assert.deepEqual(offenders, []);
});

test("TypeScript config keeps generated schemas excluded", async () => {
  const tsconfig = JSON.parse(await readFile(join(pcDir, "tsconfig.json"), "utf8")) as { exclude?: string[] };
  assert.ok(tsconfig.exclude?.includes("src/generated/**"));
});
