import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
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

import { fetchAnomalies, fetchCashFlow, fetchKpis } from "../../api/analytics";
import type { AnomalyResponse, CashFlowPoint, KpiResponse } from "../../api/analytics";
import { errorMessage } from "../../api/client";
import { fetchFraudSummary } from "../../api/fraud";
import Badge from "../../components/ui/Badge";
import StatTile from "../../components/ui/StatTile";
import { formatCurrency } from "../../utils/format";

const CASH_FLOW_MONTHS = 6;

export default function Dashboard() {
  const [accountId, setAccountId] = useState("ACC-1");
  const [cashFlow, setCashFlow] = useState<CashFlowPoint[]>([]);
  const [kpis, setKpis] = useState<KpiResponse | null>(null);
  const [anomalies, setAnomalies] = useState<AnomalyResponse | null>(null);
  const [openAlerts, setOpenAlerts] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async (id: string) => {
    setLoading(true);
    setError(null);
    try {
      // Settled, not all: the anomaly and alert tiles are secondary, and a failure
      // there must not blank out the cash-flow chart the page exists for.
      const [cashFlowResult, kpiResult, anomalyResult, summaryResult] = await Promise.allSettled([
        fetchCashFlow(id, CASH_FLOW_MONTHS),
        fetchKpis(id),
        fetchAnomalies(id, 5),
        fetchFraudSummary(id),
      ]);

      if (cashFlowResult.status === "fulfilled") {
        setCashFlow(cashFlowResult.value.series);
      }
      if (kpiResult.status === "fulfilled") {
        setKpis(kpiResult.value);
      }
      setAnomalies(anomalyResult.status === "fulfilled" ? anomalyResult.value : null);
      setOpenAlerts(summaryResult.status === "fulfilled" ? summaryResult.value.openAlerts : null);

      if (cashFlowResult.status === "rejected" || kpiResult.status === "rejected") {
        setError(
          errorMessage(
            cashFlowResult.status === "rejected" ? cashFlowResult.reason : (kpiResult as PromiseRejectedResult).reason,
            "Failed to load analytics. Is the analytics-service running?"
          )
        );
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load(accountId);
    // Intentionally runs once: subsequent loads are driven by the Load button so a
    // half-typed account id does not trigger a request.
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
            aria-label="Account ID"
            className="rounded-md border border-slate-300 px-3 py-1 text-sm"
            placeholder="Account ID"
          />
          <button
            type="submit"
            className="rounded-md bg-brand-600 px-3 py-1 text-sm font-medium text-white hover:bg-brand-700"
          >
            Load
          </button>
        </form>
      </div>

      {loading && <p className="text-sm text-slate-500">Loading analytics...</p>}
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      {kpis && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-4">
          <StatTile label="Burn rate (trailing 3 mo.)" value={formatCurrency(kpis.burn_rate)} />
          <StatTile
            label="Open receivables (AR)"
            value={formatCurrency(Object.values(kpis.ar_aging).reduce((a, b) => a + b, 0))}
          />
          <StatTile
            label="Open payables (AP)"
            value={formatCurrency(Object.values(kpis.ap_aging).reduce((a, b) => a + b, 0))}
          />
          <StatTile
            label="Open anomaly alerts"
            value={openAlerts ?? "-"}
            hint={openAlerts === null ? "Alert count unavailable" : "Awaiting analyst triage"}
          />
        </div>
      )}

      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="mb-4 text-lg font-medium text-slate-900">
          Cash flow (trailing {CASH_FLOW_MONTHS} months)
        </h2>
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

      <section className="rounded-lg border border-slate-200 bg-white p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-lg font-medium text-slate-900">Top statistical anomalies</h2>
          <Link to="/fraud" className="text-sm font-medium text-brand-700 hover:underline">
            Review all alerts
          </Link>
        </div>
        {!anomalies && <p className="text-sm text-slate-500">Anomaly scoring unavailable.</p>}
        {anomalies && anomalies.findings.length === 0 && (
          <p className="text-sm text-slate-500">No anomalies detected for this account.</p>
        )}
        {anomalies && anomalies.findings.length > 0 && (
          <ul className="divide-y divide-slate-100 text-sm">
            {anomalies.findings.map((finding) => (
              <li key={`${finding.transactionId}-${finding.ruleId}`} className="flex gap-3 py-2">
                <Badge tone={finding.severity === "HIGH" ? "danger" : finding.severity === "MEDIUM" ? "warning" : "neutral"}>
                  {finding.severity}
                </Badge>
                <span className="text-slate-600">{finding.reason}</span>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
