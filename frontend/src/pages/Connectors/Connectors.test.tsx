import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  connectProvider,
  disconnectProvider,
  fetchAuthorizeUrl,
  fetchConnectors,
  syncProvider,
} from "../../api/connectors";
import type { ConnectorStatus } from "../../api/connectors";
import { useAuthStore } from "../../store/authStore";
import Connectors from "./Connectors";

vi.mock("../../api/connectors", () => ({
  fetchConnectors: vi.fn(),
  fetchAuthorizeUrl: vi.fn(),
  connectProvider: vi.fn(),
  disconnectProvider: vi.fn(),
  syncProvider: vi.fn(),
}));

const mockedFetch = vi.mocked(fetchConnectors);
const mockedAuthorizeUrl = vi.mocked(fetchAuthorizeUrl);
const mockedConnect = vi.mocked(connectProvider);
const mockedDisconnect = vi.mocked(disconnectProvider);
const mockedSync = vi.mocked(syncProvider);

function status(overrides: Partial<ConnectorStatus> = {}): ConnectorStatus {
  return {
    provider: "quickbooks",
    account: "ACC-1",
    connected: true,
    sandbox: true,
    status: "CONNECTED",
    realmId: "sandbox-realm-quickbooks",
    scope: "com.intuit.quickbooks.accounting",
    accessTokenExpiresAt: "2026-08-01T11:00:00Z",
    lastSyncAt: "2026-07-31T09:00:00Z",
    lastSyncError: null,
    ...overrides,
  };
}

function renderPage(initialEntry = "/connectors", role: "ADMIN" | "ANALYST" = "ADMIN") {
  useAuthStore.setState({ token: "t", username: "u", role, isAuthenticated: true });
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Connectors />
    </MemoryRouter>
  );
}

describe("Connectors page", () => {
  beforeEach(() => {
    mockedFetch.mockReset();
    mockedAuthorizeUrl.mockReset();
    mockedConnect.mockReset();
    mockedDisconnect.mockReset();
    mockedSync.mockReset();
    mockedFetch.mockResolvedValue([status()]);
    window.localStorage.clear();
  });

  it("lists providers with their connection state and sandbox mode", async () => {
    renderPage();

    expect(await screen.findByText("quickbooks")).toBeInTheDocument();
    expect(screen.getByText("CONNECTED")).toBeInTheDocument();
    expect(screen.getByText("sandbox")).toBeInTheDocument();
  });

  it("explains sandbox mode so it is not mistaken for a live connection", async () => {
    renderPage();

    expect(await screen.findByText(/sandbox mode/i)).toBeInTheDocument();
    expect(screen.getByText(/CONNECTORS_SANDBOX=false/)).toBeInTheDocument();
  });

  it("shows a provider that has never been connected rather than hiding it", async () => {
    mockedFetch.mockResolvedValue([
      status({ provider: "xero", account: null, connected: false, status: "DISCONNECTED", lastSyncAt: null }),
    ]);

    renderPage();

    expect(await screen.findByText("xero")).toBeInTheDocument();
    expect(screen.getByText("DISCONNECTED")).toBeInTheDocument();
    expect(screen.getByText("never")).toBeInTheDocument();
  });

  it("syncs and reports imported vs skipped counts", async () => {
    mockedSync.mockResolvedValue({
      provider: "quickbooks",
      account: "ACC-1",
      since: "2026-07-30",
      sandbox: true,
      pagesFetched: 2,
      fetched: 10,
      imported: 10,
      skipped: 0,
      errored: 0,
      errors: [],
      syncedAt: "2026-08-01T10:00:00Z",
    });

    renderPage();
    const row = (await screen.findByText("quickbooks")).closest("tr")!;

    fireEvent.click(within(row).getByRole("button", { name: /^sync$/i }));

    await waitFor(() => expect(mockedSync).toHaveBeenCalledWith("quickbooks", "ACC-1"));
    // The counts are split across elements (the imported figure is emphasised), so
    // match on the banner's overall text rather than a single text node.
    const banner = await screen.findByText(/skipped as already present/i);
    expect(banner.textContent).toMatch(/10 imported/);
    expect(banner.textContent).toMatch(/0 skipped as already present/);
    expect(banner.textContent).toMatch(/2 page\(s\)/);
  });

  it("surfaces a sync failure", async () => {
    mockedSync.mockRejectedValue(new Error("boom"));

    renderPage();
    const row = (await screen.findByText("quickbooks")).closest("tr")!;
    fireEvent.click(within(row).getByRole("button", { name: /^sync$/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/quickbooks sync failed/i);
  });

  it("refuses to start the OAuth flow with no ledger account chosen", async () => {
    renderPage();
    await screen.findByText("quickbooks");

    fireEvent.change(screen.getByLabelText(/ledger account to feed/i), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: /reconnect/i }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/enter the ledger account/i);
    expect(mockedAuthorizeUrl).not.toHaveBeenCalled();
  });

  it("completes the code exchange when the provider redirects back", async () => {
    mockedConnect.mockResolvedValue(status());

    renderPage("/connectors/callback?provider=quickbooks&code=sandbox-code-abc&state=abc&account=ACC-7");

    await waitFor(() =>
      expect(mockedConnect).toHaveBeenCalledWith("quickbooks", "ACC-7", "sandbox-code-abc", undefined)
    );
    expect(await screen.findByText(/connected quickbooks for account ACC-7/i)).toBeInTheDocument();
  });

  it("passes a provider realmId through the callback when one is supplied", async () => {
    mockedConnect.mockResolvedValue(status());

    renderPage("/connectors/callback?provider=quickbooks&code=c&state=s&account=ACC-1&realmId=9130354");

    await waitFor(() =>
      expect(mockedConnect).toHaveBeenCalledWith("quickbooks", "ACC-1", "c", "9130354")
    );
  });

  it("reports a failed code exchange", async () => {
    mockedConnect.mockRejectedValue(new Error("invalid_grant"));

    renderPage("/connectors/callback?provider=quickbooks&code=stale&state=s&account=ACC-1");

    expect(await screen.findByRole("alert")).toHaveTextContent(/could not complete the quickbooks connection/i);
  });

  it("disconnects a provider", async () => {
    mockedDisconnect.mockResolvedValue({ data: undefined } as never);

    renderPage();
    const row = (await screen.findByText("quickbooks")).closest("tr")!;

    fireEvent.click(within(row).getByRole("button", { name: /disconnect/i }));

    await waitFor(() => expect(mockedDisconnect).toHaveBeenCalledWith("quickbooks", "ACC-1"));
  });

  it("hides connect and disconnect from a non-admin but still allows sync", async () => {
    renderPage("/connectors", "ANALYST");
    const row = (await screen.findByText("quickbooks")).closest("tr")!;

    expect(within(row).queryByRole("button", { name: /reconnect/i })).not.toBeInTheDocument();
    expect(within(row).queryByRole("button", { name: /disconnect/i })).not.toBeInTheDocument();
    expect(within(row).getByRole("button", { name: /^sync$/i })).toBeEnabled();
    expect(screen.getByText(/requires the ADMIN role/i)).toBeInTheDocument();
  });

  it("shows the last sync error when the provider previously failed", async () => {
    mockedFetch.mockResolvedValue([
      status({ status: "EXPIRED", lastSyncError: "Token refresh failed: invalid_grant" }),
    ]);

    renderPage();

    expect(await screen.findByText(/Token refresh failed: invalid_grant/)).toBeInTheDocument();
    expect(screen.getByText("EXPIRED")).toBeInTheDocument();
  });
});
