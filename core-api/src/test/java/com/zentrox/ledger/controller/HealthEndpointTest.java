package com.zentrox.ledger.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the operational health endpoint.
 *
 * This exists because application.yml configured `management.endpoints.web.exposure`
 * while spring-boot-starter-actuator was never on the classpath, so
 * /actuator/health returned 404 - discovered only when a container health check
 * started depending on it. Docker, Render's healthCheckPath and Cloud Run's probe
 * all hit this path, so a regression here silently breaks every deployment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointIsExposedAndUnauthenticated() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void otherActuatorEndpointsAreNotReachableAnonymously() throws Exception {
        // Only `health` and `info` are in management.endpoints.web.exposure.include,
        // and SecurityConfig permits only /actuator/health anonymously - so env/beans
        // come back 401 rather than 404. That is the preferable answer: it does not
        // disclose whether the endpoint exists at all.
        mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isUnauthorized());
    }
}
