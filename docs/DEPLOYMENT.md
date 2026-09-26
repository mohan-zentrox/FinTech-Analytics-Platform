# Deployment

Where to run Project Ledger for free, and what each option actually costs you in
limitations. Every claim below was checked in September 2026 — free tiers move,
so re-verify before committing.

The stack is three deployables plus Postgres:

| Component | Needs | Idle behaviour |
|---|---|---|
| `frontend` | static file hosting | none — it's just files |
| `core-api` | ~400 MB container, JVM | cold start ~10-15s from zero |
| `analytics-service` | ~250 MB container, Python | cold start ~2s from zero |
| Postgres | 0.5 GB is plenty to start | — |

---

## TL;DR — the recommended free stack

**Neon (database) + Google Cloud Run (both services) + Cloudflare Pages (frontend).**

Nothing here expires, nothing needs a card beyond GCP's standard verification, and
the whole thing costs $0 at low traffic.

| Layer | Provider | Free allowance | Catch |
|---|---|---|---|
| Postgres | [Neon](https://neon.com/docs/introduction/plans) | 0.5 GB storage, 100 compute-hours/project/month, permanent | Compute sleeps after 5 min idle; 6-hour restore window only |
| core-api + analytics | [Cloud Run](https://cloud.google.com/run/pricing) | 2M requests, 180k vCPU-s, 360k GiB-s per month, per **account** | Always-free only in `us-east1`, `us-west1`, `us-central1` |
| frontend | Cloudflare Pages / Vercel / Netlify | unlimited static hosting | — |

Use `deploy/cloudrun-deploy.sh`, which sets `--min-instances=0` (scale to zero is
what keeps you inside the free allowance) and wires secrets from Secret Manager.

### Why not the obvious alternatives

- **Render's free Postgres expires 30 days after creation** and is then deleted
  after a 14-day grace period ([docs](https://render.com/docs/free)). Fine for a
  demo, unusable as a ledger's home. `render.yaml` here therefore deploys only the
  three services and expects an external database.
- **Koyeb** closed its free tier to new sign-ups after the Mistral AI acquisition
  in February 2026.
- **Hugging Face Spaces** now requires a paid plan to create a Docker Space; only
  static Spaces remain free.
- **Fly.io** no longer has a standing free allowance — new organisations get trial
  credit. `deploy/fly-core-api.toml` is included as the cheapest *always-on* option,
  not a free one.

---

## Option A — Cloud Run + Neon (recommended)

1. **Database.** Create a Neon project, then a database named `ledger`. Copy the
   connection string.
2. **Secrets.** Create these in Secret Manager:

   ```bash
   printf 'jdbc:postgresql://ep-xxx.aws.neon.tech/ledger?sslmode=require' | \
     gcloud secrets create ledger-database-url --data-file=-
   openssl rand -base64 48 | tr -d '\n' | gcloud secrets create ledger-jwt-secret --data-file=-
   openssl rand -base64 32 | tr -d '\n' | gcloud secrets create ledger-connector-key --data-file=-
   # plus: ledger-db-user, ledger-db-password, ledger-db-host, ledger-db-name,
   #       ledger-analytics-db-user, ledger-analytics-db-password
   ```

3. **Deploy.** `PROJECT_ID=my-project REGION=us-central1 ./deploy/cloudrun-deploy.sh`
4. **Frontend.** Build with the two service URLs and publish `frontend/dist`:

   ```bash
   cd frontend
   VITE_CORE_API_BASE_URL=https://ledger-core-api-xxx.run.app/api \
   VITE_ANALYTICS_API_BASE_URL=https://ledger-analytics-service-xxx.run.app/analytics \
     npm run build
   npx wrangler pages deploy dist        # or: vercel deploy --prod / netlify deploy --prod
   ```

5. **CORS.** Set `CORS_ALLOW_ORIGINS` on analytics-service to the frontend's exact
   origin. See the CORS note below — this is the single most common thing to get
   wrong.

## Option B — Render (fastest to click through)

`render.yaml` is a Blueprint: point Render at the repo and it creates all three
services. Supply `DATABASE_URL` and friends from a Neon/Supabase project.

Free-plan realities: services sleep after 15 minutes idle (first request then pays
a ~30s JVM cold start), and 750 instance-hours/month are shared across the whole
workspace. The static frontend does not sleep and does not consume hours.

## Option C — one always-free VM (no sleep, no cold starts)

Oracle Cloud's Always Free ARM allowance (2 OCPU / 12 GB as of June 2026, halved
from 4/24) runs the entire stack including Postgres with room to spare:

```bash
cp .env.example .env     # set every change-me value
docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml up -d --build
```

Put Caddy or nginx in front for TLS; the compose override binds every service to
`127.0.0.1` only. This is the one option where **the scheduled monthly report job
actually fires**, because nothing scales to zero — hence
`REPORTS_SCHEDULE_ENABLED` defaults to `true` there and `false` everywhere else.

---

## Configuration that bites

### The two services must share `JWT_SECRET`

analytics-service has no login of its own: it verifies the same HS256 tokens
core-api issues. If the two values differ, every dashboard read returns 401 while
core-api looks perfectly healthy.

Both sides pin the algorithm to HS256. core-api does this explicitly because jjwt
otherwise picks the HMAC variant from the *key length* — a 64-byte secret would
silently produce HS512 tokens that analytics rejects.

### CORS must name the frontend's exact origin

`CORS_ALLOW_ORIGINS` on analytics-service is an exact-match list.
`http://localhost:5173` does **not** match `http://127.0.0.1:5173`, and
`https://app.example.com` does not match `https://www.app.example.com`. A mismatch
shows up in the UI as "Failed to load analytics. Is the analytics-service running?"
while the service is in fact running and healthy — check the browser console for
the CORS error before debugging the backend.

core-api allows all origins by design (it is a token-authenticated API), so a CORS
symptom is almost always analytics-service.

### The read-only database role

core-api's Flyway migrations create `ledger_readonly` and grant it SELECT on
exactly four tables (`transactions`, `audit_log`, `reconciliation_match`,
`app_user`) — deliberately not `connector_credential`, `report_run` or
`fraud_alert`.

Managed Postgres often forbids `CREATE ROLE`. The migration detects that, logs a
NOTICE and continues rather than failing startup — so point
`ANALYTICS_DB_USER`/`ANALYTICS_DB_PASSWORD` at provider-issued read-only
credentials instead. Never point analytics-service at the owner role.

### Connection pooling on free Postgres

Free tiers cap connections hard. `DB_POOL_MAX` defaults to 5 and should be 3 or
less when two scale-to-zero services can each spin up several instances. With
Supabase, use the pooler endpoint (port 6543), not the direct one.

### Scheduled reports and scale-to-zero

A cron trigger inside a sleeping container never fires. On Cloud Run/Render, leave
`REPORTS_SCHEDULE_ENABLED=false` and drive generation externally — Cloud Scheduler
or a GitHub Actions cron calling `POST /api/reports/generate` with an admin token.

### Storage growth

Generated reports are stored as `BYTEA` in `report_run` (no object storage needed,
which is what keeps this free-tier deployable). On Neon's 0.5 GB that is fine at
tens of KB per report, but set `REPORTS_RETENTION_DAYS` so the table is pruned.

---

## First run

```bash
# 1. Create the first admin
curl -X POST https://<core-api>/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","email":"admin@example.com","password":"<strong-password>","role":"ADMIN"}'
```

Registration currently accepts a `role` for bootstrap convenience. **Restrict
`POST /api/auth/register` to admins before real use** — see `AuthController`.

```bash
# 2. Verify the whole platform end to end
python scripts/e2e-smoke-test.py     # against a fresh local stack
```

## Health checks

| Service | Path | Notes |
|---|---|---|
| core-api | `/actuator/health` | unauthenticated; other actuator endpoints are not exposed |
| analytics-service | `/health` | unauthenticated; reports whether auth is enforced |

Point your platform's health check at these. Both Dockerfiles also declare a
container `HEALTHCHECK`, which is what `docker compose` waits on so
analytics-service never starts before core-api's migrations have created its role.
