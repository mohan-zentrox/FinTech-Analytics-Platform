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
 * Oracle NetSuite connector (FRD S6.2).
 *
 * Uses the SuiteQL endpoint of the REST Record service, which is the only
 * NetSuite interface that can filter and page transactions in one call. Paging is
 * offset/limit and the response carries an explicit `hasMore` flag, so - unlike
 * QuickBooks and Xero - the last page does not have to be inferred from a short
 * result set.
 *
 * Ref: https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_158151704258.html
 */
@Service
public class NetSuiteConnectorService extends AbstractAccountingConnector {

    private static final String PROVIDER = "netsuite";
    /** SuiteQL requires this header; without it NetSuite rejects the request. */
    private static final String PREFER_HEADER = "Prefer";

    private final RestClient restClient;

    public NetSuiteConnectorService(ConnectorProperties properties,
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
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        int limit = properties.getSyncPageSize();

        String suiteQl = String.format(
                "SELECT t.id, t.trandate, t.memo, t.currency, tl.foreignamount "
                        + "FROM transaction t JOIN transactionline tl ON tl.transaction = t.id "
                        + "WHERE t.trandate >= TO_DATE('%s', 'YYYY-MM-DD') AND tl.mainline = 'T' "
                        + "ORDER BY t.trandate, t.id", since);

        String uri = UriComponentsBuilder.fromUriString(config().getApiBaseUri())
                .path("/../query/v1/suiteql")
                .queryParam("limit", limit)
                .queryParam("offset", offset)
                .build()
                .normalize()
                .encode()
                .toUriString();

        JsonNode root = post(uri, accessToken, suiteQl);

        List<ExternalTransaction> transactions = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            BigDecimal amount = new BigDecimal(item.path("foreignamount").asText("0"));
            transactions.add(new ExternalTransaction(
                    item.path("id").asText(),
                    account,
                    amount,
                    item.path("currency").asText("USD"),
                    LocalDate.parse(item.path("trandate").asText()),
                    item.path("memo").asText("NetSuite transaction"),
                    null));
        }

        boolean hasMore = root.path("hasMore").asBoolean(false);
        return new SyncPage(transactions, hasMore ? String.valueOf(offset + transactions.size()) : null);
    }

    private JsonNode post(String uri, String accessToken, String suiteQl) {
        try {
            return restClient.post()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(PREFER_HEADER, "transient")
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"q\":\"" + suiteQl.replace("\"", "\\\"") + "\"}")
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ConnectorException("NetSuite SuiteQL request failed: "
                    + e.getMostSpecificCause().getMessage(), e);
        }
    }
}
