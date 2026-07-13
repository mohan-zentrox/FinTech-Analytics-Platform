"""
Read-only data access layer. All transaction reads go through
`fetch_transactions`, which returns a pandas DataFrame with the canonical
column set used throughout app/services/analytics.py.

Kept deliberately thin (SQLAlchemy Core, not an ORM) since this service only
ever SELECTs - all writes happen in core-api. The engine is created lazily
so importing this module (e.g. from tests) never requires a live database.
"""
from datetime import date
from functools import lru_cache
from typing import Optional

import pandas as pd
from sqlalchemy import create_engine
from sqlalchemy.engine import Engine

from app.core.config import get_settings

TRANSACTION_COLUMNS = [
    "id", "source", "account", "amount", "currency", "posted_date",
    "description", "category", "status", "external_id",
]


@lru_cache
def get_engine() -> Engine:
    settings = get_settings()
    return create_engine(settings.database_url, pool_pre_ping=True)


def fetch_transactions(
    account_id: str,
    date_from: Optional[date] = None,
    date_to: Optional[date] = None,
) -> pd.DataFrame:
    """Fetch transactions for one account (optionally bounded by posted_date) as a DataFrame."""
    query = """
        SELECT id, source, account, amount, currency, posted_date,
               description, category, status, external_id
        FROM transactions
        WHERE account = %(account_id)s
          AND (%(date_from)s IS NULL OR posted_date >= %(date_from)s)
          AND (%(date_to)s IS NULL OR posted_date <= %(date_to)s)
        ORDER BY posted_date
    """
    params = {"account_id": account_id, "date_from": date_from, "date_to": date_to}
    with get_engine().connect() as conn:
        df = pd.read_sql(query, conn, params=params)
    return _normalize(df)


def _normalize(df: pd.DataFrame) -> pd.DataFrame:
    if df.empty:
        return pd.DataFrame(columns=TRANSACTION_COLUMNS)
    df["posted_date"] = pd.to_datetime(df["posted_date"])
    df["amount"] = df["amount"].astype(float)
    return df
