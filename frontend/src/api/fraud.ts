import { coreApi } from "./client";
import type { PageResponse } from "./transactions";

export type FraudRule = "DUPLICATE_PAYMENT" | "AMOUNT_OUTLIER" | "VELOCITY_SPIKE" | "ROUND_AMOUNT";
export type AlertSeverity = "LOW" | "MEDIUM" | "HIGH";
export type AlertStatus = "OPEN" | "CONFIRMED" | "DISMISSED";

export interface FraudAlert {
  id: string;
  scanId: string;
  transactionId: string;
  account: string;
  ruleId: FraudRule;
  severity: AlertSeverity;
  score: number;
  reason: string | null;
  status: AlertStatus;
  detectedAt: string;
  resolvedAt: string | null;
  resolvedBy: string | null;
  resolutionNote: string | null;
}

export interface FraudAlertSearchParams {
  account?: string;
  status?: AlertStatus;
  severity?: AlertSeverity;
  ruleId?: FraudRule;
  page?: number;
  size?: number;
}

export interface FraudScanRequest {
  account?: string;
  dateFrom?: string;
  dateTo?: string;
}

export interface FraudScanResult {
  scanId: string;
  account: string | null;
  dateFrom: string;
  dateTo: string;
  transactionsScanned: number;
  findings: number;
  newAlerts: number;
  updatedAlerts: number;
  suppressedAlerts: number;
  findingsByRule: Partial<Record<FraudRule, number>>;
}

export function searchFraudAlerts(params: FraudAlertSearchParams) {
  return coreApi.get<PageResponse<FraudAlert>>("/fraud/alerts", { params }).then((r) => r.data);
}

export function runFraudScan(request: FraudScanRequest) {
  return coreApi.post<FraudScanResult>("/fraud/scan", request).then((r) => r.data);
}

export function decideFraudAlert(id: string, status: AlertStatus, resolutionNote?: string) {
  return coreApi
    .patch<FraudAlert>(`/fraud/alerts/${id}`, { status, resolutionNote: resolutionNote || null })
    .then((r) => r.data);
}

export interface FraudSummary {
  account: string;
  openAlerts: number;
}

export function fetchFraudSummary(account?: string) {
  return coreApi.get<FraudSummary>("/fraud/summary", { params: { account } }).then((r) => r.data);
}
