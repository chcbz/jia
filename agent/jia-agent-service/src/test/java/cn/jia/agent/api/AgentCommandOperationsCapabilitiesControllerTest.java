package cn.jia.agent.api;

import cn.jia.agent.config.AgentCommandOperationsConfiguration;
import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.service.AgentCommandOperationsService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentCommandOperationsCapabilitiesControllerTest {
    private static final String PATH = "/agent/internal/command-operations/capabilities";

    @Test
    void grantedReadCapabilityIsAvailableAndEmitsOnlyTheFrozenPublicContract() throws Exception {
        MockMvc mvc = mvc(true);

        mvc.perform(get(PATH).principal(jwt(validClaims(), "operator-a",
                        AgentCommandOperationsController.AUTHORITY_READ)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.contractVersion")
                        .value("command-observability-v1"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.readOnly").value(true))
                .andExpect(jsonPath("$.data.reason").value(nullValue()))
                .andExpect(content().string(not(containsString("tenant-a"))))
                .andExpect(content().string(not(containsString("client-a"))))
                .andExpect(content().string(not(containsString("operator-a"))))
                .andExpect(content().string(not(containsString("agent-command-ops-read"))))
                .andExpect(content().string(not(containsString("fixture-token"))))
                .andExpect(content().string(not(containsString("secret-value"))));
    }

    @Test
    void disabledReadCapabilityIsReportedWithoutTouchingOperationsInfrastructure() throws Exception {
        mvc(false).perform(get(PATH).principal(jwt(validClaims(), "operator-a",
                        AgentCommandOperationsController.AUTHORITY_READ)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.readOnly").value(true))
                .andExpect(jsonPath("$.data.reason").value("DISABLED"));
    }

    @Test
    void missingReadAuthorityTakesForbiddenPriorityOverDisabledFlag() throws Exception {
        mvc(false).perform(get(PATH).principal(jwt(validClaims(), "operator-a", "agent-task-read")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("E0"))
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.readOnly").value(true))
                .andExpect(jsonPath("$.data.reason").value("FORBIDDEN"));
    }

    @Test
    void anonymousUnauthenticatedAndNonJwtPrincipalsReturn401() throws Exception {
        MockMvc mvc = mvc(true);
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));

        var unauthenticated = new UsernamePasswordAuthenticationToken("operator-a", "ignored");
        assertFalse(unauthenticated.isAuthenticated());
        mvc.perform(get(PATH).principal(unauthenticated))
                .andExpect(status().isUnauthorized());

        var nonJwt = new UsernamePasswordAuthenticationToken(
                "operator-a", "ignored",
                List.of(new SimpleGrantedAuthority(
                        AgentCommandOperationsController.AUTHORITY_READ)));
        mvc.perform(get(PATH).principal(nonJwt))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingMalformedAndNoncanonicalClaimsFail403BeforeCapabilityEvaluation() throws Exception {
        MockMvc mvc = mvc(true);
        List<Map<String, Object>> invalidClaims = List.of(
                claims(null, "client-a", "operator-a"),
                claims("tenant-a", null, "operator-a"),
                claims("tenant-a", "client-a", null),
                claims(7, "client-a", "operator-a"),
                claims("tenant-a", List.of("client-a"), "operator-a"),
                claims("tenant-a", "client-a", Boolean.TRUE),
                claims(" tenant-a", "client-a", "operator-a"),
                claims("tenant-a", "client-a ", "operator-a"),
                claims("tenant-a", "client-a", "operator-a\n"),
                claims("", "client-a", "operator-a"));

        for (Map<String, Object> claims : invalidClaims) {
            mvc.perform(get(PATH).principal(jwt(claims, "operator-a",
                            AgentCommandOperationsController.AUTHORITY_READ)))
                    .andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"));
        }

        mvc.perform(get(PATH).principal(jwt(validClaims(), "different-name",
                        AgentCommandOperationsController.AUTHORITY_READ)))
                .andExpect(status().isForbidden());
    }

    @Test
    void capabilityBeanRemainsRegisteredWhenOperationsServiceIsDisabled() {
        new ApplicationContextRunner()
                .withBean(AgentCommandOperationsCapabilitiesController.class)
                .withUserConfiguration(AgentCommandOperationsConfiguration.class)
                .withPropertyValues(
                        "agent.rabbit-operations.read-enabled=false",
                        "agent.rabbit-operations.redrive-enabled=false",
                        "agent.rabbit-operations.async-redrive-enabled=false",
                        "agent.rabbit-operations.reissue-enabled=false")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(
                            AgentCommandOperationsCapabilitiesController.class).size());
                    assertTrue(context.getBeansOfType(
                            AgentCommandOperationsService.class).isEmpty());
                    assertTrue(context.getBeansOfType(AgentCommandOperationsDao.class).isEmpty());
                    assertFalse(context.containsBean("agentCommandOperationsService"));
                    assertFalse(context.containsBean("agentCommandOperationsDao"));
                });
    }

    private MockMvc mvc(boolean readEnabled) {
        return MockMvcBuilders.standaloneSetup(
                new AgentCommandOperationsCapabilitiesController(readEnabled)).build();
    }

    private JwtAuthenticationToken jwt(
            Map<String, Object> claims, String name, String authority) {
        Jwt.Builder builder = Jwt.withTokenValue("fixture-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        claims.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(),
                List.of(new SimpleGrantedAuthority(authority)), name);
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = claims("tenant-a", "client-a", "operator-a");
        claims.put("private_secret", "secret-value");
        return claims;
    }

    private Map<String, Object> claims(Object tenant, Object client, Object subject) {
        Map<String, Object> claims = new LinkedHashMap<>();
        if (tenant != null) claims.put("jiacn", tenant);
        if (client != null) claims.put("client_id", client);
        if (subject != null) claims.put("sub", subject);
        return claims;
    }
}
