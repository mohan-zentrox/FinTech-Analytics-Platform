"""
Shared test fixtures.

The JWT secret is pinned here, in a module that pytest imports before any test
module, because app.main resolves settings at import time and Settings is
lru_cached - setting it later would have no effect.
"""
import os
from datetime import datetime, timedelta, timezone

TEST_JWT_SECRET = "pytest-analytics-secret-key-at-least-32-bytes-long"

os.environ["JWT_SECRET"] = TEST_JWT_SECRET
os.environ["ANALYTICS_REQUIRE_AUTH"] = "true"

import jwt  # noqa: E402  (must follow the env setup above)
import pandas as pd  # noqa: E402
import pytest  # noqa: E402

TRANSACTION_COLUMNS = [
    "id", "source", "account", "amount", "currency", "posted_date",
    "description", "category", "status", "external_id",
]


def make_token(
    username: str = "t2-data1",
    role: str = "ANALYST",
    expires_in_seconds: int = 3600,
    secret: str = TEST_JWT_SECRET,
    algorithm: str = "HS256",
    include_exp: bool = True,
) -> str:
    """Mints a token shaped exactly like core-api's JwtService issues."""
    now = datetime.now(tz=timezone.utc)
    claims: dict = {"sub": username, "role": role, "iat": now}
    if include_exp:
        claims["exp"] = now + timedelta(seconds=expires_in_seconds)
    return jwt.encode(claims, secret, algorithm=algorithm)


@pytest.fixture
def auth_headers() -> dict:
    return {"Authorization": f"Bearer {make_token()}"}


@pytest.fixture
def admin_headers() -> dict:
    return {"Authorization": f"Bearer {make_token(username='t2-lead', role='ADMIN')}"}


def frame(rows: list[dict]) -> pd.DataFrame:
    """Builds a DataFrame with the canonical app.db.TRANSACTION_COLUMNS shape."""
    df = pd.DataFrame(rows, columns=TRANSACTION_COLUMNS)
    if not df.empty:
        df["posted_date"] = pd.to_datetime(df["posted_date"])
        df["amount"] = df["amount"].astype(float)
    return df
