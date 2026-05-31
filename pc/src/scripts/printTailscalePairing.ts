import { discoverTailscaleEndpoints } from "../host/EndpointDiscovery.js";
import { loadOrCreateHostBridgeConfig } from "../host/HostConfigStore.js";
const DEFAULT_PORT = 8788;

const port = Number.parseInt(process.env.PHONE_AGENT_PORT ?? String(DEFAULT_PORT), 10);
if (!Number.isFinite(port) || port <= 0) {
  throw new Error(`Invalid PHONE_AGENT_PORT: ${process.env.PHONE_AGENT_PORT}`);
}

const endpoints = await discoverTailscaleEndpoints(port);
const endpoint = endpoints[0];
if (!endpoint) {
  throw new Error("Unable to discover a Tailscale endpoint. Install Tailscale, log in, or use LAN pairing.");
}
const wsUrl = endpoint.url;
const healthUrl = endpoint.url.replace(/^ws:/, "http:").replace(/\/phone$/, "/health");
const hostConfig = loadOrCreateHostBridgeConfig();
const token = process.env.PHONE_AGENT_TOKEN ?? hostConfig.config.phoneAgentToken;

console.log("Tailscale Android pairing");
console.log("");
console.log(`WebSocket URL: ${wsUrl}`);
console.log(`Health URL:    ${healthUrl}`);
console.log(`Host source:   ${endpoint.source}`);
if (endpoints.length > 1) {
  console.log("");
  console.log("Fallback URLs:");
  for (const fallback of endpoints.slice(1)) {
    console.log(`- ${fallback.url} (${fallback.source})`);
  }
}
console.log("");
console.log("Android app path:");
console.log("  OpenAgent -> Open Connection & Config -> Bridge");
console.log("");
console.log("Bridge fields:");
console.log(`- WebSocket URL: ${wsUrl}`);
console.log(`- Device ID: ${process.env.PHONE_AGENT_DEFAULT_DEVICE ?? hostConfig.config.phoneAgentDefaultDevice ?? "openclaw-agent"}`);
console.log(`- Auth token: ${token}`);
console.log("");
console.log("Start the PC side with:");
console.log("  openclaw gateway start");
console.log(`  PHONE_AGENT_PORT=${port} npm run bridge`);
