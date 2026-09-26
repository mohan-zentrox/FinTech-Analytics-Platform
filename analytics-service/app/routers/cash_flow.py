from typing import Annotated

from fastapi import APIRouter, Depends, Query

from app.core.security import Principal, require_reader
from app.deps import AccountTransactionsProvider, get_transactions_provider
from app.services.analytics import compute_cash_flow

router = APIRouter(prefix="/analytics", tags=["analytics"])


@router.get("/cash-flow")
def cash_flow(
    principal: Annotated[Principal, Depends(require_reader)],
    transactions_provider: Annotated[AccountTransactionsProvider, Depends(get_transactions_provider)],
    accountId: str = Query(..., description="Account identifier, matches core-api Transaction.account"),
    months: int = Query(6, ge=1, le=36, description="Trailing calendar months to include"),
):
    """
    GET /analytics/cash-flow?accountId=&months=

    Returns monthly {month, inflow, outflow, net} for the requested account,
    computed with pandas from the shared Postgres transactions table (via
    the read-only ledger_readonly role).

    Requires the same bearer token core-api issues (see app/core/security.py).
    """
    df = transactions_provider(accountId)
    series = compute_cash_flow(df, months=months)
    return {"accountId": accountId, "months": months, "series": series}
