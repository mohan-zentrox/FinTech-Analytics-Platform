# Demo account credentials

Four accounts are created in the database automatically when the local stack
starts, covering every role and both admin personas. Use them to sign in at
http://localhost:5173 instead of registering by hand.

> ### These passwords are public
>
> They are committed to this repository, so treat them as public knowledge. They
> exist to make the demo stack usable in one step, nothing more.
>
> Seeding is **off by default**. `docker-compose.yml` switches it on because that
> file *is* the local demo stack; `render.yaml`, `deploy/cloudrun-deploy.sh` and
> the production compose profile all leave it off, so a deployed environment
> never gets these accounts unless someone deliberately sets
> `LEDGER_SEED_ENABLED=true`.

## The accounts

| Username  | Password             | Role    | Use it to see |
|-----------|----------------------|---------|---------------|
| `admin`   | `LedgerAdmin#2026`   | ADMIN   | Everything, including deletes and connector setup |
| `auditor` | `LedgerAudit#2026`   | ADMIN   | The compliance view — the Audit Trail screen |
| `analyst` | `LedgerAnalyst#2026` | ANALYST | Day-to-day work: import, reconcile, triage alerts, generate reports |
| `viewer`  | `LedgerViewer#2026`  | VIEWER  | Read-only access — a good way to watch RBAC take links away |

Emails are `<username>@projectledger.local`.

### What each role can reach

Signing in as `viewer` and then as `admin` is the quickest way to see the
role gating, which is enforced server-side and only mirrored in the menu.

| Screen | admin / auditor | analyst | viewer |
|---|:---:|:---:|:---:|
| Dashboard, Transactions (view), Reports (view/download) | ✅ | ✅ | ✅ |
| Transactions (create / import), Reconciliation, Alerts, Reports (generate), Connector sync | ✅ | ✅ | — |
| Transaction delete, Connector connect/disconnect, Audit Trail | ✅ | — | — |

## How it works

`ledger.seed.users` in `core-api/src/main/resources/application.yml` defines the
accounts; `config/DevDataSeeder.java` creates them once the application is up and
Flyway has run.

- **Idempotent** — an account whose username already exists is skipped, never
  overwritten. Restarting the stack will not reset a password you have changed,
  and a seeded account you deleted will not quietly come back.
- **Properly hashed** — passwords go through the same `PasswordEncoder` bean the
  login path verifies against, so a seeded account is indistinguishable from a
  registered one. No hashes are written by hand.
- **Never logged** — the seeder logs usernames and roles only.

## Using your own passwords

Every password has an environment-variable override, so a shared demo can run
these same four personas with credentials that are not in git:

```bash
SEED_ADMIN_PASSWORD=...
SEED_AUDITOR_PASSWORD=...
SEED_ANALYST_PASSWORD=...
SEED_VIEWER_PASSWORD=...
```

Set them in `.env` before the **first** start. Because seeding is idempotent,
changing a password after the account exists has no effect, and there is no
change-password endpoint (see README's "Known limitations"), so the only way to
rotate a seeded password is to reset the database: `docker compose down -v`.

## Turning seeding off

```bash
# in .env
LEDGER_SEED_ENABLED=false
```

Then register your first account through the UI's **Create one** link.

## Before any real deployment

These accounts are a local convenience and are not the security model.

Keep `LEDGER_SEED_ENABLED` unset or `false` — every deployment manifest in
`deploy/` already does. Note that `.env.example` ships it as `true` for the local
demo, so if you copied that file as the basis for a deployment, change it.

`POST /api/auth/register` used to let anyone create an account at any role
including ADMIN. It no longer does: self-service registration always produces a
`VIEWER`, and a privileged role requires an ADMIN token. The one exception is a
completely empty user table, which lets a seeding-disabled deployment bootstrap
its first administrator — see `docs/API.md` for the full matrix.
