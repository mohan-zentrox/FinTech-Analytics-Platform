from fastapi import APIRouter, Depends, Query

from app.deps import AccountTransactionsProvider, get_transactions_provider
from app.services.analytics import compute_kpis

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.get("/kpis")
def kpis(
    accountId: str = Query(..., description="Account identifier, matches core-api Transaction.account"),
    transactions_provider: AccountTransactionsProvider = Depends(get_transactions_provider),
):
    """
    GET /analytics/kpis?accountId=

    Returns AR/AP aging buckets (0-30/31-60/61-90/90+ days) and a trailing
    3-month burn rate for the requested account, computed with pandas.
    """
    df = transactions_provider(accountId)
    result = compute_kpis(df)
    return {"accountId": accountId, **result}
