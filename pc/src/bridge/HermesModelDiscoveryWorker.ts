import { parentPort, workerData } from "node:worker_threads";
import { discoverHermesModels } from "./HermesModelDiscovery.js";

const defaultModel = typeof (workerData as { defaultModel?: unknown })?.defaultModel === "string"
  ? (workerData as { defaultModel: string }).defaultModel
  : "hermes-agent";

try {
  const models = discoverHermesModels(defaultModel);
  parentPort?.postMessage({ ok: true, models });
} catch (error) {
  parentPort?.postMessage({
    ok: false,
    error: error instanceof Error ? error.message : String(error)
  });
}
