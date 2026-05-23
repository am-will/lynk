export interface GatewayEvent {
  event: string;
  payload: unknown;
  seq?: number;
}

export interface GatewayChatSendResult {
  runId: string;
  sessionKey: string;
}

export type GatewayEventHandler = (event: GatewayEvent) => void;
