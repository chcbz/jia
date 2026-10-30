package cn.jia.agent.security;

import cn.jia.agent.api.*;
import cn.jia.agent.api.funding.AgentBountySettlementController;
import cn.jia.agent.conversation.ConversationDeliverableController;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.economy.api.EconomyWalletController;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary mapping only: process admission and persistence are covered by their own suites. */
class TenantActorAdaptersTest {
    record Adapter(Class<?> type, String method) {
        Object resolve(Authentication auth) {
            return type == AgentWorkItemReassignmentController.class
                    ? ReflectionTestUtils.invokeMethod(type, method, auth, false)
                    : ReflectionTestUtils.invokeMethod(type, method, auth);
        }
    }

    static Stream<Adapter> adapters() {
        return Stream.of(
                new Adapter(AgentTaskArtifactController.class, "requireJwtScope"),
                new Adapter(AgentTaskArtifactOutcomeController.class, "requireJwtScope"),
                new Adapter(AgentTaskDeliverableController.class, "requireJwtScope"),
                new Adapter(AgentTaskFormalDeliveryController.class, "requireJwtScope"),
                new Adapter(AgentWorkItemPlanController.class, "requireJwtScope"),
                new Adapter(AgentWorkItemReassignmentController.class, "principal"),
                new Adapter(HallRequestDraftController.class, "scope"),
                new Adapter(AgentHostingRentController.class, "requirePrincipal"),
                new Adapter(ConversationDeliverableController.class, "requireJwtScope"),
                new Adapter(AgentController.class, "requireJwtScope"),
                new Adapter(AgentController.class, "requireMoneyActor"),
                new Adapter(AgentBountySettlementController.class, "actor"),
                new Adapter(HostingRentHttp.class, "actor"),
                new Adapter(EconomyWalletController.class, "requireSubject"));
    }

    @ParameterizedTest @MethodSource("adapters")
    void preservesEveryExplicitPartition(Adapter adapter) {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            Object scope = adapter.resolve(jwt(Map.of("tenant_id", tenant)));
            assertNotNull(scope);
            if (adapter.type() == EconomyWalletController.class) {
                scope = ReflectionTestUtils.invokeMethod(scope, "scope");
            }
            assertEquals(tenant, ReflectionTestUtils.invokeMethod(scope, "tenantId"), adapter.toString());
            assertEquals("client", ReflectionTestUtils.invokeMethod(scope, "clientId"), adapter.toString());
        }
    }

    @ParameterizedTest @MethodSource("adapters")
    void missingScopeCannotBecomeProduction(Adapter adapter) {
        // Canonical/alias/version/format rules are tested once in TenantClaimPolicyTest.
        assertThrows(RuntimeException.class, () -> adapter.resolve(jwt(Map.of())), adapter.toString());
    }

    @ParameterizedTest @MethodSource("adapters")
    void genericPrincipalCannotSupplyBusinessScope(Adapter adapter) {
        var auth = new UsernamePasswordAuthenticationToken("actor", "synthetic", List.of());
        assertThrows(RuntimeException.class, () -> adapter.resolve(auth), adapter.toString());
    }

    @Test void narrowRuntimeReassignmentPreservesScopeButCannotGainOwnerAuthority() {
        for (String tenant : List.of("0", "1", "tenant-blue")) {
            var auth = new AgentRuntimeAuthentication(new AgentRuntimeAuthentication.Scope(
                    tenant, "client", "owner", "agent", "runtime"));
            Object actor = new Adapter(AgentWorkItemReassignmentController.class, "principal").resolve(auth);
            assertEquals(tenant, ReflectionTestUtils.invokeMethod(actor, "tenantId"));
            assertEquals("owner", ReflectionTestUtils.invokeMethod(actor, "ownerJiacn"));
            assertEquals("agent", ReflectionTestUtils.invokeMethod(actor, "subject"));
            assertThrows(RuntimeException.class, () -> ReflectionTestUtils.invokeMethod(
                    AgentWorkItemReassignmentController.class, "principal", auth, true));
        }
    }

    private static JwtAuthenticationToken jwt(Map<String, Object> extra) {
        var builder = Jwt.withTokenValue("synthetic").header("alg", "none").subject("actor")
                .claim("jiacn", "owner").claim("client_id", "client");
        extra.forEach(builder::claim);
        return new JwtAuthenticationToken(builder.build(), List.of(), "actor");
    }
}
