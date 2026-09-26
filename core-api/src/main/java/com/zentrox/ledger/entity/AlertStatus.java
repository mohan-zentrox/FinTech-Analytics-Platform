package com.zentrox.ledger.entity;

/**
 * Review lifecycle of a fraud alert. A re-scan only ever (re)opens alerts that
 * are still OPEN - an analyst's CONFIRMED/DISMISSED decision is never silently
 * overwritten by the next scan.
 */
public enum AlertStatus {
    OPEN,
    CONFIRMED,
    DISMISSED
}
