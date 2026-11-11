package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingRentApplicationService;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.entity.AgentTaskTeamRecommendationDTO;
import cn.jia.agent.entity.AgentTaskTeamRecommendationRequestDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentPersonaProvisioningService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.security.AllowSensitiveOutput;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentControllerTest {
    private final AgentService agentService = mock(AgentService.class);
    private final AgentPersonaProvisioningService provisioning = mock(AgentPersonaProvisioningService.class);
    private final AgentController controller = new AgentController(
            agentService, mock(AbilityEvaluationService.class));

    @Test
    void retiredHostedRepairRouteIsNotMappedAndCannotMutateBinding() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/agent/personas/bindings/17/repair"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        verifyNoInteractions(provisioning,agentService);
    }

    @Test
    void abilitiesSaveRequiresJwtAndMapsConflictAndValidationToHttpStatus() {
        var request = new cn.jia.agent.entity.AgentAbilitiesUpdateDTO();
        request.setExpectedAbilities(java.util.List.of());
        request.setAbilities(java.util.List.of("分析"));
        var wire = new cn.jia.core.entity.JsonRequest<>(1, "ability-test", request);
        ResponseEntity<?> forbidden = assertInstanceOf(ResponseEntity.class,
                controller.updateAbilities("agent-a", wire, null));
        assertEquals(403, forbidden.getStatusCode().value());
        verifyNoInteractions(agentService);
        when(agentService.updateAbilities("agent-a", request)).thenThrow(
                new cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException(
                        "AGENT_ABILITIES_CONFLICT", "conflict"));
        ResponseEntity<?> conflict = assertInstanceOf(ResponseEntity.class,
                controller.updateAbilities("agent-a", wire, jwt("Owner-A", "Client-A")));
        assertEquals(409, conflict.getStatusCode().value());
        assertEquals("private, no-store", conflict.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        doThrow(new IllegalArgumentException("invalid")).when(agentService).updateAbilities("agent-a", request);
        ResponseEntity<?> invalid = assertInstanceOf(ResponseEntity.class,
                controller.updateAbilities("agent-a", wire, jwt("Owner-A", "Client-A")));
        assertEquals(400, invalid.getStatusCode().value());
    }

    @Test
    void bindPersonaNeverAllowsSensitiveOutput() throws Exception {
        Method method = AgentController.class.getDeclaredMethod(
                "bindPersonaHttp", String.class, Authentication.class, jakarta.servlet.http.HttpServletRequest.class, byte[].class);
        assertNull(method.getAnnotation(AllowSensitiveOutput.class));
    }

    @Test
    void exactJwtScopeIsForwardedByteExactForEveryPublicOperation() {
        JwtAuthenticationToken auth = jwt("Owner-A", "Client-A");

        Object catalogResponse = controller.personaCatalog(auth);
        var rent = mock(HostingRentApplicationService.class);
        controller.setHostingRent(rent);
        var removal = assertInstanceOf(ResponseEntity.class, controller.unbindPersona("wuyong", auth,
                "{\"agentId\":\"agt_0123456789abcdef0123456789abcdef\",\"leaseId\":\"lease-original\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("private, no-store", removal.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(rent).remove(new HostingRentHttp.Actor("login-sub", "0", "Client-A", "Owner-A"),
                "wuyong", "agt_0123456789abcdef0123456789abcdef", "lease-original");

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, catalogResponse);
        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        var scope = new cn.jia.agent.service.AgentOwnerScope(
                "0", "Client-A", "Owner-A");
        verify(agentService).listPersonaCatalog("0", "Client-A", "Owner-A");
        verifyNoInteractions(provisioning);
        verifyNoMoreInteractions(provisioning);
    }

    @Test
    void tenantOneJwtCatalogReturnsOkAndForwardsExactMinimalScope() {
        JwtAuthenticationToken auth = jwtTenant("1", "owner-a", "cyf-local");

        ResponseEntity<?> response = assertInstanceOf(
                ResponseEntity.class, controller.personaCatalog(auth));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("private, no-store",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(agentService).listPersonaCatalog("1", "cyf-local", "owner-a");
        verifyNoInteractions(provisioning);
    }

    @Test
    void teamRecommendationRequiresExactJwtScopeAndReturnsNoStorePreview() throws Exception {
        AgentTaskTeamRecommendationRequestDTO request = new AgentTaskTeamRecommendationRequestDTO();
        request.setMaxTeamSize(3);
        request.setBudgetUnits(3);
        request.setHighRisk(false);
        AgentTaskTeamRecommendationDTO recommendation = new AgentTaskTeamRecommendationDTO();
        recommendation.setTaskId("task-001");
        when(agentService.recommendTaskTeam(
                "0", "Client-A", "task-001", request)).thenReturn(recommendation);

        Object raw = controller.recommendTaskTeam(
                "task-001", new cn.jia.core.entity.JsonRequest<>(1, "test-request", request), jwt("Owner-A", "Client-A"));

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, raw);
        assertEquals("private, no-store",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(agentService).recommendTaskTeam(
                "0", "Client-A", "task-001", request);
        Method method = AgentController.class.getDeclaredMethod(
                "recommendTaskTeam", String.class,
                cn.jia.core.entity.JsonRequest.class, Authentication.class);
        assertArrayEquals(new String[]{"/tasks/{taskId}/team-recommendation"},
                method.getAnnotation(PostMapping.class).value());
        assertNull(method.getAnnotation(AllowSensitiveOutput.class));
    }

    @Test
    void teamRecommendationRejectsInvalidAuthenticationWithoutServiceCall() {
        AgentTaskTeamRecommendationRequestDTO request = new AgentTaskTeamRecommendationRequestDTO();
        request.setMaxTeamSize(1);
        request.setBudgetUnits(1);
        request.setHighRisk(false);
        List<Authentication> invalid = java.util.Arrays.asList(
                null,
                UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of()),
                jwt(" Owner-A", "Client-A"),
                jwt("Owner-A", "Client-A\n"),
                jwt("0", "Client-A"));

        for (Authentication authentication : invalid) {
            AgentBizException failure = assertThrows(AgentBizException.class,
                    () -> controller.recommendTaskTeam(
                            "task-001", new cn.jia.core.entity.JsonRequest<>(1, "test-request", request), authentication));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                    failure.getCode());
        }
        verifyNoInteractions(agentService);
    }

    @Test
    void bothDeploymentModesUsePaidBoundaryWithExactActor() {
        var rent = mock(HostingRentApplicationService.class); controller.setHostingRent(rent);
        when(rent.bind(any(), anyString(), anyString(), any())).thenReturn(
                new HostingRentApplicationService.MutationView("agent", "intent", "lease", "quote", "tx", "FUNDS_RESERVED",
                        "WEN", "50", "2592000", "2000"));
        for (String mode : List.of("server", "local")) {
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            request.addHeader("Idempotency-Key", "00000000-0000-0000-0000-000000000001");
            var body = ("{\"mode\":\"" + mode + "\",\"hostingAction\":\"INITIAL\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var response = assertInstanceOf(ResponseEntity.class, controller.bindPersonaHttp("wuyong", jwt("Owner-A", "Client-A"), request, body));
            assertEquals(202, response.getStatusCode().value());
            verify(rent).bind(new HostingRentHttp.Actor("login-sub", "0", "Client-A", "Owner-A"), "wuyong",
                    "00000000-0000-0000-0000-000000000001", java.util.Map.of("mode", mode, "hostingAction", "INITIAL"));
        }
        verifyNoInteractions(provisioning, agentService);
    }

    @Test
    void nullDefaultAndLocalWithoutQuoteKeyCannotCreateFreeRelationship() {
        var rent = mock(HostingRentApplicationService.class); controller.setHostingRent(rent);
        for (String body : List.of("null", "{}", "{\"mode\":null}", "{\"mode\":\" \"}", "{\"mode\":\"local\"}")) {
            assertThrows(HostingRentApplicationException.class, () -> controller.bindPersonaHttp("wuyong", jwt("Owner-A", "Client-A"),
                    new org.springframework.mock.web.MockHttpServletRequest(), body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        verifyNoInteractions(rent, provisioning, agentService);
    }

    @Test
    void invalidAuthenticationAndClaimsHaveZeroProvisioningSideEffects() {
        List<Authentication> invalid = new java.util.ArrayList<>();
        invalid.add(null);
        invalid.add(UsernamePasswordAuthenticationToken.authenticated("user", "n/a", List.of()));
        invalid.add(jwtClaims(null, "client"));
        invalid.add(jwtClaims("owner", null));
        invalid.add(jwtClaims(7, "client"));
        invalid.add(jwtClaims("owner", 7));
        invalid.add(jwt(" owner", "client"));
        invalid.add(jwt("owner", "client\u00a0"));
        invalid.add(jwt("owner\n", "client"));
        invalid.add(jwt("owner\ud800", "client"));
        invalid.add(jwt("0", "client"));
        invalid.add(jwt("o".repeat(51), "client"));

        for (Authentication authentication : invalid) {
            assertThrows(AgentBizException.class,
                    () -> controller.personaCatalog(authentication));
            assertThrows(HostingRentApplicationException.class,
                    () -> controller.bindPersonaHttp("wuyong", authentication, new org.springframework.mock.web.MockHttpServletRequest(), "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThrows(HostingRentApplicationException.class,
                    () -> controller.unbindPersona("wuyong", authentication, "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        verifyNoInteractions(provisioning, agentService);
    }

    @Test
    void removalRequiresExplicitLeaseAndNeverFallsBackToDestructiveUnbind() {
        var rent = mock(HostingRentApplicationService.class); controller.setHostingRent(rent);
        for (String body : List.of("{}", "{\"agentId\":\"agent\"}", "{\"leaseId\":\"lease\"}")) {
            assertThrows(HostingRentApplicationException.class, () -> controller.unbindPersona(
                    "wuyong", jwt("Owner-A", "Client-A"), body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        verifyNoInteractions(rent, provisioning, agentService);
    }

    private static JwtAuthenticationToken jwt(String jiacn, String clientId) {
        return jwtClaims(jiacn, clientId);
    }

    private static JwtAuthenticationToken jwtTenant(
            String tenantId, String jiacn, String clientId) {
        Jwt token = Jwt.withTokenValue("token").subject("login-sub").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("tenant_id", tenantId).claim("tenant_claim_version", "1")
                .claim("jiacn", jiacn).claim("client_id", clientId).build();
        return new JwtAuthenticationToken(token, List.of());
    }

    private static JwtAuthenticationToken jwtClaims(Object jiacn, Object clientId) {
        Jwt.Builder builder = Jwt.withTokenValue("token").subject("login-sub").claim("tenant_id", "0").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (jiacn != null) builder.claim("jiacn", jiacn);
        if (clientId != null) builder.claim("client_id", clientId);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
