import type {
  SessionConfigOption,
  SessionConfigSelectGroup,
  SessionConfigSelectOption,
  SessionConfigSelectOptions
} from "@agentclientprotocol/sdk";
import type { ChatModelOption, ChatReasoningOption } from "../../protocol/messages.js";
import { ChatClientError } from "../chat/ChatErrors.js";

export interface DevinModelOption {
  value: string;
  label: string;
  description?: string | null;
}

export interface DevinThoughtLevelOption {
  value: string;
  label: string;
  description?: string | null;
}

export interface DevinModeOption {
  value: string;
  label: string;
  description?: string | null;
}

export interface DevinEffectiveConfig {
  options: SessionConfigOption[];
  modelConfig?: {
    id: string;
    currentValue: string;
    options: DevinModelOption[];
  };
  thoughtConfig?: {
    id: string;
    currentValue: string;
    options: DevinThoughtLevelOption[];
  };
  modeConfig?: {
    id: string;
    currentValue: string;
    options: DevinModeOption[];
  };
}

export function devinConfigFromOptions(
  options: SessionConfigOption[] | null | undefined
): DevinEffectiveConfig {
  const result: DevinEffectiveConfig = { options: options ?? [] };
  for (const option of result.options) {
    if (option.type !== "select") {
      continue;
    }
    const selectOptions = flattenSelectOptions(option.options);
    if (option.category === "model" || option.category === "model_config") {
      result.modelConfig = {
        id: option.id,
        currentValue: option.currentValue,
        options: selectOptions.map((o) => ({
          value: o.value,
          label: o.name,
          description: o.description
        }))
      };
    }
    if (option.category === "thought_level") {
      result.thoughtConfig = {
        id: option.id,
        currentValue: option.currentValue,
        options: selectOptions.map((o) => ({
          value: o.value,
          label: o.name,
          description: o.description
        }))
      };
    }
    if (option.category === "mode") {
      result.modeConfig = {
        id: option.id,
        currentValue: option.currentValue,
        options: selectOptions.map((o) => ({
          value: o.value,
          label: o.name,
          description: o.description
        }))
      };
    }
  }
  return result;
}

function flattenSelectOptions(options: SessionConfigSelectOptions): SessionConfigSelectOption[] {
  const result: SessionConfigSelectOption[] = [];
  for (const option of options) {
    if (isSelectGroup(option)) {
      result.push(...option.options);
    } else {
      result.push(option);
    }
  }
  return result;
}

function isSelectGroup(option: SessionConfigSelectOption | SessionConfigSelectGroup): option is SessionConfigSelectGroup {
  return "options" in option && Array.isArray(option.options);
}

export function chatModelOptionsFromDevinConfig(config: DevinEffectiveConfig): ChatModelOption[] {
  const reasoningOptions: ChatReasoningOption[] | null =
    config.thoughtConfig && config.thoughtConfig.options.length > 0
      ? config.thoughtConfig.options.map((option) => ({ id: option.value, label: option.label }))
      : null;
  const defaultReasoningEffort = config.thoughtConfig?.currentValue ?? null;

  if (!config.modelConfig || config.modelConfig.options.length === 0) {
    return [
      {
        id: "default",
        label: "Devin default",
        modelId: "default",
        harnessId: "devin",
        harnessLabel: "Devin",
        provider: "devin",
        reasoningOptions,
        defaultReasoningEffort
      }
    ];
  }

  return config.modelConfig.options.map((option) => ({
    id: option.value,
    label: option.label,
    modelId: option.value,
    harnessId: "devin",
    harnessLabel: "Devin",
    provider: "devin",
    reasoningOptions,
    defaultReasoningEffort
  }));
}

export function selectDevinModelConfigId(
  config: DevinEffectiveConfig,
  requestedModel: string | undefined
): string | undefined {
  if (!requestedModel || requestedModel === "default") {
    return undefined;
  }
  if (!config.modelConfig) {
    throw new ChatClientError("Devin does not advertise a model selector.", {
      code: "devin.model_not_available"
    });
  }
  if (!config.modelConfig.options.some((option) => option.value === requestedModel)) {
    throw new ChatClientError(`Devin model "${requestedModel}" is not currently advertised.`, {
      code: "devin.model_not_available"
    });
  }
  return config.modelConfig.id;
}

export function selectDevinThoughtConfigId(
  config: DevinEffectiveConfig,
  requestedReasoning: string | undefined
): string | undefined {
  if (!requestedReasoning) {
    return undefined;
  }
  if (!config.thoughtConfig) {
    throw new ChatClientError("Devin does not advertise a reasoning selector.", {
      code: "devin.reasoning_not_available"
    });
  }
  if (!config.thoughtConfig.options.some((option) => option.value === requestedReasoning)) {
    throw new ChatClientError(`Devin reasoning level "${requestedReasoning}" is not currently advertised.`, {
      code: "devin.reasoning_not_available"
    });
  }
  return config.thoughtConfig.id;
}

export function selectDevinModeConfigId(
  config: DevinEffectiveConfig,
  requestedMode: string | undefined
): string | undefined {
  if (!requestedMode || config.modeConfig?.currentValue === requestedMode) {
    return undefined;
  }
  if (!config.modeConfig) {
    throw new ChatClientError("Devin does not advertise a permission mode selector.", {
      code: "devin.mode_not_available"
    });
  }
  if (!config.modeConfig.options.some((option) => option.value === requestedMode)) {
    throw new ChatClientError(`Devin mode "${requestedMode}" is not currently advertised.`, {
      code: "devin.mode_not_available"
    });
  }
  return config.modeConfig.id;
}
