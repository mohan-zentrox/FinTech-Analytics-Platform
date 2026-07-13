import { type ChangeEvent, useEffect, useState } from "react";

import { importTransactionsCsv, searchTransactions } from "../../api/transactions";
import type { CsvImportResult, Transaction, TransactionSearchParams, TransactionStatus } from "../../api/transactions";
import { formatCurrency, formatDate } from "../../utils/format";

const STATUSES: TransactionStatus[] = ["PENDING", "POSTED", "RECONCILED", "FLAGGED", "VOID"];

export default function TransactionExplorer() {
  const [filters, setFilters] = useState<TransactionSearchParams>({ page: 0, size: 20 });
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [importResult, setImportResult] = useState<CsvImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setError(null);
    try {
      const result = await searchTransactions(filters);
      setTransactions(result.content);
      setTotalPages(result.totalPages);
    } catch {
      setError("Failed to load transactions.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filters]);

  function updateFilter<K extends keyof TransactionSearchParams>(key: K, value: TransactionSearchParams[K]) {
    setFilters((prev) => ({ ...prev, [key]: value, page: 0 }));
  }

  async function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const result = await importTransactionsCsv(file);
      setImportResult(result);
      load();
    } catch {
      setError("CSV import failed.");
    } finally {
      e.target.value = "";
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Transaction Explorer</h1>
        <label className="cursor-pointer rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700">
          Import CSV
          <input type="file" accept=".csv" className="hidden" onChange={handleFileChange} />
        </label>
      </div>

      <div className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-4 md:grid-cols-6">
        <input
          placeholder="Account"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("account", e.target.value || undefined)}
        />
        <input
          type="date"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("dateFrom", e.target.value || undefined)}
        />
        <input
          type="date"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("dateTo", e.target.value || undefined)}
        />
        <input
          type="number"
          placeholder="Min amount"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("minAmount", e.target.value ? Number(e.target.value) : undefined)}
        />
        <input
          type="number"
          placeholder="Max amount"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("maxAmount", e.target.value ? Number(e.target.value) : undefined)}
        />
        <select
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          onChange={(e) => updateFilter("status", (e.target.value || undefined) as TransactionStatus | undefined)}
        >
          <option value="">Any status</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </div>

      {importResult && (
        <div className="rounded-md border border-brand-200 bg-brand-50 p-3 text-sm text-brand-900">
          Imported {importResult.imported}, skipped {importResult.skipped}, errored {importResult.errored}.
          {importResult.errors.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {importResult.errors.slice(0, 5).map((e, i) => (
                <li key={i}>
                  Row {e.row}: {e.message}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {error && <p className="text-sm text-red-600">{error}</p>}

      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Date</th>
              <th className="px-4 py-2">Account</th>
              <th className="px-4 py-2">Description</th>
              <th className="px-4 py-2">Category</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2 text-right">Amount</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                  Loading...
                </td>
              </tr>
            )}
            {!loading && transactions.length === 0 && (
              <tr>
                <td colSpan={6} className="px-4 py-6 text-center text-slate-500">
                  No transactions found.
                </td>
              </tr>
            )}
            {transactions.map((tx) => (
              <tr key={tx.id}>
                <td className="px-4 py-2">{formatDate(tx.postedDate)}</td>
                <td className="px-4 py-2">{tx.account}</td>
                <td className="px-4 py-2">{tx.description}</td>
                <td className="px-4 py-2">{tx.category}</td>
                <td className="px-4 py-2">{tx.status}</td>
                <td className="px-4 py-2 text-right">{formatCurrency(tx.amount, tx.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex items-center justify-between text-sm text-slate-600">
        <span>
          Page {(filters.page ?? 0) + 1} of {Math.max(totalPages, 1)}
        </span>
        <div className="flex gap-2">
          <button
            disabled={(filters.page ?? 0) === 0}
            onClick={() => setFilters((prev) => ({ ...prev, page: (prev.page ?? 0) - 1 }))}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Previous
          </button>
          <button
            disabled={(filters.page ?? 0) + 1 >= totalPages}
            onClick={() => setFilters((prev) => ({ ...prev, page: (prev.page ?? 0) + 1 }))}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
