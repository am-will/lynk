import qrcode from "qrcode-terminal";
import { getBridgeConfig } from "../bridge/config.js";
import { buildDiagnosticsBundle } from "./Diagnostics.js";
import { createHostPairingPayload } from "./PairingPayload.js";
import { refreshHostIntegrations } from "./IntegrationManager.js";
import { serviceInstallPlan } from "./ServiceManager.js";

const command = process.argv[2]?.trim() || "help";

switch (command) {
  case "pairing":
    {
      const payload = await createHostPairingPayload(getBridgeConfig());
      if (process.argv.includes("--qr")) {
        qrcode.generate(payload.deepLink, { small: true });
        console.log(payload.deepLink);
      } else {
        console.log(JSON.stringify(payload, null, 2));
      }
    }
    break;
  case "refresh":
    console.log(JSON.stringify(await refreshHostIntegrations({ configureMcp: process.argv.includes("--configure-mcp") }), null, 2));
    break;
  case "mcp":
    console.log(JSON.stringify(await refreshHostIntegrations({ configureMcp: true }), null, 2));
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
      "lynk-bridge host commands",
      "",
      "Commands:",
      "  pairing        Print Android pairing payload and deep link",
      "  refresh        Rescan OpenClaw, Hermes, Codex, Tailscale, and ADB",
      "  mcp            Install or update optional phone-control MCP registrations",
      "  service-plan   Print OS-specific service installation commands",
      "  diagnostics    Print a redacted diagnostics bundle",
      "",
      "Options:",
      "  pairing --qr             Print a terminal QR code for Android pairing",
      "  refresh --configure-mcp   Also update available host MCP registrations"
    ].join("\n"));
    break;
}
