import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { decideFraudAlert, runFraudScan, searchFraudAlerts } from "../../api/fraud";
import type { FraudAlert as FraudAlertModel } from "../../api/fraud";
import FraudAlerts from "./FraudAlerts";

vi.mock("../../api/fraud", () => ({
  searchFraudAlerts: vi.fn(),
  runFraudScan: vi.fn(),
  decideFraudAlert: vi.fn(),
}));

const mockedSearch = vi.mocked(searchFraudAlerts);
const mockedScan = vi.mocked(runFraudScan);
const mockedDecide = vi.mocked(decideFraudAlert);

function alert(overrides: Partial<FraudAlertModel> = {}): FraudAlertModel {
  return {
    id: "alert-1",
    scanId: "scan-1",
    transactionId: "tx-1",
    account: "ACC-1",
    ruleId: "DUPLICATE_PAYMENT",
    severity: "HIGH",
    score: 1,
    reason: "Possible duplicate payment: same account, amount -2500.00",
    status: "OPEN",
    detectedAt: "2026-06-02T10:00:00Z",
    resolvedAt: null,
    resolvedBy: null,
    resolutionNote: null,
    ...overrides,
  };
}

function page(content: FraudAlertModel[]) {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    last: true,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <FraudAlerts />
    </MemoryRouter>
  );
}

describe("FraudAlerts page", () => {
  beforeEach(() => {
    mockedSearch.mockReset();
    mockedScan.mockReset();
    mockedDecide.mockReset();
    mockedSearch.mockResolvedValue(page([alert()]));
  });

  it("loads open alerts by default and renders their detail", async () => {
    renderPage();

    // Scoped to the row: the filter dropdowns carry the same rule/status labels.
    const row = (await screen.findByText(/Possible duplicate payment/)).closest("tr")!;
    expect(within(row).getByText("ACC-1")).toBeInTheDocument();
    expect(within(row).getByText("DUPLICATE PAYMENT")).toBeInTheDocument();
    expect(within(row).getByText("HIGH")).toBeInTheDocument();
    expect(mockedSearch).toHaveBeenCalledWith(
      expect.objectContaining({ status: "OPEN", page: 0, size: 20 })
    );
  });

  it("filters by severity without losing the page size", async () => {
    renderPage();
    await screen.findByText(/Possible duplicate payment/);

    fireEvent.change(screen.getByLabelText(/severity/i), { target: { value: "MEDIUM" } });

    await waitFor(() =>
      expect(mockedSearch).toHaveBeenLastCalledWith(
        expect.objectContaining({ severity: "MEDIUM", page: 0, size: 20 })
      )
    );
  });

  it("runs a scan, shows what it changed, and reloads the queue", async () => {
    mockedScan.mockResolvedValue({
      scanId: "scan-2",
      account: null,
      dateFrom: "2026-03-01",
      dateTo: "2026-06-01",
      transactionsScanned: 128,
      findings: 4,
      newAlerts: 3,
      updatedAlerts: 1,
      suppressedAlerts: 2,
      findingsByRule: { DUPLICATE_PAYMENT: 2, AMOUNT_OUTLIER: 2 },
    });

    renderPage();
    await screen.findByText(/Possible duplicate payment/);
    const loadsBeforeScan = mockedSearch.mock.calls.length;

    fireEvent.click(screen.getByRole("button", { name: /run scan/i }));

    expect(await screen.findByText("128")).toBeInTheDocument();
    expect(screen.getByText("Transactions scanned")).toBeInTheDocument();
    // The suppressed count is the visible evidence that triage is not undone.
    expect(screen.getByText("Suppressed")).toBeInTheDocument();
    await waitFor(() => expect(mockedSearch.mock.calls.length).toBeGreaterThan(loadsBeforeScan));
  });

  it("records a CONFIRMED decision and reflects the new status in place", async () => {
    mockedDecide.mockResolvedValue(alert({ status: "CONFIRMED", resolvedBy: "t2-data1" }));

    renderPage();
    const row = (await screen.findByText(/Possible duplicate payment/)).closest("tr")!;

    fireEvent.click(within(row).getByRole("button", { name: /confirm/i }));

    await waitFor(() => expect(mockedDecide).toHaveBeenCalledWith("alert-1", "CONFIRMED"));
    await waitFor(() =>
      expect(within(row).getByText("CONFIRMED")).toBeInTheDocument()
    );
    // Patched in place, not refetched - the current filter would exclude it.
    expect(mockedSearch).toHaveBeenCalledTimes(1);
  });

  it("disables the decision a row is already in", async () => {
    mockedSearch.mockResolvedValue(page([alert({ status: "DISMISSED" })]));

    renderPage();
    const row = (await screen.findByText(/Possible duplicate payment/)).closest("tr")!;

    expect(within(row).getByRole("button", { name: /dismiss/i })).toBeDisabled();
    expect(within(row).getByRole("button", { name: /confirm/i })).toBeEnabled();
  });

  it("surfaces a load failure without blanking the page", async () => {
    mockedSearch.mockRejectedValue(new Error("boom"));

    renderPage();

    expect(await screen.findByRole("alert")).toHaveTextContent(/failed to load fraud alerts/i);
  });

  it("surfaces a scan failure", async () => {
    mockedScan.mockRejectedValue(new Error("boom"));

    renderPage();
    await screen.findByText(/Possible duplicate payment/);
    fireEvent.click(screen.getByRole("button", { name: /run scan/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/fraud scan failed/i);
  });

  it("tells the reviewer when nothing matches", async () => {
    mockedSearch.mockResolvedValue(page([]));

    renderPage();

    expect(await screen.findByText(/no alerts match these filters/i)).toBeInTheDocument();
  });
});
