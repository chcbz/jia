package cn.jia.agent.api;

import cn.jia.agent.entity.AgentPersonaBindRequestDTO;
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
            agentService, mock(AbilityEvaluationService.class), provisioning);

    @Test
    void bindPersonaNeverAllowsSensitiveOutput() throws Exception {
        Method method = AgentController.class.getDeclaredMethod(
                "bindPersona", String.class, AgentPersonaBindRequestDTO.class, Authentication.class);
        assertNull(method.getAnnotation(AllowSensitiveOutput.class));
    }

    @Test
    void exactJwtScopeIsForwardedByteExactForEveryPublicOperation() {
        JwtAuthenticationToken auth = jwt("Owner-A", "Client-A");
        AgentPersonaBindRequestDTO request = new AgentPersonaBindRequestDTO();
        request.setMode("local");

        Object catalogResponse = controller.personaCatalog(auth);
        controller.bindPersona("wuyong", request, auth);
        controller.repairPersonaBinding(17L, auth);
        controller.unbindPersona("wuyong", auth);

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, catalogResponse);
        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        var scope = new cn.jia.agent.service.AgentHostedBindingTransaction.Scope(
                "Owner-A", "Client-A", "Owner-A");
        verify(agentService).listPersonaCatalog("Owner-A", "Client-A", "Owner-A");
        verify(provisioning).bind(scope, "wuyong", "local");
        verify(provisioning).repair(scope, 17L);
        verify(provisioning).unbind(scope, "wuyong");
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
                "Owner-A", "Client-A", "task-001", request)).thenReturn(recommendation);

        Object raw = controller.recommendTaskTeam(
                "task-001", request, jwt("Owner-A", "Client-A"));

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, raw);
        assertEquals("private, no-store",
                response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        verify(agentService).recommendTaskTeam(
                "Owner-A", "Client-A", "task-001", request);
        Method method = AgentController.class.getDeclaredMethod(
                "recommendTaskTeam", String.class,
                AgentTaskTeamRecommendationRequestDTO.class, Authentication.class);
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
                            "task-001", request, authentication));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                    failure.getCode());
        }
        verifyNoInteractions(agentService);
    }

    @Test
    void directServerModeCannotBypassPaidHostingRentBoundary() {
        AgentPersonaBindRequestDTO request = new AgentPersonaBindRequestDTO();
        request.setMode("server");

        var failure = assertThrows(cn.jia.agent.service.HostingRentAdmissionException.class,
                () -> controller.bindPersona("wuyong", request, jwt("owner", "client")));

        assertEquals(cn.jia.agent.service.HostingRentAdmissionException.Reason.HOSTING_RENT_NOT_READY,
                failure.reason());
        verifyNoInteractions(provisioning, agentService);
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

        AgentPersonaBindRequestDTO request = new AgentPersonaBindRequestDTO();
        request.setMode("server");
        for (Authentication authentication : invalid) {
            assertThrows(AgentBizException.class,
                    () -> controller.personaCatalog(authentication));
            assertThrows(AgentBizException.class,
                    () -> controller.bindPersona("wuyong", request, authentication));
            assertThrows(AgentBizException.class,
                    () -> controller.repairPersonaBinding(1L, authentication));
            assertThrows(AgentBizException.class,
                    () -> controller.unbindPersona("wuyong", authentication));
        }
        verifyNoInteractions(provisioning, agentService);
    }

    private static JwtAuthenticationToken jwt(String jiacn, String clientId) {
        return jwtClaims(jiacn, clientId);
    }

    private static JwtAuthenticationToken jwtClaims(Object jiacn, Object clientId) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (jiacn != null) builder.claim("jiacn", jiacn);
        if (clientId != null) builder.claim("client_id", clientId);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
