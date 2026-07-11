import { describe, it } from "node:test";
import assert from "node:assert/strict";
import type { SessionConfigOption } from "@agentclientprotocol/sdk";
import { ChatClientError } from "../chat/ChatErrors.js";
import {
  chatModelOptionsFromDevinConfig,
  devinConfigFromOptions,
  selectDevinModelConfigId,
  selectDevinThoughtConfigId
} from "./DevinSessionConfig.js";

function modelOption(overrides: Partial<SessionConfigOption> = {}): SessionConfigOption {
  return {
    id: "model",
    name: "Model",
    type: "select",
    category: "model",
    currentValue: "default",
    options: [
      { value: "default", name: "Devin default" },
      { value: "claude-sonnet", name: "Claude Sonnet" }
    ],
    ...overrides
  } as SessionConfigOption;
}

function thoughtOption(overrides: Partial<SessionConfigOption> = {}): SessionConfigOption {
  return {
    id: "thought",
    name: "Thought level",
    type: "select",
    category: "thought_level",
    currentValue: "normal",
    options: [
      { value: "minimal", name: "Minimal" },
      { value: "normal", name: "Normal" },
      { value: "deep", name: "Deep" }
    ],
    ...overrides
  } as SessionConfigOption;
}

describe("DevinSessionConfig", () => {
  it("extracts model and thought config options by category", () => {
    const config = devinConfigFromOptions([modelOption(), thoughtOption()]);
    assert.equal(config.modelConfig?.id, "model");
    assert.equal(config.modelConfig?.currentValue, "default");
    assert.equal(config.modelConfig?.options.length, 2);
    assert.equal(config.thoughtConfig?.id, "thought");
    assert.equal(config.thoughtConfig?.options.length, 3);
  });

  it("recognizes model_config category as model selector", () => {
    const config = devinConfigFromOptions([modelOption({ category: "model_config" })]);
    assert.equal(config.modelConfig?.id, "model");
  });

  it("returns default model option when no model selector is advertised", () => {
    const config = devinConfigFromOptions([thoughtOption()]);
    const models = chatModelOptionsFromDevinConfig(config);
    assert.equal(models.length, 1);
    assert.equal(models[0]?.id, "default");
    assert.equal(models[0]?.label, "Devin default");
    assert.equal(models[0]?.modelId, "default");
  });

  it("maps advertised model values with reasoning options", () => {
    const config = devinConfigFromOptions([modelOption({ currentValue: "claude-sonnet" }), thoughtOption({ currentValue: "deep" })]);
    const models = chatModelOptionsFromDevinConfig(config);
    assert.equal(models.length, 2);
    assert.equal(models[0]?.id, "default");
    assert.equal(models[0]?.defaultReasoningEffort, "deep");
    assert.ok(models[0]?.reasoningOptions);
    assert.equal(models[0]?.reasoningOptions?.length, 3);
  });

  it("selectDevinModelConfigId accepts advertised values and default", () => {
    const config = devinConfigFromOptions([modelOption()]);
    assert.equal(selectDevinModelConfigId(config, "default"), undefined);
    assert.equal(selectDevinModelConfigId(config, undefined), undefined);
    assert.equal(selectDevinModelConfigId(config, "claude-sonnet"), "model");
  });

  it("selectDevinModelConfigId rejects unknown models", () => {
    const config = devinConfigFromOptions([modelOption()]);
    assert.throws(() => selectDevinModelConfigId(config, "unknown"), ChatClientError);
  });

  it("selectDevinThoughtConfigId accepts advertised values and undefined", () => {
    const config = devinConfigFromOptions([thoughtOption()]);
    assert.equal(selectDevinThoughtConfigId(config, undefined), undefined);
    assert.equal(selectDevinThoughtConfigId(config, "deep"), "thought");
  });

  it("selectDevinThoughtConfigId rejects unknown reasoning values", () => {
    const config = devinConfigFromOptions([thoughtOption()]);
    assert.throws(() => selectDevinThoughtConfigId(config, "unknown"), ChatClientError);
  });
});
