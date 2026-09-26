"""
HTTP-contract tests for GET /analytics/kpis. Uses dates relative to
`date.today()` (rather than fixed calendar dates) so the aging-bucket
assertions hold no matter when the suite is executed.
"""
from datetime import date, timedelta

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
    today = date.today()
    rows = [
        {"id": "1", "source": "manual", "account": "ACC-1", "amount": 100.0, "currency": "USD",
         "posted_date": (today - timedelta(days=5)).isoformat(), "description": "", "category": "",
         "status": "PENDING", "external_id": "e1"},
        {"id": "2", "source": "manual", "account": "ACC-1", "amount": -200.0, "currency": "USD",
         "posted_date": (today - timedelta(days=95)).isoformat(), "description": "", "category": "",
         "status": "PENDING", "external_id": "e2"},
    ]
    df = pd.DataFrame(rows, columns=TRANSACTION_COLUMNS)
    df["posted_date"] = pd.to_datetime(df["posted_date"])
    df["amount"] = df["amount"].astype(float)
    return df


def override_provider():
    return lambda account_id: fake_df()


@pytest.fixture(autouse=True)
def _override_transactions_provider():
    # Per-test setup/teardown - see the matching comment in
    # test_cash_flow_router.py for why this can't be a module-level
    # assignment against the shared `app` singleton.
    app.dependency_overrides[get_transactions_provider] = override_provider
    yield
    app.dependency_overrides.pop(get_transactions_provider, None)


client = TestClient(app)


def test_kpis_endpoint_returns_aging_buckets_and_burn_rate(auth_headers):
    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"}, headers=auth_headers)

    assert response.status_code == 200
    body = response.json()
    assert body["accountId"] == "ACC-1"
    assert body["ar_aging"]["0-30"] == 100.0
    assert body["ap_aging"]["90+"] == 200.0
    assert "burn_rate" in body


def test_kpis_requires_account_id(auth_headers):
    response = client.get("/analytics/kpis", headers=auth_headers)
    assert response.status_code == 422
