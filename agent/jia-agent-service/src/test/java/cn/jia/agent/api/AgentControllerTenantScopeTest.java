package cn.jia.agent.api;

import cn.jia.agent.entity.AgentTaskCreateDTO;
import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskTeamRecommendationRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelDTO;
import cn.jia.agent.hosting.HostingRentApplicationService;
import cn.jia.agent.service.AbilityEvaluationService;
import cn.jia.agent.service.AgentPersonaProvisioningService;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.funding.FundedBountyActor;
import cn.jia.agent.service.funding.FundedBountyException;
import cn.jia.agent.service.funding.FundedBountyQuoteClaimService;
import cn.jia.agent.service.funding.FundedBountyService;
import cn.jia.agent.service.impl.AgentServiceImpl.AgentBizException;
import cn.jia.core.entity.JsonRequest;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Exact region binding for the two legacy owner/money actor factories only. */
class AgentControllerTenantScopeTest {
    private final AgentService agent = mock(AgentService.class);
    private final FundedBountyService funding = mock(FundedBountyService.class);
    private final FundedBountyQuoteClaimService quoteClaim = mock(FundedBountyQuoteClaimService.class);
    private final AgentController controller = new AgentController(agent,
            mock(AbilityEvaluationService.class), funding, quoteClaim);

    @Test
    void otherAreaOrDomainCannotEnterOwnerOrFundedBountyServices() {
        AgentTaskCreateDTO funded = new AgentTaskCreateDTO();
        funded.setGrossBountyAmountMicro("1000000");
        AgentTaskFundingCancelDTO cancel = new AgentTaskFundingCancelDTO();
        cancel.setExpectedTaskVersion("1");
        for (JwtAuthenticationToken auth : List.of(
                jwtForArea("1", "cyf"), jwtForArea("00", "cyf"),
                jwtForArea("0", "other"), jwtForArea(null, "cyf"),
                jwtForArea("0", null))) {
            AgentBizException ownerFailure = assertThrows(AgentBizException.class,
                    () -> controller.personaCatalog(auth));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                    ownerFailure.getCode());
            assertThrows(AgentBizException.class,
                    () -> controller.recommendTaskTeam("task-1",
                            new JsonRequest<>(1, "test-request", new AgentTaskTeamRecommendationRequestDTO()), auth));
            FundedBountyException createFailure = assertThrows(FundedBountyException.class,
                    () -> controller.createTask(new JsonRequest<>(1, "test-request", funded), "idempotency-key-01", auth));
            assertEquals(HttpStatus.FORBIDDEN, createFailure.status());
            assertEquals("ECONOMY_FORBIDDEN", createFailure.code());
            assertThrows(FundedBountyException.class,
                    () -> controller.cancelTaskFunding("task-1", new JsonRequest<>(1, "test-request", cancel),
                            "idempotency-key-01", auth));
            assertThrows(FundedBountyException.class,
                    () -> controller.quoteTask("task-1", null,
                            "idempotency-key-01", auth));
            assertThrows(FundedBountyException.class,
                    () -> controller.claimTask("task-1", null,
                            "idempotency-key-01", auth));
        }
        verifyNoInteractions(agent, funding, quoteClaim);
    }

    @Test
    void regionOneCannotCreateUnfundedLegacyTaskOrBindPaidHosting() {
        HostingRentApplicationService rent = mock(HostingRentApplicationService.class);
        controller.setHostingRent(rent);
        AgentTaskCreateDTO legacy = new AgentTaskCreateDTO();
        legacy.setTitle("legacy");
        byte[] body = "{\"mode\":\"server\"}".getBytes(StandardCharsets.UTF_8);
        for (JwtAuthenticationToken auth : List.of(
                jwtForArea("1", "cyf"), jwtForArea("00", "cyf"),
                jwtForArea("0", "other"), jwtForArea(null, "cyf"),
                jwtForArea("0", null))) {
            AgentBizException failure = assertThrows(AgentBizException.class,
                    () -> controller.createTask(new JsonRequest<>(1, "test-request", legacy), null, auth));
            assertEquals(cn.jia.agent.common.AgentErrorConstants.AGENT_FORBIDDEN,
                    failure.getCode());
            Object result = controller.bindPersonaHttp("wuyong", auth,
                    new MockHttpServletRequest(), body);
            assertEquals(HttpStatus.FORBIDDEN,
                    assertInstanceOf(ResponseEntity.class, result).getStatusCode());
        }
        verifyNoInteractions(agent, rent);
    }

    @Test
    void exactRegionZeroStillCreatesUnfundedLegacyTask() {
        AgentTaskCreateDTO legacy = new AgentTaskCreateDTO();
        legacy.setTitle("legacy");
        when(agent.createTask(legacy)).thenReturn(new AgentTaskDTO());
        TrustedTenantContext.runWithScope(scope("0"), () ->
                controller.createTask(new JsonRequest<>(1, "test-request", legacy), null, jwtForArea("0", "cyf")));
        verify(agent).createTask(legacy);
    }

    @Test
    void exactRegionZeroUsesExistingLegacyScope() {
        JwtAuthenticationToken auth = jwtForArea("0", "cyf");
        TrustedTenantContext.runWithScope(scope("0"), () -> {
            controller.personaCatalog(auth);
            AgentTaskFundingCancelDTO cancel = new AgentTaskFundingCancelDTO();
            cancel.setExpectedTaskVersion("1");
            controller.cancelTaskFunding("task-1", new JsonRequest<>(1, "test-request", cancel), "idempotency-key-01", auth);
        });
        verify(agent).listPersonaCatalog("0", "client-a", "owner-a");
        verify(funding).cancel(eq(new FundedBountyActor("0", "client-a", "owner-a", "user-a")),
                eq("idempotency-key-01"), any(), eq("task-1"), eq(1L));
    }

    @Test
    void regionOneOrdinaryLocalBindUsesBusinessScopeButHostedOperationsStayClosed() {
        AgentPersonaProvisioningService provisioning = mock(AgentPersonaProvisioningService.class);
        AgentController localController = new AgentController(
                agent, mock(AbilityEvaluationService.class), provisioning);
        JwtAuthenticationToken auth = jwtForArea("1", "cyf");
        cn.jia.agent.entity.AgentPersonaBindRequestDTO request =
                new cn.jia.agent.entity.AgentPersonaBindRequestDTO();
        request.setMode("local");
        BusinessScope scope = scope("1");

        TrustedTenantContext.runWithScope(scope, () ->
                localController.bindPersona("wuyong", request, auth));

        verify(provisioning).bindLocal(scope, "wuyong");
        TrustedTenantContext.runWithScope(scope, () -> {
            assertThrows(AgentBizException.class,
                    () -> localController.repairPersonaBinding(1L, auth));
            assertThrows(AgentBizException.class,
                    () -> localController.unbindPersona("wuyong", auth));
        });
        verifyNoMoreInteractions(provisioning);
    }

    private static BusinessScope scope(String tenantId) {
        return new BusinessScope(
                TenantId.of(tenantId), DataDomainId.of("cyf"), "owner-a", "client-a");
    }

    private static JwtAuthenticationToken jwtForArea(String area, String domain) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "owner-a").claim("client_id", "client-a").subject("user-a")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (area != null) builder.claim("tenant_id", area);
        if (domain != null) builder.claim("data_domain_id", domain);
        return new JwtAuthenticationToken(builder.build(), List.of());
    }
}
