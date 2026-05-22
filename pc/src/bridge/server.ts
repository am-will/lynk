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

const config = getBridgeConfig();
const audit = new AuditLog();
const hub = new PhoneHub(config.defaultDeviceId, audit);
const dispatcher = new Dispatcher(hub, audit);
const chatBridge = new OpenClawChatBridge(config, hub, dispatcher, audit);
const realtimeClient = new OpenAiRealtimeClient(config);
const webSearchClient = new OpenAiWebSearchClient(config);
const adbReverseMonitor = startAdbReverseMonitor({ port: config.port });

let realtimeTaskManager: RealtimeTaskManager;

async function stopAgentWork(deviceId: string, reason: string): Promise<void> {
  hub.cancelPendingCommands(deviceId, reason);
  await realtimeTaskManager.cancelDevice(deviceId, reason);
}

const realtime = new BridgeRealtime({
  config,
  hub,
  audit,
  realtimeClient,
  stopAgentWork
});

realtimeTaskManager = new RealtimeTaskManager({
  taskDelegate: chatBridge,
  audit,
  sendRealtime: (deviceId, message) => realtime.sendRealtime(deviceId, message),
  webSearch: webSearchClient,
  getRealtimeApiKey: (deviceId) => realtime.getRealtimeApiKey(deviceId),
  getRealtimeLocation: (deviceId) => realtime.getRealtimeLocation(deviceId)
});

const handleHttp = createBridgeHttpHandler({
  config,
  hub,
  audit,
  dispatcher,
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

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url ?? "/", `http://${req.headers.host ?? "localhost"}`);
  if (url.pathname !== "/phone") {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
});

server.listen(config.port, config.host, () => {
  console.log(`open-claw-agent bridge listening on ws://${config.host}:${config.port}/phone`);
  console.log(`HTTP API listening on ${config.bridgeUrl}`);
});

function shutdown(): void {
  adbReverseMonitor.stop();
  server.close();
  wss.close();
  chatBridge.close();
}

process.once("SIGINT", () => {
  shutdown();
  process.exit(130);
});

process.once("SIGTERM", () => {
  shutdown();
  process.exit(143);
});
