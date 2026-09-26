import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { downloadReport, fetchReportHistory, generateReport } from "../../api/reports";
import type { ReportRun } from "../../api/reports";
import Reports from "./Reports";

vi.mock("../../api/reports", () => ({
  fetchReportHistory: vi.fn(),
  generateReport: vi.fn(),
  downloadReport: vi.fn(),
}));

const mockedHistory = vi.mocked(fetchReportHistory);
const mockedGenerate = vi.mocked(generateReport);
const mockedDownload = vi.mocked(downloadReport);

function run(overrides: Partial<ReportRun> = {}): ReportRun {
  return {
    id: "run-1",
    reportType: "CASH_FLOW",
    format: "PDF",
    account: "ACC-1",
    periodFrom: "2026-05-01",
    periodTo: "2026-05-31",
    status: "COMPLETED",
    requestedBy: "t2-lead",
    fileName: "cash-flow_ACC-1_2026-05-01_2026-05-31.pdf",
    contentType: "application/pdf",
    sizeBytes: 20480,
    errorMessage: null,
    createdAt: "2026-06-01T09:00:00Z",
    completedAt: "2026-06-01T09:00:01Z",
    ...overrides,
  };
}

function page(content: ReportRun[]) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    last: true,
  };
}

describe("Reports page", () => {
  beforeEach(() => {
    mockedHistory.mockReset();
    mockedGenerate.mockReset();
    mockedDownload.mockReset();
    mockedHistory.mockResolvedValue(page([run()]));
  });

  it("lists report history with size and status", async () => {
    render(<Reports />);

    expect(await screen.findByText("CASH FLOW")).toBeInTheDocument();
    expect(screen.getByText("COMPLETED")).toBeInTheDocument();
    expect(screen.getByText("20.0 KB")).toBeInTheDocument();
  });

  it("generates a report with the chosen type, format and period", async () => {
    mockedGenerate.mockResolvedValue(run({ format: "XLSX", fileName: "cash-flow.xlsx" }));

    render(<Reports />);
    await screen.findByText("CASH FLOW");

    fireEvent.change(screen.getByLabelText(/^report$/i), { target: { value: "FRAUD_ALERTS" } });
    fireEvent.change(screen.getByLabelText(/format/i), { target: { value: "XLSX" } });
    fireEvent.change(screen.getByLabelText(/account/i), { target: { value: "ACC-9" } });
    fireEvent.change(screen.getByLabelText(/period from/i), { target: { value: "2026-04-01" } });
    fireEvent.change(screen.getByLabelText(/period to/i), { target: { value: "2026-04-30" } });
    fireEvent.click(screen.getByRole("button", { name: /^generate$/i }));

    await waitFor(() =>
      expect(mockedGenerate).toHaveBeenCalledWith({
        reportType: "FRAUD_ALERTS",
        format: "XLSX",
        account: "ACC-9",
        periodFrom: "2026-04-01",
        periodTo: "2026-04-30",
      })
    );
    expect(await screen.findByText(/generated cash-flow\.xlsx/i)).toBeInTheDocument();
  });

  it("omits an empty period so the backend applies its previous-month default", async () => {
    mockedGenerate.mockResolvedValue(run());

    render(<Reports />);
    await screen.findByText("CASH FLOW");
    fireEvent.click(screen.getByRole("button", { name: /^generate$/i }));

    await waitFor(() =>
      expect(mockedGenerate).toHaveBeenCalledWith({
        reportType: "CASH_FLOW",
        format: "PDF",
        account: undefined,
        periodFrom: undefined,
        periodTo: undefined,
      })
    );
  });

  it("treats a FAILED run as an error even though the request succeeded", async () => {
    mockedGenerate.mockResolvedValue(
      run({ status: "FAILED", errorMessage: "font metrics unavailable", fileName: null, sizeBytes: null })
    );

    render(<Reports />);
    await screen.findByText("CASH FLOW");
    fireEvent.click(screen.getByRole("button", { name: /^generate$/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/font metrics unavailable/i);
  });

  it("downloads a completed report through the authenticated client", async () => {
    render(<Reports />);
    const row = (await screen.findByText("CASH FLOW")).closest("tr")!;

    fireEvent.click(within(row).getByRole("button", { name: /download/i }));

    await waitFor(() => expect(mockedDownload).toHaveBeenCalledWith(expect.objectContaining({ id: "run-1" })));
  });

  it("does not offer a download for a failed report", async () => {
    mockedHistory.mockResolvedValue(page([run({ status: "FAILED", errorMessage: "boom" })]));

    render(<Reports />);
    const row = (await screen.findByText("CASH FLOW")).closest("tr")!;

    expect(within(row).getByRole("button", { name: /download/i })).toBeDisabled();
    expect(screen.getByText("boom")).toBeInTheDocument();
  });

  it("filters history by report type", async () => {
    render(<Reports />);
    await screen.findByText("CASH FLOW");

    fireEvent.change(screen.getByLabelText(/filter by report/i), {
      target: { value: "RECONCILIATION_EXCEPTIONS" },
    });

    await waitFor(() =>
      expect(mockedHistory).toHaveBeenLastCalledWith(
        expect.objectContaining({ reportType: "RECONCILIATION_EXCEPTIONS", page: 0 })
      )
    );
  });

  it("shows an empty state when nothing has been generated", async () => {
    mockedHistory.mockResolvedValue(page([]));

    render(<Reports />);

    expect(await screen.findByText(/no reports generated yet/i)).toBeInTheDocument();
  });
});
