package com.zentrox.ledger.connector;

/**
 * A connector operation failed for a reason attributable to the provider or the
 * connector configuration (bad code, expired refresh token, unreachable API).
 *
 * Mapped to 502 Bad Gateway by ConnectorExceptionHandling in
 * GlobalExceptionHandler: the caller's request was well-formed; an upstream
 * dependency is what failed.
 */
public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
