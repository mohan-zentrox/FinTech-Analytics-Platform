# Architecture

## Overview

Project Ledger is split into three deployable services plus a shared
Postgres database:

```
                         ┌─────────────────────┐
                         │      frontend        │
                         │  React + TS + Tailwind│
                         │  + Recharts (Vite)    │
                         └──────────┬───────────┘
                     JWT (Bearer)   │        analytics reads (no auth yet -
                     REST/JSON      │        see docs/API.md TODO)
              ┌──────────────────┐ │  ┌───────────────────────┐
              │      core-api     │◄┘  │   analytics-service    │
              │  Spring Boot 3    │    │   FastAPI + Pandas     │
              │  Java 17          │    │   Python 3.11          │
              │  JWT auth + RBAC  │    └───────────┬─────────────┘
              └─────────┬─────────┘                │
                write + read-write role             │ read-only role
                         │                           │ (ledger_readonly)
                         ▼                           ▼
                    ┌───────────────────────────────────┐
                    │             PostgreSQL              │
                    │  transactions / app_user /           │
                    │  audit_log / reconciliation_match     │
                    └───────────────────────────────────┘
```

## Services

### core-api (Spring Boot, Java 17)

Owns all writes to the ledger. Responsibilities:

- **Auth & RBAC** (`security/`, `config/SecurityConfig.java`): JWT issued on
  `/api/auth/login` and `/api/auth/register`, validated per-request by
  `JwtAuthenticationFilter`. Three roles - `ADMIN`, `ANALYST`, `VIEWER` -
  enforced with `@PreAuthorize` at the controller layer.
- **Transaction ledger** (`entity/Transaction.java`, `service/TransactionService.java`):
  CRUD + `GET /api/transactions` search/filter/pagination via a JPA
  `Specification` built from query params.
- **CSV import** (`service/CsvImportService.java`): parses uploads with
  Apache Commons CSV, de-duplicates on `(source, externalId)`, returns a
  row-level error report.
- **Reconciliation** (`service/ReconciliationService.java`): a pure,
  independently-unit-tested `match()` function pairs transactions across two
  sets within an amount/date tolerance; `run()` wraps it with persistence to
  `reconciliation_match` and DTO mapping.
- **Audit log** (`aspect/AuditLoggingAspect.java` + `aspect/Audited.java`):
  an AOP `@Around` advice on any service method annotated `@Audited` writes
  an append-only `audit_log` row (actor/action/entity/entityId/timestamp) in
  its own transaction, so it survives even if the aspect itself misbehaves,
  and never blocks the primary operation on a logging failure.
- **Anomaly detection** (`fraud/`, FRD S5.4): `FraudRuleEngine` is a pure
  function of (transactions, thresholds) - no repository, no Spring, no clock
  beyond the caller-supplied date - so every rule is unit-testable against
  in-memory lists, the same shape as the reconciliation matcher.
  `FraudDetectionService` wraps it with persistence and the alert lifecycle.
- **Reporting** (`reports/`, FRD S6.1): `ReportDataAssembler` turns ledger rows
  into a format-independent `ReportData`; `PdfReportRenderer` (OpenPDF) and
  `XlsxReportRenderer` (Apache POI) render it. The service picks a renderer by
  format, so adding CSV or HTML output means adding a bean, not editing the
  service. Documents are stored inline in `report_run` rather than object
  storage - a deliberate trade to keep the platform free-tier deployable.
- **Accounting connectors** (`connector/`, FRD S6.2): `AbstractAccountingConnector`
  owns the standard OAuth2 behaviour; the three provider classes own only their
  authorization-URL shape and their JSON-to-`ExternalTransaction` mapping.
  `ConnectorSyncService` handles token refresh, paging and de-duplicated
  ingestion. Tokens are encrypted with AES-256-GCM by `TokenCipher`.

### analytics-service (FastAPI + Pandas, Python 3.11)

Read-only. Connects to the same Postgres instance as core-api but via a
dedicated `ledger_readonly` DB role (created in core-api's Flyway migrations,
`V2__analytics_readonly_role.sql`, and scoped down by `V6`) so a bug here can
never mutate ledger data - and cannot read the connector token store, the report
documents or the persisted alerts either. Exposes:

- `GET /analytics/cash-flow?accountId=&months=` - monthly inflow/outflow/net
- `GET /analytics/kpis?accountId=` - AR/AP aging buckets + trailing 3-month
  burn rate
- `GET /analytics/anomalies?accountId=&...` - statistical anomaly findings with
  request-tunable thresholds (FRD S5.4)

Every `/analytics/**` route requires the same HS256 bearer token core-api issues,
verified with the shared `JWT_SECRET` (`app/core/security.py`).

All the actual pandas logic lives in `app/services/analytics.py` as pure
functions of a DataFrame, independent of FastAPI/SQLAlchemy - this is what
lets `tests/test_analytics_service.py` run without a database.

### frontend (React + TypeScript + Tailwind + Recharts, Vite)

- `api/` - thin Axios clients for core-api and analytics-service, with a
  request interceptor that attaches the JWT and a response interceptor that
  logs the user out on `401`.
- `store/authStore.ts` - Zustand store, persists the session to
  `localStorage`.
- `pages/` - Login, Register, Dashboard, TransactionExplorer, Reconciliation,
  FraudAlerts, Reports, Connectors and AuditLog. Routes and navigation are
  role-gated to mirror the backend `@PreAuthorize` rules; that is a usability
  measure, not the access control itself, which is enforced server-side.
- `components/ui/` - the shared Badge / Pagination / StatTile / Field / EmptyRow
  primitives the feature pages are built from.

## Data model

See `core-api/src/main/resources/db/migration/V1__init.sql` for the
authoritative schema. Summary:

- `app_user(id, username, email, password_hash, role, created_at)`
- `transactions(id, source, account, amount, currency, posted_date,
  description, category, status, external_id, created_at, updated_at)` -
  unique on `(source, external_id)` for idempotent import/sync.
- `audit_log(id, actor, action, entity, entity_id, details, timestamp)` -
  append-only, never updated/deleted by the application.
- `reconciliation_match(id, run_id, transaction_id,
  matched_transaction_id, match_type, amount_delta, date_delta_days,
  created_at)` - one row per matched pair or exception, grouped by
  `run_id`.
- `fraud_alert(id, scan_id, transaction_id, account, rule_id, severity, score,
  reason, status, detected_at, resolved_at, resolved_by, resolution_note)` -
  unique on `(transaction_id, rule_id)`, which is what makes a re-scan an upsert
  rather than a duplicate generator.
- `report_run(id, report_type, format, account, period_from, period_to, status,
  requested_by, file_name, content_type, size_bytes, content, error_message,
  created_at, completed_at)` - `content` is the rendered document as `BYTEA`.
- `connector_credential(id, provider, account, realm_id, access_token,
  refresh_token, ..., status, last_sync_at, last_sync_cursor, last_sync_error)` -
  unique on `(provider, account)`; both token columns hold AES-256-GCM ciphertext.

## Cross-cutting decisions

- **IDs**: all primary keys are `UUID`, generated Java-side by Hibernate
  (`@GeneratedValue` with no explicit strategy resolves to
  `org.hibernate.id.uuid.UuidGenerator` for `UUID`-typed fields in Hibernate
  6+), so no DB extension (`pgcrypto`/`uuid-ossp`) is required.
- **Migrations**: Flyway, single baseline migration for this foundation
  repo (`V1__init.sql`). Add `V2__...sql` etc. as the schema evolves - do
  not edit `V1` after it has shipped to any shared environment.
- **Auth boundary**: analytics-service validates the *same* HS256 tokens
  core-api issues, using a shared `JWT_SECRET`. It has no user table and no login
  of its own - the signature, expiry and role claim are all it needs, because it
  has no write access to anything. Both sides pin the algorithm to HS256:
  accepting whatever a token's own header advertises is the classic JWT
  algorithm-confusion hole, and jjwt's default (choose the MAC variant from the
  key length) made the issued algorithm depend on how long an operator's secret
  happened to be.
- **Two implementations of the anomaly rules, deliberately**: core-api owns the
  alert *lifecycle* (persistence, triage, suppression of analyst decisions) and
  must be able to scan without a second service being reachable;
  analytics-service offers the statistical rules as an exploratory view with
  request-tunable thresholds. Three of the four rules exist on both sides and are
  defined identically, with both test suites asserting the same behaviours so they
  cannot quietly diverge; ROUND_AMOUNT is core-api only, being a fixed policy
  threshold rather than something an analyst would tune.
- **Reports are generated synchronously** and stored in the database. At this
  volume (tens of KB, milliseconds to render) a job queue and object storage
  would add a worker that free-tier hosting would put to sleep, for no benefit.
  Swap `report_run.content` for a `storage_url` when volume justifies it.
- **Least privilege for the read-only role**: `V6` withdraws the
  `ALTER DEFAULT PRIVILEGES` grant that `V2` left behind, so a table added in a
  future migration is *not* readable by analytics-service until someone grants it
  deliberately.

## Deployment topology

See `docs/DEPLOYMENT.md`. The three services are independently deployable and the
frontend is pure static output, which is what allows the recommended free stack:
managed Postgres (Neon), two scale-to-zero containers (Cloud Run), and static
hosting (Cloudflare Pages / Vercel / Netlify).

Two consequences of scale-to-zero are baked into the defaults:

- `REPORTS_SCHEDULE_ENABLED` defaults to `false`, because a cron trigger inside a
  sleeping container never fires. An external scheduler calling
  `POST /api/reports/generate` is the supported pattern there; the single-VM
  compose profile (`deploy/docker-compose.prod.yml`) enables the in-process job
  instead, because nothing sleeps.
- `DB_POOL_MAX` defaults to 5 and should be lower still on free Postgres, where
  several cold-started instances can otherwise exhaust the connection cap between
  them.
