import assert from "node:assert/strict";
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { discoverHermesModels } from "./HermesModelDiscovery.js";

function withHermesHome(files: Record<string, string>, run: () => void): void {
  const previousHome = process.env.HERMES_HOME;
  const previousConfigPath = process.env.HERMES_CONFIG_PATH;
  const previousPython = process.env.HERMES_PYTHON;
  const home = mkdtempSync(join(tmpdir(), "open-claw-hermes-"));
  try {
    for (const [name, content] of Object.entries(files)) {
      const path = join(home, name);
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content);
      if (name.endsWith("/python")) {
        chmodSync(path, 0o755);
      }
    }
    process.env.HERMES_HOME = home;
    process.env.HERMES_PYTHON = join(home, "hermes-agent", "venv", "bin", "python");
    delete process.env.HERMES_CONFIG_PATH;
    run();
  } finally {
    if (previousHome === undefined) {
      delete process.env.HERMES_HOME;
    } else {
      process.env.HERMES_HOME = previousHome;
    }
    if (previousConfigPath === undefined) {
      delete process.env.HERMES_CONFIG_PATH;
    } else {
      process.env.HERMES_CONFIG_PATH = previousConfigPath;
    }
    if (previousPython === undefined) {
      delete process.env.HERMES_PYTHON;
    } else {
      process.env.HERMES_PYTHON = previousPython;
    }
    rmSync(home, { recursive: true, force: true });
  }
}

const configYaml = [
  "model:",
  "  default: MiniMax-M2.7",
  "  provider: local-minimax",
  "  context_length: 149429",
  "providers:",
  "  local-minimax:",
  "    models:",
  "      MiniMax-M2.7:",
  "        context_length: 149429"
].join("\n");

const authJson = JSON.stringify({
  providers: {
    "openai-codex": {}
  }
});

test("Hermes model discovery uses Codex OAuth context length cache", () => {
  withHermesHome({
    "config.yaml": configYaml,
    "auth.json": authJson,
    "context_length_cache.yaml": [
      "context_lengths:",
      "  gpt-5.5@https://chatgpt.com/backend-api/codex: 333000"
    ].join("\n")
  }, () => {
    const models = discoverHermesModels("hermes-agent");

    assert.equal(models.find((model) => model.id === "openai-codex:gpt-5.5")?.contextWindow, 333_000);
    assert.equal(models.find((model) => model.id === "local-minimax:MiniMax-M2.7")?.contextWindow, 149_429);
  });
});

test("Hermes model discovery falls back to known Codex OAuth context windows", () => {
  withHermesHome({
    "config.yaml": configYaml,
    "auth.json": authJson
  }, () => {
    const models = discoverHermesModels("hermes-agent");

    assert.equal(models.find((model) => model.id === "openai-codex:gpt-5.5")?.contextWindow, 272_000);
    assert.equal(models.find((model) => model.id === "openai-codex:gpt-5.3-codex-spark")?.contextWindow, 128_000);
  });
});

test("Hermes model discovery includes authenticated picker providers", () => {
  withHermesHome({
    "config.yaml": configYaml,
    "hermes-agent/venv/bin/python": [
      "#!/bin/sh",
      "cat <<'JSON'",
      JSON.stringify({
        anthropic: ["claude-sonnet-4-6"],
        gemini: ["gemini-3-pro-preview"]
      }),
      "JSON"
    ].join("\n")
  }, () => {
    const models = discoverHermesModels("hermes-agent");

    assert.equal(models.find((model) => model.id === "anthropic:claude-sonnet-4-6")?.label, "Anthropic / claude-sonnet-4-6");
    assert.equal(models.find((model) => model.id === "gemini:gemini-3-pro-preview")?.label, "Google AI Studio / gemini-3-pro-preview");
  });
});

test("Hermes model discovery uses Hermes provider model cache", () => {
  withHermesHome({
    "config.yaml": configYaml,
    "provider_models_cache.json": JSON.stringify({
      gemini: {
        at: 1780454854,
        models: ["gemini-3-pro-preview", "gemini-3.5-flash"]
      }
    })
  }, () => {
    const models = discoverHermesModels("hermes-agent");

    assert.deepEqual(
      models.filter((model) => model.provider === "gemini").map((model) => model.id),
      ["gemini:gemini-3-pro-preview", "gemini:gemini-3.5-flash"]
    );
  });
});
