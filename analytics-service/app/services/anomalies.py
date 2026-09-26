"""
Pandas-side anomaly detection (FRD S5.4) - the counterpart to core-api's
com.zentrox.ledger.fraud.FraudRuleEngine.

Why both exist, rather than one calling the other: core-api owns the alert
lifecycle (persistence, analyst triage) and must be able to scan without a second
service being reachable, while this module is the exploratory/statistical view an
analyst drives from the dashboard. The three rules implemented here are deliberately
defined the same way as their core-api counterparts, so the two services do not
disagree about what an anomaly is. ROUND_AMOUNT is intentionally core-api only:
it is a fixed policy threshold, not a statistical judgement, so there is nothing
for an analyst to tune interactively.

  - duplicate_payment : same account, same |amount|, same normalised description
                        within `duplicate_window_days`
  - amount_outlier    : |z-score| of |amount| within its (account, category) peer
                        group above `z_threshold`
  - velocity_spike    : a day whose transaction count is `z_threshold` standard
                        deviations above that account's daily mean

Every function is a pure function of a DataFrame with the app.db.TRANSACTION_COLUMNS
shape, so the tests run against hand-built frames with no database - the same
approach app/services/analytics.py takes.
"""
from datetime import date
from typing import Optional

import pandas as pd

# Defaults mirror ledger.fraud.* in core-api's application.yml.
DEFAULT_DUPLICATE_WINDOW_DAYS = 7
DEFAULT_Z_THRESHOLD = 3.0
DEFAULT_MIN_SAMPLE_SIZE = 8
DEFAULT_MIN_DAILY_COUNT = 5

# Fewer active days than this gives no usable velocity baseline.
MIN_ACTIVE_DAYS_FOR_VELOCITY = 3

SEVERITY_HIGH = "HIGH"
SEVERITY_MEDIUM = "MEDIUM"
SEVERITY_LOW = "LOW"


def _normalize_description(value) -> str:
    """Lowercased, punctuation-free, whitespace-collapsed; NaN/None collapse to ''."""
    if value is None or (isinstance(value, float) and pd.isna(value)):
        return ""
    return " ".join(str(value).lower().replace("_", " ").translate(
        str.maketrans({c: " " for c in "!\"#$%&'()*+,-./:;<=>?@[\\]^`{|}~"})
    ).split())


def _severity_for(z: float, threshold: float) -> str:
    if z >= threshold + 2:
        return SEVERITY_HIGH
    if z >= threshold + 1:
        return SEVERITY_MEDIUM
    return SEVERITY_LOW


def _prepare(df: pd.DataFrame) -> pd.DataFrame:
    """Adds the derived columns every rule needs. Returns a copy; never mutates the input."""
    working = df.copy()
    working["posted_date"] = pd.to_datetime(working["posted_date"])
    working["amount"] = working["amount"].astype(float)
    working["abs_amount"] = working["amount"].abs()
    working["day"] = working["posted_date"].dt.normalize()
    working["norm_description"] = working["description"].map(_normalize_description)
    working["norm_category"] = (
        working["category"].fillna("uncategorized").astype(str).str.lower().replace("", "uncategorized")
    )
    return working


def detect_duplicate_payments(
    df: pd.DataFrame, duplicate_window_days: int = DEFAULT_DUPLICATE_WINDOW_DAYS
) -> list[dict]:
    """
    Flags the *later* transaction of each near-identical pair, so the original
    payment never appears as a finding against itself.
    """
    if df.empty:
        return []

    working = _prepare(df).sort_values(["posted_date", "external_id"])
    findings: list[dict] = []

    for _, group in working.groupby(["account", "abs_amount", "norm_description"], sort=False):
        if len(group) < 2:
            continue
        rows = group.to_dict("records")
        for previous, current in zip(rows, rows[1:]):
            gap_days = int((current["posted_date"] - previous["posted_date"]).days)
            if gap_days > duplicate_window_days:
                continue
            findings.append({
                "transactionId": str(current["id"]),
                "account": current["account"],
                "ruleId": "DUPLICATE_PAYMENT",
                "severity": SEVERITY_HIGH if gap_days == 0 else SEVERITY_MEDIUM,
                "score": 1.0,
                "reason": (
                    f"Possible duplicate payment: same account, amount {current['amount']:.2f} and "
                    f"description as the entry posted {previous['posted_date'].date()} "
                    f"({gap_days} day(s) earlier)"
                ),
            })
    return findings


def detect_amount_outliers(
    df: pd.DataFrame,
    z_threshold: float = DEFAULT_Z_THRESHOLD,
    min_sample_size: int = DEFAULT_MIN_SAMPLE_SIZE,
) -> list[dict]:
    """z-score of |amount| within each (account, category) peer group."""
    if df.empty:
        return []

    working = _prepare(df)
    findings: list[dict] = []

    for (account, category), group in working.groupby(["account", "norm_category"], sort=False):
        if len(group) < min_sample_size:
            continue
        mean = float(group["abs_amount"].mean())
        # ddof=1 (sample stddev), matching core-api's FraudRuleEngine.
        std_dev = float(group["abs_amount"].std(ddof=1))
        if not std_dev or pd.isna(std_dev) or std_dev <= 0.0:
            # Every amount in the peer group is identical; no outlier is definable.
            continue

        z_scores = (group["abs_amount"] - mean).abs() / std_dev
        for (_, row), z in zip(group.iterrows(), z_scores):
            z = float(z)
            if z < z_threshold:
                continue
            findings.append({
                "transactionId": str(row["id"]),
                "account": account,
                "ruleId": "AMOUNT_OUTLIER",
                "severity": _severity_for(z, z_threshold),
                "score": round(z, 4),
                "reason": (
                    f"Amount {row['amount']:.2f} is {z:.2f} standard deviations from the "
                    f"peer-group ({account}|{category}) mean of {mean:.2f} over {len(group)} transactions"
                ),
            })
    return findings


def detect_velocity_spikes(
    df: pd.DataFrame,
    z_threshold: float = DEFAULT_Z_THRESHOLD,
    min_daily_count: int = DEFAULT_MIN_DAILY_COUNT,
) -> list[dict]:
    """
    One finding per spiking day, raised against that day's largest transaction -
    flagging all N would bury the analyst in alerts for a single event.
    """
    if df.empty:
        return []

    working = _prepare(df)
    findings: list[dict] = []

    for account, account_rows in working.groupby("account", sort=False):
        daily_counts = account_rows.groupby("day").size()
        if len(daily_counts) < MIN_ACTIVE_DAYS_FOR_VELOCITY:
            continue
        mean = float(daily_counts.mean())
        std_dev = float(daily_counts.std(ddof=1))
        if not std_dev or pd.isna(std_dev) or std_dev <= 0.0:
            continue

        for day, count in daily_counts.items():
            count = int(count)
            if count < min_daily_count:
                continue
            z = (count - mean) / std_dev
            if z < z_threshold:
                continue
            day_rows = account_rows[account_rows["day"] == day]
            representative = day_rows.loc[day_rows["abs_amount"].idxmax()]
            findings.append({
                "transactionId": str(representative["id"]),
                "account": account,
                "ruleId": "VELOCITY_SPIKE",
                "severity": SEVERITY_HIGH if z >= z_threshold + 2 else SEVERITY_MEDIUM,
                "score": round(float(z), 4),
                "reason": (
                    f"{count} transactions posted on {day.date()} against account {account} - "
                    f"{z:.2f} standard deviations above its daily mean of {mean:.2f}"
                ),
            })
    return findings


def score_transactions(
    df: pd.DataFrame,
    as_of: Optional[date] = None,
    z_threshold: float = DEFAULT_Z_THRESHOLD,
    min_sample_size: int = DEFAULT_MIN_SAMPLE_SIZE,
    duplicate_window_days: int = DEFAULT_DUPLICATE_WINDOW_DAYS,
    min_daily_count: int = DEFAULT_MIN_DAILY_COUNT,
) -> list[dict]:
    """
    Runs every rule and returns findings sorted by score, highest first, so the
    caller can take the top N without sorting again.

    Each finding is {transactionId, account, ruleId, severity, score, reason} -
    the same shape core-api persists as a fraud_alert row, so a finding from here
    can be handed straight to it.
    """
    if df is None or df.empty:
        return []

    findings = (
        detect_duplicate_payments(df, duplicate_window_days)
        + detect_amount_outliers(df, z_threshold, min_sample_size)
        + detect_velocity_spikes(df, z_threshold, min_daily_count)
    )
    return sorted(findings, key=lambda f: f["score"], reverse=True)


def summarize(findings: list[dict]) -> dict:
    """Counts by rule and severity, for the dashboard's anomaly tiles."""
    by_rule: dict[str, int] = {}
    by_severity: dict[str, int] = {SEVERITY_HIGH: 0, SEVERITY_MEDIUM: 0, SEVERITY_LOW: 0}
    for finding in findings:
        by_rule[finding["ruleId"]] = by_rule.get(finding["ruleId"], 0) + 1
        by_severity[finding["severity"]] = by_severity.get(finding["severity"], 0) + 1
    return {"total": len(findings), "byRule": by_rule, "bySeverity": by_severity}
