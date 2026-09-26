# Getting Started with Project Ledger

How to run the platform on your own machine and use every screen in it. No prior
knowledge of the codebase needed.

**Time to first login: about 5 minutes**, most of it waiting for the first build.

---

## 1. What you need

Just **Docker Desktop** — it brings everything else (Java, Python, Node, PostgreSQL)
with it.

- [Install Docker Desktop](https://www.docker.com/products/docker-desktop/) and make
  sure it is running (whale icon in your tray/menu bar).
- Optional, only for the sample data in step 4: Python 3.

Check it works:

```bash
docker --version
```

---

## 2. Start the platform

From the project folder:

```bash
# 1. Create your settings file from the template
cp .env.example .env
```

Now **open `.env` and change these four values.** The defaults are placeholders and
the app is not safe to expose with them:

| Setting | What to put |
|---|---|
| `JWT_SECRET` | any random string of **48+ characters** |
| `CONNECTOR_ENCRYPTION_KEY` | any random string of **32+ characters** |
| `DB_PASSWORD` | any password you like |
| `ANALYTICS_DB_PASSWORD` | a **different** password |

If you have `openssl`, generate the two secrets properly:

```bash
openssl rand -base64 48    # paste into JWT_SECRET
openssl rand -base64 32    # paste into CONNECTOR_ENCRYPTION_KEY
```

Then start everything:

```bash
docker compose up -d --build --wait
```

**The first run takes 4–6 minutes** — it is compiling the Java service and building
the frontend inside containers. Later starts take about 30 seconds.

The command returns once all four services report **healthy**, which is your signal
that it is ready. They keep running in the background after you close the terminal;
see step 8 for how to stop them.

If you would rather watch the logs scroll by, use `docker compose up --build`
instead and leave that terminal open.

---

## 3. Open the app

| What | Where |
|---|---|
| **The application** | **http://localhost:5173** |
| Core API | http://localhost:8080/api |
| Analytics API (interactive docs) | http://localhost:8000/docs |
| Database | localhost:5432 |

> ### ⚠️ Use `localhost`, not `127.0.0.1`
>
> They look interchangeable but the browser treats them as different origins, and
> the analytics service is configured to accept only `http://localhost:5173`. Open
> it at `127.0.0.1` and the Dashboard will show *"Failed to load analytics"* even
> though everything is running perfectly.
>
> If you need a different address, change `CORS_ALLOW_ORIGINS` in `.env` to match
> it exactly and restart.

### Create your account

There are no default users — you make the first one.

1. Go to http://localhost:5173
2. Click **Create one** under the sign-in box
3. Fill it in and choose the **Admin** role so you can see every screen
   - password must be at least 8 characters
4. You are signed in and land on the Dashboard

> Anyone can currently self-register at any role, which is fine for a local trial
> but must be locked down before real use. See `docs/DEPLOYMENT.md`.

### Changing the ports

If any of those ports are already taken, create a **`docker-compose.override.yml`**
next to `docker-compose.yml`. Compose reads it automatically, so `docker compose up`
keeps working unchanged, and it is gitignored so your local choices stay local.

```yaml
services:
  postgres:
    ports: !override ["15432:5432"]
  core-api:
    ports: !override ["18080:8080"]
  analytics-service:
    ports: !override ["18000:8000"]
    environment:
      # Must match the frontend origin exactly or the browser blocks analytics.
      CORS_ALLOW_ORIGINS: http://localhost:15173
  frontend:
    ports: !override ["15173:80"]
    environment:
      # The image bakes the default API URLs in at build time; these override them
      # at container start, so the bundle does not need rebuilding.
      CORE_API_BASE_URL: http://localhost:18080/api
      ANALYTICS_API_BASE_URL: http://localhost:18000/analytics
```

Then open the app at your frontend port instead — `http://localhost:15173` for the
example above. The last two settings are the part people miss: move core-api and the
frontend still looks for it on 8080 until you tell it otherwise.

---

## 4. Load some sample data (recommended)

A brand-new database is empty, so every chart will be blank. This generates a
realistic month-by-month history for account `ACC-1` — the account the Dashboard
opens by default:

```bash
python scripts/make-sample-data.py
```

That writes `sample-transactions.csv`: 45 transactions across the last five months,
including a couple of deliberate problems for the anomaly detector to find.

To load it:

1. Go to **Transactions**
2. Click **Import CSV** (top right) and choose `sample-transactions.csv`
3. You should see **Imported 45, skipped 0, errored 0**

Now go back to the **Dashboard** and everything will have filled in.

> **Try this:** import the same file a second time. It will report *imported 0,
> skipped 45* — the platform de-duplicates on `(source, externalId)`, so
> re-importing an overlapping export can never double-count your money.

---

## 5. A tour of the screens

### Dashboard
Your financial overview for one account. Type an account ID and press **Load** to
switch (the sample data is all on `ACC-1`).

- **Burn rate** — average monthly net outflow over the last three months
- **Open receivables / payables** — money owed to and by you
- **Open anomaly alerts** — how many findings are waiting for review
- **Cash flow chart** — inflow, outflow and net per month
- **AR / AP aging** — how overdue the open items are, in 30-day buckets
- **Top statistical anomalies** — the most suspicious things found

### Transactions
Every ledger entry, searchable. Filter by account, date range, amount range and
status; filters apply as you type. **Import CSV** is here.

<details>
<summary>CSV format, if you want to bring your own data</summary>

Required columns: `source`, `account`, `amount`, `currency`, `postedDate`, `externalId`
Optional columns: `description`, `category`, `status`

- `postedDate` must be `YYYY-MM-DD`
- `amount` is positive for money in, negative for money out
- `status` defaults to `PENDING`; others are `POSTED`, `RECONCILED`, `FLAGGED`, `VOID`
- `source` names where the row came from (e.g. `manual`, `csv-import`, `quickbooks`)
- `externalId` must be unique *within a source* — this is what makes re-imports safe

Bad rows are reported individually and do not stop the rest of the file importing.
</details>

### Reconciliation
Matches two sets of transactions against each other — typically your ledger against
a bank statement — allowing for small differences in amount and date.

**To try it with the sample data:**

| Field | Value |
|---|---|
| Account A / Account B | `ACC-1` |
| Source A | `manual` |
| Source B | `csv-import` |
| Date from | a date ~6 months ago |
| Date to | today |
| Amount tolerance | `0.01` |
| Date tolerance | `2` |

Click **Run reconciliation**. You will get **6 matched pairs** — entries that differ
by a cent and a day or two, which is exactly the drift real bank feeds have — plus
exceptions on both sides. Look for `Unidentified card settlement` in the Set B
exceptions: a bank line with nothing in the ledger to explain it, which is precisely
the sort of thing reconciliation exists to surface.

### Alerts
The anomaly review queue. Click **Run scan** and it examines the last 90 days for:

| Rule | What it catches |
|---|---|
| Duplicate payment | The same amount and description paid twice within a week |
| Amount outlier | An amount far outside the norm for its account and category |
| Velocity spike | A day with unusually many transactions |
| Round amount | A large, suspiciously round figure |

With the sample data you will get findings from three of these, including the
`Acme Corp invoice 88` paid twice and a $50,000 "Consulting retainer" that is both a
statistical outlier and suspiciously round.

Each alert has **Confirm** (it is a real problem) and **Dismiss** (it is fine).

> **Try this:** dismiss an alert, then click **Run scan** again. It reports the
> alert as *suppressed* and leaves your decision alone. A re-scan refreshes open
> findings but never overrules a human — otherwise every scheduled scan would undo
> the whole review queue.

### Reports
Generates real PDF and Excel documents.

1. Pick a report: **Cash flow statement**, **Reconciliation exceptions** or **Anomaly alerts**
2. Pick **PDF** or **XLSX**
3. Set account `ACC-1` and a period covering the sample data
4. Click **Generate**, then **Download** from the history table

Leaving the period blank reports on the previous whole calendar month. The Excel
output has real numbers in the cells (not text), so it works with formulas and pivot
tables straight away.

### Connectors
Connects accounting systems — QuickBooks, Xero, NetSuite — over OAuth.

You will see an amber **sandbox mode** banner. That means no real provider
credentials are configured, so the platform simulates the whole handshake and a
transaction feed locally. This lets you walk the entire flow without signing up for
a developer account anywhere:

1. Leave **Ledger account to feed** as `ACC-1`
2. Click **Connect** on `quickbooks` — you will be sent away and come straight back, connected
3. Click **Sync** — it imports 10 simulated transactions
4. Click **Sync** again — it imports **0** and skips 10, because they are already there

To connect for real, put your provider's client ID and secret in `.env` and set
`CONNECTORS_SANDBOX=false`.

### Audit (Admin only)
An append-only record of every change anyone made: who, what, when, to which record.
Nothing in the application ever edits or deletes these rows. Filter by entity, actor
or action to trace a specific change.

---

## 6. Who can see what

Roles are enforced by the server, not just hidden in the menu.

| Screen | Admin | Analyst | Viewer |
|---|:---:|:---:|:---:|
| Dashboard | ✅ | ✅ | ✅ |
| Transactions (view) | ✅ | ✅ | ✅ |
| Transactions (create / edit / import) | ✅ | ✅ | — |
| Transactions (delete) | ✅ | — | — |
| Reconciliation | ✅ | ✅ | — |
| Alerts (scan and triage) | ✅ | ✅ | — |
| Reports (view and download) | ✅ | ✅ | ✅ |
| Reports (generate) | ✅ | ✅ | — |
| Connectors (sync) | ✅ | ✅ | — |
| Connectors (connect / disconnect) | ✅ | — | — |
| Audit trail | ✅ | — | — |

To see this working, create a second account with the **Viewer** role and sign in as
them — the Alerts, Reconciliation, Connectors and Audit links disappear.

---

## 7. If something goes wrong

| Symptom | Cause and fix |
|---|---|
| **"port is already allocated"** on startup | Something else on your machine is using 5173, 8080, 8000 or 5432. See "Changing the ports" below — do not edit `docker-compose.yml`. |
| **Dashboard says "Failed to load analytics"** | Almost always the `localhost` vs `127.0.0.1` issue — see the warning in step 3. Check your browser's address bar. |
| **Everything returns "session expired" / you keep getting logged out** | `JWT_SECRET` differs between the two services, or you changed it while signed in. Sign in again; if it persists, confirm there is only one `JWT_SECRET` line in `.env`. |
| **The app won't start after editing `.env`** | Restart it: `docker compose down` then `docker compose up`. Changes to `.env` are only picked up on start. |
| **Charts are empty** | The account has no data for that period. The sample data is on `ACC-1` — check the account box on the Dashboard. |
| **First build seems stuck** | It isn't — downloading Java and Node dependencies takes several minutes on the first run only. |

To see what a service is actually doing:

```bash
docker compose logs core-api           # or: analytics-service, frontend, postgres
docker compose ps                      # is everything healthy?
```

---

## 8. Stopping and starting

```bash
docker compose stop          # stop, keep your data
docker compose start         # start again
docker compose down          # stop and remove the containers, keep your data
docker compose down -v       # ⚠️ also deletes the database - full reset
```

After a `down -v` you start over from step 3, including creating your account again.

---

## 9. Check everything works

To confirm the whole platform is functioning — every endpoint, both services, the
security rules, real PDF and Excel output:

```bash
docker compose down -v        # the check needs a clean database
docker compose up -d --build
python scripts/e2e-smoke-test.py
```

It runs 96 checks and prints a pass/fail line for each. All 96 should pass.

---

## Where to go next

| Document | What's in it |
|---|---|
| [`README.md`](README.md) | What the platform does, and how to run the test suites |
| [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) | Hosting it online for free, and what to lock down first |
| [`docs/API.md`](docs/API.md) | Every REST endpoint, for building against it |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | How the three services fit together and why |
