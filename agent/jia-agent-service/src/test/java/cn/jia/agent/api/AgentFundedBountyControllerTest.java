package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.funding.AgentSkillRequirementDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.funding.FundedBountyActor;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.agent.service.funding.FundedBountyService;
import cn.jia.core.entity.JsonResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentFundedBountyControllerTest {
    @Test
    void legacyCreateRemainsUnfundedWithoutJwtOrIdempotencyHeader() {
        AgentService legacy = mock(AgentService.class);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("legacy");
        when(legacy.createTask(request)).thenReturn(new AgentTaskDTO());
        AgentController controller = new AgentController(
                legacy, mock(AbilityEvaluationService.class), mock(FundedBountyService.class));

        controller.createTask(request, null, null);

        verify(legacy).createTask(request);
    }

    @Test
    void partialFundingBodyFailsClosedWithoutJwtAndNeverCallsLegacyCreate() {
        AgentService legacy = mock(AgentService.class);
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("funded");
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        AgentController controller = new AgentController(
                legacy, mock(AbilityEvaluationService.class), mock(FundedBountyService.class));

        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> controller.createTask(request, null, null));

        assertEquals("ECONOMY_UNAUTHENTICATED", failure.code());
        verify(legacy, never()).createTask(any());
    }

    @Test
    void fundedCreateUsesOnlyExactJwtClaimsAndCanonicalSemanticDigest() {
        AgentService legacy = mock(AgentService.class);
        FundedBountyService funded = mock(FundedBountyService.class);
        AgentTaskCreateDTO request = fundedRequest();
        when(funded.create(any(), eq("018f0000-0000-7000-8000-000000000004"), any(), eq(request)))
                .thenReturn(new AgentTaskDTO());
        AgentController controller = new AgentController(
                legacy, mock(AbilityEvaluationService.class), funded);

        controller.createTask(request, "018f0000-0000-7000-8000-000000000004",
                jwt("user-1", "Tenant-A", "Client-A"));
        controller.createTask(request, "018f0000-0000-7000-8000-000000000004",
                jwt("user-1", "Tenant-A", "Client-A"));

        ArgumentCaptor<FundedBountyActor> actor = ArgumentCaptor.forClass(FundedBountyActor.class);
        ArgumentCaptor<byte[]> digest = ArgumentCaptor.forClass(byte[].class);
        verify(funded, org.mockito.Mockito.times(2)).create(actor.capture(),
                eq("018f0000-0000-7000-8000-000000000004"), digest.capture(), eq(request));
        assertEquals(new FundedBountyActor("Tenant-A", "Client-A", "user-1"), actor.getAllValues().getFirst());
        assertArrayEquals(digest.getAllValues().getFirst(), digest.getAllValues().getLast());
        verify(legacy, never()).createTask(any());
    }

    @Test
    void cancelRejectsNonCanonicalVersionBeforeServiceMutation() {
        FundedBountyService funded = mock(FundedBountyService.class);
        AgentController controller = new AgentController(mock(AgentService.class),
                mock(AbilityEvaluationService.class), funded);
        AgentTaskFundingCancelDTO request = new AgentTaskFundingCancelDTO();
        request.setExpectedTaskVersion("01");

        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> controller.cancelTaskFunding("task-1", request,
                        "018f0000-0000-7000-8000-000000000005",
                        jwt("user-1", "Tenant-A", "Client-A")));

        assertEquals("BAD_REQUEST", failure.code());
        verify(funded, never()).cancel(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void cancelPassesExactScopeAndParsedVersion() {
        FundedBountyService funded = mock(FundedBountyService.class);
        AgentTaskFundingCancelDTO request = new AgentTaskFundingCancelDTO();
        request.setExpectedTaskVersion("8");
        when(funded.cancel(any(), any(), any(), eq("task-1"), eq(8L))).thenReturn(
                new AgentTaskFundingCancelReceiptDTO("task-1", "REFUNDED", "etx-1",
                        "10", "0", "9", "2", "100"));
        AgentController controller = new AgentController(mock(AgentService.class),
                mock(AbilityEvaluationService.class), funded);

        Object raw = controller.cancelTaskFunding("task-1", request,
                "018f0000-0000-7000-8000-000000000005",
                jwt("user-1", "Tenant-A", "Client-A"));

        assertEquals("task-1", ((AgentTaskFundingCancelReceiptDTO) ((JsonResult<?>) raw).getData()).taskId());
        verify(funded).cancel(eq(new FundedBountyActor("Tenant-A", "Client-A", "user-1")),
                eq("018f0000-0000-7000-8000-000000000005"), any(), eq("task-1"), eq(8L));
    }

    private static AgentTaskCreateDTO fundedRequest() {
        AgentTaskCreateDTO request = new AgentTaskCreateDTO();
        request.setTitle("funded");
        request.setDescription("body");
        request.setRequiredAbilities(List.of("test"));
        request.setGrossBountyAmountMicro("1000000");
        request.setSettlementPolicy("GROSS_INCLUSIVE");
        AgentSkillRequirementDTO skill = new AgentSkillRequirementDTO();
        skill.setSkillKey("repo-test");
        skill.setVersionRange(">=1.0.0");
        request.setRequiredSkillRequirements(List.of(skill));
        return request;
    }

    private static JwtAuthenticationToken jwt(String subject, String tenant, String client) {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", tenant).claim("client_id", client).subject(subject)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of());
    }
}
