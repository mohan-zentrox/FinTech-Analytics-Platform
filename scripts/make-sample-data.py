"""
Generates `sample-transactions.csv` for a first run of Project Ledger.

Dates are written relative to today so the file never goes stale: the dashboard's
trailing-6-month chart, the AR/AP aging buckets and the 90-day anomaly scan window
all look at recent activity, and a CSV with hard-coded 2026 dates would quietly
stop producing anything to look at.

The rows are shaped so every screen has something real in it:

  * two sources for account ACC-1 ("manual" = your ledger, "csv-import" = the bank
    feed) that mostly agree, so Reconciliation finds matches AND an exception
  * a large peer group of similar "supplies" amounts plus one huge round outlier,
    which trips both the AMOUNT_OUTLIER and ROUND_AMOUNT rules
  * the same payment entered twice a day apart, which trips DUPLICATE_PAYMENT
  * PENDING rows of varying age, which fill the AR/AP aging buckets
  * inflows and outflows across several months, which give the cash-flow chart
    and burn rate a shape

Usage:
    python scripts/make-sample-data.py            # writes ./sample-transactions.csv
    python scripts/make-sample-data.py out.csv    # writes somewhere else
"""
import csv
import sys
from datetime import date, timedelta

HEADER = [
    "source", "account", "amount", "currency", "postedDate",
    "description", "category", "status", "externalId",
]

ACCOUNT = "ACC-1"  # matches the account the Dashboard loads by default
TODAY = date.today()


def day(offset_days: int) -> str:
    """A date `offset_days` before today, ISO-formatted."""
    return (TODAY - timedelta(days=offset_days)).isoformat()


def row(source, amount, days_ago, description, category, status, external_id):
    return {
        "source": source,
        "account": ACCOUNT,
        "amount": f"{amount:.2f}",
        "currency": "USD",
        "postedDate": day(days_ago),
        "description": description,
        "category": category,
        "status": status,
        "externalId": external_id,
    }


def build_rows() -> list[dict]:
    rows: list[dict] = []

    # --- Revenue across the last five months: gives the cash-flow chart a shape.
    for i, days_ago in enumerate([150, 120, 90, 60, 30, 5]):
        rows.append(row("manual", 12000 + i * 1500, days_ago,
                        f"Customer invoice {2001 + i}", "revenue", "POSTED", f"INV-{2001 + i}"))

    # --- Recurring costs, same period.
    for i, days_ago in enumerate([148, 118, 88, 58, 28, 3]):
        rows.append(row("manual", -4200, days_ago,
                        "Monthly office rent", "opex", "POSTED", f"RENT-{i + 1}"))
        rows.append(row("manual", -3100 - i * 90, days_ago - 1,
                        "Payroll run", "payroll", "POSTED", f"PAY-{i + 1}"))

    # --- A peer group of similar "supplies" spend. The anomaly rule needs at least
    #     8 comparable transactions before it will score anything, so this is what
    #     makes the outlier below meaningful rather than noise.
    for i in range(10):
        rows.append(row("manual", -(180 + i * 12), 70 - i * 6,
                        f"Office supplies order {i + 1}", "supplies", "POSTED", f"SUP-{i + 1}"))

    # --- The outlier: same category, wildly different size, and suspiciously round.
    #     Trips AMOUNT_OUTLIER and ROUND_AMOUNT together.
    rows.append(row("manual", -50000, 12,
                    "Consulting retainer", "supplies", "POSTED", "SUP-BIG"))

    # --- The same invoice paid twice, one day apart. Trips DUPLICATE_PAYMENT.
    #     Note the descriptions differ in punctuation and case - the rule normalises
    #     them, which is the point.
    rows.append(row("manual", -2500, 9, "Acme Corp invoice 88", "opex", "POSTED", "ACME-A"))
    rows.append(row("manual", -2500, 8, "ACME CORP - Invoice #88!", "opex", "POSTED", "ACME-B"))

    # --- Open items of varying age: these fill the AR/AP aging buckets, which only
    #     count PENDING (and FLAGGED) rows. Positive = receivable, negative = payable.
    rows.append(row("manual", 4800, 10, "Invoice 3001 - awaiting payment", "revenue", "PENDING", "AR-1"))
    rows.append(row("manual", 2650, 45, "Invoice 2990 - overdue", "revenue", "PENDING", "AR-2"))
    rows.append(row("manual", 1900, 75, "Invoice 2975 - chasing", "revenue", "PENDING", "AR-3"))
    rows.append(row("manual", 3300, 120, "Invoice 2940 - escalated", "revenue", "PENDING", "AR-4"))
    rows.append(row("manual", -1450, 15, "Supplier bill 771", "opex", "PENDING", "AP-1"))
    rows.append(row("manual", -980, 50, "Supplier bill 742", "opex", "PENDING", "AP-2"))
    rows.append(row("manual", -2100, 100, "Supplier bill 690", "opex", "PENDING", "AP-3"))

    # --- The "bank statement" side, for Reconciliation. Each line mirrors a ledger
    #     entry a day later and a cent or two off, which is exactly what the default
    #     tolerances (0.01 amount, 2 days) are designed to match through.
    bank = [
        (12000, 149, "BANK-1", "Deposit 2001"),
        (13500, 119, "BANK-2", "Deposit 2002"),
        (15000, 89, "BANK-3", "Deposit 2003"),
        (-4200, 147, "BANK-4", "Rent debit"),
        (-4200, 117, "BANK-5", "Rent debit"),
        (-3100, 146, "BANK-6", "Payroll debit"),
    ]
    for amount, days_ago, external_id, description in bank:
        # One cent of drift, so the match is a real tolerance match, not an exact one.
        drift = 0.01 if amount > 0 else -0.01
        rows.append(row("csv-import", amount + drift, days_ago, description,
                        "bank", "POSTED", external_id))

    # --- One bank line with no ledger counterpart: this is the reconciliation
    #     EXCEPTION, which is the row an analyst actually has to do something about.
    rows.append(row("csv-import", -775.40, 20, "Unidentified card settlement",
                    "bank", "POSTED", "BANK-ORPHAN"))

    return rows


def main() -> None:
    out_path = sys.argv[1] if len(sys.argv) > 1 else "sample-transactions.csv"
    rows = build_rows()

    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=HEADER)
        writer.writeheader()
        writer.writerows(rows)

    inflow = sum(float(r["amount"]) for r in rows if float(r["amount"]) > 0)
    outflow = -sum(float(r["amount"]) for r in rows if float(r["amount"]) < 0)
    print(f"Wrote {len(rows)} transactions to {out_path}")
    print(f"  account:  {ACCOUNT}")
    print(f"  period:   {day(150)} to {day(3)}")
    print(f"  inflow:   {inflow:,.2f}")
    print(f"  outflow:  {outflow:,.2f}")
    print("\nImport it from the Transactions screen, then try Reconciliation and Alerts.")


if __name__ == "__main__":
    main()
