import { useCallback, useEffect, useState } from "react";

import { errorMessage } from "../../api/client";
import { searchAuditLogs } from "../../api/audit";
import type { AuditLogEntry, AuditLogSearchParams } from "../../api/audit";
import EmptyRow from "../../components/ui/EmptyRow";
import Field from "../../components/ui/Field";
import Pagination from "../../components/ui/Pagination";
import { useDebouncedValue } from "../../utils/useDebouncedValue";
import { formatDateTime } from "../../utils/format";

const ACTIONS = ["CREATE", "UPDATE", "DELETE", "IMPORT", "RECONCILE", "SCAN", "TRIAGE", "GENERATE", "CONNECT", "DISCONNECT", "SYNC"];

/**
 * Admin-only view of the append-only audit trail.
 *
 * All filters are optional, which is what makes this usable: the endpoint used to
 * require entity+entityId, so you could only look something up if you already
 * knew its id.
 */
export default function AuditLog() {
  const [entity, setEntity] = useState("");
  const [entityId, setEntityId] = useState("");
  const [actor, setActor] = useState("");
  const [action, setAction] = useState("");
  const [page, setPage] = useState(0);

  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Debounced so typing in a text filter does not fire a request per keystroke.
  const debouncedEntity = useDebouncedValue(entity, 350);
  const debouncedEntityId = useDebouncedValue(entityId, 350);
  const debouncedActor = useDebouncedValue(actor, 350);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    const params: AuditLogSearchParams = {
      entity: debouncedEntity || undefined,
      entityId: debouncedEntityId || undefined,
      actor: debouncedActor || undefined,
      action: action || undefined,
      page,
      size: 25,
    };
    try {
      const result = await searchAuditLogs(params);
      setEntries(result.content);
      setTotalPages(result.totalPages);
      setTotalElements(result.totalElements);
    } catch (e) {
      setError(errorMessage(e, "Failed to load the audit trail."));
    } finally {
      setLoading(false);
    }
  }, [debouncedEntity, debouncedEntityId, debouncedActor, action, page]);

  useEffect(() => {
    load();
  }, [load]);

  // Any filter change returns to the first page; staying on page 7 of a narrower
  // result set would show an empty table.
  useEffect(() => {
    setPage(0);
  }, [debouncedEntity, debouncedEntityId, debouncedActor, action]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold text-slate-900">Audit Trail</h1>
        <p className="text-sm text-slate-500">
          Append-only record of every mutating operation, written by the AOP audit aspect. Rows are never
          updated or deleted by the application.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-4 rounded-lg border border-slate-200 bg-white p-4 md:grid-cols-4">
        <Field label="Entity">
          <input
            className="input"
            placeholder="e.g. Transaction"
            value={entity}
            onChange={(e) => setEntity(e.target.value)}
          />
        </Field>
        <Field label="Entity id">
          <input className="input" value={entityId} onChange={(e) => setEntityId(e.target.value)} />
        </Field>
        <Field label="Actor">
          <input
            className="input"
            placeholder="username"
            value={actor}
            onChange={(e) => setActor(e.target.value)}
          />
        </Field>
        <Field label="Action">
          <select className="input" value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="">Any action</option>
            {ACTIONS.map((a) => (
              <option key={a} value={a}>
                {a}
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
              <th className="px-4 py-2">Timestamp</th>
              <th className="px-4 py-2">Actor</th>
              <th className="px-4 py-2">Action</th>
              <th className="px-4 py-2">Entity</th>
              <th className="px-4 py-2">Entity id</th>
              <th className="px-4 py-2">Details</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading && <EmptyRow colSpan={6} message="Loading audit trail..." />}
            {!loading && entries.length === 0 && (
              <EmptyRow colSpan={6} message="No audit entries match these filters." />
            )}
            {!loading &&
              entries.map((entry) => (
                <tr key={entry.id}>
                  <td className="whitespace-nowrap px-4 py-2">{formatDateTime(entry.timestamp)}</td>
                  <td className="px-4 py-2">{entry.actor}</td>
                  <td className="px-4 py-2">{entry.action}</td>
                  <td className="px-4 py-2">{entry.entity}</td>
                  <td className="px-4 py-2 font-mono text-xs text-slate-500">{entry.entityId ?? "-"}</td>
                  <td className="max-w-lg truncate px-4 py-2 text-slate-600" title={entry.details ?? ""}>
                    {entry.details ?? "-"}
                  </td>
                </tr>
              ))}
          </tbody>
        </table>
      </div>

      <Pagination page={page} totalPages={totalPages} disabled={loading} onChange={setPage} />
      <p className="text-xs text-slate-400">{totalElements} entr{totalElements === 1 ? "y" : "ies"} matched.</p>
    </div>
  );
}
