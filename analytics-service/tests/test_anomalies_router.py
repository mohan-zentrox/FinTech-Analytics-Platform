"""
HTTP-contract tests for GET /analytics/anomalies, with the Postgres-backed
provider swapped for an in-memory fake (same pattern as the other router tests).
"""
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.deps import get_transactions_provider
from app.main import app
from tests.conftest import frame


def fake_df() -> pd.DataFrame:
    rows = [
        # A duplicate pair -> DUPLICATE_PAYMENT.
        {"id": "dup-a", "source": "manual", "account": "ACC-1", "amount": -2500.0, "currency": "USD",
         "posted_date": "2026-06-01", "description": "Acme invoice 88", "category": "opex",
         "status": "POSTED", "external_id": "e-dup-a"},
        {"id": "dup-b", "source": "manual", "account": "ACC-1", "amount": -2500.0, "currency": "USD",
         "posted_date": "2026-06-02", "description": "Acme invoice 88", "category": "opex",
         "status": "POSTED", "external_id": "e-dup-b"},
    ]
    # A peer group large enough to score, plus one clear outlier.
    for i in range(10):
        rows.append({
            "id": f"n{i}", "source": "manual", "account": "ACC-1", "amount": 100.0 + i,
            "currency": "USD", "posted_date": f"2026-06-{i + 3:02d}", "description": f"Normal {i}",
            "category": "supplies", "status": "POSTED", "external_id": f"e-n{i}",
        })
    rows.append({
        "id": "outlier", "source": "manual", "account": "ACC-1", "amount": 90000.0,
        "currency": "USD", "posted_date": "2026-06-20", "description": "One-off",
        "category": "supplies", "status": "POSTED", "external_id": "e-outlier",
    })
    return frame(rows)


@pytest.fixture(autouse=True)
def _override_transactions_provider():
    app.dependency_overrides[get_transactions_provider] = lambda: (lambda account_id: fake_df())
    yield
    app.dependency_overrides.pop(get_transactions_provider, None)


client = TestClient(app)


def test_returns_findings_with_a_summary_and_the_thresholds_used(auth_headers):
    response = client.get("/analytics/anomalies", params={"accountId": "ACC-1"}, headers=auth_headers)

    assert response.status_code == 200
    body = response.json()
    assert body["accountId"] == "ACC-1"
    assert body["summary"]["total"] == len(body["findings"])
    assert body["thresholds"]["zThreshold"] == 3.0
    assert body["truncated"] is False

    rules = {f["ruleId"] for f in body["findings"]}
    assert "DUPLICATE_PAYMENT" in rules
    assert "AMOUNT_OUTLIER" in rules


def test_findings_are_ordered_by_score_descending(auth_headers):
    body = client.get("/analytics/anomalies", params={"accountId": "ACC-1"},
                      headers=auth_headers).json()

    scores = [f["score"] for f in body["findings"]]
    assert scores == sorted(scores, reverse=True)


def test_limit_truncates_and_flags_that_it_did(auth_headers):
    body = client.get("/analytics/anomalies", params={"accountId": "ACC-1", "limit": 1},
                      headers=auth_headers).json()

    assert len(body["findings"]) == 1
    assert body["truncated"] is True
    # The summary still reports the full count, not the truncated one.
    assert body["summary"]["total"] > 1


def test_raising_the_z_threshold_suppresses_marginal_findings(auth_headers):
    strict = client.get("/analytics/anomalies", params={"accountId": "ACC-1", "zThreshold": 10.0},
                        headers=auth_headers).json()

    assert all(f["ruleId"] != "AMOUNT_OUTLIER" for f in strict["findings"])
    assert strict["thresholds"]["zThreshold"] == 10.0


def test_a_zero_duplicate_window_still_catches_same_day_duplicates(auth_headers):
    body = client.get("/analytics/anomalies",
                      params={"accountId": "ACC-1", "duplicateWindowDays": 0},
                      headers=auth_headers).json()

    # The fixture's duplicates are one day apart, so a zero window excludes them.
    assert all(f["ruleId"] != "DUPLICATE_PAYMENT" for f in body["findings"])


def test_requires_an_account_id(auth_headers):
    assert client.get("/analytics/anomalies", headers=auth_headers).status_code == 422


def test_rejects_out_of_range_parameters(auth_headers):
    for params in [
        {"accountId": "ACC-1", "limit": 0},
        {"accountId": "ACC-1", "limit": 501},
        {"accountId": "ACC-1", "zThreshold": 0.5},
        {"accountId": "ACC-1", "zThreshold": 11},
        {"accountId": "ACC-1", "minSampleSize": 1},
        {"accountId": "ACC-1", "duplicateWindowDays": -1},
        {"accountId": "ACC-1", "minDailyCount": 0},
    ]:
        assert client.get("/analytics/anomalies", params=params,
                          headers=auth_headers).status_code == 422, params


def test_an_account_with_no_transactions_returns_an_empty_result(auth_headers):
    from tests.conftest import TRANSACTION_COLUMNS

    app.dependency_overrides[get_transactions_provider] = (
        lambda: (lambda account_id: pd.DataFrame(columns=TRANSACTION_COLUMNS))
    )
    try:
        body = client.get("/analytics/anomalies", params={"accountId": "ACC-EMPTY"},
                          headers=auth_headers).json()
    finally:
        app.dependency_overrides.pop(get_transactions_provider, None)

    assert body["findings"] == []
    assert body["summary"]["total"] == 0
    assert body["truncated"] is False
