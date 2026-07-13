package com.zentrox.ledger.entity;

/** Lifecycle status of a ledger transaction. */
public enum TransactionStatus {
    PENDING,
    POSTED,
    RECONCILED,
    FLAGGED,
    VOID
}
