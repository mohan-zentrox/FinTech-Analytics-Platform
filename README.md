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

This repo was authored in a sandbox where neither a Java/Maven nor a
Node/npm toolchain was reachable (`java -version`, `mvn -version`,
`node -version` all failed, including a search of common install
locations) - so **core-api and frontend could not be built or
test-executed here**. Python and git *were* available (once added to
`PATH`), and were used for real:

- **analytics-service - actually executed**: `pip install -r
  requirements.txt` + `pytest -v` were run for real. All **12 tests
  passed**. This caught two genuine bugs that hand review alone missed:
  1. an aging-bucket label bug (`_aging_buckets` was emitting `"91-90+"`
     instead of `"90+"` for the open-ended bucket - fixed in
     `app/services/analytics.py`);
  2. a test-isolation bug where `test_cash_flow_router.py` and
     `test_kpis_router.py` both mutated the same `app.dependency_overrides`
     dict at import time, so whichever module pytest imported last would
     silently clobber the other's fake data depending on collection order -
     fixed by moving both to `@pytest.fixture(autouse=True)` setup/teardown.
  The suite was also re-run after restoring the sandbox's original global
  package versions (fastapi/pydantic/etc., which the first `pip install`
  had inadvertently downgraded) to confirm it isn't accidentally coupled to
  one exact dependency resolution.
- **core-api and frontend - reviewed by hand only, not compiled**: import
  consistency across every Java/TypeScript file, Spring
  Boot/Security/JPA/AOP wiring (bean names, `@PreAuthorize` expressions,
  JWT (jjwt 0.12.x) API usage, Hibernate UUID id generation, commons-csv
  `setIgnoreHeaderCase` header lookups, `AuthenticationEntryPoint` status
  codes), and TypeScript/React types (Axios client, Zustand store shape,
  Recharts prop types, a missing `ReactNode` import caught in
  `Reconciliation.tsx`). **Before merging, run `mvn test` in `core-api/`
  and `npm run build && npm test` in `frontend/` on a machine with those
  toolchains** - this is a real gap, not a formality.
- Test data in `analytics-service/tests/test_kpis_router.py` and
  `test_cash_flow_router.py` deliberately uses dates relative to
  `date.today()` / a wide trailing window rather than fixed calendar dates,
  specifically so the suite doesn't silently rot relative to "when someone
  actually runs it."

## Git history

This repo's git history starts with a single commit containing this
foundation (`git log --oneline` -> `Initial commit: Project Ledger
foundation - ...`), created with a repo-local (not global) identity:

```bash
git config user.name "Zentrox Engineering"
git config user.email "engineering@zentroxglobaltechnologies.com"
```

## Team / roles

See `docs/TEAM.md`. Only role IDs (`T2-LEAD`, `T2-FE1`, `T2-DATA1`,
`T2-DATA2`, `T2-BA1`, `T2-QA1`) are used anywhere in this repo - never real
names, per the BRD/FRD compliance rule.
