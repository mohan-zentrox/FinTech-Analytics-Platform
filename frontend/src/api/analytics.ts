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
