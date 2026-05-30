import { createHostPairingPayload } from "./PairingPayload.js";
import { refreshHostIntegrations } from "./IntegrationManager.js";
import { installHostService } from "./ServiceManager.js";
import { getBridgeConfig } from "../bridge/config.js";

function shouldInstallService(): boolean {
  if (process.env.LYNK_BRIDGE_SKIP_SERVICE_INSTALL === "1") {
    return false;
  }
  if (process.env.LYNK_BRIDGE_INSTALL_SERVICE === "1") {
    return true;
  }
  return process.env.npm_config_global === "true" || process.env.npm_config_location === "global";
}

if (shouldInstallService()) {
  try {
    await refreshHostIntegrations({ configureMcp: false });
    const service = await installHostService();
    const pairing = await createHostPairingPayload(getBridgeConfig());
    console.log(`[lynk-bridge] ${service.message}`);
    console.log(`[lynk-bridge] Pair Android with: lynk-bridge-host pairing --qr`);
    console.log(`[lynk-bridge] First endpoint: ${pairing.endpoints[0]?.url ?? "none discovered"}`);
    console.log(`[lynk-bridge] Optional phone-control MCP: lynk-bridge-host mcp`);
  } catch (error) {
    console.warn(`[lynk-bridge] Startup service install failed: ${error instanceof Error ? error.message : String(error)}`);
    console.warn("[lynk-bridge] Run manually: lynk-bridge-host install-service");
  }
}
