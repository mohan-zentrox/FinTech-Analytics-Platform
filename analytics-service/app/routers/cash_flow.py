from fastapi import APIRouter, Depends, Query

from app.deps import AccountTransactionsProvider, get_transactions_provider
from app.services.analytics import compute_cash_flow

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.get("/cash-flow")
def cash_flow(
    accountId: str = Query(..., description="Account identifier, matches core-api Transaction.account"),
    months: int = Query(6, ge=1, le=36, description="Trailing calendar months to include"),
    transactions_provider: AccountTransactionsProvider = Depends(get_transactions_provider),
):
    """
    GET /analytics/cash-flow?accountId=&months=

    Returns monthly {month, inflow, outflow, net} for the requested account,
    computed with pandas from the shared Postgres transactions table (via
    the read-only ledger_readonly role).
    """
    df = transactions_provider(accountId)
    series = compute_cash_flow(df, months=months)
    return {"accountId": accountId, "months": months, "series": series}
