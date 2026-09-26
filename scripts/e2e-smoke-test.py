"""
End-to-end smoke test for the whole platform.

Drives every REST endpoint of core-api and analytics-service over real HTTP
against a running stack, asserting on both the success paths and the behaviour
unit tests cannot reach: RBAC enforced through the real filter chain, the shared
JWT accepted across two services in different languages, CSV/connector
idempotency against a real unique constraint, and whether the generated PDF/XLSX
bytes are actually valid documents.

Requires a FRESH stack, because it asserts on exact counts (how many CSV rows
imported, how many alerts a first scan creates):

    docker compose down -v
    docker compose up -d --build
    python scripts/e2e-smoke-test.py

Override the base URLs with LEDGER_CORE_URL / LEDGER_ANALYTICS_URL /
LEDGER_FRONTEND_URL when the stack is not on the default compose ports.

Exits non-zero on the first failing assertion set, so it is usable as a CI gate.
"""
import os
import io
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta

CORE = os.environ.get("LEDGER_CORE_URL", "http://127.0.0.1:8080/api")
ANALYTICS_ROOT = os.environ.get("LEDGER_ANALYTICS_URL", "http://127.0.0.1:8000")
ANALYTICS = f"{ANALYTICS_ROOT}/analytics"
FRONTEND = os.environ.get("LEDGER_FRONTEND_URL", "http://127.0.0.1:5173")

passed = []
failed = []


def call(method, url, token=None, body=None, raw_body=None, content_type="application/json", expect=None):
    data = None
    headers = {}
    if raw_body is not None:
        data = raw_body
        headers["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            payload = resp.read()
            status = resp.status
            ctype = resp.headers.get("Content-Type", "")
            disposition = resp.headers.get("Content-Disposition", "")
    except urllib.error.HTTPError as e:
        payload = e.read()
        status = e.code
        ctype = e.headers.get("Content-Type", "")
        disposition = ""
    except Exception as e:  # connection refused etc.
        return None, 0, str(e), ""

    parsed = payload
    if "application/json" in ctype:
        try:
            parsed = json.loads(payload)
        except Exception:
            pass
    return parsed, status, ctype, disposition


def check(name, condition, detail=""):
    if condition:
        passed.append(name)
        print(f"  PASS  {name}")
    else:
        failed.append(f"{name}: {detail}")
        print(f"  FAIL  {name} -- {detail}")


print("=" * 78)
print("1. Auth and RBAC")
print("=" * 78)

admin, admin_status, _, _ = call("POST", f"{CORE}/auth/register", body={
    "username": "e2e-admin", "email": "e2e-admin@example.com",
    "password": "password123", "role": "ADMIN"})
check("register ADMIN returns 201 with a token", admin_status == 201 and admin.get("token"),
      f"status={admin_status} body={admin}")
ADMIN_TOKEN = admin.get("token") if isinstance(admin, dict) else None

analyst, st, _, _ = call("POST", f"{CORE}/auth/register", body={
    "username": "e2e-analyst", "email": "e2e-analyst@example.com",
    "password": "password123", "role": "ANALYST"})
check("register ANALYST", st == 201, f"status={st}")
ANALYST_TOKEN = analyst.get("token") if isinstance(analyst, dict) else None

viewer, st, _, _ = call("POST", f"{CORE}/auth/register", body={
    "username": "e2e-viewer", "email": "e2e-viewer@example.com",
    "password": "password123", "role": "VIEWER"})
check("register VIEWER", st == 201, f"status={st}")
VIEWER_TOKEN = viewer.get("token") if isinstance(viewer, dict) else None

_, st, _, _ = call("POST", f"{CORE}/auth/login", body={"username": "e2e-admin", "password": "password123"})
check("login with correct password -> 200", st == 200, f"status={st}")

_, st, _, _ = call("POST", f"{CORE}/auth/login", body={"username": "e2e-admin", "password": "wrong"})
check("login with wrong password -> 401", st == 401, f"status={st}")

_, st, _, _ = call("POST", f"{CORE}/auth/register", body={
    "username": "e2e-admin", "email": "other@example.com",
    "password": "password123", "role": "VIEWER"})
check("duplicate username -> 409", st == 409, f"status={st}")

_, st, _, _ = call("POST", f"{CORE}/auth/register", body={
    "username": "x", "email": "not-an-email", "password": "short", "role": "ANALYST"})
check("invalid registration -> 400 validation", st == 400, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/transactions")
check("unauthenticated request -> 401", st == 401, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/transactions", token="garbage.token.value")
check("malformed token -> 401", st == 401, f"status={st}")

print()
print("=" * 78)
print("2. Transaction ledger + CSV import")
print("=" * 78)

created, st, _, _ = call("POST", f"{CORE}/transactions", token=ANALYST_TOKEN, body={
    "source": "manual", "account": "ACC-E2E", "amount": "1500.00", "currency": "USD",
    "postedDate": "2026-09-01", "description": "Customer invoice 1", "category": "revenue",
    "status": "POSTED", "externalId": "E2E-1"})
check("create transaction -> 201", st == 201 and created.get("id"), f"status={st} body={created}")
TX_ID = created.get("id") if isinstance(created, dict) else None

_, st, _, _ = call("POST", f"{CORE}/transactions", token=ANALYST_TOKEN, body={
    "source": "manual", "account": "ACC-E2E", "amount": "1500.00", "currency": "USD",
    "postedDate": "2026-09-01", "description": "dup", "category": "revenue",
    "status": "POSTED", "externalId": "E2E-1"})
check("duplicate (source, externalId) -> 409", st == 409, f"status={st}")

_, st, _, _ = call("POST", f"{CORE}/transactions", token=VIEWER_TOKEN, body={
    "source": "manual", "account": "ACC-E2E", "amount": "1.00", "currency": "USD",
    "postedDate": "2026-09-01", "externalId": "E2E-VIEWER"})
check("VIEWER create -> 403 (not 500)", st == 403, f"status={st}")

_, st, _, _ = call("DELETE", f"{CORE}/transactions/{TX_ID}", token=ANALYST_TOKEN)
check("ANALYST delete -> 403 (not 500)", st == 403, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/transactions/00000000-0000-0000-0000-000000000000", token=ANALYST_TOKEN)
check("missing transaction -> 404", st == 404, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/transactions/not-a-uuid", token=ANALYST_TOKEN)
check("malformed uuid -> 400 (not 500)", st == 400, f"status={st}")

# CSV import: includes a duplicate of E2E-1, an in-file duplicate, and two bad rows.
# Amounts are shaped to trip the fraud rules later on.
today = date.today()
rows = ["source,account,amount,currency,postedDate,description,category,status,externalId"]
for i in range(12):
    d = today - timedelta(days=40 - i)
    rows.append(f"csv-import,ACC-E2E,{100 + i}.00,USD,{d},Office supplies {i},supplies,POSTED,CSV-{i}")
# A large round outlier -> ROUND_AMOUNT + AMOUNT_OUTLIER
rows.append(f"csv-import,ACC-E2E,-50000.00,USD,{today - timedelta(days=10)},Consulting retainer,supplies,POSTED,CSV-BIG")
# A duplicate pair -> DUPLICATE_PAYMENT
rows.append(f"csv-import,ACC-E2E,-2500.00,USD,{today - timedelta(days=5)},Acme invoice 88,opex,POSTED,CSV-DUP-A")
rows.append(f"csv-import,ACC-E2E,-2500.00,USD,{today - timedelta(days=4)},Acme Invoice #88,opex,POSTED,CSV-DUP-B")
# Already-present row, an in-file repeat, and two malformed rows
rows.append("manual,ACC-E2E,1500.00,USD,2026-09-01,Already imported,revenue,POSTED,E2E-1")
rows.append(f"csv-import,ACC-E2E,55.00,USD,{today - timedelta(days=3)},Repeat in file,opex,POSTED,CSV-0")
rows.append(f"csv-import,ACC-E2E,not-a-number,USD,{today},Bad amount,opex,POSTED,CSV-BAD1")
rows.append(f"csv-import,ACC-E2E,10.00,USD,not-a-date,Bad date,opex,POSTED,CSV-BAD2")
csv_text = "\n".join(rows)

boundary = "----e2eboundary"
body = (
    f"--{boundary}\r\n"
    'Content-Disposition: form-data; name="file"; filename="transactions.csv"\r\n'
    "Content-Type: text/csv\r\n\r\n"
    f"{csv_text}\r\n"
    f"--{boundary}--\r\n"
).encode()

imp, st, _, _ = call("POST", f"{CORE}/transactions/import", token=ANALYST_TOKEN,
                     raw_body=body, content_type=f"multipart/form-data; boundary={boundary}")
check("CSV import -> 200", st == 200, f"status={st} body={imp}")
check("CSV import counted 15 new rows", isinstance(imp, dict) and imp.get("imported") == 15,
      f"imported={imp.get('imported') if isinstance(imp, dict) else imp}")
check("CSV import skipped the 2 duplicates", isinstance(imp, dict) and imp.get("skipped") == 2,
      f"skipped={imp.get('skipped') if isinstance(imp, dict) else imp}")
check("CSV import reported the 2 bad rows", isinstance(imp, dict) and imp.get("errored") == 2,
      f"errored={imp.get('errored') if isinstance(imp, dict) else imp}")

# Re-importing the same file must be a complete no-op.
imp2, st, _, _ = call("POST", f"{CORE}/transactions/import", token=ANALYST_TOKEN,
                      raw_body=body, content_type=f"multipart/form-data; boundary={boundary}")
check("re-import is idempotent (0 imported)", isinstance(imp2, dict) and imp2.get("imported") == 0,
      f"imported={imp2.get('imported') if isinstance(imp2, dict) else imp2}")

page, st, _, _ = call("GET", f"{CORE}/transactions?account=ACC-E2E&size=100", token=VIEWER_TOKEN)
check("VIEWER can search transactions", st == 200 and page.get("totalElements", 0) >= 16,
      f"status={st} total={page.get('totalElements') if isinstance(page, dict) else page}")

filtered, st, _, _ = call("GET", f"{CORE}/transactions?account=ACC-E2E&status=POSTED&minAmount=1000&size=100",
                          token=ANALYST_TOKEN)
check("filters apply (minAmount=1000)", st == 200 and all(
    float(t["amount"]) >= 1000 for t in filtered.get("content", [])),
      f"status={st}")

print()
print("=" * 78)
print("3. Reconciliation")
print("=" * 78)

# Give the bank side (csv-import) a near-match for the manual invoice.
call("POST", f"{CORE}/transactions", token=ANALYST_TOKEN, body={
    "source": "csv-import", "account": "ACC-E2E", "amount": "1500.02", "currency": "USD",
    "postedDate": "2026-09-02", "description": "Bank credit", "category": "revenue",
    "status": "POSTED", "externalId": "BANK-1"})

recon, st, _, _ = call("POST", f"{CORE}/reconciliation/run", token=ANALYST_TOKEN, body={
    "accountA": "ACC-E2E", "sourceA": "manual",
    "accountB": "ACC-E2E", "sourceB": "csv-import",
    "dateFrom": "2026-08-01", "dateTo": "2026-09-30",
    "amountTolerance": "0.05", "dateToleranceDays": 2})
check("reconciliation run -> 200", st == 200 and recon.get("runId"), f"status={st} body={recon}")
check("reconciliation matched the near-identical pair",
      isinstance(recon, dict) and recon.get("matchedCount", 0) >= 1,
      f"matched={recon.get('matchedCount') if isinstance(recon, dict) else recon}")

_, st, _, _ = call("POST", f"{CORE}/reconciliation/run", token=VIEWER_TOKEN, body={
    "accountA": "ACC-E2E", "sourceA": "manual", "accountB": "ACC-E2E", "sourceB": "csv-import",
    "dateFrom": "2026-08-01", "dateTo": "2026-09-30", "amountTolerance": "0.05", "dateToleranceDays": 2})
check("VIEWER reconciliation -> 403", st == 403, f"status={st}")

print()
print("=" * 78)
print("4. Fraud / anomaly detection (FRD S5.4)")
print("=" * 78)

scan, st, _, _ = call("POST", f"{CORE}/fraud/scan", token=ANALYST_TOKEN, body={"account": "ACC-E2E"})
check("fraud scan -> 200", st == 200 and scan.get("scanId"), f"status={st} body={scan}")
check("fraud scan found anomalies", isinstance(scan, dict) and scan.get("findings", 0) > 0,
      f"findings={scan.get('findings') if isinstance(scan, dict) else scan}")
rules_found = set((scan or {}).get("findingsByRule", {}).keys())
check("duplicate-payment rule fired", "DUPLICATE_PAYMENT" in rules_found, f"rules={rules_found}")
check("round-amount rule fired", "ROUND_AMOUNT" in rules_found, f"rules={rules_found}")

scan2, st, _, _ = call("POST", f"{CORE}/fraud/scan", token=ANALYST_TOKEN, body={"account": "ACC-E2E"})
check("re-scan creates no new alerts (idempotent)",
      isinstance(scan2, dict) and scan2.get("newAlerts") == 0 and scan2.get("updatedAlerts", 0) > 0,
      f"new={scan2.get('newAlerts')} updated={scan2.get('updatedAlerts')}")

alerts, st, _, _ = call("GET", f"{CORE}/fraud/alerts?account=ACC-E2E&status=OPEN&size=50", token=ANALYST_TOKEN)
check("list alerts -> 200", st == 200 and alerts.get("totalElements", 0) > 0,
      f"status={st} total={alerts.get('totalElements') if isinstance(alerts, dict) else alerts}")
ALERT_ID = alerts["content"][0]["id"] if isinstance(alerts, dict) and alerts.get("content") else None

decided, st, _, _ = call("PATCH", f"{CORE}/fraud/alerts/{ALERT_ID}", token=ANALYST_TOKEN,
                         body={"status": "DISMISSED", "resolutionNote": "Confirmed with vendor"})
check("triage alert -> DISMISSED with actor recorded",
      st == 200 and decided.get("status") == "DISMISSED" and decided.get("resolvedBy") == "e2e-analyst",
      f"status={st} body={decided}")

scan3, st, _, _ = call("POST", f"{CORE}/fraud/scan", token=ANALYST_TOKEN, body={"account": "ACC-E2E"})
check("re-scan suppresses the triaged alert rather than reopening it",
      isinstance(scan3, dict) and scan3.get("suppressedAlerts", 0) >= 1,
      f"suppressed={scan3.get('suppressedAlerts') if isinstance(scan3, dict) else scan3}")

recheck, _, _, _ = call("GET", f"{CORE}/fraud/alerts/{ALERT_ID}", token=ANALYST_TOKEN)
check("triaged alert stayed DISMISSED after a re-scan",
      isinstance(recheck, dict) and recheck.get("status") == "DISMISSED",
      f"status={recheck.get('status') if isinstance(recheck, dict) else recheck}")

summary, st, _, _ = call("GET", f"{CORE}/fraud/summary?account=ACC-E2E", token=VIEWER_TOKEN)
check("VIEWER can read the alert summary", st == 200 and "openAlerts" in (summary or {}),
      f"status={st} body={summary}")

_, st, _, _ = call("POST", f"{CORE}/fraud/scan", token=VIEWER_TOKEN, body={})
check("VIEWER fraud scan -> 403", st == 403, f"status={st}")

print()
print("=" * 78)
print("5. Reports (FRD S6.1) - PDF and XLSX")
print("=" * 78)

period_from = (today.replace(day=1) - timedelta(days=60)).isoformat()
period_to = today.isoformat()

for report_type in ["CASH_FLOW", "RECONCILIATION_EXCEPTIONS", "FRAUD_ALERTS"]:
    for fmt in ["PDF", "XLSX"]:
        run, st, _, _ = call("POST", f"{CORE}/reports/generate", token=ANALYST_TOKEN, body={
            "reportType": report_type, "format": fmt, "account": "ACC-E2E",
            "periodFrom": period_from, "periodTo": period_to})
        ok = st == 201 and run.get("status") == "COMPLETED" and (run.get("sizeBytes") or 0) > 0
        check(f"generate {report_type}/{fmt} -> COMPLETED", ok,
              f"status={st} body={run}")
        if report_type == "CASH_FLOW" and fmt == "PDF":
            PDF_RUN_ID = run.get("id")
            PDF_FILENAME = run.get("fileName")
        if report_type == "CASH_FLOW" and fmt == "XLSX":
            XLSX_RUN_ID = run.get("id")

pdf_bytes, st, ctype, disposition = call("GET", f"{CORE}/reports/{PDF_RUN_ID}/download", token=ANALYST_TOKEN)
check("download PDF -> 200 application/pdf", st == 200 and "application/pdf" in ctype,
      f"status={st} ctype={ctype}")
check("downloaded bytes are a real PDF", isinstance(pdf_bytes, bytes) and pdf_bytes.startswith(b"%PDF-")
      and b"%%EOF" in pdf_bytes,
      f"first bytes={pdf_bytes[:8] if isinstance(pdf_bytes, bytes) else pdf_bytes}")
check("PDF download sets an attachment filename", "attachment" in disposition and ".pdf" in disposition,
      f"disposition={disposition}")

xlsx_bytes, st, ctype, _ = call("GET", f"{CORE}/reports/{XLSX_RUN_ID}/download", token=ANALYST_TOKEN)
check("download XLSX -> 200 spreadsheet content type",
      st == 200 and "spreadsheetml" in ctype, f"status={st} ctype={ctype}")
# XLSX is a zip: PK\x03\x04 magic, and must contain the workbook part.
check("downloaded bytes are a real XLSX zip",
      isinstance(xlsx_bytes, bytes) and xlsx_bytes[:4] == b"PK\x03\x04",
      f"first bytes={xlsx_bytes[:4] if isinstance(xlsx_bytes, bytes) else xlsx_bytes}")
try:
    import zipfile
    with zipfile.ZipFile(io.BytesIO(xlsx_bytes)) as z:
        names = z.namelist()
    check("XLSX contains the workbook part", "xl/workbook.xml" in names, f"names={names[:5]}")
except Exception as e:
    check("XLSX contains the workbook part", False, str(e))

hist, st, _, _ = call("GET", f"{CORE}/reports?size=50", token=VIEWER_TOKEN)
check("VIEWER can list report history", st == 200 and hist.get("totalElements", 0) >= 6,
      f"status={st} total={hist.get('totalElements') if isinstance(hist, dict) else hist}")
check("history rows omit the document bytes",
      isinstance(hist, dict) and hist.get("content") and "content" not in hist["content"][0],
      "content key leaked into the history payload")

_, st, _, _ = call("POST", f"{CORE}/reports/generate", token=VIEWER_TOKEN, body={"reportType": "CASH_FLOW"})
check("VIEWER generate report -> 403", st == 403, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/reports/00000000-0000-0000-0000-000000000000/download", token=ANALYST_TOKEN)
check("download missing report -> 404", st == 404, f"status={st}")

# Deprecated alias must still work.
alias, st, _, _ = call("POST", f"{CORE}/reports/cash-flow/generate?accountId=ACC-E2E&format=PDF",
                       token=ANALYST_TOKEN)
check("deprecated /reports/cash-flow/generate alias still works",
      st == 201 and alias.get("status") == "COMPLETED", f"status={st} body={alias}")

print()
print("=" * 78)
print("6. Accounting connectors (FRD S6.2) - sandbox OAuth + sync")
print("=" * 78)

conns, st, _, _ = call("GET", f"{CORE}/connectors", token=ADMIN_TOKEN)
check("list connectors -> 200 with all three providers",
      st == 200 and {c["provider"] for c in (conns or [])} == {"quickbooks", "xero", "netsuite"},
      f"status={st} body={conns}")
check("all providers report sandbox mode", all(c["sandbox"] for c in (conns or [])), f"body={conns}")

auth_url, st, _, _ = call("GET", f"{CORE}/connectors/quickbooks/authorize-url", token=ADMIN_TOKEN)
check("authorize-url -> 200 with a state and url",
      st == 200 and auth_url.get("state") and auth_url.get("authorizationUrl"),
      f"status={st} body={auth_url}")

_, st, _, _ = call("GET", f"{CORE}/connectors/quickbooks/authorize-url", token=ANALYST_TOKEN)
check("ANALYST authorize-url -> 403 (admin only)", st == 403, f"status={st}")

_, st, _, _ = call("GET", f"{CORE}/connectors/sage/authorize-url", token=ADMIN_TOKEN)
check("unknown provider -> 404", st == 404, f"status={st}")

conn, st, _, _ = call("POST", f"{CORE}/connectors/quickbooks/connect", token=ADMIN_TOKEN, body={
    "account": "ACC-E2E", "code": "sandbox-code-e2e", "realmId": "e2e-realm"})
check("connect quickbooks -> 200 CONNECTED",
      st == 200 and conn.get("connected") is True and conn.get("status") == "CONNECTED",
      f"status={st} body={conn}")
check("connector status exposes no token material",
      isinstance(conn, dict) and not any("token" in k.lower() and "expires" not in k.lower() for k in conn),
      f"keys={list(conn) if isinstance(conn, dict) else conn}")

sync, st, _, _ = call("POST", f"{CORE}/connectors/quickbooks/sync?account=ACC-E2E", token=ANALYST_TOKEN)
check("sync -> 200 and imported rows", st == 200 and sync.get("imported", 0) > 0,
      f"status={st} body={sync}")
FIRST_IMPORT = sync.get("imported") if isinstance(sync, dict) else 0

sync2, st, _, _ = call("POST", f"{CORE}/connectors/quickbooks/sync?account=ACC-E2E&since=2026-01-01",
                       token=ANALYST_TOKEN)
check("re-sync imports nothing and skips everything (dedup by source+externalId)",
      st == 200 and sync2.get("imported") == 0 and sync2.get("skipped", 0) >= FIRST_IMPORT,
      f"status={st} body={sync2}")

qb_page, st, _, _ = call("GET", f"{CORE}/transactions?account=ACC-E2E&size=100", token=ANALYST_TOKEN)
qb_rows = [t for t in (qb_page or {}).get("content", []) if t["source"] == "quickbooks"]
check("synced rows landed in the ledger with source=quickbooks", len(qb_rows) >= FIRST_IMPORT,
      f"found={len(qb_rows)} expected>={FIRST_IMPORT}")
check("synced rows are POSTED", all(t["status"] == "POSTED" for t in qb_rows), "unexpected status")

_, st, _, _ = call("POST", f"{CORE}/connectors/xero/sync?account=ACC-NOPE", token=ANALYST_TOKEN)
check("sync with no stored connection -> 404", st == 404, f"status={st}")

_, st, _, _ = call("DELETE", f"{CORE}/connectors/quickbooks?account=ACC-E2E", token=ANALYST_TOKEN)
check("ANALYST disconnect -> 403 (admin only)", st == 403, f"status={st}")

_, st, _, _ = call("DELETE", f"{CORE}/connectors/quickbooks?account=ACC-E2E", token=ADMIN_TOKEN)
check("ADMIN disconnect -> 204", st == 204, f"status={st}")

print()
print("=" * 78)
print("7. Audit trail")
print("=" * 78)

audit, st, _, _ = call("GET", f"{CORE}/audit-logs?size=100", token=ADMIN_TOKEN)
check("ADMIN can list the whole audit trail (no entity filter required)",
      st == 200 and audit.get("totalElements", 0) > 0,
      f"status={st} total={audit.get('totalElements') if isinstance(audit, dict) else audit}")
actions = {e["action"] for e in (audit or {}).get("content", [])}
for expected in ["CREATE", "IMPORT", "RECONCILE", "SCAN", "TRIAGE", "GENERATE", "CONNECT", "SYNC", "DISCONNECT"]:
    check(f"audit recorded {expected}", expected in actions, f"actions={sorted(actions)}")

by_action, st, _, _ = call("GET", f"{CORE}/audit-logs?action=SCAN&size=10", token=ADMIN_TOKEN)
check("audit trail filters by action",
      st == 200 and all(e["action"] == "SCAN" for e in (by_action or {}).get("content", [])),
      f"status={st}")

by_actor, st, _, _ = call("GET", f"{CORE}/audit-logs?actor=e2e-analyst&size=10", token=ADMIN_TOKEN)
check("audit trail filters by actor and records the real username",
      st == 200 and (by_actor or {}).get("totalElements", 0) > 0
      and all(e["actor"] == "e2e-analyst" for e in (by_actor or {}).get("content", [])),
      f"status={st} total={(by_actor or {}).get('totalElements')}")

_, st, _, _ = call("GET", f"{CORE}/audit-logs", token=ANALYST_TOKEN)
check("ANALYST audit access -> 403", st == 403, f"status={st}")

print()
print("=" * 78)
print("8. analytics-service (read-only role + shared JWT)")
print("=" * 78)

health, st, _, _ = call("GET", f"{ANALYTICS_ROOT}/health")
check("analytics /health is open", st == 200 and health.get("status") == "ok", f"status={st} body={health}")
check("analytics reports auth is required", (health or {}).get("authRequired") is True, f"body={health}")

_, st, _, _ = call("GET", f"{ANALYTICS}/cash-flow?accountId=ACC-E2E")
check("analytics without a token -> 401", st == 401, f"status={st}")

_, st, _, _ = call("GET", f"{ANALYTICS}/cash-flow?accountId=ACC-E2E", token="forged.token.here")
check("analytics with a forged token -> 401", st == 401, f"status={st}")

cf, st, _, _ = call("GET", f"{ANALYTICS}/cash-flow?accountId=ACC-E2E&months=6", token=ANALYST_TOKEN)
check("cash-flow with a core-api token -> 200 (cross-service JWT works)",
      st == 200 and len(cf.get("series", [])) == 6, f"status={st} body={str(cf)[:200]}")
check("cash-flow reads real rows written by core-api",
      isinstance(cf, dict) and any(m["inflow"] > 0 or m["outflow"] > 0 for m in cf.get("series", [])),
      f"series={cf.get('series') if isinstance(cf, dict) else cf}")

kpi, st, _, _ = call("GET", f"{ANALYTICS}/kpis?accountId=ACC-E2E", token=VIEWER_TOKEN)
check("kpis with a VIEWER token -> 200",
      st == 200 and "ar_aging" in (kpi or {}) and "burn_rate" in (kpi or {}),
      f"status={st} body={str(kpi)[:200]}")
check("aging buckets use the corrected 90+ label",
      isinstance(kpi, dict) and "90+" in kpi.get("ar_aging", {}) and "91-90+" not in kpi.get("ar_aging", {}),
      f"ar_aging={kpi.get('ar_aging') if isinstance(kpi, dict) else kpi}")

anom, st, _, _ = call("GET", f"{ANALYTICS}/anomalies?accountId=ACC-E2E&limit=20", token=ANALYST_TOKEN)
check("anomalies -> 200 with findings and a summary",
      st == 200 and (anom or {}).get("summary", {}).get("total", 0) > 0,
      f"status={st} body={str(anom)[:300]}")
py_rules = set((anom or {}).get("summary", {}).get("byRule", {}).keys())
check("pandas rules agree with core-api's on the duplicate pair",
      "DUPLICATE_PAYMENT" in py_rules, f"rules={py_rules}")
check("anomaly findings are ordered by score, highest first",
      isinstance(anom, dict) and [f["score"] for f in anom.get("findings", [])]
      == sorted([f["score"] for f in anom.get("findings", [])], reverse=True),
      "ordering wrong")

_, st, _, _ = call("GET", f"{ANALYTICS}/anomalies?accountId=ACC-E2E&zThreshold=99", token=ANALYST_TOKEN)
check("anomalies rejects an out-of-range threshold -> 422", st == 422, f"status={st}")

print()
print("=" * 78)
print("9. Frontend container")
print("=" * 78)

html, st, ctype, _ = call("GET", f"{FRONTEND}/")
check("frontend serves index.html", st == 200 and b"<div id=\"root\">" in (html or b""), f"status={st}")
check("frontend loads the runtime config script", b"/config.js" in (html or b""), "config.js script tag missing")

spa, st, _, _ = call("GET", f"{FRONTEND}/connectors/callback?code=x&state=y")
check("SPA fallback serves the app on a deep link (OAuth redirect target)",
      st == 200 and b"<div id=\"root\">" in (spa or b""), f"status={st}")

cfg, st, ctype, _ = call("GET", f"{FRONTEND}/config.js")
check("runtime config.js is served", st == 200 and b"__LEDGER_CONFIG__" in (cfg or b""), f"status={st}")

print()
print("=" * 78)
print(f"RESULT: {len(passed)} passed, {len(failed)} failed")
print("=" * 78)
if failed:
    for f in failed:
        print(f"  FAILED: {f}")
    sys.exit(1)
print("All end-to-end checks passed.")
