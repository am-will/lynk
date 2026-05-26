import { platform } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { defaultHostBridgeConfigPath } from "./HostConfigStore.js";

export interface ServiceInstallPlan {
  platform: NodeJS.Platform;
  serviceName: string;
  description: string;
  commands: string[];
  notes: string[];
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");

export function serviceInstallPlan(): ServiceInstallPlan {
  const serviceName = "AndroidAgentBridge";
  const bridgeCommand = `${process.execPath} ${resolve(pcRoot, "dist/bridge/server.js")}`;
  const configPath = defaultHostBridgeConfigPath();
  switch (platform()) {
    case "darwin":
      return {
        platform: "darwin",
        serviceName,
        description: "Install a per-user LaunchAgent for the Android Agent host bridge.",
        commands: [
          `mkdir -p "$HOME/Library/LaunchAgents"`,
          `cat > "$HOME/Library/LaunchAgents/dev.androidagent.bridge.plist" <<'PLIST'\n${macLaunchAgentPlist(bridgeCommand, configPath)}\nPLIST`,
          `launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/dev.androidagent.bridge.plist"`,
          `launchctl enable "gui/$(id -u)/dev.androidagent.bridge"`
        ],
        notes: ["The app bundle installer should perform these steps after code signing and notarization."]
      };
    case "win32":
      return {
        platform: "win32",
        serviceName,
        description: "Install a Windows scheduled task that starts the bridge at user login.",
        commands: [
          `schtasks /Create /TN ${serviceName} /SC ONLOGON /TR "${bridgeCommand}" /F`,
          `netsh advfirewall firewall add rule name="${serviceName}" dir=in action=allow protocol=TCP localport=8788`
        ],
        notes: ["A signed MSI should run these commands with the user's consent."]
      };
    default:
      return {
        platform: platform(),
        serviceName,
        description: "Install a user-level systemd service for the Android Agent host bridge.",
        commands: [
          `mkdir -p "$HOME/.config/systemd/user"`,
          `cat > "$HOME/.config/systemd/user/android-agent-bridge.service" <<'SERVICE'\n${linuxSystemdUnit(bridgeCommand, configPath)}\nSERVICE`,
          "systemctl --user daemon-reload",
          "systemctl --user enable --now android-agent-bridge.service"
        ],
        notes: ["For non-systemd desktops, package installers should fall back to XDG autostart."]
      };
  }
}

function macLaunchAgentPlist(command: string, configPath: string): string {
  const [program, ...args] = command.split(/\s+/);
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0">\n<dict>\n  <key>Label</key><string>dev.androidagent.bridge</string>\n  <key>ProgramArguments</key>\n  <array>\n    <string>${escapeXml(program)}</string>\n${args.map((arg) => `    <string>${escapeXml(arg)}</string>`).join("\n")}\n  </array>\n  <key>EnvironmentVariables</key>\n  <dict><key>PHONE_AGENT_CONFIG_PATH</key><string>${escapeXml(configPath)}</string></dict>\n  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n</dict>\n</plist>`;
}

function linuxSystemdUnit(command: string, configPath: string): string {
  return `[Unit]\nDescription=Android Agent Bridge\nAfter=network-online.target\n\n[Service]\nType=simple\nEnvironment=PHONE_AGENT_CONFIG_PATH=${configPath}\nExecStart=${command}\nRestart=on-failure\nRestartSec=5\n\n[Install]\nWantedBy=default.target`;
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}
