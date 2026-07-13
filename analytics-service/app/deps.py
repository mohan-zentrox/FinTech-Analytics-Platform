"""
FastAPI dependency providers, isolated from app/db.py so tests can swap in
an in-memory DataFrame provider via `app.dependency_overrides` instead of
needing a live Postgres connection (see tests/test_cash_flow_router.py and
tests/test_kpis_router.py).

Routers depend on `get_transactions_provider` (a *factory*, not the data
itself) so a single override point yields a callable the route handler then
invokes with the requested accountId - this avoids FastAPI trying to
resolve a nested `account_id` query parameter that would collide with the
route's own `accountId` parameter.
"""
from typing import Callable

import pandas as pd

from app.db import fetch_transactions

AccountTransactionsProvider = Callable[[str], pd.DataFrame]


def get_account_transactions(account_id: str) -> pd.DataFrame:
    """
    Fetches the full transaction history for an account. Callers
    (routers) do their own date-window filtering via
    app.services.analytics, which keeps this dependency simple and cheap
    to override in tests.
    """
    return fetch_transactions(account_id)


def get_transactions_provider() -> AccountTransactionsProvider:
    return get_account_transactions
