import { homedir } from "node:os";
import { resolve } from "node:path";

export function resolveHermesConfigPath(): string {
  const explicit = process.env.HERMES_CONFIG_PATH?.trim();
  if (explicit) {
    return explicit;
  }
  const hermesHome = process.env.HERMES_HOME?.trim() || resolve(homedir(), ".hermes");
  return resolve(hermesHome, "config.yaml");
}
