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
RBAC. Self-service registration only ever creates a `VIEWER`; privileged roles
must be provisioned by an existing admin. Transaction CRUD with search, filtering
and pagination (page size capped server-side). Idempotent CSV
import de-duplicated by `(source, externalId)` with per-row error reporting.
Rule-based reconciliation across two transaction sets with amount/date tolerances,
persisting both matches and exceptions. Every mutating operation is recorded in an
append-only audit trail by an AOP aspect.

**Anomaly detection (FRD S5.4).** Four rules — duplicate payment, amount outlier
(z-score within an account/category peer group), velocity spike, and suspiciously
round large amounts — with every threshold configurable. Findings are persisted as
triageable alerts: re-scanning refreshes open alerts but never reopens one an
analyst has already confirmed or dismissed. `analytics-service` reimplements three
of the four in pandas (`ROUND_AMOUNT` is core-api only) as an exploratory view with
request-tunable thresholds.

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

### Demo accounts

`docker-compose.yml` seeds these four accounts on first start, covering every
role and both admin personas. Sign in at http://localhost:5173 with any of them
instead of registering by hand. Emails are `<username>@projectledger.local`.

| Username  | Password             | Role    | Use it to see |
|-----------|----------------------|---------|---------------|
| `admin`   | `LedgerAdmin#2026`   | ADMIN   | Everything, including deletes and connector setup |
| `auditor` | `LedgerAudit#2026`   | ADMIN   | The compliance view — the Audit Trail screen |
| `analyst` | `LedgerAnalyst#2026` | ANALYST | Day-to-day work: import, reconcile, triage alerts, generate reports |
| `viewer`  | `LedgerViewer#2026`  | VIEWER  | Read-only access — a good way to watch RBAC take links away |

> **These passwords are public.** They are committed to this repository, so treat
> them as public knowledge. Seeding is **off by default** (`LEDGER_SEED_ENABLED`);
> `docker-compose.yml` turns it on because that file *is* the local demo stack.
> `render.yaml` and `deploy/cloudrun-deploy.sh` leave it off, so a deployment
> never gets these accounts unless someone sets the flag deliberately.

Override any of them with `SEED_ADMIN_PASSWORD` / `SEED_AUDITOR_PASSWORD` /
`SEED_ANALYST_PASSWORD` / `SEED_VIEWER_PASSWORD` in `.env` **before the first
start** - seeding is idempotent, so it will not overwrite an account that already
exists. Full detail in `docs/CREDENTIALS.md`.

### Registering your own account

Self-service registration is open, but it is not privilege-granting - it always
creates a `VIEWER`:

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","email":"alice@example.com","password":"password123"}'
```

Creating an `ANALYST` or `ADMIN` requires an existing admin's bearer token:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"LedgerAdmin#2026"}' \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')

curl -X POST http://localhost:8080/api/auth/register \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"username":"bob","email":"bob@example.com","password":"password123","role":"ANALYST"}'
```

The one exception is first-run bootstrap: while the user table is completely
empty there is no admin to ask, so the very first account may claim any role.
That window closes permanently as soon as one account exists - which is how a
deployment running with seeding off still gets its first administrator.

### Running services individually (without Docker)

- **core-api**: `cd core-api && mvn spring-boot:run` (needs Postgres reachable per
  `application.yml` / your `.env`)
- **analytics-service**: `cd analytics-service && pip install -r requirements.txt && uvicorn app.main:app --reload`
- **frontend**: `cd frontend && npm install && npm run dev`

## Testing

| Suite | Command | Count |
|---|---|---|
| core-api | `cd core-api && mvn test` | 166 |
| analytics-service | `cd analytics-service && pytest -v` | 64 |
| frontend | `cd frontend && npm test` | 46 |
| end-to-end | `python scripts/e2e-smoke-test.py` | 116 checks |

- **core-api**: JUnit 5 + Mockito unit tests (JWT, transaction/reconciliation/CSV
  import/auth/fraud/report/connector services, the fraud rule engine and both
  document renderers), plus `@SpringBootTest` + MockMvc integration tests covering
  the auth flow, the registration role rules, the RBAC matrix and the health
  endpoint — against in-memory H2, so no live Postgres is needed.
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
  constraint, whether the generated PDF/XLSX bytes actually parse as documents,
  and that registration cannot be used to escalate to a privileged role.

  It needs a **fresh** stack — it asserts on exact row counts and creates fixed
  usernames, so a second run against the same database fails on `409 Conflict`:

  ```bash
  docker compose down -v && docker compose up -d --build --wait
  python scripts/e2e-smoke-test.py
  ```

## Verification status of this build

Unlike the initial commit — which shipped with core-api and the frontend never
compiled, because no Java or Node toolchain was available where it was authored —
**every suite above has been executed**, and the full stack has been run under
`docker compose` against real PostgreSQL 16 with all end-to-end checks passing.

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

### Security fixes in this revision

A later pass probed the running stack rather than reading it, and closed three
holes the test suites were all green through:

10. **Anyone could register themselves as `ADMIN`.** `POST /api/auth/register` is
    unauthenticated by necessity and honoured whatever `role` the body asked for,
    so a single unauthenticated request returned a working ADMIN token — verified
    against the live stack reaching `/api/audit-logs` and `/api/connectors`.
    Registration now always yields `VIEWER`; a privileged role requires an ADMIN
    bearer token, except while the user table is empty (first-run bootstrap).
    Covered by `AuthServiceTest`, `AuthControllerIntegrationTest` and the
    end-to-end suite, which now asserts the 403 and that no token is issued.
11. **`GET /api/transactions` had no page-size cap.** `?size=100000000` returned
    `200` with `size` echoed back verbatim and passed straight through to SQL
    `LIMIT`, so one request could pull the whole table into heap. The three sibling
    paginated endpoints already clamped; this one had been missed. Capped at 200,
    and the end-to-end suite now checks the clamp on all four.
12. **The analytics read-only role could read password hashes.** `V2` granted, and
    `V6` re-asserted, `SELECT` on `app_user` to `ledger_readonly` — enough to read
    every user's bcrypt hash with the analytics credentials, confirmed live.
    analytics-service only ever queries `transactions` (one `SELECT`, in
    `app/db.py`). `V7__revoke_app_user_from_analytics.sql` withdraws it, and the
    CI privilege check now includes `app_user` — omitting it is precisely how the
    grant survived `V6`.

### Known limitations

Still true of this build, and deliberately out of scope rather than overlooked:

- **No user lifecycle.** Only register and login exist — no change-password, no
  reset, no admin user management or deactivation.
- **No token revocation.** Logout clears client state only; a leaked token stays
  valid until it expires (default one hour).
- **Currency is stored but not used in arithmetic.** Cash-flow, KPI, burn-rate and
  amount-based fraud rules sum raw `amount` values, and reconciliation matches on
  amount and date without comparing `currency`. Single-currency ledgers are
  unaffected; a mixed-currency ledger would produce meaningless totals.
- **No rate limiting on `/api/auth/login`**, no CSP or other security headers in
  `frontend/nginx.conf`, and CORS on core-api allows any origin.
- **`JWT_SECRET` and `CONNECTOR_ENCRYPTION_KEY` have working defaults.** Nothing
  refuses to boot on them, so a deployment that forgets to set them runs on a key
  that is published in this repository.
- **Report documents live in Postgres `BYTEA`** — no object storage needed, which
  is the point, but it does not scale.
- **`ReportScheduler` has no distributed lock**, so more than one core-api
  instance would generate duplicate monthly reports.
- **`reconciliation_match` has no foreign key** to `transactions`, so an admin
  hard-delete leaves orphan match rows (`fraud_alert` does have one).
- **Audit writes are best-effort.** `AuditLoggingAspect` logs and swallows a
  failure so it cannot break the business operation, which means a mutation can
  succeed without an audit row.

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
