"""
Pure pandas computations for the analytics endpoints. Deliberately free of
FastAPI/SQLAlchemy concerns - every function here takes a DataFrame (with
the TRANSACTION_COLUMNS shape from app.db) and returns plain
dict/list-of-dict structures ready for JSON serialization, so pytest can
exercise them directly against hand-built DataFrames with no database
(see analytics-service/tests/test_analytics_service.py).

Referenced by:
  - app/routers/cash_flow.py -> GET /analytics/cash-flow
  - app/routers/kpis.py      -> GET /analytics/kpis
"""
from datetime import date
from typing import Optional

import pandas as pd

AGING_BUCKETS = [(0, 30), (31, 60), (61, 90), (91, None)]


def compute_cash_flow(df: pd.DataFrame, months: int, as_of: Optional[date] = None) -> list[dict]:
    """
    Monthly inflow/outflow/net for the trailing `months` calendar months
    (inclusive of the current month), regardless of whether every month has
    transactions - missing months are filled with zeros so the frontend
    chart has a continuous x-axis.
    """
    as_of = as_of or date.today()
    period_end = pd.Period(as_of, freq="M")
    period_start = period_end - (months - 1)
    all_periods = pd.period_range(start=period_start, end=period_end, freq="M")

    if df.empty:
        working = pd.DataFrame(columns=["period", "amount"])
    else:
        working = df.copy()
        working["period"] = working["posted_date"].dt.to_period("M")
        working = working[(working["period"] >= period_start) & (working["period"] <= period_end)]

    working["inflow"] = working["amount"].where(working["amount"] > 0, 0.0) if not working.empty else 0.0
    working["outflow"] = (-working["amount"]).where(working["amount"] < 0, 0.0) if not working.empty else 0.0

    if working.empty:
        grouped = pd.DataFrame({"inflow": [], "outflow": []}, index=pd.PeriodIndex([], freq="M"))
    else:
        grouped = working.groupby("period")[["inflow", "outflow"]].sum()

    grouped = grouped.reindex(all_periods, fill_value=0.0)

    result = []
    for period, row in grouped.iterrows():
        inflow = round(float(row["inflow"]), 2)
        outflow = round(float(row["outflow"]), 2)
        result.append({
            "month": str(period),  # "YYYY-MM"
            "inflow": inflow,
            "outflow": outflow,
            "net": round(inflow - outflow, 2),
        })
    return result


def _bucket_label(lo: int, hi: Optional[int]) -> str:
    return f"{lo}-{hi}" if hi is not None else "90+"


def _aging_buckets(df: pd.DataFrame, as_of: date) -> dict[str, float]:
    buckets = {_bucket_label(lo, hi): 0.0 for lo, hi in AGING_BUCKETS}
    if df.empty:
        return buckets

    age_days = (pd.Timestamp(as_of) - df["posted_date"]).dt.days
    for lo, hi in AGING_BUCKETS:
        label = _bucket_label(lo, hi)
        mask = (age_days >= lo) if hi is None else (age_days >= lo) & (age_days <= hi)
        buckets[label] = round(float(df.loc[mask, "amount"].abs().sum()), 2)
    return buckets


def compute_kpis(df: pd.DataFrame, as_of: Optional[date] = None) -> dict:
    """
    - ar_aging: open (status != POSTED/RECONCILED... here: PENDING) receivables
      (positive amount) bucketed by age since posted_date.
    - ap_aging: open payables (negative amount), same buckets.
    - burn_rate: average trailing-3-month net cash outflow
      (outflow - inflow); positive = spending more than receiving,
      negative = net cash positive over the period.
    """
    as_of = as_of or date.today()

    open_statuses = {"PENDING", "FLAGGED"}
    open_df = df[df["status"].isin(open_statuses)] if not df.empty else df

    receivables = open_df[open_df["amount"] > 0] if not open_df.empty else open_df
    payables = open_df[open_df["amount"] < 0] if not open_df.empty else open_df

    ar_aging = _aging_buckets(receivables, as_of)
    ap_aging = _aging_buckets(payables, as_of)

    cash_flow = compute_cash_flow(df, months=3, as_of=as_of)
    nets = [m["outflow"] - m["inflow"] for m in cash_flow]
    burn_rate = round(sum(nets) / len(nets), 2) if nets else 0.0

    return {
        "as_of": as_of.isoformat(),
        "ar_aging": ar_aging,
        "ap_aging": ap_aging,
        "burn_rate": burn_rate,
        "burn_rate_period_months": 3,
    }
