import { useEffect, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";

import { fetchCashFlow, fetchKpis } from "../../api/analytics";
import type { CashFlowPoint, KpiResponse } from "../../api/analytics";
import { formatCurrency } from "../../utils/format";

export default function Dashboard() {
  const [accountId, setAccountId] = useState("ACC-1");
  const [cashFlow, setCashFlow] = useState<CashFlowPoint[]>([]);
  const [kpis, setKpis] = useState<KpiResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load(id: string) {
    setLoading(true);
    setError(null);
    try {
      const [cashFlowResponse, kpiResponse] = await Promise.all([fetchCashFlow(id, 6), fetchKpis(id)]);
      setCashFlow(cashFlowResponse.series);
      setKpis(kpiResponse);
    } catch {
      setError("Failed to load analytics. Is the analytics-service running?");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(accountId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const agingData = kpis
    ? Object.keys(kpis.ar_aging).map((bucket) => ({
        bucket,
        AR: kpis.ar_aging[bucket],
        AP: kpis.ap_aging[bucket],
      }))
    : [];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold text-slate-900">Dashboard</h1>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            load(accountId);
          }}
          className="flex gap-2"
        >
          <input
            value={accountId}
            onChange={(e) => setAccountId(e.target.value)}
            className="rounded-md border border-slate-300 px-3 py-1 text-sm"
            placeholder="Account ID"
          />
          <button type="submit" className="rounded-md bg-brand-600 px-3 py-1 text-sm font-medium text-white hover:bg-brand-700">
            Load
          </button>
        </form>
      </div>

      {loading && <p className="text-sm text-slate-500">Loading analytics...</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      {kpis && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <StatTile label="Burn rate (trailing 3 mo.)" value={formatCurrency(kpis.burn_rate)} />
          <StatTile
            label="Open receivables (AR)"
            value={formatCurrency(Object.values(kpis.ar_aging).reduce((a, b) => a + b, 0))}
          />
          <StatTile
            label="Open payables (AP)"
            value={formatCurrency(Object.values(kpis.ap_aging).reduce((a, b) => a + b, 0))}
          />
        </div>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="mb-4 text-lg font-medium text-slate-900">Cash flow (trailing 6 months)</h2>
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={cashFlow}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="month" />
              <YAxis />
              <Tooltip formatter={(value: number) => formatCurrency(value)} />
              <Legend />
              <Line type="monotone" dataKey="inflow" stroke="#2563eb" name="Inflow" />
              <Line type="monotone" dataKey="outflow" stroke="#dc2626" name="Outflow" />
              <Line type="monotone" dataKey="net" stroke="#059669" name="Net" />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </section>

      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="mb-4 text-lg font-medium text-slate-900">AR / AP aging</h2>
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={agingData}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="bucket" />
              <YAxis />
              <Tooltip formatter={(value: number) => formatCurrency(value)} />
              <Legend />
              <Bar dataKey="AR" fill="#2563eb" />
              <Bar dataKey="AP" fill="#dc2626" />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </section>
    </div>
  );
}

function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-sm text-slate-500">{label}</p>
      <p className="text-2xl font-semibold text-slate-900">{value}</p>
    </div>
  );
}
