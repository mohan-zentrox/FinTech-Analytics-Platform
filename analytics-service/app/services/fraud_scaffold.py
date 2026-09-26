"""
Deprecated shim - kept so anything importing the original scaffold keeps working.

The FRD S5.4 anomaly rules are implemented in app/services/anomalies.py. This
module previously raised NotImplementedError; it now delegates, and will be removed
once nothing imports it.
"""
import warnings

import pandas as pd

from app.services.anomalies import score_transactions as _score_transactions


def score_transactions(df: pd.DataFrame) -> list[dict]:
    """Deprecated. Use app.services.anomalies.score_transactions instead."""
    warnings.warn(
        "fraud_scaffold.score_transactions is deprecated; "
        "import score_transactions from app.services.anomalies instead",
        DeprecationWarning,
        stacklevel=2,
    )
    return _score_transactions(df)
