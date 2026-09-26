package com.zentrox.ledger.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * RFC 6749 authorization-code and refresh-token exchanges (FRD S6.2).
 *
 * One client for every provider: QuickBooks, Xero and NetSuite all implement the
 * same standard grant types, differing only in endpoint URLs and how they pass
 * client credentials (all three accept HTTP Basic, which is what is used here).
 *
 * Isolated from {@link AbstractAccountingConnector} behind an interface-free but
 * injectable bean so connector tests can stub the HTTP exchange without a live
 * provider or a running web server.
 */
@Component
@Slf4j
public class OAuthTokenClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public OAuthTokenClient(RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /** Exchanges an authorization code for an access/refresh token pair. */
    public OAuthTokenResponse exchangeAuthorizationCode(ConnectorProperties.Provider provider, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", provider.getRedirectUri());
        return post(provider, form, "authorization_code");
    }

    /** Trades a refresh token for a fresh access token. */
    public OAuthTokenResponse refresh(ConnectorProperties.Provider provider, String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        return post(provider, form, "refresh_token");
    }

    private OAuthTokenResponse post(ConnectorProperties.Provider provider,
                                    MultiValueMap<String, String> form,
                                    String grantType) {
        if (provider.getTokenUri() == null || provider.getTokenUri().isBlank()) {
            throw new ConnectorException("No token-uri configured for this provider");
        }
        try {
            String body = restClient.post()
                    .uri(provider.getTokenUri())
                    .header(HttpHeaders.AUTHORIZATION, basicAuth(provider))
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            OAuthTokenResponse response = objectMapper.readValue(body, OAuthTokenResponse.class);
            if (response.accessToken() == null || response.accessToken().isBlank()) {
                throw new ConnectorException("Token endpoint returned no access_token for grant " + grantType);
            }
            return response;
        } catch (ConnectorException e) {
            throw e;
        } catch (RestClientException e) {
            // Deliberately does not echo the response body: provider error payloads
            // routinely quote back the submitted code or refresh token.
            throw new ConnectorException("Token endpoint call failed for grant " + grantType
                    + ": " + e.getMostSpecificCause().getMessage(), e);
        } catch (Exception e) {
            throw new ConnectorException("Could not parse token endpoint response for grant " + grantType, e);
        }
    }

    private String basicAuth(ConnectorProperties.Provider provider) {
        String credentials = provider.getClientId() + ":" + provider.getClientSecret();
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
