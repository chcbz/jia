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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Historical read authentication and retired write boundary. */
class AgentBountySettlementControllerTest {
    @Test
    void authenticatedHistoryReadPreservesExactPayerAndOwner() {
        AtomicReference<FundedBountyActor> actor = new AtomicReference<>();
        FundedBountySettlementService boundary = (value, taskId) -> {
            actor.set(value); assertEquals("task", taskId); return null;
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory(); beans.addBean("settlement", boundary);
        AgentBountySettlementController controller = new AgentBountySettlementController(beans.getBeanProvider(FundedBountySettlementService.class));
        var response = controller.read("task", jwt("payer", "Owner", "Client"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("private, no-store", response.getHeaders().getFirst("Cache-Control"));
        assertEquals(new FundedBountyActor("0", "Client", "Owner", "payer"), actor.get());
    }

    @Test
    void completionRouteAndCommandAreAbsentRatherThanRedirected() {
        assertFalse(java.util.Arrays.stream(AgentBountySettlementController.class.getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)));
        for (Class<?> type : List.of(FundedBountySettlementService.class, FundedBountySettlementServiceImpl.class,
                FundedBountyService.class, FundedBountyServiceImpl.class)) {
            assertFalse(java.util.Arrays.stream(type.getMethods()).anyMatch(method ->
                    java.util.Set.of("create", "cancel", "complete", "quote", "claim").contains(method.getName())));
        }
        assertThrows(ClassNotFoundException.class, () -> Class.forName("cn.jia.agent.service.funding.FundedBountyQuoteClaimServiceImpl"));
    }

    @Test
    void absentPreviewServiceAndIncompleteOrFallbackIdentitiesFailClosed() {
        AgentBountySettlementController controller = new AgentBountySettlementController(
                new StaticListableBeanFactory().getBeanProvider(FundedBountySettlementService.class));
        assertEquals("FUNDED_HISTORY_UNAVAILABLE", assertThrows(FundedBountyException.class,
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
        Jwt missingScope = Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg", "none").subject("sub").claim("jiacn", "Tenant").build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(missingScope, List.of());
        assertTrue(authentication.isAuthenticated());
        FundedBountyException failure = assertThrows(FundedBountyException.class,
                () -> controller.read("task", authentication));
        assertEquals(HttpStatus.FORBIDDEN, failure.status());
        assertEquals("ECONOMY_FORBIDDEN", failure.code());
    }

    private static JwtAuthenticationToken jwt(String subject, String tenant, String client) {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(Jwt.withTokenValue("fixture").claim("tenant_id", "0").header("alg", "none")
                .subject(subject).claim("jiacn", tenant).claim("client_id", client).build(), List.of());
        assertTrue(authentication.isAuthenticated());
        return authentication;
    }
}
