import { useCallback, useEffect, useState } from "react";

import { errorMessage } from "../../api/client";
import { downloadReport, fetchReportHistory, generateReport } from "../../api/reports";
import type { ReportFormat, ReportRun, ReportStatus, ReportType } from "../../api/reports";
import Badge from "../../components/ui/Badge";
import type { BadgeTone } from "../../components/ui/Badge";
import EmptyRow from "../../components/ui/EmptyRow";
import Field from "../../components/ui/Field";
import Pagination from "../../components/ui/Pagination";
import { formatBytes, formatDateTime } from "../../utils/format";

const REPORT_TYPES: { value: ReportType; label: string }[] = [
  { value: "CASH_FLOW", label: "Cash flow statement" },
  { value: "RECONCILIATION_EXCEPTIONS", label: "Reconciliation exceptions" },
  { value: "FRAUD_ALERTS", label: "Anomaly alerts" },
];

const FORMATS: ReportFormat[] = ["PDF", "XLSX"];

const statusTone: Record<ReportStatus, BadgeTone> = {
  COMPLETED: "success",
  PENDING: "info",
  FAILED: "danger",
};

/** FRD S6.1: generate a report now, and browse/download previously generated ones. */
export default function Reports() {
  const [reportType, setReportType] = useState<ReportType>("CASH_FLOW");
  const [format, setFormat] = useState<ReportFormat>("PDF");
  const [account, setAccount] = useState("");
  const [periodFrom, setPeriodFrom] = useState("");
  const [periodTo, setPeriodTo] = useState("");

  const [page, setPage] = useState(0);
  const [filterType, setFilterType] = useState<ReportType | "">("");
  const [history, setHistory] = useState<ReportRun[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [downloadingId, setDownloadingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchReportHistory({
        reportType: filterType || undefined,
        page,
        size: 20,
      });
      setHistory(result.content);
      setTotalPages(result.totalPages);
    } catch (e) {
      setError(errorMessage(e, "Failed to load report history."));
    } finally {
      setLoading(false);
    }
  }, [filterType, page]);

  useEffect(() => {
    load();
  }, [load]);

  async function handleGenerate(e: React.FormEvent) {
    e.preventDefault();
    setGenerating(true);
    setError(null);
    setNotice(null);
    try {
      const run = await generateReport({
        reportType,
        format,
        account: account || undefined,
        periodFrom: periodFrom || undefined,
        periodTo: periodTo || undefined,
      });

      // Refresh history BEFORE reporting the outcome: load() clears the error and
      // notice state on entry, so setting the message first would have it silently
      // wiped out by the reload that follows.
      setPage(0);
      await load();

      // A FAILED run is a successful HTTP call carrying a recorded failure, not an
      // exception - surface it as an error rather than a success message.
      if (run.status === "FAILED") {
        setError(`Report generation failed: ${run.errorMessage ?? "unknown error"}`);
      } else {
        setNotice(`Generated ${run.fileName} (${formatBytes(run.sizeBytes)}).`);
      }
    } catch (e) {
      setError(errorMessage(e, "Report generation failed."));
    } finally {
      setGenerating(false);
    }
  }

  async function handleDownload(run: ReportRun) {
    setDownloadingId(run.id);
    setError(null);
    try {
      await downloadReport(run);
    } catch (e) {
      setError(errorMessage(e, "Download failed."));
    } finally {
      setDownloadingId(null);
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Reports</h1>
        <p className="text-sm text-slate-500">
          PDF and Excel reports (FRD S6.1). Leave the period empty to report on the previous whole calendar
          month - the same period the scheduled monthly job uses.
        </p>
      </div>

      <form
        onSubmit={handleGenerate}
        className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-4 md:grid-cols-6"
      >
        <Field label="Report">
          <select className="input" value={reportType} onChange={(e) => setReportType(e.target.value as ReportType)}>
            {REPORT_TYPES.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Format">
          <select className="input" value={format} onChange={(e) => setFormat(e.target.value as ReportFormat)}>
            {FORMATS.map((f) => (
              <option key={f} value={f}>
                {f}
              </option>
            ))}
          </select>
        </Field>
        <Field label="Account">
          <input
            className="input"
            placeholder="All accounts"
            value={account}
            onChange={(e) => setAccount(e.target.value)}
          />
        </Field>
        <Field label="Period from">
          <input className="input" type="date" value={periodFrom} onChange={(e) => setPeriodFrom(e.target.value)} />
        </Field>
        <Field label="Period to">
          <input className="input" type="date" value={periodTo} onChange={(e) => setPeriodTo(e.target.value)} />
        </Field>
        <div className="flex items-end">
          <button
            type="submit"
            disabled={generating}
            className="w-full rounded-md bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
          >
            {generating ? "Generating..." : "Generate"}
          </button>
        </div>
      </form>

      {notice && (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      <div className="flex items-end justify-between gap-4">
        <h2 className="text-lg font-medium text-slate-900">History</h2>
        <div className="w-64">
          <Field label="Filter by report">
            <select
              className="input"
              value={filterType}
              onChange={(e) => {
                setFilterType(e.target.value as ReportType | "");
                setPage(0);
              }}
            >
              <option value="">All reports</option>
              {REPORT_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
          </Field>
        </div>
      </div>

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Generated</th>
              <th className="px-4 py-2">Report</th>
              <th className="px-4 py-2">Format</th>
              <th className="px-4 py-2">Account</th>
              <th className="px-4 py-2">Period</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2">Requested by</th>
              <th className="px-4 py-2 text-right">Size</th>
              <th className="px-4 py-2 text-right">Document</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <EmptyRow colSpan={9} message="Loading report history..." />}
            {!loading && history.length === 0 && (
              <EmptyRow colSpan={9} message="No reports generated yet." />
            )}
            {!loading &&
              history.map((run) => (
                <tr key={run.id}>
                  <td className="whitespace-nowrap px-4 py-2">{formatDateTime(run.createdAt)}</td>
                  <td className="whitespace-nowrap px-4 py-2">{run.reportType.replace(/_/g, " ")}</td>
                  <td className="px-4 py-2">{run.format}</td>
                  <td className="px-4 py-2">{run.account ?? "all accounts"}</td>
                  <td className="whitespace-nowrap px-4 py-2">
                    {run.periodFrom} to {run.periodTo}
                  </td>
                  <td className="px-4 py-2">
                    <Badge tone={statusTone[run.status]}>{run.status}</Badge>
                    {run.status === "FAILED" && run.errorMessage && (
                      <p className="mt-1 max-w-xs text-xs text-red-600">{run.errorMessage}</p>
                    )}
                  </td>
                  <td className="px-4 py-2">{run.requestedBy}</td>
                  <td className="px-4 py-2 text-right">{formatBytes(run.sizeBytes)}</td>
                  <td className="px-4 py-2 text-right">
                    <button
                      type="button"
                      disabled={run.status !== "COMPLETED" || downloadingId === run.id}
                      onClick={() => handleDownload(run)}
                      className="rounded-md border border-slate-300 px-2 py-1 text-xs hover:bg-slate-50 disabled:opacity-40"
                    >
                      {downloadingId === run.id ? "Downloading..." : "Download"}
                    </button>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <Pagination page={page} totalPages={totalPages} disabled={loading} onChange={setPage} />
    </div>
  );
}
