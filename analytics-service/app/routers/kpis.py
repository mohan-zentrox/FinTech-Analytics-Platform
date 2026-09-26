from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.core.security import Principal, require_reader
from app.deps import AccountTransactionsProvider, get_transactions_provider
from app.services.analytics import compute_kpis

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.get("/kpis")
def kpis(
    principal: Annotated[Principal, Depends(require_reader)],
    transactions_provider: Annotated[AccountTransactionsProvider, Depends(get_transactions_provider)],
    accountId: str = Query(..., description="Account identifier, matches core-api Transaction.account"),
):
    """
    GET /analytics/kpis?accountId=

    Returns AR/AP aging buckets (0-30/31-60/61-90/90+ days) and a trailing
    3-month burn rate for the requested account, computed with pandas.

    Requires the same bearer token core-api issues (see app/core/security.py).
    """
    df = transactions_provider(accountId)
    result = compute_kpis(df)
    return {"accountId": accountId, **result}
