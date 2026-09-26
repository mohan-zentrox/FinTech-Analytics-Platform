# API Reference

Base URLs (local/docker-compose defaults):
- core-api: `http://localhost:8080/api`
- analytics-service: `http://localhost:8000/analytics`

All core-api endpoints except `/api/auth/**` require `Authorization: Bearer <jwt>`.

## Auth (core-api)

### `POST /api/auth/register`
Body:
```json
{ "username": "carol", "email": "carol@example.com", "password": "password123", "role": "ANALYST" }
```
`role` is one of `ADMIN`, `ANALYST`, `VIEWER`. Returns `201` with an `AuthResponse`
(`token`, `tokenType`, `username`, `role`, `expiresInMs`).

> Registration currently accepts `role` directly for bootstrap/demo
> convenience - restrict this to admin-only in a hardened deployment
> (see docs/ARCHITECTURE.md).

### `POST /api/auth/login`
Body: `{ "username": "carol", "password": "password123" }` -> `200` with an `AuthResponse`.

## Transactions (core-api)

RBAC: `GET` = ADMIN/ANALYST/VIEWER, `POST`/`PUT`/import = ADMIN/ANALYST, `DELETE` = ADMIN only.

### `GET /api/transactions?account=&dateFrom=&dateTo=&minAmount=&maxAmount=&status=&page=&size=`
All query params optional except pagination defaults (`page=0`, `size=20`).
`dateFrom`/`dateTo` are ISO-8601 dates (`yyyy-MM-dd`). Returns a `PageResponse<TransactionDto>`.

### `GET /api/transactions/{id}`
### `POST /api/transactions` - body matches `TransactionCreateRequest`.
### `PUT /api/transactions/{id}` - full-replace update (`TransactionUpdateRequest`); `source`/`externalId` are immutable.
### `DELETE /api/transactions/{id}`

### `POST /api/transactions/import`
`multipart/form-data` with a `file` field (CSV). Required columns
(case-insensitive): `source, account, amount, currency, postedDate, externalId`.
Optional: `description, category, status`. Rows are de-duplicated by
`(source, externalId)` against both the database and the rest of the same
upload. Returns:
```json
{ "imported": 12, "skipped": 2, "errored": 1, "errors": [{ "row": 5, "message": "Invalid amount value: 'abc'" }] }
```

## Reconciliation (core-api)

RBAC: ADMIN/ANALYST.

### `POST /api/reconciliation/run`
Body (`ReconciliationRequest`):
```json
{
  "accountA": "ACC-1", "sourceA": "manual",
  "accountB": "ACC-1", "sourceB": "csv-import",
  "dateFrom": "2026-01-01", "dateTo": "2026-01-31",
  "amountTolerance": 0.01, "dateToleranceDays": 2
}
```
Greedily matches transactions from set A (`accountA`/`sourceA`) to set B
within the given amount/date tolerance. Returns matched pairs and the
unmatched transactions on each side; persists every row (matched and
exception) to `reconciliation_match` keyed by a new `runId`.

## Audit log (core-api)

### `GET /api/audit-logs?entity=&entityId=&actor=&action=&from=&to=&page=&size=` (ADMIN only)
Every filter is optional, so the endpoint serves both "show me the whole trail,
newest first" and "everything that touched this one entity". `action` is one of
`CREATE`, `UPDATE`, `DELETE`, `IMPORT`, `RECONCILE`, `SCAN`, `TRIAGE`,
`GENERATE`, `CONNECT`, `DISCONNECT`, `SYNC`. `from`/`to` are ISO-8601 instants.

## Anomaly / fraud detection (core-api, FRD S5.4)

RBAC: scan and triage = ADMIN/ANALYST; summary = all roles.

### `POST /api/fraud/scan`
Body (all fields optional; an empty body scans every account over the trailing
`ledger.fraud.lookback-days`):
```json
{ "account": "ACC-1", "dateFrom": "2026-06-01", "dateTo": "2026-06-30" }
```
Runs four rules - `DUPLICATE_PAYMENT`, `AMOUNT_OUTLIER`, `VELOCITY_SPIKE`,
`ROUND_AMOUNT` - and upserts findings per `(transactionId, ruleId)`. Safe to call
repeatedly: an existing OPEN alert is refreshed, while one an analyst has already
CONFIRMED or DISMISSED is *suppressed*, never reopened. Returns:
```json
{
  "scanId": "...", "account": "ACC-1",
  "dateFrom": "2026-06-01", "dateTo": "2026-06-30",
  "transactionsScanned": 128, "findings": 4,
  "newAlerts": 3, "updatedAlerts": 1, "suppressedAlerts": 0,
  "findingsByRule": { "DUPLICATE_PAYMENT": 2, "ROUND_AMOUNT": 2 }
}
```

### `GET /api/fraud/alerts?account=&status=&severity=&ruleId=&page=&size=`
Paginated `PageResponse<FraudAlertDto>`, newest first. `status` is one of
`OPEN`, `CONFIRMED`, `DISMISSED`; `severity` is `LOW`/`MEDIUM`/`HIGH`.

### `GET /api/fraud/alerts/{id}`

### `PATCH /api/fraud/alerts/{id}`
Body: `{ "status": "CONFIRMED", "resolutionNote": "Escalated to finance" }`.
Records the decision with the acting username and a timestamp. Setting `OPEN`
re-opens the alert and clears its resolution metadata.

### `GET /api/fraud/summary?account=`
`{ "account": "ACC-1", "openAlerts": 7 }` - for dashboard tiles. All roles.

Thresholds for every rule are configuration (`ledger.fraud.*`, each with an
env-var override) - see `.env.example`.

## Reports (core-api, FRD S6.1)

RBAC: generate = ADMIN/ANALYST; list/download = all roles.

### `POST /api/reports/generate`
Body:
```json
{
  "reportType": "CASH_FLOW",
  "format": "PDF",
  "account": "ACC-1",
  "periodFrom": "2026-06-01",
  "periodTo": "2026-06-30"
}
```
`reportType` is `CASH_FLOW`, `RECONCILIATION_EXCEPTIONS` or `FRAUD_ALERTS`;
`format` is `PDF` or `XLSX` (defaults to `ledger.reports.default-format`).
Omitting the period defaults to the previous whole calendar month - the same
period the scheduled job uses. Returns `201` with a `ReportRunDto`.

A render failure is **not** an error response: it returns `201` with
`"status": "FAILED"` and an `errorMessage`, so the failure is recorded in report
history rather than lost.

### `POST /api/reports/cash-flow/generate?accountId=&format=` *(deprecated)*
Preserved alias for the original scaffolded route; equivalent to
`POST /api/reports/generate` with `reportType=CASH_FLOW`.

### `GET /api/reports?reportType=&page=&size=`
Report history, newest first. Rows never include the document bytes.

### `GET /api/reports/{id}`
### `GET /api/reports/{id}/download`
Streams the stored PDF/XLSX with a `Content-Disposition: attachment` filename.
Requires the `Authorization` header like every other endpoint, so a plain
`<a href>` will not work - fetch it and hand the blob to the browser.

## Accounting connectors (core-api, FRD S6.2)

RBAC: authorize/connect/disconnect = ADMIN; list/sync = ADMIN/ANALYST.

With no provider client credentials configured (the default), connectors run in
**sandbox mode**: the OAuth handshake and the transaction feed are simulated
deterministically, so the whole flow is demoable without an Intuit/Xero/NetSuite
developer account. Set a provider client id/secret and `CONNECTORS_SANDBOX=false`
to go live.

### `GET /api/connectors`
Per-provider connection state. Never includes token material of any kind:
```json
[{ "provider": "quickbooks", "account": "ACC-1", "connected": true,
   "sandbox": true, "status": "CONNECTED", "realmId": "...", "scope": "...",
   "accessTokenExpiresAt": "...", "lastSyncAt": "...", "lastSyncError": null }]
```

### `GET /api/connectors/{provider}/authorize-url?state=`
Step 1 of the authorization-code flow. `state` is generated server-side when
omitted. Returns `{ provider, state, authorizationUrl }`.

### `POST /api/connectors/{provider}/connect`
Step 2. Body: `{ "account": "ACC-1", "code": "...", "realmId": "..." }`.
`realmId` is the provider tenant id (QuickBooks realmId / Xero tenantId) and is
required for a live connection to those two. Tokens are encrypted with AES-256-GCM
(`CONNECTOR_ENCRYPTION_KEY`) before storage. Re-connecting an existing
`(provider, account)` pair replaces its credentials in place.

### `DELETE /api/connectors/{provider}?account=`

### `POST /api/connectors/{provider}/sync?account=&since=`
Pages through the transactions of the provider into the canonical ledger,
de-duplicated by `(source, externalId)` exactly like CSV import - so re-syncing an
overlapping window imports nothing. Omitting `since` resumes from a day before the
last successful sync. Returns:
```json
{ "provider": "quickbooks", "account": "ACC-1", "since": "2026-07-30",
  "sandbox": true, "pagesFetched": 2, "fetched": 10,
  "imported": 10, "skipped": 0, "errored": 0, "errors": [], "syncedAt": "..." }
```
An expired access token is refreshed transparently; if the refresh itself fails the
connection is marked `EXPIRED` and a `502` explains that re-authorisation is needed.

## Analytics (analytics-service)

**Every `/analytics/**` endpoint requires the same `Authorization: Bearer <jwt>`
that core-api issues** - analytics-service verifies it with the shared
`JWT_SECRET` (HS256, pinned on both sides) and has no login of its own. Any of
`ADMIN`, `ANALYST` or `VIEWER` may read. A token with an unrecognised role gets
`403`; a missing, forged, expired or wrong-algorithm token gets `401`.

`GET /health` is deliberately unauthenticated so platform health checks work.

### `GET /analytics/cash-flow?accountId=&months=`
`months` defaults to `6` (1-36). Returns:
```json
{ "accountId": "ACC-1", "months": 6, "series": [{ "month": "2026-01", "inflow": 12000.0, "outflow": 8400.0, "net": 3600.0 }] }
```

### `GET /analytics/kpis?accountId=`
Returns AR/AP aging buckets (`0-30`, `31-60`, `61-90`, `90+` days, bucketed
by `posted_date` age for open/pending transactions) and a trailing
3-month burn rate:
```json
{
  "accountId": "ACC-1", "as_of": "2026-07-13",
  "ar_aging": { "0-30": 500.0, "31-60": 0.0, "61-90": 0.0, "90+": 0.0 },
  "ap_aging": { "0-30": 0.0, "31-60": 250.0, "61-90": 0.0, "90+": 0.0 },
  "burn_rate": 900.0, "burn_rate_period_months": 3
}
```

### `GET /analytics/anomalies?accountId=&limit=&zThreshold=&minSampleSize=&duplicateWindowDays=&minDailyCount=`

Statistical anomaly findings computed with pandas (FRD S5.4). This is the
*exploratory* view - thresholds are request parameters so sensitivity can be tuned
interactively, and nothing is written to the database. Persisted, triageable alerts
live in core-api (`POST /api/fraud/scan`), which owns the alert lifecycle. The rule
definitions are kept equivalent on both sides.

```json
{
  "accountId": "ACC-1",
  "thresholds": { "zThreshold": 3.0, "minSampleSize": 8,
                  "duplicateWindowDays": 7, "minDailyCount": 5 },
  "summary": { "total": 3, "byRule": { "DUPLICATE_PAYMENT": 1, "AMOUNT_OUTLIER": 2 },
               "bySeverity": { "HIGH": 1, "MEDIUM": 1, "LOW": 1 } },
  "findings": [{ "transactionId": "...", "account": "ACC-1",
                 "ruleId": "AMOUNT_OUTLIER", "severity": "HIGH",
                 "score": 5.12, "reason": "Amount ... standard deviations ..." }],
  "truncated": false
}
```
Findings are ordered by score, highest first.

### `GET /health`
Unauthenticated. Returns `{ "status": "ok", "authRequired": true }`.

## Error responses

Every error is the same JSON envelope:

```json
{ "timestamp": "2026-09-26T04:00:00Z", "status": 403, "error": "Access denied: insufficient role for this operation" }
```

| Status | When |
|---|---|
| 400 | validation failure (also returns `fieldErrors`), malformed body, bad path/query type |
| 401 | missing, malformed, expired or wrong-algorithm token; bad credentials |
| 403 | authenticated, but the role is not permitted for the operation |
| 404 | entity or endpoint does not exist |
| 405 | wrong HTTP method for the path |
| 409 | duplicate `(source, externalId)`, username or email; DB unique-constraint conflict |
| 413 | uploaded CSV exceeds the configured maximum |
| 502 | an accounting provider or connector configuration failed |
| 500 | genuinely unexpected - the detail is logged server-side and never returned to the caller |

Note that a role denial is `403`, not `500`: the catch-all exception handler used
to intercept Spring Security's `AccessDeniedException`, so every RBAC denial
surfaced as a misleading server error.
