package com.zentrox.ledger.connector;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Intuit QuickBooks Online connector (FRD S6.2).
 *
 * Reads Purchase entities through the QBO query endpoint, which is SQL-like:
 * {@code SELECT * FROM Purchase WHERE TxnDate >= '...' STARTPOSITION n MAXRESULTS m}.
 * Purchases are money out, so amounts are negated on the way into the ledger.
 *
 * Ref: https://developer.intuit.com/app/developer/qbo/docs/api/accounting/all-entities/purchase
 */
@Service
public class QuickBooksConnectorService extends AbstractAccountingConnector {

    private static final String PROVIDER = "quickbooks";
    /** QBO STARTPOSITION is 1-based, unlike almost every other paging API. */
    private static final int FIRST_POSITION = 1;

    private final RestClient restClient;

    public QuickBooksConnectorService(ConnectorProperties properties,
                                      OAuthTokenClient tokenClient,
                                      RestClient.Builder restClientBuilder) {
        super(properties, tokenClient);
        this.restClient = restClientBuilder.build();
    }

    @Override
    public String providerName() {
        return PROVIDER;
    }

    @Override
    protected SyncPage fetchLiveTransactions(String accessToken, String realmId, String account,
                                             LocalDate since, String cursor) {
        if (realmId == null || realmId.isBlank()) {
            throw new ConnectorException(
                    "QuickBooks requires a realmId (company id); re-run the connect flow so it is captured");
        }
        int startPosition = cursor == null ? FIRST_POSITION : Integer.parseInt(cursor);
        int pageSize = properties.getSyncPageSize();

        String query = String.format(
                "SELECT * FROM Purchase WHERE TxnDate >= '%s' ORDER BY TxnDate STARTPOSITION %d MAXRESULTS %d",
                since, startPosition, pageSize);

        String uri = UriComponentsBuilder.fromUriString(config().getApiBaseUri())
                .path("/v3/company/{realmId}/query")
                .queryParam("query", query)
                .queryParam("minorversion", 70)
                .buildAndExpand(realmId)
                .encode()
                .toUriString();

        JsonNode root = get(uri, accessToken);
        JsonNode purchases = root.path("QueryResponse").path("Purchase");

        List<ExternalTransaction> transactions = new ArrayList<>();
        for (JsonNode purchase : purchases) {
            BigDecimal total = new BigDecimal(purchase.path("TotalAmt").asText("0"));
            transactions.add(new ExternalTransaction(
                    purchase.path("Id").asText(),
                    account,
                    total.negate(),
                    purchase.path("CurrencyRef").path("value").asText("USD"),
                    LocalDate.parse(purchase.path("TxnDate").asText()),
                    purchase.path("PrivateNote").asText(
                            purchase.path("EntityRef").path("name").asText("QuickBooks purchase")),
                    purchase.path("AccountRef").path("name").asText(null)));
        }

        // A short page means the feed is exhausted; anything else advances the cursor.
        String nextCursor = transactions.size() < pageSize
                ? null
                : String.valueOf(startPosition + transactions.size());
        return new SyncPage(transactions, nextCursor);
    }

    private JsonNode get(String uri, String accessToken) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ConnectorException("QuickBooks query failed: " + e.getMostSpecificCause().getMessage(), e);
        }
    }
}
