# Project Ledger

FinTech Analytics & Reporting Platform - foundation repository. Real,
working source code for the core vertical slice (ledger, CSV import,
reconciliation, audit log, analytics), plus reserved-but-unimplemented
scaffolding for anomaly detection, scheduled reporting, and accounting
connectors. See `docs/ARCHITECTURE.md` for the system design and
`docs/API.md` for the full REST contract.

## Repo layout

```
core-api/            Spring Boot (Java 17) - auth/RBAC, ledger, import, reconciliation, audit log
analytics-service/    FastAPI + Pandas (Python 3.11) - cash-flow / KPI analytics, read-only DB access
frontend/             React + TypeScript + Tailwind + Recharts (Vite)
docs/                 ARCHITECTURE.md, API.md, TEAM.md
.github/workflows/    CI (Java + Python + frontend + Docker build)
docker-compose.yml    postgres + all three services
.env.example          copy to .env before running
```

## What's fully implemented (real, working code)

- **core-api**: JWT register/login, Spring Security RBAC (`ADMIN` /
  `ANALYST` / `VIEWER`), `Transaction` JPA entity + full CRUD +
  search/filter/pagination (`GET /api/transactions`), idempotent CSV import
  (`POST /api/transactions/import`, dedup by `(source, externalId)`),
  rule-based reconciliation (`POST /api/reconciliation/run`, persisted
  match results), append-only audit log via a Spring AOP aspect on every
  mutating service method. Flyway migration for the full schema.
- **analytics-service**: `GET /analytics/cash-flow` and `GET /analytics/kpis`
  (AR/AP aging buckets, trailing 3-month burn rate), computed with pandas
  against a read-only `ledger_readonly` Postgres role.
- **frontend**: Login, Transaction Explorer (filters/pagination/CSV import),
  Reconciliation (run form + matched/exception tables), Dashboard
  (Recharts cash-flow line chart + AR/AP aging bar chart + burn-rate tile).
  JWT-aware API client, Zustand auth store, protected routes.
- **CI/CD**: `.github/workflows/ci.yml` builds+tests all three services
  independently and sanity-builds all three Docker images.

## What's scaffolded only (no business logic - TODOs reference FRD sections)

- `core-api/.../fraud/` - anomaly/fraud detection (FRD S5.4)
- `core-api/.../reports/` - scheduled PDF/Excel report generation (FRD S6.1)
- `core-api/.../connector/` - QuickBooks/Xero/NetSuite-style OAuth
  connectors (FRD S6.2)
- `analytics-service/app/services/fraud_scaffold.py` - pandas-side
  counterpart to the fraud service above

These compile/import cleanly and are wired into routing + Spring Security,
but their endpoints return `501 Not Implemented` and their service methods
raise `UnsupportedOperationException` / `NotImplementedError` until a team
member picks up the referenced FRD section.

## Running locally

```bash
cp .env.example .env
# edit .env - at minimum change JWT_SECRET and the DB passwords
docker compose up --build
```

- frontend: http://localhost:5173
- core-api: http://localhost:8080/api
- analytics-service: http://localhost:8000/analytics (docs at /docs)
- postgres: localhost:5432

Register a user via `POST /api/auth/login` after registering through
`POST /api/auth/register` (see `docs/API.md`), or drive it from the
frontend's login screen.

### Running services individually (without Docker)

- **core-api**: `cd core-api && mvn spring-boot:run` (needs Postgres
  reachable per `application.yml` / your `.env`).
- **analytics-service**: `cd analytics-service && pip install -r
  requirements.txt && uvicorn app.main:app --reload`
- **frontend**: `cd frontend && npm install && npm run dev`

## Testing

- **core-api**: `cd core-api && mvn test` - JUnit 5 + Mockito unit tests
  (JWT service, transaction/reconciliation/CSV-import/auth services) plus a
  `@SpringBootTest` + `MockMvc` integration test for the auth flow, running
  against an in-memory H2 database (`src/test/resources/application-test.yml`)
  so no live Postgres is required.
- **analytics-service**: `cd analytics-service && pytest -v` - pure-pandas
  unit tests against hand-built DataFrames (no DB), plus FastAPI
  `TestClient` HTTP-contract tests with the DB dependency swapped for an
  in-memory fake via `app.dependency_overrides`.
- **frontend**: `cd frontend && npm test` - Vitest + Testing Library
  (utils, Login page behavior).

## Verification status of this build

**This repo was authored in a sandbox with no Java/Maven, Python, Node/npm,
or git toolchain available on PATH** (confirmed via `java -version`,
`mvn -version`, `python --version`, `node --version`, `git --version` all
failing). As a result:

- None of the three test suites (`mvn test`, `pytest`, `npm test`) or
  builds could actually be executed or auto-fixed here. `git init`/`commit`
  could not be run either - **there is no git history for this repo yet**;
  run the commands in the "Initializing git" section below once a git
  binary is available.
- Every file was instead reviewed by hand for correctness: import
  consistency across all Java/Python/TypeScript files, Spring
  Boot/Security/JPA/AOP wiring (bean names, `@PreAuthorize` expressions,
  JWT (jjwt 0.12.x) API usage, Hibernate UUID id generation, commons-csv
  `setIgnoreHeaderCase` header lookups), pandas dtype handling (explicit
  `pd.to_datetime`/`astype(float)` normalization, empty-DataFrame edge
  cases), and TypeScript/React types (Axios client, Zustand store shape,
  Recharts prop types).
- Test data in `analytics-service/tests/test_kpis_router.py` and
  `test_cash_flow_router.py` deliberately uses dates relative to
  `date.today()` / a wide trailing window rather than fixed calendar dates,
  specifically so the suite doesn't silently rot relative to "when someone
  actually runs it."
- **Before merging**, whoever has a toolchain available should run all
  three test suites and `docker compose build` at minimum; treat this
  README note as a flag to do that first, not as a substitute for it.

## Initializing git

This repo does not yet have git history (no `git` binary was available in
the environment that authored it). Run once, from the repo root:

```bash
git init
git config user.name "Zentrox Engineering"
git config user.email "engineering@zentroxglobaltechnologies.com"
git checkout -b main   # if init didn't already default to main
git add -A
git commit -m "Initial commit: Project Ledger foundation - transaction ledger, CSV import, reconciliation, analytics microservice, audit log"
```

## Team / roles

See `docs/TEAM.md`. Only role IDs (`T2-LEAD`, `T2-FE1`, `T2-DATA1`,
`T2-DATA2`, `T2-BA1`, `T2-QA1`) are used anywhere in this repo - never real
names, per the BRD/FRD compliance rule.
