"""
Unit tests for app/services/anomalies.py (FRD S5.4).

Pure-pandas, no database and no HTTP - hand-built DataFrames only, matching the
approach in test_analytics_service.py. Rule definitions here are intended to agree
with core-api's FraudRuleEngine, so several cases mirror
core-api/src/test/java/.../FraudRuleEngineTest.java on purpose.
"""
import pandas as pd
import pytest

from app.services.anomalies import (
    detect_amount_outliers,
    detect_duplicate_payments,
    detect_velocity_spikes,
    score_transactions,
    summarize,
)
from tests.conftest import TRANSACTION_COLUMNS, frame


def row(tx_id, amount, posted_date, description="Supplier payment", category="opex", account="ACC-1"):
    return {
        "id": tx_id, "source": "manual", "account": account, "amount": amount,
        "currency": "USD", "posted_date": posted_date, "description": description,
        "category": category, "status": "POSTED", "external_id": f"ext-{tx_id}",
    }


# ------------------------------------------------------------------ duplicates

def test_flags_only_the_later_of_two_identical_payments():
    df = frame([
        row("1", -2500.0, "2026-06-01", "Acme Corp invoice 88"),
        row("2", -2500.0, "2026-06-03", "ACME CORP - Invoice #88!"),
    ])

    findings = detect_duplicate_payments(df)

    assert len(findings) == 1
    assert findings[0]["transactionId"] == "2"
    assert findings[0]["ruleId"] == "DUPLICATE_PAYMENT"
    assert findings[0]["severity"] == "MEDIUM"


def test_same_day_duplicates_are_high_severity():
    df = frame([
        row("1", -900.0, "2026-06-10", "Vendor payment"),
        row("2", -900.0, "2026-06-10", "Vendor payment"),
    ])

    findings = detect_duplicate_payments(df)

    assert len(findings) == 1
    assert findings[0]["severity"] == "HIGH"


def test_ignores_identical_payments_outside_the_window():
    df = frame([
        row("1", -2500.0, "2026-01-05", "Monthly rent"),
        row("2", -2500.0, "2026-02-05", "Monthly rent"),
    ])

    assert detect_duplicate_payments(df, duplicate_window_days=7) == []


def test_same_amount_on_different_accounts_is_not_a_duplicate():
    df = frame([
        row("1", -500.0, "2026-06-01", "Subscription", account="ACC-1"),
        row("2", -500.0, "2026-06-01", "Subscription", account="ACC-2"),
    ])

    assert detect_duplicate_payments(df) == []


def test_treats_missing_descriptions_as_equal_rather_than_crashing():
    df = frame([
        row("1", -100.0, "2026-06-01", None),
        row("2", -100.0, "2026-06-02", None),
    ])

    findings = detect_duplicate_payments(df)

    assert len(findings) == 1


def test_a_run_of_three_duplicates_flags_the_second_and_third():
    df = frame([
        row("1", -100.0, "2026-06-01", "Same"),
        row("2", -100.0, "2026-06-02", "Same"),
        row("3", -100.0, "2026-06-03", "Same"),
    ])

    findings = detect_duplicate_payments(df)

    assert {f["transactionId"] for f in findings} == {"2", "3"}


# --------------------------------------------------------------------- outlier

def test_flags_an_amount_far_from_its_peer_group_mean():
    rows = [row(str(i), 100.0, f"2026-06-{i + 1:02d}") for i in range(12)]
    rows[0]["amount"] = 110.0  # give the group a non-zero standard deviation
    rows.append(row("99", 50000.0, "2026-06-20"))

    findings = detect_amount_outliers(frame(rows))

    assert "99" in {f["transactionId"] for f in findings}
    assert all(f["ruleId"] == "AMOUNT_OUTLIER" for f in findings)
    assert all(f["score"] >= 3.0 for f in findings)


def test_skips_peer_groups_below_the_minimum_sample_size():
    df = frame([
        row("1", 10.0, "2026-06-01", category="travel"),
        row("2", 12.0, "2026-06-02", category="travel"),
        row("3", 99999.0, "2026-06-03", category="travel"),
    ])

    assert detect_amount_outliers(df, min_sample_size=8) == []


def test_does_not_divide_by_zero_when_every_peer_amount_is_identical():
    df = frame([row(str(i), 250.0, f"2026-06-{i + 1:02d}", category="fees") for i in range(10)])

    assert detect_amount_outliers(df) == []


def test_separates_peer_groups_by_category():
    rows = []
    for i in range(10):
        rows.append(row(f"s{i}", 100.0 + i, f"2026-06-{i + 1:02d}", category="supplies"))
        rows.append(row(f"c{i}", 90000.0 + i, f"2026-06-{i + 1:02d}", category="capex"))

    assert detect_amount_outliers(frame(rows)) == []


def test_treats_a_missing_category_as_its_own_uncategorized_group():
    rows = [row(str(i), 100.0 + i, f"2026-06-{i + 1:02d}", category=None) for i in range(10)]
    rows.append(row("99", 500000.0, "2026-06-20", category=None))

    findings = detect_amount_outliers(frame(rows))

    assert "99" in {f["transactionId"] for f in findings}
    assert "uncategorized" in findings[0]["reason"]


# -------------------------------------------------------------------- velocity

def test_raises_one_finding_per_spiking_day_against_its_largest_transaction():
    rows = [row(f"b{i}", 50.0, f"2026-06-{i + 1:02d}") for i in range(20)]
    rows.append(row("biggest", -5000.0, "2026-06-25"))
    rows += [row(f"burst{i}", 20.0, "2026-06-25") for i in range(14)]

    findings = detect_velocity_spikes(frame(rows))

    assert len(findings) == 1
    assert findings[0]["transactionId"] == "biggest"
    assert findings[0]["ruleId"] == "VELOCITY_SPIKE"
    assert "15 transactions" in findings[0]["reason"]


def test_ignores_busy_days_below_the_minimum_daily_count():
    rows = [row(f"b{i}", 50.0, f"2026-06-{i + 1:02d}") for i in range(20)]
    rows += [row(f"m{i}", 50.0, "2026-06-25") for i in range(3)]

    assert detect_velocity_spikes(frame(rows), min_daily_count=5) == []


def test_ignores_accounts_with_too_few_active_days_for_a_baseline():
    rows = [row(str(i), 10.0, "2026-06-02") for i in range(30)]

    assert detect_velocity_spikes(frame(rows)) == []


# ------------------------------------------------------------- score_transactions

def test_returns_nothing_for_an_empty_or_none_frame():
    assert score_transactions(pd.DataFrame(columns=TRANSACTION_COLUMNS)) == []
    assert score_transactions(None) == []


def test_combines_rules_and_sorts_by_score_descending():
    rows = [row(str(i), 100.0, f"2026-06-{i + 1:02d}") for i in range(10)]
    rows[0]["amount"] = 120.0
    rows.append(row("outlier", 90000.0, "2026-06-20", description="One-off"))
    rows.append(row("dup-a", -777.0, "2026-06-21", description="Repeat me"))
    rows.append(row("dup-b", -777.0, "2026-06-22", description="Repeat me"))

    findings = score_transactions(frame(rows))

    rules = {f["ruleId"] for f in findings}
    assert "AMOUNT_OUTLIER" in rules
    assert "DUPLICATE_PAYMENT" in rules
    scores = [f["score"] for f in findings]
    assert scores == sorted(scores, reverse=True)


def test_every_finding_has_the_shape_core_api_persists():
    df = frame([
        row("1", -2500.0, "2026-06-01", "Acme invoice"),
        row("2", -2500.0, "2026-06-02", "Acme invoice"),
    ])

    findings = score_transactions(df)

    assert findings
    for finding in findings:
        assert set(finding) == {"transactionId", "account", "ruleId", "severity", "score", "reason"}
        assert finding["severity"] in {"HIGH", "MEDIUM", "LOW"}
        assert isinstance(finding["score"], float)
        assert finding["account"] == "ACC-1"


def test_does_not_mutate_the_caller_s_dataframe():
    df = frame([
        row("1", -2500.0, "2026-06-01", "Acme invoice"),
        row("2", -2500.0, "2026-06-02", "Acme invoice"),
    ])
    before = list(df.columns)

    score_transactions(df)

    assert list(df.columns) == before


def test_summarize_counts_by_rule_and_severity():
    findings = [
        {"ruleId": "DUPLICATE_PAYMENT", "severity": "HIGH", "score": 1.0},
        {"ruleId": "DUPLICATE_PAYMENT", "severity": "MEDIUM", "score": 1.0},
        {"ruleId": "AMOUNT_OUTLIER", "severity": "LOW", "score": 3.1},
    ]

    summary = summarize(findings)

    assert summary["total"] == 3
    assert summary["byRule"] == {"DUPLICATE_PAYMENT": 2, "AMOUNT_OUTLIER": 1}
    assert summary["bySeverity"] == {"HIGH": 1, "MEDIUM": 1, "LOW": 1}


def test_summarize_handles_no_findings():
    assert summarize([]) == {
        "total": 0,
        "byRule": {},
        "bySeverity": {"HIGH": 0, "MEDIUM": 0, "LOW": 0},
    }


def test_deprecated_scaffold_still_works_but_warns():
    from app.services import fraud_scaffold

    df = frame([
        row("1", -100.0, "2026-06-01", "Repeat"),
        row("2", -100.0, "2026-06-02", "Repeat"),
    ])

    with pytest.warns(DeprecationWarning):
        findings = fraud_scaffold.score_transactions(df)

    assert len(findings) == 1
