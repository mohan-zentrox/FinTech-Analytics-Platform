"""
HTTP-contract tests for GET /analytics/cash-flow. The real Postgres-backed
dependency (app.deps.get_transactions_provider) is swapped for an in-memory
fake via FastAPI's dependency_overrides, so this suite needs no database.
"""
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.deps import get_transactions_provider
from app.main import app

TRANSACTION_COLUMNS = [
    "id", "source", "account", "amount", "currency", "posted_date",
    "description", "category", "status", "external_id",
]


def fake_df():
    df = pd.DataFrame([
        {"id": "1", "source": "manual", "account": "ACC-1", "amount": 1000.0, "currency": "USD",
         "posted_date": "2026-06-01", "description": "", "category": "", "status": "POSTED", "external_id": "e1"},
        {"id": "2", "source": "manual", "account": "ACC-1", "amount": -400.0, "currency": "USD",
         "posted_date": "2026-06-15", "description": "", "category": "", "status": "POSTED", "external_id": "e2"},
    ], columns=TRANSACTION_COLUMNS)
    df["posted_date"] = pd.to_datetime(df["posted_date"])
    df["amount"] = df["amount"].astype(float)
    return df


def override_provider():
    return lambda account_id: fake_df()


@pytest.fixture(autouse=True)
def _override_transactions_provider():
    # Set up (and, crucially, tear down) the override per-test rather than
    # once at import time - app.dependency_overrides is a single dict on
    # the shared `app` singleton, so a module-level assignment here would
    # leak into (and be clobbered by) test_kpis_router.py depending on
    # pytest's collection order.
    app.dependency_overrides[get_transactions_provider] = override_provider
    yield
    app.dependency_overrides.pop(get_transactions_provider, None)


client = TestClient(app)


def test_cash_flow_endpoint_returns_series_for_account():
    # A wide trailing window (24 months) so this HTTP-contract test doesn't
    # depend on which calendar month the suite happens to run in - the
    # month-bucketing logic itself is covered precisely, with an explicit
    # as_of, in tests/test_analytics_service.py.
    response = client.get("/analytics/cash-flow", params={"accountId": "ACC-1", "months": 24})

    assert response.status_code == 200
    body = response.json()
    assert body["accountId"] == "ACC-1"
    assert len(body["series"]) == 24
    assert sum(m["inflow"] for m in body["series"]) == 1000.0
    assert sum(m["outflow"] for m in body["series"]) == 400.0


def test_cash_flow_requires_account_id():
    response = client.get("/analytics/cash-flow")
    assert response.status_code == 422


def test_health_check():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
