import { coreApi } from "./client";

export type ConnectorStatusValue = "CONNECTED" | "EXPIRED" | "DISCONNECTED";

export interface ConnectorStatus {
  provider: string;
  account: string | null;
  connected: boolean;
  sandbox: boolean;
  status: ConnectorStatusValue;
  realmId: string | null;
  scope: string | null;
  accessTokenExpiresAt: string | null;
  lastSyncAt: string | null;
  lastSyncError: string | null;
}

export interface SyncResult {
  provider: string;
  account: string;
  since: string;
  sandbox: boolean;
  pagesFetched: number;
  fetched: number;
  imported: number;
  skipped: number;
  errored: number;
  errors: string[];
  syncedAt: string;
}

export interface AuthorizeUrlResponse {
  provider: string;
  state: string;
  authorizationUrl: string;
}

export function fetchConnectors() {
  return coreApi.get<ConnectorStatus[]>("/connectors").then((r) => r.data);
}

export function fetchAuthorizeUrl(provider: string) {
  return coreApi
    .get<AuthorizeUrlResponse>(`/connectors/${provider}/authorize-url`)
    .then((r) => r.data);
}

export function connectProvider(provider: string, account: string, code: string, realmId?: string) {
  return coreApi
    .post<ConnectorStatus>(`/connectors/${provider}/connect`, { account, code, realmId: realmId || null })
    .then((r) => r.data);
}

export function disconnectProvider(provider: string, account: string) {
  return coreApi.delete(`/connectors/${provider}`, { params: { account } });
}

export function syncProvider(provider: string, account: string, since?: string) {
  return coreApi
    .post<SyncResult>(`/connectors/${provider}/sync`, null, { params: { account, since } })
    .then((r) => r.data);
}
