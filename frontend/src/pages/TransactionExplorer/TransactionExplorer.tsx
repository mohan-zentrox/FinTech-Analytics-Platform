import { type ChangeEvent, useCallback, useEffect, useMemo, useState } from "react";

import { errorMessage } from "../../api/client";
import { importTransactionsCsv, searchTransactions } from "../../api/transactions";
import type { CsvImportResult, Transaction, TransactionSearchParams, TransactionStatus } from "../../api/transactions";
import EmptyRow from "../../components/ui/EmptyRow";
import Pagination from "../../components/ui/Pagination";
import { formatCurrency, formatDate } from "../../utils/format";
import { useDebouncedValue } from "../../utils/useDebouncedValue";

const STATUSES: TransactionStatus[] = ["PENDING", "POSTED", "RECONCILED", "FLAGGED", "VOID"];
const PAGE_SIZE = 20;

export default function TransactionExplorer() {
  // Text/number inputs are held separately from the committed query so they can be
  // debounced: the previous version put them straight into the effect's dependency,
  // firing one API request per keystroke.
  const [account, setAccount] = useState("");
  const [dateFrom, setDateFrom] = useState("");
  const [dateTo, setDateTo] = useState("");
  const [minAmount, setMinAmount] = useState("");
  const [maxAmount, setMaxAmount] = useState("");
  const [status, setStatus] = useState<TransactionStatus | "">("");
  const [page, setPage] = useState(0);

  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [importResult, setImportResult] = useState<CsvImportResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const debouncedAccount = useDebouncedValue(account, 350);
  const debouncedMinAmount = useDebouncedValue(minAmount, 350);
  const debouncedMaxAmount = useDebouncedValue(maxAmount, 350);

  const query = useMemo<TransactionSearchParams>(
    () => ({
      account: debouncedAccount || undefined,
      dateFrom: dateFrom || undefined,
      dateTo: dateTo || undefined,
      minAmount: debouncedMinAmount ? Number(debouncedMinAmount) : undefined,
      maxAmount: debouncedMaxAmount ? Number(debouncedMaxAmount) : undefined,
      status: status || undefined,
      page,
      size: PAGE_SIZE,
    }),
    [debouncedAccount, dateFrom, dateTo, debouncedMinAmount, debouncedMaxAmount, status, page]
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await searchTransactions(query);
      setTransactions(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (e) {
      setError(errorMessage(e, "Failed to load transactions."));
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    load();
  }, [load]);

  // Narrowing the filters must return to page 1; otherwise the user can be left
  // sitting on a page that no longer exists, looking at an empty table.
  useEffect(() => {
    setPage(0);
  }, [debouncedAccount, dateFrom, dateTo, debouncedMinAmount, debouncedMaxAmount, status]);

  async function handleFileChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    try {
      const result = await importTransactionsCsv(file);
      setImportResult(result);
      await load();
    } catch (err) {
      setError(errorMessage(err, "CSV import failed."));
    } finally {
      // Reset the input so selecting the same file again still fires a change event.
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
          aria-label="Account"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={account}
          onChange={(e) => setAccount(e.target.value)}
        />
        <input
          type="date"
          aria-label="Date from"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={dateFrom}
          onChange={(e) => setDateFrom(e.target.value)}
        />
        <input
          type="date"
          aria-label="Date to"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={dateTo}
          onChange={(e) => setDateTo(e.target.value)}
        />
        <input
          type="number"
          placeholder="Min amount"
          aria-label="Min amount"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={minAmount}
          onChange={(e) => setMinAmount(e.target.value)}
        />
        <input
          type="number"
          placeholder="Max amount"
          aria-label="Max amount"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={maxAmount}
          onChange={(e) => setMaxAmount(e.target.value)}
        />
        <select
          aria-label="Status"
          className="rounded-md border border-slate-300 px-2 py-1 text-sm"
          value={status}
          onChange={(e) => setStatus((e.target.value || "") as TransactionStatus | "")}
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

      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Date</th>
              <th className="px-4 py-2">Account</th>
              <th className="px-4 py-2">Source</th>
              <th className="px-4 py-2">Description</th>
              <th className="px-4 py-2">Category</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2 text-right">Amount</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <EmptyRow colSpan={7} message="Loading..." />}
            {!loading && transactions.length === 0 && (
              <EmptyRow colSpan={7} message="No transactions found." />
            )}
            {!loading &&
              transactions.map((tx) => (
                <tr key={tx.id}>
                  <td className="whitespace-nowrap px-4 py-2">{formatDate(tx.postedDate)}</td>
                  <td className="px-4 py-2">{tx.account}</td>
                  <td className="px-4 py-2 text-slate-500">{tx.source}</td>
                  <td className="px-4 py-2">{tx.description}</td>
                  <td className="px-4 py-2">{tx.category}</td>
                  <td className="px-4 py-2">{tx.status}</td>
                  <td className="px-4 py-2 text-right">{formatCurrency(tx.amount, tx.currency)}</td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <Pagination page={page} totalPages={totalPages} disabled={loading} onChange={setPage} />
      <p className="text-xs text-slate-400">{totalElements} transaction(s) matched.</p>
    </div>
  );
}
