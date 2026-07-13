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

### `GET /api/audit-logs?entity=&entityId=&page=&size=` (ADMIN only)

## Scaffolded, not yet implemented (core-api)

These routes exist and are wired into Spring Security but currently return
`501 Not Implemented` - see the referenced FRD sections in each service's
source comments:

- `GET /api/fraud/alerts` (FRD S5.4)
- `POST /api/reports/cash-flow/generate` (FRD S6.1)
- `GET /api/connectors`, `POST /api/connectors/{provider}/connect`,
  `POST /api/connectors/{provider}/sync` (FRD S6.2)

## Analytics (analytics-service)

No auth yet (see docs/ARCHITECTURE.md "Cross-cutting decisions").

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

### `GET /health`
