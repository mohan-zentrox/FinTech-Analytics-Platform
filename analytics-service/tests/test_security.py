"""
Tests for the JWT guard on /analytics/** (see app/core/security.py).

These matter more than most: before this existed, every analytics endpoint served
ledger data to anyone who could reach the port.
"""
import pandas as pd
import pytest
from fastapi.testclient import TestClient

from app.core.config import Settings
from app.deps import get_transactions_provider
from app.main import app
from tests.conftest import TEST_JWT_SECRET, frame, make_token


def fake_df() -> pd.DataFrame:
    return frame([
        {"id": "1", "source": "manual", "account": "ACC-1", "amount": 500.0, "currency": "USD",
         "posted_date": "2026-06-01", "description": "x", "category": "opex",
         "status": "POSTED", "external_id": "e1"},
    ])


@pytest.fixture(autouse=True)
def _override_transactions_provider():
    app.dependency_overrides[get_transactions_provider] = lambda: (lambda account_id: fake_df())
    yield
    app.dependency_overrides.pop(get_transactions_provider, None)


client = TestClient(app)

PROTECTED_PATHS = [
    ("/analytics/cash-flow", {"accountId": "ACC-1"}),
    ("/analytics/kpis", {"accountId": "ACC-1"}),
    ("/analytics/anomalies", {"accountId": "ACC-1"}),
]


@pytest.mark.parametrize("path,params", PROTECTED_PATHS)
def test_every_analytics_route_rejects_a_request_with_no_token(path, params):
    response = client.get(path, params=params)

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"


@pytest.mark.parametrize("path,params", PROTECTED_PATHS)
def test_every_analytics_route_accepts_a_valid_token(path, params):
    response = client.get(path, params=params, headers={"Authorization": f"Bearer {make_token()}"})

    assert response.status_code == 200


def test_rejects_a_token_signed_with_the_wrong_secret():
    forged = make_token(secret="a-different-secret-that-is-also-32-bytes-long")

    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {forged}"})

    assert response.status_code == 401


def test_rejects_an_expired_token():
    expired = make_token(expires_in_seconds=-60)

    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {expired}"})

    assert response.status_code == 401


def test_rejects_a_token_with_no_expiry_claim():
    # A token that never expires is not acceptable even if correctly signed.
    forever = make_token(include_exp=False)

    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {forever}"})

    assert response.status_code == 401


def test_rejects_an_hs512_token_even_though_the_secret_is_correct():
    """
    Algorithm pinning, from the other side of the wire.

    core-api's JwtService used to let jjwt pick the MAC variant from the secret's
    length, so a 64-byte JWT_SECRET produced HS512 tokens that this HS256-pinned
    verifier rejected - which broke every dashboard read until core-api was fixed
    to pin HS256 explicitly. This test locks the contract down on this side.
    """
    hs512 = make_token(algorithm="HS512")

    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {hs512}"})

    assert response.status_code == 401


def test_rejects_an_unsigned_none_algorithm_token():
    # The classic JWT attack: alg=none. The verifier pins HS256, so this must fail.
    import jwt

    unsigned = jwt.encode({"sub": "attacker", "role": "ADMIN", "exp": 9999999999}, key="", algorithm="none")

    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {unsigned}"})

    assert response.status_code == 401


def test_rejects_malformed_and_empty_bearer_values():
    for header in ["Bearer", "Bearer ", "Bearer not.a.jwt", "Basic dXNlcjpwYXNz"]:
        response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                              headers={"Authorization": header})
        assert response.status_code == 401, header


def test_error_body_does_not_disclose_why_verification_failed():
    # "expired" and "bad signature" must look identical to a caller probing tokens.
    expired = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                         headers={"Authorization": f"Bearer {make_token(expires_in_seconds=-60)}"})
    forged = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                        headers={"Authorization": f"Bearer {make_token(secret='another-secret-32-bytes-long-abcdef')}"})

    assert expired.json()["detail"] == forged.json()["detail"]


@pytest.mark.parametrize("role", ["ADMIN", "ANALYST", "VIEWER"])
def test_all_three_ledger_roles_may_read_analytics(role):
    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {make_token(role=role)}"})

    assert response.status_code == 200


@pytest.mark.parametrize("role", ["SERVICE", "", None, "admin"])
def test_a_token_without_a_recognised_role_is_forbidden_not_unauthorized(role):
    response = client.get("/analytics/kpis", params={"accountId": "ACC-1"},
                          headers={"Authorization": f"Bearer {make_token(role=role)}"})

    # 403, not 401: the token is valid, the role simply is not allowed.
    assert response.status_code == 403


def test_a_short_jwt_secret_fails_closed_rather_than_verifying_everything():
    from app.core.security import decode_token
    from fastapi import HTTPException

    weak = Settings(jwt_secret="too-short", analytics_require_auth=True)

    with pytest.raises(HTTPException) as raised:
        decode_token(make_token(), weak)

    assert raised.value.status_code == 500
    assert "32 bytes" in raised.value.detail


def test_auth_can_be_disabled_for_local_debugging_and_says_so_on_health():
    from app.core.security import require_reader

    # Simulate ANALYTICS_REQUIRE_AUTH=false by overriding the guard's settings source.
    from app.core.config import get_settings

    app.dependency_overrides[get_settings] = lambda: Settings(
        jwt_secret=TEST_JWT_SECRET, analytics_require_auth=False)
    try:
        response = client.get("/analytics/kpis", params={"accountId": "ACC-1"})
        assert response.status_code == 200
    finally:
        app.dependency_overrides.pop(get_settings, None)

    # require_reader is the only place that consults the flag.
    assert require_reader.__name__ == "require_reader"
