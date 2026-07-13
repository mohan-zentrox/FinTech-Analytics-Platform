package com.zentrox.ledger.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentrox.ledger.dto.auth.LoginRequest;
import com.zentrox.ledger.dto.auth.RegisterRequest;
import com.zentrox.ledger.entity.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end auth flow against an in-memory H2 database (test profile) -
 * exercises Spring Security, JPA/Flyway-free schema generation, JWT issuance
 * and RBAC-protected route access together.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void registerThenLoginReturnsAJwt() throws Exception {
        RegisterRequest register = new RegisterRequest("carol", "carol@example.com", "password123", Role.ANALYST);

        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.role").value("ANALYST"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        LoginRequest login = new LoginRequest("carol", "password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginWithWrongPasswordReturnsUnauthorized() throws Exception {
        RegisterRequest register = new RegisterRequest("dave", "dave@example.com", "password123", Role.VIEWER);
        mockMvc.perform(post("/api/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        LoginRequest badLogin = new LoginRequest("dave", "wrong-password");
        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedRequestToProtectedEndpointIsRejected() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/transactions"))
                .andExpect(status().isUnauthorized());
    }
}
