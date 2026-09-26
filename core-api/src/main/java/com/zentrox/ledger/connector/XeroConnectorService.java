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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Xero connector (FRD S6.2).
 *
 * Reads BankTransactions, which Xero pages 100 at a time via a 1-based `page`
 * parameter and scopes to a tenant through the `Xero-tenant-id` header (stored as
 * realmId). SPEND transactions are money out and are negated; RECEIVE stay
 * positive.
 *
 * Ref: https://developer.xero.com/documentation/api/accounting/banktransactions
 */
@Service
public class XeroConnectorService extends AbstractAccountingConnector {

    private static final String PROVIDER = "xero";
    private static final String TENANT_HEADER = "Xero-tenant-id";
    /** Xero's fixed page size for BankTransactions; not configurable per request. */
    private static final int XERO_PAGE_SIZE = 100;

    private final RestClient restClient;

    public XeroConnectorService(ConnectorProperties properties,
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
                    "Xero requires a tenantId; re-run the connect flow so it is captured");
        }
        int page = cursor == null ? 1 : Integer.parseInt(cursor);

        String uri = UriComponentsBuilder.fromUriString(config().getApiBaseUri())
                .path("/BankTransactions")
                .queryParam("page", page)
                .queryParam("where", String.format("Date >= DateTime(%d, %d, %d)",
                        since.getYear(), since.getMonthValue(), since.getDayOfMonth()))
                .build()
                .encode()
                .toUriString();

        JsonNode root = get(uri, accessToken, realmId);
        JsonNode bankTransactions = root.path("BankTransactions");

        List<ExternalTransaction> transactions = new ArrayList<>();
        for (JsonNode bankTransaction : bankTransactions) {
            BigDecimal total = new BigDecimal(bankTransaction.path("Total").asText("0"));
            boolean spend = "SPEND".equalsIgnoreCase(bankTransaction.path("Type").asText(""));

            transactions.add(new ExternalTransaction(
                    bankTransaction.path("BankTransactionID").asText(),
                    account,
                    spend ? total.negate() : total,
                    bankTransaction.path("CurrencyCode").asText("USD"),
                    parseXeroDate(bankTransaction.path("DateString").asText(
                            bankTransaction.path("Date").asText(null))),
                    bankTransaction.path("Reference").asText(
                            bankTransaction.path("Contact").path("Name").asText("Xero bank transaction")),
                    bankTransaction.path("LineItems").path(0).path("AccountCode").asText(null)));
        }

        String nextCursor = transactions.size() < XERO_PAGE_SIZE ? null : String.valueOf(page + 1);
        return new SyncPage(transactions, nextCursor);
    }

    /**
     * Xero returns both a readable `DateString` ("2026-06-01T00:00:00") and a
     * legacy `/Date(…)/` form. Only the former is parsed; anything unexpected
     * fails loudly rather than silently importing a wrong posted date.
     */
    private LocalDate parseXeroDate(String value) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException("Xero bank transaction has no usable date field");
        }
        String datePart = value.length() >= 10 ? value.substring(0, 10) : value;
        try {
            return LocalDate.parse(datePart);
        } catch (DateTimeParseException e) {
            throw new ConnectorException("Unrecognised Xero date format: " + value, e);
        }
    }

    private JsonNode get(String uri, String accessToken, String tenantId) {
        try {
            return restClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(TENANT_HEADER, tenantId)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new ConnectorException("Xero request failed: " + e.getMostSpecificCause().getMessage(), e);
        }
    }
}
