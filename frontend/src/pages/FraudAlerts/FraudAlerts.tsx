import { useCallback, useEffect, useState } from "react";

import { errorMessage } from "../../api/client";
import {
  decideFraudAlert,
  runFraudScan,
  searchFraudAlerts,
} from "../../api/fraud";
import type {
  AlertSeverity,
  AlertStatus,
  FraudAlert,
  FraudAlertSearchParams,
  FraudRule,
  FraudScanResult,
} from "../../api/fraud";
import Badge from "../../components/ui/Badge";
import type { BadgeTone } from "../../components/ui/Badge";
import EmptyRow from "../../components/ui/EmptyRow";
import Field from "../../components/ui/Field";
import Pagination from "../../components/ui/Pagination";
import StatTile from "../../components/ui/StatTile";
import { formatDateTime } from "../../utils/format";

const STATUSES: AlertStatus[] = ["OPEN", "CONFIRMED", "DISMISSED"];
const SEVERITIES: AlertSeverity[] = ["HIGH", "MEDIUM", "LOW"];
const RULES: FraudRule[] = ["DUPLICATE_PAYMENT", "AMOUNT_OUTLIER", "VELOCITY_SPIKE", "ROUND_AMOUNT"];

const severityTone: Record<AlertSeverity, BadgeTone> = {
  HIGH: "danger",
  MEDIUM: "warning",
  LOW: "neutral",
};

const statusTone: Record<AlertStatus, BadgeTone> = {
  OPEN: "warning",
  CONFIRMED: "danger",
  DISMISSED: "neutral",
};

/** FRD S5.4 review queue: run a scan, then triage what it found. */
export default function FraudAlerts() {
  const [filters, setFilters] = useState<FraudAlertSearchParams>({ status: "OPEN", page: 0, size: 20 });
  const [alerts, setAlerts] = useState<FraudAlert[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [scanResult, setScanResult] = useState<FraudScanResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [decidingId, setDecidingId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await searchFraudAlerts(filters);
      setAlerts(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (e) {
      setError(errorMessage(e, "Failed to load fraud alerts."));
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    load();
  }, [load]);

  function updateFilter<K extends keyof FraudAlertSearchParams>(key: K, value: FraudAlertSearchParams[K]) {
    setFilters((prev) => ({ ...prev, [key]: value, page: 0 }));
  }

  async function handleScan() {
    setScanning(true);
    setError(null);
    setScanResult(null);
    try {
      const result = await runFraudScan({ account: filters.account || undefined });
      setScanResult(result);
      await load();
    } catch (e) {
      setError(errorMessage(e, "Fraud scan failed."));
    } finally {
      setScanning(false);
    }
  }

  async function handleDecision(alert: FraudAlert, status: AlertStatus) {
    setDecidingId(alert.id);
    setError(null);
    try {
      const updated = await decideFraudAlert(alert.id, status);
      // Patch in place rather than reloading: the current filter may exclude the
      // new status, and yanking the row out from under the reviewer mid-triage is
      // worse than letting them see what they just decided.
      setAlerts((prev) => prev.map((a) => (a.id === updated.id ? updated : a)));
    } catch (e) {
      setError(errorMessage(e, "Could not record the decision."));
    } finally {
      setDecidingId(null);
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Anomaly Alerts</h1>
          <p className="text-sm text-slate-500">
            Rule-based and statistical findings over the ledger (FRD S5.4). Re-scanning is safe: existing
            findings are refreshed, and alerts you have already triaged are never reopened.
          </p>
        </div>
        <button
          type="button"
          onClick={handleScan}
          disabled={scanning}
          className="rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
        >
          {scanning ? "Scanning..." : "Run scan"}
        </button>
      </div>

      {scanResult && (
        <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
          <StatTile label="Transactions scanned" value={scanResult.transactionsScanned} />
          <StatTile label="New alerts" value={scanResult.newAlerts} />
          <StatTile label="Refreshed" value={scanResult.updatedAlerts} />
          <StatTile
            label="Suppressed"
            value={scanResult.suppressedAlerts}
            hint="Already triaged by an analyst"
          />
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-4 md:grid-cols-4">
        <Field label="Account">
          <input
            className="input"
            placeholder="All accounts"
            value={filters.account ?? ""}
            onChange={(e) => updateFilter("account", e.target.value || undefined)}
          />
        </Field>
        <Field label="Status">
          <select
            className="input"
            value={filters.status ?? ""}
            onChange={(e) => updateFilter("status", (e.target.value || undefined) as AlertStatus | undefined)}
          >
            <option value="">Any status</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Severity">
          <select
            className="input"
            value={filters.severity ?? ""}
            onChange={(e) =>
              updateFilter("severity", (e.target.value || undefined) as AlertSeverity | undefined)
            }
          >
            <option value="">Any severity</option>
            {SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Rule">
          <select
            className="input"
            value={filters.ruleId ?? ""}
            onChange={(e) => updateFilter("ruleId", (e.target.value || undefined) as FraudRule | undefined)}
          >
            <option value="">Any rule</option>
            {RULES.map((r) => (
              <option key={r} value={r}>
                {r.replace(/_/g, " ")}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Detected</th>
              <th className="px-4 py-2">Account</th>
              <th className="px-4 py-2">Rule</th>
              <th className="px-4 py-2">Severity</th>
              <th className="px-4 py-2 text-right">Score</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2">Reason</th>
              <th className="px-4 py-2 text-right">Decision</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <EmptyRow colSpan={8} message="Loading alerts..." />}
            {!loading && alerts.length === 0 && (
              <EmptyRow colSpan={8} message="No alerts match these filters. Run a scan to look for new ones." />
            )}
            {!loading &&
              alerts.map((alert) => (
                <tr key={alert.id}>
                  <td className="whitespace-nowrap px-4 py-2">{formatDateTime(alert.detectedAt)}</td>
                  <td className="px-4 py-2">{alert.account}</td>
                  <td className="whitespace-nowrap px-4 py-2">{alert.ruleId.replace(/_/g, " ")}</td>
                  <td className="px-4 py-2">
                    <Badge tone={severityTone[alert.severity]}>{alert.severity}</Badge>
                  </td>
                  <td className="px-4 py-2 text-right">{alert.score.toFixed(2)}</td>
                  <td className="px-4 py-2">
                    <Badge tone={statusTone[alert.status]}>{alert.status}</Badge>
                  </td>
                  <td className="max-w-md px-4 py-2 text-slate-600">{alert.reason}</td>
                  <td className="whitespace-nowrap px-4 py-2 text-right">
                    <div className="inline-flex gap-2">
                      <button
                        type="button"
                        disabled={decidingId === alert.id || alert.status === "CONFIRMED"}
                        onClick={() => handleDecision(alert, "CONFIRMED")}
                        className="rounded-md border border-red-200 px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-40"
                      >
                        Confirm
                      </button>
                      <button
                        type="button"
                        disabled={decidingId === alert.id || alert.status === "DISMISSED"}
                        onClick={() => handleDecision(alert, "DISMISSED")}
                        className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50 disabled:opacity-40"
                      >
                        Dismiss
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <Pagination
        page={filters.page ?? 0}
        totalPages={totalPages}
        disabled={loading}
        onChange={(page) => setFilters((prev) => ({ ...prev, page }))}
      />
      <p className="text-xs text-slate-400">{totalElements} alert(s) match the current filters.</p>
    </div>
  );
}
