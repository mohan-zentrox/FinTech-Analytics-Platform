package com.zentrox.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the RBAC contract in docs/API.md over real HTTP, and specifically that a
 * role denial surfaces as 403.
 *
 * That last point is why this test exists: the catch-all {@code Exception} handler
 * in GlobalExceptionHandler used to intercept Spring Security's
 * {@code AccessDeniedException} and report every insufficient-role request as
 * {@code 500 Internal Server Error}. Unit tests could not see it - only an
 * end-to-end request through the filter chain and the advice can.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RbacIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** Registers a user and returns its bearer token. */
    private String tokenFor(String username, Role role) throws Exception {
        RegisterRequest register = new RegisterRequest(
                username, username + "@example.com", "password123", role);

        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return "Bearer " + objectMapper.readTree(body).get("token").asText();
    }

    @Test
    void viewerIsForbiddenNotServerErroredWhenDeletingATransaction() throws Exception {
        String viewer = tokenFor("rbac-viewer", Role.VIEWER);

        mockMvc.perform(delete("/api/transactions/{id}", "3fa85f64-5717-4562-b3fc-2c963f66afa6")
                        .header("Authorization", viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Access denied")));
    }

    @Test
    void viewerIsForbiddenFromRunningAFraudScan() throws Exception {
        String viewer = tokenFor("rbac-viewer-scan", Role.VIEWER);

        mockMvc.perform(post("/api/fraud/scan")
                        .header("Authorization", viewer)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void viewerIsForbiddenFromGeneratingAReport() throws Exception {
        String viewer = tokenFor("rbac-viewer-report", Role.VIEWER);

        mockMvc.perform(post("/api/reports/generate")
                        .header("Authorization", viewer)
                        .contentType("application/json")
                        .content("{\"reportType\":\"CASH_FLOW\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void analystIsForbiddenFromTheAdminOnlyAuditTrailAndConnectorAdmin() throws Exception {
        String analyst = tokenFor("rbac-analyst", Role.ANALYST);

        mockMvc.perform(get("/api/audit-logs").header("Authorization", analyst))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/connectors/quickbooks/authorize-url").header("Authorization", analyst))
                .andExpect(status().isForbidden());
    }

    @Test
    void analystCanReachTheEndpointsItsRoleAllows() throws Exception {
        String analyst = tokenFor("rbac-analyst-allowed", Role.ANALYST);

        mockMvc.perform(get("/api/transactions").header("Authorization", analyst))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/fraud/alerts").header("Authorization", analyst))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports").header("Authorization", analyst))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/connectors").header("Authorization", analyst))
                .andExpect(status().isOk());
    }

    @Test
    void adminCanReadTheAuditTrailWithNoEntityFilter() throws Exception {
        String admin = tokenFor("rbac-admin", Role.ADMIN);

        // entity/entityId used to be mandatory query params, making a 400 the only
        // possible response to "show me the whole trail".
        mockMvc.perform(get("/api/audit-logs").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void anUnauthenticatedRequestIsUnauthorizedOnEveryNewFeatureRoute() throws Exception {
        mockMvc.perform(get("/api/fraud/alerts")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/reports")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/connectors")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownConnectorProviderIsANotFoundRatherThanAServerError() throws Exception {
        String admin = tokenFor("rbac-admin-connector", Role.ADMIN);

        mockMvc.perform(get("/api/connectors/{provider}/authorize-url", "sage")
                        .header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void aMissingRequiredQueryParameterIsABadRequestRatherThanAServerError() throws Exception {
        String admin = tokenFor("rbac-admin-params", Role.ADMIN);

        // /sync requires `account`; omitting it must not reach the catch-all handler.
        mockMvc.perform(post("/api/connectors/{provider}/sync", "quickbooks")
                        .header("Authorization", admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void aMalformedUuidPathVariableIsABadRequestRatherThanAServerError() throws Exception {
        String analyst = tokenFor("rbac-analyst-uuid", Role.ANALYST);

        mockMvc.perform(get("/api/transactions/{id}", "not-a-uuid").header("Authorization", analyst))
                .andExpect(status().isBadRequest());
    }
}
