import { type FormEvent, type ReactNode, useState } from "react";

import { runReconciliation } from "../../api/reconciliation";
import type { ReconciliationRequest, ReconciliationResult } from "../../api/reconciliation";
import { formatCurrency, formatDate } from "../../utils/format";

const initialForm: ReconciliationRequest = {
  accountA: "",
  sourceA: "manual",
  accountB: "",
  sourceB: "csv-import",
  dateFrom: "",
  dateTo: "",
  amountTolerance: 0.01,
  dateToleranceDays: 2,
};

export default function Reconciliation() {
  const [form, setForm] = useState<ReconciliationRequest>(initialForm);
  const [result, setResult] = useState<ReconciliationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function update<K extends keyof ReconciliationRequest>(key: K, value: ReconciliationRequest[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      const data = await runReconciliation(form);
      setResult(data);
    } catch {
      setError("Reconciliation run failed. Check the request parameters.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold text-slate-900">Reconciliation</h1>

      <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-4 md:grid-cols-4">
        <Field label="Account A">
          <input required value={form.accountA} onChange={(e) => update("accountA", e.target.value)} className="input" />
        </Field>
        <Field label="Source A">
          <input required value={form.sourceA} onChange={(e) => update("sourceA", e.target.value)} className="input" />
        </Field>
        <Field label="Account B">
          <input required value={form.accountB} onChange={(e) => update("accountB", e.target.value)} className="input" />
        </Field>
        <Field label="Source B">
          <input required value={form.sourceB} onChange={(e) => update("sourceB", e.target.value)} className="input" />
        </Field>
        <Field label="Date from">
          <input required type="date" value={form.dateFrom} onChange={(e) => update("dateFrom", e.target.value)} className="input" />
        </Field>
        <Field label="Date to">
          <input required type="date" value={form.dateTo} onChange={(e) => update("dateTo", e.target.value)} className="input" />
        </Field>
        <Field label="Amount tolerance">
          <input
            type="number"
            step="0.01"
            value={form.amountTolerance}
            onChange={(e) => update("amountTolerance", Number(e.target.value))}
            className="input"
          />
        </Field>
        <Field label="Date tolerance (days)">
          <input
            type="number"
            value={form.dateToleranceDays}
            onChange={(e) => update("dateToleranceDays", Number(e.target.value))}
            className="input"
          />
        </Field>
        <div className="col-span-2 md:col-span-4">
          <button
            type="submit"
            disabled={loading}
            className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
          >
            {loading ? "Running..." : "Run reconciliation"}
          </button>
        </div>
      </form>

      {error && <p className="text-sm text-red-600">{error}</p>}

      {result && (
        <div className="space-y-6">
          <div className="grid grid-cols-2 gap-4">
            <SummaryCard label="Matched" value={result.matchedCount} tone="positive" />
            <SummaryCard label="Exceptions" value={result.exceptionCount} tone="warning" />
          </div>

          <section>
            <h2 className="mb-2 text-lg font-medium text-slate-900">Matched pairs</h2>
            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
              <table className="min-w-full divide-y divide-slate-200 text-sm">
                <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
                  <tr>
                    <th className="px-4 py-2">A</th>
                    <th className="px-4 py-2">B</th>
                    <th className="px-4 py-2 text-right">Amount delta</th>
                    <th className="px-4 py-2 text-right">Date delta (days)</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {result.matched.map((pair, i) => (
                    <tr key={i}>
                      <td className="px-4 py-2">
                        {formatDate(pair.transactionA.postedDate)} - {formatCurrency(pair.transactionA.amount)}
                      </td>
                      <td className="px-4 py-2">
                        {formatDate(pair.transactionB.postedDate)} - {formatCurrency(pair.transactionB.amount)}
                      </td>
                      <td className="px-4 py-2 text-right">{formatCurrency(pair.amountDelta)}</td>
                      <td className="px-4 py-2 text-right">{pair.dateDeltaDays}</td>
                    </tr>
                  ))}
                  {result.matched.length === 0 && (
                    <tr>
                      <td colSpan={4} className="px-4 py-4 text-center text-slate-500">
                        No matches.
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </section>

          <section>
            <h2 className="mb-2 text-lg font-medium text-slate-900">Exceptions</h2>
            <ExceptionsTable title="Set A" transactions={result.exceptionsA} />
            <div className="h-4" />
            <ExceptionsTable title="Set B" transactions={result.exceptionsB} />
          </section>
        </div>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="block text-sm">
      <span className="mb-1 block font-medium text-slate-700">{label}</span>
      {children}
    </label>
  );
}

function SummaryCard({ label, value, tone }: { label: string; value: number; tone: "positive" | "warning" }) {
  const toneClass = tone === "positive" ? "text-emerald-600" : "text-amber-600";
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-sm text-slate-500">{label}</p>
      <p className={`text-2xl font-semibold ${toneClass}`}>{value}</p>
    </div>
  );
}

function ExceptionsTable({ title, transactions }: { title: string; transactions: ReconciliationResult["exceptionsA"] }) {
  return (
    <div>
      <h3 className="mb-1 text-sm font-medium text-slate-700">{title}</h3>
      <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <tbody className="divide-y divide-slate-100">
            {transactions.length === 0 && (
              <tr>
                <td className="px-4 py-3 text-center text-slate-500">No exceptions.</td>
              </tr>
            )}
            {transactions.map((tx) => (
              <tr key={tx.id}>
                <td className="px-4 py-2">{formatDate(tx.postedDate)}</td>
                <td className="px-4 py-2">{tx.description}</td>
                <td className="px-4 py-2 text-right">{formatCurrency(tx.amount, tx.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
