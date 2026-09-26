import { analyticsApi } from "./client";

export interface CashFlowPoint {
  month: string;
  inflow: number;
  outflow: number;
  net: number;
}

export interface CashFlowResponse {
  accountId: string;
  months: number;
  series: CashFlowPoint[];
}

export function fetchCashFlow(accountId: string, months = 6) {
  return analyticsApi.get<CashFlowResponse>("/cash-flow", { params: { accountId, months } }).then((r) => r.data);
}

export interface KpiResponse {
  accountId: string;
  as_of: string;
  ar_aging: Record<string, number>;
  ap_aging: Record<string, number>;
  burn_rate: number;
  burn_rate_period_months: number;
}

export function fetchKpis(accountId: string) {
  return analyticsApi.get<KpiResponse>("/kpis", { params: { accountId } }).then((r) => r.data);
}

export type AnomalyRule = "DUPLICATE_PAYMENT" | "AMOUNT_OUTLIER" | "VELOCITY_SPIKE";

export interface AnomalyFinding {
  transactionId: string;
  account: string;
  ruleId: AnomalyRule;
  severity: "LOW" | "MEDIUM" | "HIGH";
  score: number;
  reason: string;
}

export interface AnomalyResponse {
  accountId: string;
  thresholds: {
    zThreshold: number;
    minSampleSize: number;
    duplicateWindowDays: number;
    minDailyCount: number;
  };
  summary: {
    total: number;
    byRule: Partial<Record<AnomalyRule, number>>;
    bySeverity: Record<"HIGH" | "MEDIUM" | "LOW", number>;
  };
  findings: AnomalyFinding[];
  truncated: boolean;
}

/**
 * Exploratory anomaly view from analytics-service (pandas), with tunable
 * thresholds. Distinct from core-api's persisted, triageable fraud alerts -
 * see the Fraud Alerts page for those.
 */
export function fetchAnomalies(accountId: string, limit = 50, zThreshold?: number) {
  return analyticsApi
    .get<AnomalyResponse>("/anomalies", { params: { accountId, limit, zThreshold } })
    .then((r) => r.data);
}
