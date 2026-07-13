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
- **Scaffolded only** (see README): `fraud/`, `reports/`, `connector/`
  packages reserve the REST surface (mostly 501s) and document the FRD
  sections they map to; no business logic yet.

### analytics-service (FastAPI + Pandas, Python 3.11)

Read-only. Connects to the same Postgres instance as core-api but via a
dedicated `ledger_readonly` DB role (created in core-api's Flyway migration,
`V1__init.sql`) so a bug here can never mutate ledger data. Exposes:

- `GET /analytics/cash-flow?accountId=&months=` - monthly inflow/outflow/net
- `GET /analytics/kpis?accountId=` - AR/AP aging buckets + trailing 3-month
  burn rate

All the actual pandas logic lives in `app/services/analytics.py` as pure
functions of a DataFrame, independent of FastAPI/SQLAlchemy - this is what
lets `tests/test_analytics_service.py` run without a database.

### frontend (React + TypeScript + Tailwind + Recharts, Vite)

- `api/` - thin Axios clients for core-api and analytics-service, with a
  request interceptor that attaches the JWT and a response interceptor that
  logs the user out on `401`.
- `store/authStore.ts` - Zustand store, persists the session to
  `localStorage`.
- `pages/Login`, `pages/TransactionExplorer`, `pages/Reconciliation`,
  `pages/Dashboard` - the four vertical-slice screens.

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

## Cross-cutting decisions

- **IDs**: all primary keys are `UUID`, generated Java-side by Hibernate
  (`@GeneratedValue` with no explicit strategy resolves to
  `org.hibernate.id.uuid.UuidGenerator` for `UUID`-typed fields in Hibernate
  6+), so no DB extension (`pgcrypto`/`uuid-ossp`) is required.
- **Migrations**: Flyway, single baseline migration for this foundation
  repo (`V1__init.sql`). Add `V2__...sql` etc. as the schema evolves - do
  not edit `V1` after it has shipped to any shared environment.
- **Auth boundary**: analytics-service currently has no auth of its own
  (relies on network-level trust / same-VPC deployment in
  `docker-compose.yml`). Before production rollout, front it with the same
  JWT validation as core-api or put it behind an internal-only ingress -
  tracked as a follow-up, not yet in the FRD scaffolding for this repo.
