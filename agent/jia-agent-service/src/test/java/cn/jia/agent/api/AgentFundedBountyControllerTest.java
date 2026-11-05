package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.core.entity.JsonRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Retired money requests cannot debit, create a replacement task, or enter quoted assignment. */
class AgentFundedBountyControllerTest {
    @Test
    void ordinaryCreateUsesTrustedJwtWithoutRequiringIdempotencyHeader() {
        AgentService service = mock(AgentService.class);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("ordinary");
        when(service.createTask(any(AgentService.TaskOwnerScope.class), same(request)))
                .thenReturn(new AgentTaskDTO());
        controller(service).createTask(body(request), null, jwt());
        verify(service).createTask(new AgentService.TaskOwnerScope("0", "Client-A", "owner-1"), request);
        verifyNoMoreInteractions(service);
    }

    @Test
    void ordinaryCreateRejectsMissingIdentityBeforeService() {
        AgentService service = mock(AgentService.class);
        assertThrows(RuntimeException.class, () -> controller(service).createTask(
                body(new AgentTaskCreateDTO()), null, null));
        verifyNoInteractions(service);
    }

    @Test
    void everyRetiredFundingFieldIsRejectedEvenWhenBlankOrEmpty() {
        List<Consumer<AgentTaskCreateDTO>> oldBodies = List.of(
                request -> request.setGrossBountyAmountWen("100"),
                request -> request.setGrossBountyAmountWen("0"),
                request -> request.setGrossBountyAmountWen(""),
                request -> request.setSettlementPolicy("GROSS_INCLUSIVE"),
                request -> request.setSettlementPolicy(""),
                request -> request.setRequiredSkillRequirements(List.of()));
        AgentService service = mock(AgentService.class);
        AgentController controller = controller(service);
        for (var configure : oldBodies) {
            AgentTaskCreateDTO request = new AgentTaskCreateDTO();
            request.setTitle("original funded request");
            configure.accept(request);
            var failure = assertThrows(FundedBountyException.class,
                    () -> controller.createTask(body(request), "original-key", jwt()));
            assertEquals("FUNDED_BOUNTY_RETIRED", failure.code());
            assertEquals(HttpStatus.GONE, failure.status());
        }
        verifyNoInteractions(service);
    }

    @Test
    void retryOfRetiredCreateNeverCreatesAnOrdinaryReplacement() {
        AgentService service = mock(AgentService.class);
        AgentController controller = controller(service);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setGrossBountyAmountWen("100");
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(FundedBountyException.class,
                    () -> controller.createTask(body(request), "same-original-key", jwt()));
        }
        verifyNoInteractions(service);
    }

    @Test
    void retiredFundingBodyDoesNotBypassJwtAuthentication() {
        AgentService service = mock(AgentService.class);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        assertThrows(cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException.class,
                () -> controller(service).createTask(body(request), "original-key", null));
        verifyNoInteractions(service);
    }

    @Test
    void legacyFundingWriteRoutesAndDependenciesAreAbsent() {
        Set<String> retired = Set.of("/tasks/{taskId}/funding/cancel",
                "/tasks/{taskId}/quotes", "/tasks/{taskId}/claim");
        for (var method : AgentController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping != null) {
                assertTrue(Arrays.stream(mapping.value()).noneMatch(retired::contains));
                assertTrue(Arrays.stream(mapping.path()).noneMatch(retired::contains));
            }
        }
        for (var field : AgentController.class.getDeclaredFields()) {
            assertFalse(field.getType().getSimpleName().startsWith("FundedBounty"));
        }
        // Historical reads stay available, but no quote/claim compatibility redirect is installed.
        assertDoesNotThrow(() -> AgentController.class.getMethod("getTask", String.class));
        assertFalse(Arrays.stream(AgentController.class.getDeclaredMethods())
                .anyMatch(method -> Set.of("quoteTask", "claimTask", "cancelTaskFunding").contains(method.getName())));
    }

    private static AgentController controller(AgentService service) {
        return new AgentController(service, mock(AbilityEvaluationService.class));
    }

    private static JsonRequest<AgentTaskCreateDTO> body(AgentTaskCreateDTO request) {
        return new JsonRequest<>(1, "test-request", request);
    }

    private static JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("tenant_id", "0").claim("jiacn", "owner-1")
                .claim("client_id", "Client-A").subject("payer-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
