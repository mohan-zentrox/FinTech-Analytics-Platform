# Project Ledger

FinTech Analytics & Reporting Platform. A transaction ledger with CSV import,
reconciliation, anomaly detection, PDF/Excel reporting, OAuth2 accounting-source
connectors, an append-only audit trail, and a pandas analytics service — all
deployable on free infrastructure.

> **New here? Start with [GETTING_STARTED.md](GETTING_STARTED.md)** — running the
> platform locally and a walkthrough of every screen, in about five minutes.

See `docs/ARCHITECTURE.md` for the system design, `docs/API.md` for the full REST
contract, and `docs/DEPLOYMENT.md` for where to host it for free.

## Repo layout

```
core-api/             Spring Boot (Java 17) - auth/RBAC, ledger, import, reconciliation,
                      fraud detection, reporting, connectors, audit log
analytics-service/    FastAPI + Pandas (Python 3.11) - cash-flow / KPI / anomaly analytics,
                      read-only DB access, JWT-protected
frontend/             React + TypeScript + Tailwind + Recharts (Vite)
GETTING_STARTED.md    run it locally and tour every screen - read this first
docs/                 ARCHITECTURE.md, API.md, DEPLOYMENT.md, TEAM.md
deploy/               Cloud Run, Fly, single-VM, Vercel and Netlify configurations
scripts/              e2e-smoke-test.py (drives every endpoint against a running stack)
                      make-sample-data.py (generates a demo CSV to import)
.github/workflows/    CI (Java + Python + frontend + Docker build)
docker-compose.yml    postgres + all three services
render.yaml           Render Blueprint
.env.example          copy to .env before running
```

## What it does

**Ledger (core-api).** JWT register/login with `ADMIN` / `ANALYST` / `VIEWER`
RBAC. Transaction CRUD with search, filtering and pagination. Idempotent CSV
import de-duplicated by `(source, externalId)` with per-row error reporting.
Rule-based reconciliation across two transaction sets with amount/date tolerances,
persisting both matches and exceptions. Every mutating operation is recorded in an
append-only audit trail by an AOP aspect.

**Anomaly detection (FRD S5.4).** Four rules — duplicate payment, amount outlier
(z-score within an account/category peer group), velocity spike, and suspiciously
round large amounts — with every threshold configurable. Findings are persisted as
triageable alerts: re-scanning refreshes open alerts but never reopens one an
analyst has already confirmed or dismissed. `analytics-service` implements the same
rules in pandas as an exploratory view with request-tunable thresholds.

**Reporting (FRD S6.1).** Cash-flow statements, reconciliation-exception reports
and anomaly-alert reports, rendered to PDF (OpenPDF) or Excel (Apache POI).
Generated documents are stored in Postgres — no object storage required — and are
listed and downloaded through a report-history API. An optional monthly cron job
generates them for every account with activity.

**Accounting connectors (FRD S6.2).** OAuth2 authorization-code flow with
automatic token refresh for QuickBooks Online, Xero and NetSuite. Tokens are
encrypted at rest with AES-256-GCM. Synced transactions funnel through the same
canonical schema and the same `(source, externalId)` uniqueness as CSV import, so
re-syncing is a no-op. Without provider credentials the connectors run in a
deterministic **sandbox mode**, so the whole flow is demoable end to end without an
Intuit/Xero/NetSuite developer account.

**Frontend.** Login/register, Dashboard (cash-flow and AR/AP charts, burn rate,
open-alert tile, top anomalies), Transaction Explorer (debounced filters, CSV
import), Reconciliation, Anomaly Alerts (scan + triage queue), Reports
(generate/browse/download), Connectors (OAuth + sync), and an admin Audit Trail.
Navigation and routes are role-gated to mirror the backend's `@PreAuthorize` rules.

## Running locally

```bash
cp .env.example .env
# edit .env - at minimum change JWT_SECRET, CONNECTOR_ENCRYPTION_KEY and the DB passwords
docker compose up --build
```

- frontend: http://localhost:5173
- core-api: http://localhost:8080/api (health: `/actuator/health`)
- analytics-service: http://localhost:8000/analytics (docs at `/docs`, health at `/health`)
- postgres: localhost:5432

Create the first user through the frontend's **Create one** link, or:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","email":"admin@example.com","password":"password123","role":"ADMIN"}'
```

> Registration accepts a `role` for bootstrap convenience. Restrict
> `POST /api/auth/register` to admins before any real deployment.

### Running services individually (without Docker)

- **core-api**: `cd core-api && mvn spring-boot:run` (needs Postgres reachable per
  `application.yml` / your `.env`)
- **analytics-service**: `cd analytics-service && pip install -r requirements.txt && uvicorn app.main:app --reload`
- **frontend**: `cd frontend && npm install && npm run dev`

## Testing

| Suite | Command | Count |
|---|---|---|
| core-api | `cd core-api && mvn test` | 150 |
| analytics-service | `cd analytics-service && pytest -v` | 64 |
| frontend | `cd frontend && npm test` | 46 |
| end-to-end | `python scripts/e2e-smoke-test.py` | 96 checks |

- **core-api**: JUnit 5 + Mockito unit tests (JWT, transaction/reconciliation/CSV
  import/auth/fraud/report/connector services, the fraud rule engine and both
  document renderers), plus `@SpringBootTest` + MockMvc integration tests covering
  the auth flow, the RBAC matrix and the health endpoint — against in-memory H2, so
  no live Postgres is needed.
- **analytics-service**: pure-pandas unit tests against hand-built DataFrames, plus
  FastAPI `TestClient` HTTP-contract tests with the DB dependency swapped for an
  in-memory fake, plus a JWT suite covering forged/expired/`alg=none`/wrong-algorithm
  tokens and role enforcement.
- **frontend**: Vitest + Testing Library over the utilities, the debounce hook and
  the Login, Fraud Alerts, Reports and Connectors pages.
- **end-to-end**: `scripts/e2e-smoke-test.py` drives every REST endpoint over real
  HTTP against a running `docker compose` stack. It asserts on what unit tests
  cannot reach: RBAC through the real filter chain, the shared JWT accepted across
  two services in different languages, idempotency against a real unique
  constraint, and whether the generated PDF/XLSX bytes actually parse as documents.

## Verification status of this build

Unlike the initial commit — which shipped with core-api and the frontend never
compiled, because no Java or Node toolchain was available where it was authored —
**every suite above has been executed**, and the full stack has been run under
`docker compose` against real PostgreSQL 16 with all 96 end-to-end checks passing.

Running it for real is what surfaced the following, none of which any amount of
hand review had caught:

1. **RBAC denials returned 500 instead of 403.** The catch-all `Exception` handler
   in `GlobalExceptionHandler` intercepted Spring Security's
   `AccessDeniedException` (and `NoResourceFoundException`, and
   `HttpRequestMethodNotSupportedException`) before the framework could map them.
2. **`/actuator/health` never existed.** `application.yml` configured actuator
   endpoints while `spring-boot-starter-actuator` was not a dependency, so every
   health check 404s.
3. **The JWT algorithm depended on the length of your secret.** jjwt picks HS256 /
   HS384 / HS512 from the key size, so a 64-byte `JWT_SECRET` silently issued HS512
   tokens. Once analytics-service began verifying those tokens (pinned to HS256, as
   it must be), every dashboard read returned 401.
4. **analytics-service could not authenticate to the database.** `V1__init.sql`
   hard-coded the `ledger_readonly` password while docker-compose passed a
   different default.
5. **Flyway parses placeholders inside SQL comments.** A `${ANALYTICS_DB_PASSWORD}`
   reference in a comment failed the migration and prevented startup entirely.
6. **`@Lob byte[]` maps to Postgres `oid`, not `BYTEA`.** It disagreed with the
   migration and was rejected outright by H2 in PostgreSQL mode.
7. **The read-only role could read the OAuth token table.** An
   `ALTER DEFAULT PRIVILEGES` grant silently extended SELECT to every table created
   after it, including `connector_credential` and `report_run`.
8. **The audit-log endpoint required the id of the row you were looking for**,
   which made "show me the audit trail" impossible.
9. **The transaction explorer issued one API request per keystroke.**

## Git history

This repo's history starts from the `Initial commit: Project Ledger foundation`
(see `git log`), created with a repo-local identity:

```bash
git config user.name "Zentrox Engineering"
git config user.email "engineering@zentroxglobaltechnologies.com"
```

## Team / roles

See `docs/TEAM.md`. Only role IDs (`T2-LEAD`, `T2-FE1`, `T2-DATA1`, `T2-DATA2`,
`T2-BA1`, `T2-QA1`) are used anywhere in this repo — never real names, per the
BRD/FRD compliance rule.
