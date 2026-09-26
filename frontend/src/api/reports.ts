import { coreApi } from "./client";
import type { PageResponse } from "./transactions";

export type ReportType = "CASH_FLOW" | "RECONCILIATION_EXCEPTIONS" | "FRAUD_ALERTS";
export type ReportFormat = "PDF" | "XLSX";
export type ReportStatus = "PENDING" | "COMPLETED" | "FAILED";

export interface ReportRun {
  id: string;
  reportType: ReportType;
  format: ReportFormat;
  account: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  status: ReportStatus;
  requestedBy: string;
  fileName: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface ReportGenerateRequest {
  reportType: ReportType;
  format?: ReportFormat;
  account?: string;
  periodFrom?: string;
  periodTo?: string;
}

export function generateReport(request: ReportGenerateRequest) {
  return coreApi.post<ReportRun>("/reports/generate", request).then((r) => r.data);
}

export function fetchReportHistory(params: { reportType?: ReportType; page?: number; size?: number }) {
  return coreApi.get<PageResponse<ReportRun>>("/reports", { params }).then((r) => r.data);
}

/**
 * Downloads a generated document and hands it to the browser.
 *
 * Fetched through the Axios client rather than pointed at with a plain link
 * because the endpoint requires the Authorization header - a bare <a href> would
 * arrive unauthenticated and 401.
 */
export async function downloadReport(run: ReportRun): Promise<void> {
  const response = await coreApi.get<Blob>(`/reports/${run.id}/download`, { responseType: "blob" });

  const url = window.URL.createObjectURL(response.data);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = run.fileName ?? `report-${run.id}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    // Release the object URL so the blob can be garbage collected.
    window.URL.revokeObjectURL(url);
  }
}
