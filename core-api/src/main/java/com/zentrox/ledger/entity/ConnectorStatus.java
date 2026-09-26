package com.zentrox.ledger.entity;

/** Connection state of a stored accounting-source credential (FRD S6.2). */
public enum ConnectorStatus {

    /** Usable: access token valid, or refreshable with the stored refresh token. */
    CONNECTED,

    /** Refresh token itself has expired or was revoked - the user must re-authorise. */
    EXPIRED,

    /** Explicitly disconnected by an administrator. */
    DISCONNECTED
}
