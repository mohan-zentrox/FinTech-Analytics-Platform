import { coreApi } from "./client";

export type TransactionStatus = "PENDING" | "POSTED" | "RECONCILED" | "FLAGGED" | "VOID";

export interface Transaction {
  id: string;
  source: string;
  account: string;
  amount: number;
  currency: string;
  postedDate: string;
  description: string | null;
  category: string | null;
  status: TransactionStatus;
  externalId: string;
  createdAt: string;
  updatedAt: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface TransactionSearchParams {
  account?: string;
  dateFrom?: string;
  dateTo?: string;
  minAmount?: number;
  maxAmount?: number;
  status?: TransactionStatus;
  page?: number;
  size?: number;
}

export function searchTransactions(params: TransactionSearchParams) {
  return coreApi.get<PageResponse<Transaction>>("/transactions", { params }).then((r) => r.data);
}

export function getTransaction(id: string) {
  return coreApi.get<Transaction>(`/transactions/${id}`).then((r) => r.data);
}

export interface CsvImportResult {
  imported: number;
  skipped: number;
  errored: number;
  errors: { row: number; message: string }[];
}

export function importTransactionsCsv(file: File) {
  const form = new FormData();
  form.append("file", file);
  return coreApi
    .post<CsvImportResult>("/transactions/import", form, {
      headers: { "Content-Type": "multipart/form-data" },
    })
    .then((r) => r.data);
}

export function deleteTransaction(id: string) {
  return coreApi.delete(`/transactions/${id}`);
}
