import { coreApi } from "./client";
import type { Transaction } from "./transactions";

export interface ReconciliationRequest {
  accountA: string;
  sourceA: string;
  accountB: string;
  sourceB: string;
  dateFrom: string;
  dateTo: string;
  amountTolerance: number;
  dateToleranceDays: number;
}

export interface MatchedPair {
  transactionA: Transaction;
  transactionB: Transaction;
  amountDelta: number;
  dateDeltaDays: number;
}

export interface ReconciliationResult {
  runId: string;
  matched: MatchedPair[];
  exceptionsA: Transaction[];
  exceptionsB: Transaction[];
  matchedCount: number;
  exceptionCount: number;
}

export function runReconciliation(request: ReconciliationRequest) {
  return coreApi.post<ReconciliationResult>("/reconciliation/run", request).then((r) => r.data);
}
