from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.core.security import Principal, require_reader
from app.deps import AccountTransactionsProvider, get_transactions_provider
from app.services.anomalies import (
    DEFAULT_DUPLICATE_WINDOW_DAYS,
    DEFAULT_MIN_DAILY_COUNT,
    DEFAULT_MIN_SAMPLE_SIZE,
    DEFAULT_Z_THRESHOLD,
    score_transactions,
    summarize,
)

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.get("/anomalies")
def anomalies(
    principal: Annotated[Principal, Depends(require_reader)],
    transactions_provider: Annotated[AccountTransactionsProvider, Depends(get_transactions_provider)],
    accountId: str = Query(..., description="Account identifier, matches core-api Transaction.account"),
    limit: int = Query(50, ge=1, le=500, description="Maximum findings to return, highest score first"),
    zThreshold: float = Query(
        DEFAULT_Z_THRESHOLD, ge=1.0, le=10.0,
        description="Minimum |z-score| for the outlier and velocity rules",
    ),
    minSampleSize: int = Query(
        DEFAULT_MIN_SAMPLE_SIZE, ge=2, le=1000,
        description="Smallest peer group the outlier rule will score",
    ),
    duplicateWindowDays: int = Query(
        DEFAULT_DUPLICATE_WINDOW_DAYS, ge=0, le=365,
        description="Window within which identical payments are treated as duplicates",
    ),
    minDailyCount: int = Query(
        DEFAULT_MIN_DAILY_COUNT, ge=1, le=1000,
        description="Smallest daily transaction count that can be a velocity spike",
    ),
):
    """
    GET /analytics/anomalies?accountId=&limit=&zThreshold=...

    Statistical anomaly findings for one account (FRD S5.4), computed with pandas.

    This is the exploratory view: thresholds are request parameters so an analyst
    can tune sensitivity interactively. Persisted, triageable alerts live in
    core-api (POST /api/fraud/scan, GET /api/fraud/alerts), which owns the alert
    lifecycle; nothing here writes to the database.
    """
    df = transactions_provider(accountId)
    findings = score_transactions(
        df,
        z_threshold=zThreshold,
        min_sample_size=minSampleSize,
        duplicate_window_days=duplicateWindowDays,
        min_daily_count=minDailyCount,
    )
    return {
        "accountId": accountId,
        "thresholds": {
            "zThreshold": zThreshold,
            "minSampleSize": minSampleSize,
            "duplicateWindowDays": duplicateWindowDays,
            "minDailyCount": minDailyCount,
        },
        "summary": summarize(findings),
        "findings": findings[:limit],
        "truncated": len(findings) > limit,
    }
