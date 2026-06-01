import { execFile } from "node:child_process";
import { mkdir, rm, writeFile } from "node:fs/promises";
import { homedir, platform } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { promisify } from "node:util";
import { defaultHostBridgeConfigPath } from "./HostConfigStore.js";

const execFileAsync = promisify(execFile);

export interface ServiceInstallPlan {
  platform: NodeJS.Platform;
  serviceName: string;
  description: string;
  commands: string[];
  notes: string[];
}

export interface ServiceActionResult {
  platform: NodeJS.Platform;
  serviceName: string;
  installed?: boolean;
  running?: boolean;
  configPath: string;
  message: string;
  details?: string;
}

const scriptDir = dirname(fileURLToPath(import.meta.url));
const pcRoot = resolve(scriptDir, "../..");
const macLabel = "dev.androidagent.bridge";
const linuxUnitName = "lynk-bridge.service";
const windowsTaskName = "LynkBridge";

export function serviceInstallPlan(): ServiceInstallPlan {
  const serviceName = serviceNameForPlatform();
  const bridgeCommand = commandForShell(process.execPath, [resolve(pcRoot, "dist/bridge/server.js")]);
  const configPath = defaultHostBridgeConfigPath();
  switch (platform()) {
    case "darwin":
      return {
        platform: "darwin",
        serviceName,
        description: "Install a per-user LaunchAgent for the Lynk bridge.",
        commands: [
          `mkdir -p "$HOME/Library/LaunchAgents"`,
          `cat > "$HOME/Library/LaunchAgents/${macLabel}.plist" <<'PLIST'\n${macLaunchAgentPlist(process.execPath, [resolve(pcRoot, "dist/bridge/server.js")], configPath)}\nPLIST`,
          `launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/${macLabel}.plist"`,
          `launchctl enable "gui/$(id -u)/${macLabel}"`
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
        description: "Install a user-level systemd service for the Lynk bridge.",
        commands: [
          `mkdir -p "$HOME/.config/systemd/user"`,
          `cat > "$HOME/.config/systemd/user/${linuxUnitName}" <<'SERVICE'\n${linuxSystemdUnit(bridgeCommand, configPath)}\nSERVICE`,
          "systemctl --user daemon-reload",
          `systemctl --user enable --now ${linuxUnitName}`
        ],
        notes: ["For non-systemd desktops, package installers should fall back to XDG autostart."]
      };
  }
}

export async function installHostService(): Promise<ServiceActionResult> {
  const configPath = defaultHostBridgeConfigPath();
  switch (platform()) {
    case "darwin":
      return await installMacLaunchAgent(configPath);
    case "win32":
      return await installWindowsTask(configPath);
    default:
      return await installLinuxService(configPath);
  }
}

export async function uninstallHostService(): Promise<ServiceActionResult> {
  const configPath = defaultHostBridgeConfigPath();
  switch (platform()) {
    case "darwin":
      return await uninstallMacLaunchAgent(configPath);
    case "win32":
      return await uninstallWindowsTask(configPath);
    default:
      return await uninstallLinuxService(configPath);
  }
}

export async function hostServiceStatus(): Promise<ServiceActionResult> {
  const configPath = defaultHostBridgeConfigPath();
  switch (platform()) {
    case "darwin":
      return await macServiceStatus(configPath);
    case "win32":
      return await windowsServiceStatus(configPath);
    default:
      return await linuxServiceStatus(configPath);
  }
}

function macLaunchAgentPlist(program: string, args: string[], configPath: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0">\n<dict>\n  <key>Label</key><string>dev.androidagent.bridge</string>\n  <key>ProgramArguments</key>\n  <array>\n    <string>${escapeXml(program)}</string>\n${args.map((arg) => `    <string>${escapeXml(arg)}</string>`).join("\n")}\n  </array>\n  <key>WorkingDirectory</key><string>${escapeXml(pcRoot)}</string>\n  <key>EnvironmentVariables</key>\n  <dict>\n    <key>PHONE_AGENT_CONFIG_PATH</key><string>${escapeXml(configPath)}</string>\n    <key>PATH</key><string>${escapeXml(servicePath())}</string>\n  </dict>\n  <key>RunAtLoad</key><true/>\n  <key>KeepAlive</key><true/>\n</dict>\n</plist>`;
}

function linuxSystemdUnit(command: string, configPath: string): string {
  return `[Unit]\nDescription=Lynk Bridge\nAfter=network-online.target\n\n[Service]\nType=simple\nWorkingDirectory=${systemdEscape(pcRoot)}\nEnvironment=PHONE_AGENT_CONFIG_PATH=${systemdEscape(configPath)}\nExecStart=${command}\nRestart=on-failure\nRestartSec=5\n\n[Install]\nWantedBy=default.target`;
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function servicePath(): string {
  return process.env.PATH?.trim() || "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin";
}

async function installMacLaunchAgent(configPath: string): Promise<ServiceActionResult> {
  const plistPath = resolve(homedir(), "Library", "LaunchAgents", `${macLabel}.plist`);
  await mkdir(dirname(plistPath), { recursive: true });
  await writeFile(plistPath, macLaunchAgentPlist(process.execPath, [resolve(pcRoot, "dist/bridge/server.js")], configPath));
  await execFileQuiet("launchctl", ["bootout", `gui/${process.getuid?.() ?? ""}`, plistPath]);
  await execFileAsync("launchctl", ["bootstrap", `gui/${process.getuid?.() ?? ""}`, plistPath]);
  await execFileAsync("launchctl", ["enable", `gui/${process.getuid?.() ?? ""}/${macLabel}`]);
  return {
    platform: "darwin",
    serviceName: macLabel,
    installed: true,
    running: true,
    configPath,
    message: `Installed and started LaunchAgent ${macLabel}.`
  };
}

async function uninstallMacLaunchAgent(configPath: string): Promise<ServiceActionResult> {
  const plistPath = resolve(homedir(), "Library", "LaunchAgents", `${macLabel}.plist`);
  await execFileQuiet("launchctl", ["bootout", `gui/${process.getuid?.() ?? ""}`, plistPath]);
  await rm(plistPath, { force: true });
  return {
    platform: "darwin",
    serviceName: macLabel,
    installed: false,
    running: false,
    configPath,
    message: `Removed LaunchAgent ${macLabel}.`
  };
}

async function macServiceStatus(configPath: string): Promise<ServiceActionResult> {
  const result = await execFileQuiet("launchctl", ["print", `gui/${process.getuid?.() ?? ""}/${macLabel}`]);
  return {
    platform: "darwin",
    serviceName: macLabel,
    installed: result.ok,
    running: result.ok,
    configPath,
    message: result.ok ? `${macLabel} is registered with launchctl.` : `${macLabel} is not registered with launchctl.`,
    details: result.output.trim() || undefined
  };
}

async function installLinuxService(configPath: string): Promise<ServiceActionResult> {
  if (await commandOk("systemctl", ["--user", "--version"])) {
    const unitPath = resolve(process.env.XDG_CONFIG_HOME?.trim() || resolve(homedir(), ".config"), "systemd", "user", linuxUnitName);
    await mkdir(dirname(unitPath), { recursive: true });
    await writeFile(unitPath, linuxSystemdUnit(commandForShell(process.execPath, [resolve(pcRoot, "dist/bridge/server.js")]), configPath));
    await execFileAsync("systemctl", ["--user", "daemon-reload"]);
    await execFileAsync("systemctl", ["--user", "enable", "--now", linuxUnitName]);
    return {
      platform: platform(),
      serviceName: linuxUnitName,
      installed: true,
      running: true,
      configPath,
      message: `Installed and started user systemd service ${linuxUnitName}.`
    };
  }

  const desktopPath = resolve(process.env.XDG_CONFIG_HOME?.trim() || resolve(homedir(), ".config"), "autostart", "lynk-bridge.desktop");
  await mkdir(dirname(desktopPath), { recursive: true });
  await writeFile(desktopPath, `[Desktop Entry]\nType=Application\nName=Lynk Bridge\nExec=${process.execPath} ${resolve(pcRoot, "dist/bridge/server.js")}\nX-GNOME-Autostart-enabled=true\n`);
  return {
    platform: platform(),
    serviceName: "lynk-bridge.desktop",
    installed: true,
    running: false,
    configPath,
    message: "Installed XDG autostart entry. The bridge will start on next desktop login."
  };
}

async function uninstallLinuxService(configPath: string): Promise<ServiceActionResult> {
  await execFileQuiet("systemctl", ["--user", "disable", "--now", linuxUnitName]);
  const unitPath = resolve(process.env.XDG_CONFIG_HOME?.trim() || resolve(homedir(), ".config"), "systemd", "user", linuxUnitName);
  const desktopPath = resolve(process.env.XDG_CONFIG_HOME?.trim() || resolve(homedir(), ".config"), "autostart", "lynk-bridge.desktop");
  await rm(unitPath, { force: true });
  await rm(desktopPath, { force: true });
  await execFileQuiet("systemctl", ["--user", "daemon-reload"]);
  return {
    platform: platform(),
    serviceName: linuxUnitName,
    installed: false,
    running: false,
    configPath,
    message: `Removed ${linuxUnitName} and XDG autostart entry if present.`
  };
}

async function linuxServiceStatus(configPath: string): Promise<ServiceActionResult> {
  const result = await execFileQuiet("systemctl", ["--user", "is-active", linuxUnitName]);
  return {
    platform: platform(),
    serviceName: linuxUnitName,
    installed: result.ok,
    running: result.output.trim() === "active",
    configPath,
    message: result.output.trim() === "active" ? `${linuxUnitName} is active.` : `${linuxUnitName} is not active.`,
    details: result.output.trim() || undefined
  };
}

async function installWindowsTask(configPath: string): Promise<ServiceActionResult> {
  const bridgeCommand = `"${process.execPath}" "${resolve(pcRoot, "dist", "bridge", "server.js")}"`;
  await execFileAsync("schtasks.exe", ["/Create", "/TN", windowsTaskName, "/SC", "ONLOGON", "/TR", bridgeCommand, "/F"]);
  await execFileQuiet("netsh.exe", ["advfirewall", "firewall", "add", "rule", `name=${windowsTaskName}`, "dir=in", "action=allow", "protocol=TCP", "localport=8788"]);
  await execFileQuiet("schtasks.exe", ["/Run", "/TN", windowsTaskName]);
  return {
    platform: "win32",
    serviceName: windowsTaskName,
    installed: true,
    running: true,
    configPath,
    message: `Installed scheduled task ${windowsTaskName}.`
  };
}

async function uninstallWindowsTask(configPath: string): Promise<ServiceActionResult> {
  await execFileQuiet("schtasks.exe", ["/Delete", "/TN", windowsTaskName, "/F"]);
  await execFileQuiet("netsh.exe", ["advfirewall", "firewall", "delete", "rule", `name=${windowsTaskName}`]);
  return {
    platform: "win32",
    serviceName: windowsTaskName,
    installed: false,
    running: false,
    configPath,
    message: `Removed scheduled task ${windowsTaskName}.`
  };
}

async function windowsServiceStatus(configPath: string): Promise<ServiceActionResult> {
  const result = await execFileQuiet("schtasks.exe", ["/Query", "/TN", windowsTaskName, "/FO", "LIST"]);
  return {
    platform: "win32",
    serviceName: windowsTaskName,
    installed: result.ok,
    running: result.ok && /Status:\s+Running/i.test(result.output),
    configPath,
    message: result.ok ? `${windowsTaskName} is registered.` : `${windowsTaskName} is not registered.`,
    details: result.output.trim() || undefined
  };
}

async function execFileQuiet(file: string, args: string[]): Promise<{ ok: boolean; output: string }> {
  try {
    const { stdout, stderr } = await execFileAsync(file, args, { timeout: 10_000, maxBuffer: 1024 * 1024 });
    return { ok: true, output: `${stdout}${stderr}` };
  } catch (error) {
    const typed = error as { stdout?: string; stderr?: string; message?: string };
    return { ok: false, output: `${typed.stdout ?? ""}${typed.stderr ?? ""}${typed.message ?? ""}` };
  }
}

async function commandOk(file: string, args: string[]): Promise<boolean> {
  return (await execFileQuiet(file, args)).ok;
}

function serviceNameForPlatform(): string {
  switch (platform()) {
    case "darwin":
      return macLabel;
    case "win32":
      return windowsTaskName;
    default:
      return linuxUnitName;
  }
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`;
}

function commandForShell(program: string, args: string[]): string {
  if (platform() === "win32") {
    return [`"${program.replace(/"/g, '\\"')}"`, ...args.map((arg) => `"${arg.replace(/"/g, '\\"')}"`)].join(" ");
  }
  return [shellQuote(program), ...args.map(shellQuote)].join(" ");
}

function systemdEscape(value: string): string {
  return value.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}
