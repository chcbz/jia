package cn.jia.agent.api.funding;

import cn.jia.agent.entity.funding.*;
import cn.jia.agent.service.funding.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary unit source only; real SQL/transaction proof is in FundedBountySettlementRealTransactionTest. */
class AgentBountySettlementControllerTest {
    private static final String KEY = "00000000-0000-0000-0000-000000000004";

    @Test
    void exactJwtSubjectAndCanonicalStringBodyReachServiceWithoutMonetaryOverride() {
        AtomicReference<FundedBountyActor> actor = new AtomicReference<>();
        AtomicReference<AgentTaskFundingCompleteDTO> request = new AtomicReference<>();
        FundedBountySettlementService boundary = new FundedBountySettlementService() {
            @Override public AgentTaskSettlementReceiptDTO complete(FundedBountyActor value, String key,
                    String taskId, AgentTaskFundingCompleteDTO body) {
                actor.set(value); request.set(body); assertEquals(KEY, key); assertEquals("task", taskId);
                return new AgentTaskSettlementReceiptDTO("task", "SETTLED", "q", "agt", "esc", "10", "1", "1", "8",
                        "0", "0", "2", "2", "4", "1", List.of("tx"));
            }
            @Override public AgentTaskSettlementDTO read(FundedBountyActor value, String taskId) {
                actor.set(value); return null;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(); beans.addBean("settlement", boundary);
        AgentBountySettlementController controller = new AgentBountySettlementController(beans.getBeanProvider(FundedBountySettlementService.class));
        var response = controller.complete("task", Map.of("expectedTaskVersion", "1", "actualComputeMicro", "1"), KEY, jwt("sub", "Tenant", "Client"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(new FundedBountyActor("Tenant", "Client", "sub"), actor.get());
        assertEquals(new AgentTaskFundingCompleteDTO("1", "1"), request.get());
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("expectedTaskVersion", 1, "actualComputeMicro", "1"),
                Map.of("expectedTaskVersion", "1", "actualComputeMicro", 1),
                Map.of("expectedTaskVersion", "1", "actualComputeMicro", "1", "platformFeeMicro", "0"),
                Map.of("expectedTaskVersion", "1", "actualComputeMicro", "1", "agentId", "another"))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(FundedBountyException.class,
                    () -> controller.complete("task", body, KEY, jwt("sub", "Tenant", "Client"))).status());
        }
    }

    @Test
    void absentPreviewServiceAndIncompleteOrFallbackIdentitiesFailClosed() {
        AgentBountySettlementController controller = new AgentBountySettlementController(
                new StaticListableBeanFactory().getBeanProvider(FundedBountySettlementService.class));
        assertEquals("ECONOMY_PREVIEW_DISABLED", assertThrows(FundedBountyException.class,
                () -> controller.read("task", jwt("sub", "Tenant", "Client"))).code());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(FundedBountyException.class, () -> controller.read("task", null)).status());
        assertThrows(FundedBountyException.class, () -> controller.read("task",
                new UsernamePasswordAuthenticationToken("fallback", "unused", List.of())));
        assertThrows(FundedBountyException.class, () -> controller.read("task",
                new JwtAuthenticationToken(jwt("sub", "Tenant", "Client").getToken(), List.of(), "fallback")));
    }

    @Test
    void unauthenticatedJwtWithCompleteScopeIsRejectedBeforePreviewLookup() {
        AgentBountySettlementController controller = new AgentBountySettlementController(
                new StaticListableBeanFactory().getBeanProvider(FundedBountySettlementService.class));
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt("sub", "Tenant", "Client").getToken());
        assertFalse(authentication.isAuthenticated());
        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> controller.read("task", authentication));
        assertEquals(HttpStatus.UNAUTHORIZED, failure.status());
        assertEquals("ECONOMY_UNAUTHENTICATED", failure.code());
    }

    @Test
    void authenticatedJwtMissingClientScopeIsForbiddenBeforePreviewLookup() {
        AgentBountySettlementController controller = new AgentBountySettlementController(
                new StaticListableBeanFactory().getBeanProvider(FundedBountySettlementService.class));
        Jwt missingScope = Jwt.withTokenValue("fixture").header("alg", "none").subject("sub").claim("jiacn", "Tenant").build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(missingScope, List.of());
        assertTrue(authentication.isAuthenticated());
        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> controller.read("task", authentication));
        assertEquals(HttpStatus.FORBIDDEN, failure.status());
        assertEquals("ECONOMY_FORBIDDEN", failure.code());
    }

    private static JwtAuthenticationToken jwt(String subject, String tenant, String client) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").header("alg", "none")
                .subject(subject).claim("jiacn", tenant).claim("client_id", client).build(), List.of());
        assertTrue(authentication.isAuthenticated());
        return authentication;
    }
}
