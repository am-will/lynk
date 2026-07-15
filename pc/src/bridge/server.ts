import { createServer } from "node:http";
import { AuditLog } from "./AuditLog.js";
import { Dispatcher } from "../dispatcher/dispatcher.js";
import { OpenClawChatBridge } from "./OpenClawChatBridge.js";
import { OpenAiRealtimeClient } from "./OpenAiRealtimeClient.js";
import { OpenAiWebSearchClient } from "./OpenAiWebSearchClient.js";
import { RealtimeTaskManager } from "./RealtimeTaskManager.js";
import { getBridgeConfig } from "./config.js";
import { PhoneHub } from "./PhoneHub.js";
import { createBridgeHttpHandler } from "./bridgeHttp.js";
import { BridgeRealtime } from "./bridgeRealtime.js";
import { createPhoneWebSocketServer } from "./phoneWebSocket.js";
import { startAdbReverseMonitor } from "./AdbReverseMonitor.js";
import { createPhoneUpgradeHandler } from "./webSocketUpgrade.js";
import { HostBlobStore } from "./blob/HostBlobStore.js";
import { hostPathsForConfigPath } from "../host/HostPaths.js";

const config = getBridgeConfig();
const hostPaths = hostPathsForConfigPath(config.configPath);
const audit = new AuditLog(1_000, hostPaths.auditRoot);
const hub = new PhoneHub(config.defaultDeviceId, audit);
const dispatcher = new Dispatcher(hub, audit);
const blobs = new HostBlobStore(hostPaths.blobRoot);
const chatBridge = new OpenClawChatBridge(config, hub, dispatcher, audit, undefined, blobs);
const realtimeClient = new OpenAiRealtimeClient(config);
const webSearchClient = new OpenAiWebSearchClient(config);
const adbReverseMonitor = startAdbReverseMonitor({
  port: config.port,
  enabled: config.adbReverseEnabled
});

let realtimeTaskManager: RealtimeTaskManager;

async function stopAgentWork(deviceId: string, reason: string): Promise<void> {
  hub.cancelPendingCommands(deviceId, reason);
  await realtimeTaskManager.cancelDevice(deviceId, reason);
}

async function stopVoiceSessionWork(deviceId: string, voiceSessionId: string, reason: string): Promise<void> {
  await realtimeTaskManager.cancelSession(deviceId, voiceSessionId, reason);
}

function detachVoiceSessionWork(deviceId: string, voiceSessionId: string): void {
  realtimeTaskManager.detachSession(deviceId, voiceSessionId);
}

const realtime = new BridgeRealtime({
  config,
  hub,
  audit,
  realtimeClient,
  stopAgentWork: stopVoiceSessionWork,
  detachAgentWork: detachVoiceSessionWork
});

realtimeTaskManager = new RealtimeTaskManager({
  taskDelegate: chatBridge,
  audit,
  sendRealtime: (deviceId, message) => realtime.sendRealtime(deviceId, message),
  webSearch: webSearchClient,
  getRealtimeApiKey: (deviceId, voiceSessionId) => realtime.getRealtimeApiKey(deviceId, voiceSessionId),
  getRealtimeLocation: (deviceId, voiceSessionId) => realtime.getRealtimeLocation(deviceId, voiceSessionId),
  isVoiceSessionActive: (deviceId, voiceSessionId) => realtime.owns(deviceId, voiceSessionId)
});

const handleHttp = createBridgeHttpHandler({
  config,
  hub,
  audit,
  dispatcher,
  chatBridge,
  blobs,
  stopAgentWork
});

const server = createServer((req, res) => {
  handleHttp(req, res);
});

const wss = createPhoneWebSocketServer({
  config,
  hub,
  audit,
  dispatcher,
  chatBridge,
  realtimeTaskManager,
  realtime,
  stopAgentWork
});

server.on("upgrade", createPhoneUpgradeHandler(wss));

server.listen(config.port, config.host, () => {
  console.log(`lynk bridge listening on ws://${config.host}:${config.port}/phone`);
  console.log(`HTTP API listening on ${config.bridgeUrl}`);
});

async function shutdown(): Promise<void> {
  adbReverseMonitor.stop();
  server.close();
  wss.close();
  await chatBridge.close();
  await audit.close();
}

process.once("SIGINT", async () => {
  await shutdown();
  process.exit(130);
});

process.once("SIGTERM", async () => {
  await shutdown();
  process.exit(143);
});
