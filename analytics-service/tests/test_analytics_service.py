"""
Unit tests for app/services/analytics.py. These build DataFrames by hand -
no database connection is required, so this suite runs standalone with
`pytest` against any Python 3.11 environment that has requirements.txt
installed.
"""
from datetime import date

import pandas as pd
import pytest

from app.services.analytics import compute_cash_flow, compute_kpis

TRANSACTION_COLUMNS = [
    "id", "source", "account", "amount", "currency", "posted_date",
    "description", "category", "status", "external_id",
]


def make_df(rows: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(rows, columns=TRANSACTION_COLUMNS)
    if not df.empty:
        df["posted_date"] = pd.to_datetime(df["posted_date"])
        df["amount"] = df["amount"].astype(float)
    return df


def row(amount, posted_date, status="POSTED", account="ACC-1"):
    return {
        "id": "id", "source": "manual", "account": account, "amount": amount,
        "currency": "USD", "posted_date": posted_date, "description": "",
        "category": "", "status": status, "external_id": "ext",
    }


class TestComputeCashFlow:
    def test_empty_dataframe_returns_zeroed_months(self):
        df = make_df([])
        series = compute_cash_flow(df, months=3, as_of=date(2026, 3, 15))

        assert [m["month"] for m in series] == ["2026-01", "2026-02", "2026-03"]
        assert all(m["inflow"] == 0.0 and m["outflow"] == 0.0 and m["net"] == 0.0 for m in series)

    def test_aggregates_inflow_outflow_net_per_month(self):
        df = make_df([
            row(1000.0, "2026-01-05"),
            row(-300.0, "2026-01-10"),
            row(500.0, "2026-02-01"),
        ])
        series = compute_cash_flow(df, months=2, as_of=date(2026, 2, 28))

        by_month = {m["month"]: m for m in series}
        assert by_month["2026-01"]["inflow"] == 1000.0
        assert by_month["2026-01"]["outflow"] == 300.0
        assert by_month["2026-01"]["net"] == 700.0
        assert by_month["2026-02"]["inflow"] == 500.0
        assert by_month["2026-02"]["outflow"] == 0.0

    def test_excludes_transactions_outside_the_window(self):
        df = make_df([
            row(999.0, "2025-01-01"),  # far outside a 2-month trailing window ending 2026-02
        ])
        series = compute_cash_flow(df, months=2, as_of=date(2026, 2, 28))

        assert sum(m["inflow"] for m in series) == 0.0


class TestComputeKpis:
    def test_buckets_open_receivables_by_age(self):
        as_of = date(2026, 3, 15)
        df = make_df([
            row(100.0, "2026-03-10", status="PENDING"),   # 5 days old -> 0-30
            row(200.0, "2026-01-20", status="PENDING"),   # ~54 days old -> 31-60
            row(300.0, "2025-11-01", status="PENDING"),   # >90 days old -> 90+
            row(400.0, "2026-03-10", status="POSTED"),    # not open, excluded
        ])
        result = compute_kpis(df, as_of=as_of)

        assert result["ar_aging"]["0-30"] == 100.0
        assert result["ar_aging"]["31-60"] == 200.0
        assert result["ar_aging"]["90+"] == 300.0
        assert result["ap_aging"]["0-30"] == 0.0

    def test_buckets_open_payables_by_age(self):
        as_of = date(2026, 3, 15)
        df = make_df([
            row(-150.0, "2026-03-01", status="PENDING"),  # 14 days -> 0-30
            row(-250.0, "2026-01-05", status="FLAGGED"),  # ~69 days -> 61-90
        ])
        result = compute_kpis(df, as_of=as_of)

        assert result["ap_aging"]["0-30"] == 150.0
        assert result["ap_aging"]["61-90"] == 250.0

    def test_burn_rate_reflects_trailing_three_month_net_outflow(self):
        as_of = date(2026, 3, 31)
        df = make_df([
            row(-900.0, "2026-01-15"),
            row(-900.0, "2026-02-15"),
            row(-900.0, "2026-03-15"),
        ])
        result = compute_kpis(df, as_of=as_of)

        assert result["burn_rate"] == pytest.approx(900.0)
        assert result["burn_rate_period_months"] == 3

    def test_empty_dataframe_produces_zeroed_kpis(self):
        result = compute_kpis(make_df([]), as_of=date(2026, 3, 15))

        assert result["burn_rate"] == 0.0
        assert all(v == 0.0 for v in result["ar_aging"].values())
        assert all(v == 0.0 for v in result["ap_aging"].values())
