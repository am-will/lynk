import { getBridgeConfig } from "../bridge/config.js";
import { buildDiagnosticsBundle } from "./Diagnostics.js";
import { createHostPairingPayload } from "./PairingPayload.js";
import { refreshHostIntegrations } from "./IntegrationManager.js";
import { serviceInstallPlan } from "./ServiceManager.js";

const command = process.argv[2]?.trim() || "help";

switch (command) {
  case "pairing":
    console.log(JSON.stringify(await createHostPairingPayload(getBridgeConfig()), null, 2));
    break;
  case "refresh":
    console.log(JSON.stringify(await refreshHostIntegrations({ configureMcp: process.argv.includes("--configure-mcp") }), null, 2));
    break;
  case "service-plan":
    console.log(JSON.stringify(serviceInstallPlan(), null, 2));
    break;
  case "diagnostics":
    console.log(JSON.stringify(await buildDiagnosticsBundle(), null, 2));
    break;
  case "help":
  default:
    console.log([
      "android-agent-bridge host commands",
      "",
      "Commands:",
      "  pairing        Print Android pairing payload and deep link",
      "  refresh        Rescan OpenClaw, Hermes, Codex, Tailscale, and ADB",
      "  service-plan   Print OS-specific service installation commands",
      "  diagnostics    Print a redacted diagnostics bundle",
      "",
      "Options:",
      "  refresh --configure-mcp   Also update available host MCP registrations"
    ].join("\n"));
    break;
}
