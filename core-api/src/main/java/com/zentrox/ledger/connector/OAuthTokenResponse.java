package com.zentrox.ledger.connector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * RFC 6749 token endpoint response. Providers add their own fields
 * (Intuit sends `x_refresh_token_expires_in`, Xero sends `id_token`), so unknown
 * properties are ignored rather than failing the exchange.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("x_refresh_token_expires_in") Long refreshTokenExpiresIn,
        @JsonProperty("scope") String scope
) {
    /** Never include token material in a log line. */
    @Override
    public String toString() {
        return "OAuthTokenResponse{tokenType=" + tokenType + ", expiresIn=" + expiresIn
                + ", scope=" + scope + ", accessToken=***, refreshToken=***}";
    }
}
