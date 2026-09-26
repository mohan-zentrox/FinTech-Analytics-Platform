import { coreApi } from "./client";
import type { PageResponse } from "./transactions";

export interface AuditLogEntry {
  id: string;
  actor: string;
  action: string;
  entity: string;
  entityId: string | null;
  details: string | null;
  timestamp: string;
}

export interface AuditLogSearchParams {
  entity?: string;
  entityId?: string;
  actor?: string;
  action?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export function searchAuditLogs(params: AuditLogSearchParams) {
  return coreApi.get<PageResponse<AuditLogEntry>>("/audit-logs", { params }).then((r) => r.data);
}
