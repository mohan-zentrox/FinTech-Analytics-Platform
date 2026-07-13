"""
SCAFFOLD ONLY - no business logic implemented yet.

TODO (FRD S5.4 "Anomaly & Fraud Detection"): this module is the pandas-side
counterpart to core-api's com.zentrox.ledger.fraud.FraudDetectionService.
Candidate approach once implemented:
  - pull a rolling window of transactions per account via app.db.fetch_transactions
  - compute per-account/category z-scores on `amount` to flag outliers
  - compute day-over-day transaction velocity per account
  - return a list of {transactionId, ruleId, score, reason} findings that
    core-api can persist / expose via GET /api/fraud/alerts
TODO: decide the trigger (called synchronously by core-api after import, or
polled on a schedule) and the score threshold ownership (T2-DATA1 /
T2-DATA2 per BRD S4.2).
"""
import pandas as pd


def score_transactions(df: pd.DataFrame) -> list[dict]:
    raise NotImplementedError(
        "fraud_scaffold.score_transactions is not implemented - see FRD S5.4"
    )
