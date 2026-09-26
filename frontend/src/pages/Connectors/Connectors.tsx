import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { errorMessage } from "../../api/client";
import {
  connectProvider,
  disconnectProvider,
  fetchAuthorizeUrl,
  fetchConnectors,
  syncProvider,
} from "../../api/connectors";
import type { ConnectorStatus, ConnectorStatusValue, SyncResult } from "../../api/connectors";
import Badge from "../../components/ui/Badge";
import type { BadgeTone } from "../../components/ui/Badge";
import EmptyRow from "../../components/ui/EmptyRow";
import Field from "../../components/ui/Field";
import { useAuthStore } from "../../store/authStore";
import { formatDateTime } from "../../utils/format";

const statusTone: Record<ConnectorStatusValue, BadgeTone> = {
  CONNECTED: "success",
  EXPIRED: "warning",
  DISCONNECTED: "neutral",
};

/**
 * FRD S6.2: connect an accounting source over OAuth2 and pull its transactions.
 *
 * Also serves as the OAuth redirect target: the provider (or, in sandbox mode,
 * the simulated authorize URL) comes back to /connectors/callback?code=...&state=...
 * and this page completes the exchange.
 */
export default function Connectors() {
  const role = useAuthStore((s) => s.role);
  const isAdmin = role === "ADMIN";

  const [searchParams, setSearchParams] = useSearchParams();
  const [connectors, setConnectors] = useState<ConnectorStatus[]>([]);
  const [account, setAccount] = useState("ACC-1");
  const [loading, setLoading] = useState(false);
  const [busyProvider, setBusyProvider] = useState<string | null>(null);
  const [syncResult, setSyncResult] = useState<SyncResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setConnectors(await fetchConnectors());
    } catch (e) {
      setError(errorMessage(e, "Failed to load connectors."));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  // Completes the OAuth handshake when the provider redirects back here.
  const code = searchParams.get("code");
  const callbackProvider = searchParams.get("provider");
  const realmIdParam = searchParams.get("realmId");
  const callbackAccount = searchParams.get("account");

  useEffect(() => {
    if (!code || !callbackProvider) return;

    let cancelled = false;
    async function completeConnect() {
      setBusyProvider(callbackProvider);
      setError(null);
      try {
        await connectProvider(
          callbackProvider!,
          callbackAccount || account,
          code!,
          realmIdParam ?? undefined
        );
        if (cancelled) return;
        setNotice(`Connected ${callbackProvider} for account ${callbackAccount || account}.`);
        await load();
      } catch (e) {
        if (!cancelled) setError(errorMessage(e, `Could not complete the ${callbackProvider} connection.`));
      } finally {
        if (!cancelled) {
          setBusyProvider(null);
          // Clear the OAuth params so a refresh does not replay a spent code.
          setSearchParams({}, { replace: true });
        }
      }
    }
    completeConnect();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [code, callbackProvider]);

  async function handleConnect(provider: string) {
    if (!account.trim()) {
      setError("Enter the ledger account this connection should feed before connecting.");
      return;
    }
    setBusyProvider(provider);
    setError(null);
    setNotice(null);
    try {
      const { authorizationUrl } = await fetchAuthorizeUrl(provider);
      // Carry the chosen account through the round-trip: the provider echoes back
      // only `state` and `code`, so without this the callback would not know which
      // ledger account the tokens belong to.
      const url = new URL(authorizationUrl, window.location.origin);
      url.searchParams.set("account", account.trim());
      window.location.assign(url.toString());
    } catch (e) {
      setError(errorMessage(e, `Could not start the ${provider} authorization flow.`));
      setBusyProvider(null);
    }
  }

  async function handleSync(provider: string, syncAccount: string) {
    setBusyProvider(provider);
    setError(null);
    setNotice(null);
    setSyncResult(null);
    try {
      const result = await syncProvider(provider, syncAccount);
      setSyncResult(result);
      await load();
    } catch (e) {
      setError(errorMessage(e, `${provider} sync failed.`));
    } finally {
      setBusyProvider(null);
    }
  }

  async function handleDisconnect(provider: string, disconnectAccount: string) {
    setBusyProvider(provider);
    setError(null);
    try {
      await disconnectProvider(provider, disconnectAccount);
      setNotice(`Disconnected ${provider} for account ${disconnectAccount}.`);
      await load();
    } catch (e) {
      setError(errorMessage(e, `Could not disconnect ${provider}.`));
    } finally {
      setBusyProvider(null);
    }
  }

  const anySandbox = connectors.some((c) => c.sandbox);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Accounting Connectors</h1>
        <p className="text-sm text-slate-500">
          OAuth2 connections to QuickBooks, Xero and NetSuite (FRD S6.2). Synced transactions land in the
          ledger de-duplicated by (source, externalId), so re-syncing never double-counts.
        </p>
      </div>

      {anySandbox && (
        <p className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          One or more providers are in <strong>sandbox mode</strong> - the OAuth handshake and sync are
          simulated locally so the flow is demoable without a provider developer account. Set the provider&apos;s
          client id/secret and <code>CONNECTORS_SANDBOX=false</code> to go live.
        </p>
      )}

      <div className="max-w-xs rounded-lg border border-slate-200 bg-white p-4">
        <Field label="Ledger account to feed">
          <input className="input" value={account} onChange={(e) => setAccount(e.target.value)} />
        </Field>
      </div>

      {notice && (
        <p className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-800">{notice}</p>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      {syncResult && (
        <div className="rounded-md border border-brand-200 bg-brand-50 p-3 text-sm text-brand-900">
          Synced {syncResult.provider} / {syncResult.account} since {syncResult.since}:{" "}
          <strong>{syncResult.imported}</strong> imported, {syncResult.skipped} skipped as already present,{" "}
          {syncResult.errored} errored across {syncResult.pagesFetched} page(s).
          {syncResult.errors.length > 0 && (
            <ul className="mt-1 list-disc pl-5">
              {syncResult.errors.slice(0, 5).map((e, i) => (
                <li key={i}>{e}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="min-w-full divide-y divide-slate-200 text-sm">
          <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-2">Provider</th>
              <th className="px-4 py-2">Account</th>
              <th className="px-4 py-2">Status</th>
              <th className="px-4 py-2">Mode</th>
              <th className="px-4 py-2">Last sync</th>
              <th className="px-4 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <EmptyRow colSpan={6} message="Loading connectors..." />}
            {!loading && connectors.length === 0 && (
              <EmptyRow colSpan={6} message="No connectors configured." />
            )}
            {!loading &&
              connectors.map((connector) => (
                <tr key={`${connector.provider}-${connector.account ?? "none"}`}>
                  <td className="px-4 py-2 font-medium capitalize">{connector.provider}</td>
                  <td className="px-4 py-2">{connector.account ?? "-"}</td>
                  <td className="px-4 py-2">
                    <Badge tone={statusTone[connector.status]}>{connector.status}</Badge>
                    {connector.lastSyncError && (
                      <p className="mt-1 max-w-xs text-xs text-red-600">{connector.lastSyncError}</p>
                    )}
                  </td>
                  <td className="px-4 py-2">
                    <Badge tone={connector.sandbox ? "warning" : "info"}>
                      {connector.sandbox ? "sandbox" : "live"}
                    </Badge>
                  </td>
                  <td className="whitespace-nowrap px-4 py-2">
                    {connector.lastSyncAt ? formatDateTime(connector.lastSyncAt) : "never"}
                  </td>
                  <td className="whitespace-nowrap px-4 py-2 text-right">
                    <div className="inline-flex gap-2">
                      {isAdmin && (
                        <button
                          type="button"
                          disabled={busyProvider === connector.provider}
                          onClick={() => handleConnect(connector.provider)}
                          className="rounded-md border border-brand-300 px-2 py-1 text-xs text-brand-700 hover:bg-brand-50 disabled:opacity-40"
                        >
                          {connector.connected ? "Reconnect" : "Connect"}
                        </button>
                      )}
                      <button
                        type="button"
                        disabled={!connector.connected || !connector.account || busyProvider === connector.provider}
                        onClick={() => handleSync(connector.provider, connector.account!)}
                        className="rounded-md border border-slate-300 px-2 py-1 text-xs hover:bg-slate-50 disabled:opacity-40"
                      >
                        {busyProvider === connector.provider ? "Working..." : "Sync"}
                      </button>
                      {isAdmin && (
                        <button
                          type="button"
                          disabled={!connector.account || busyProvider === connector.provider}
                          onClick={() => handleDisconnect(connector.provider, connector.account!)}
                          className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-600 hover:bg-slate-50 disabled:opacity-40"
                        >
                          Disconnect
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      {!isAdmin && (
        <p className="text-xs text-slate-400">
          Connecting and disconnecting a provider requires the ADMIN role; analysts can trigger a sync.
        </p>
      )}
    </div>
  );
}
