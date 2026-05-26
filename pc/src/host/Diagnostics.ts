import { platform, release } from "node:os";
import { getBridgeConfig } from "../bridge/config.js";
import { detectIntegrations } from "./IntegrationManager.js";
import { discoverEndpoints } from "./EndpointDiscovery.js";
import { loadOrCreateHostBridgeConfig, redactedHostBridgeConfig } from "./HostConfigStore.js";
import { serviceInstallPlan } from "./ServiceManager.js";

export async function buildDiagnosticsBundle(): Promise<Record<string, unknown>> {
  const loaded = loadOrCreateHostBridgeConfig();
  const bridgeConfig = getBridgeConfig();
  const discovery = await discoverEndpoints({ port: bridgeConfig.port });
  const integrations = await detectIntegrations();
  return {
    product: "android-agent-bridge",
    generatedAt: new Date().toISOString(),
    os: {
      platform: platform(),
      release: release()
    },
    configPath: loaded.path,
    config: redactedHostBridgeConfig(loaded.config),
    bridge: {
      host: bridgeConfig.host,
      port: bridgeConfig.port,
      defaultDeviceId: bridgeConfig.defaultDeviceId,
      bridgeUrl: bridgeConfig.bridgeUrl,
      codexConfigured: bridgeConfig.codexConfigured
    },
    discovery,
    integrations,
    service: serviceInstallPlan()
  };
}
