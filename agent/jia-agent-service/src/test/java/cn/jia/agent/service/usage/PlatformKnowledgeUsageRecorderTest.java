package cn.jia.agent.service.usage;

import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AuthenticatedRuntimeOperation;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import static cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder.Phase.*;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformKnowledgeUsageRecorderTest {
    final ObjectMapper json = new ObjectMapper();
    final PlatformKnowledgeMaintenanceFixture fixture = new PlatformKnowledgeMaintenanceFixture("tenant", "client", "agent", "installation");
    final HostingRentFinishingMapper relationships = fixture.relationships;
    final UsageCostService costs = mock(UsageCostService.class);
    final ModelPriceCatalog prices = mock(ModelPriceCatalog.class);
    final PlatformKnowledgeUsageRecorder recorder = new PlatformKnowledgeUsageRecorder(relationships, costs, prices, fixture.bindings, fixture.renewal);
    final cn.jia.agent.entity.AgentRuntimeV1InstallationView scope = fixture.installation;
    final ModelTokenPrice price = new ModelTokenPrice("platform", "gpt-6-luna", "standard", "v1", "fixture-cny",
            Map.of(INPUT, new BigDecimal("1"), CACHED_INPUT, new BigDecimal("0.1"), OUTPUT, new BigDecimal("5")), null, Map.of());
    PlatformKnowledgeUsageRecorder.Evidence evidence(PlatformKnowledgeUsageRecorder.Phase phase) {
        return new PlatformKnowledgeUsageRecorder.Evidence("0", "extract", phase, "platform", "gpt-6-luna", "standard", phase == STARTED ? null : json.readTree("""
                {"providerReceiptId":"response-1","actualModel":"gpt-6-luna","serviceTier":"default",
                 "usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":20},"output_tokens":10,"total_tokens":110}}
                """));
    }
    void authorize() { /* Fixture holds the exact original stopped relationship. */ }
    UsageCostService.Observation capture() {
        var captor = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs).record(captor.capture()); return captor.getValue();
    }
    @Test void startingFreezesPriceAndBindsPlatformAgentHandoverWithoutUserOrTask() {
        authorize(); when(prices.find("platform", "gpt-6-luna", "standard")).thenReturn(Optional.of(price));
        recorder.record(scope, "lease", "handover", evidence(STARTED));
        var actual = capture(); assertNull(actual.binding().ownerJiacn()); assertNull(actual.binding().taskId());
        assertEquals("PUBLIC_KNOWLEDGE_EXTRACTION", actual.binding().source());
        assertEquals("[\"agent\",\"lease\",\"handover\",\"0\",\"extract\"]", actual.binding().sourceAttemptId());
        assertEquals(price, actual.price()); assertNull(actual.normalizedUsage()); assertEquals(0, actual.expectedRevision());
    }
    @Test void responsePricesOnceUsingFrozenSnapshotAndDisjointInputCacheOutput() {
        authorize();
        when(costs.observation(any(), eq("started"))).thenReturn(Optional.of(new UsageCostService.Observation(
                UsageCostService.platformBinding(new cn.jia.economy.service.EconomyScope("tenant", "client"), "source", "attempt"),
                "started", 0, json.writeValueAsString(evidence(STARTED)), null, price, 0)));
        recorder.record(scope, "lease", "handover", evidence(RESPONDED));
        var actual = capture(); assertEquals(price, actual.price()); assertEquals(1, actual.expectedRevision());
        assertEquals(Map.of(INPUT, 80L, CACHED_INPUT, 20L, OUTPUT, 10L), actual.normalizedUsage().tokens());
        assertEquals(new BigDecimal("0.000132"), cn.jia.economy.usage.TokenUsageCost.assess(actual.normalizedUsage(), price).costYuan());
        verifyNoInteractions(prices);
    }
    @Test void missingInitialPriceRemainsPendingInsteadOfUsingNewCurrentTariffs() {
        authorize();
        when(costs.observation(any(), eq("started"))).thenReturn(Optional.of(new UsageCostService.Observation(
                UsageCostService.platformBinding(new cn.jia.economy.service.EconomyScope("tenant", "client"), "source", "attempt"),
                "started", 0, json.writeValueAsString(evidence(STARTED)), null, null, 0)));
        recorder.record(scope, "lease", "handover", evidence(RESPONDED));
        assertNull(capture().price()); verifyNoInteractions(prices);
    }
    @Test void responseWithoutPreCallStartCannotLookUpTodaysPriceOrCreateAnAttempt() {
        authorize(); assertThrows(UsageCostConflict.class, () -> recorder.record(scope, "lease", "handover", evidence(RESPONDED)));
        verifyNoInteractions(prices); verify(costs, never()).record(any());
    }
    @Test void replayRetainsStoredPriceAndNormalizerButStillChecksIncomingRawEvidence() {
        authorize(); var event = evidence(RESPONDED);
        var saved = new UsageCostService.Observation(UsageCostService.platformBinding(new cn.jia.economy.service.EconomyScope("tenant", "client"), "source", "attempt"),
                "responded", 1, json.writeValueAsString(event), null, price, 0);
        when(costs.observation(any(), eq("responded"))).thenReturn(Optional.of(saved));
        recorder.record(scope, "lease", "handover", event);
        var actual = capture(); assertEquals(saved.rawUsageJson(), actual.rawUsageJson()); assertEquals(saved.price(), actual.price());
        assertNull(actual.normalizedUsage()); verifyNoInteractions(prices);
    }
    @Test void changedRequestedRouteCannotRebindAnExistingPaidAttempt() {
        authorize(); var start = evidence(STARTED);
        var changed = new PlatformKnowledgeUsageRecorder.Evidence("0", "extract", STARTED, "other-channel", start.requestedModel(), "standard", null);
        when(costs.observation(any(), eq("started"))).thenReturn(Optional.of(new UsageCostService.Observation(
                UsageCostService.platformBinding(new cn.jia.economy.service.EconomyScope("tenant", "client"), "source", "attempt"),
                "started", 0, json.writeValueAsString(changed), null, price, 0)));
        assertThrows(UsageCostConflict.class, () -> recorder.record(scope, "lease", "handover", evidence(RESPONDED)));
        verify(costs, never()).record(any()); verifyNoInteractions(prices);
    }
    @Test void wrongInstallationAndUnstoppedOrClosedLeaseNeverTouchCosts() {
        fixture.intent.setRuntimeInstallationId("other");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "lease", "handover", evidence(STARTED)));
        fixture.intent.setRuntimeInstallationId(scope.installationId());
        fixture.lease.setExecutorStoppedAt(null);
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "lease", "handover", evidence(STARTED)));
        fixture.lease.setExecutorStoppedAt(100L); fixture.lease.setStatus("CLOSED");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "lease", "handover", evidence(STARTED)));
        fixture.lease.setStatus("FINISHING");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "lease", "other-handover", evidence(STARTED)));
        fixture.binding.setAgentId("other");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class, () -> recorder.record(scope, "lease", "handover", evidence(STARTED)));
        verifyNoInteractions(costs, prices);
    }
    @Test void incompleteOrInconsistentCountersAndMissingActualIdentityRemainUnknown() {
        var valid = evidence(RESPONDED).evidence().toString();
        for (String invalid : new String[]{"{}", valid.replace("\"cached_tokens\":20", "\"cached_tokens\":101"),
                valid.replace("\"total_tokens\":110", "\"total_tokens\":109"),
                valid.replace("\"input_tokens\":100", "\"input_tokens\":100.5"),
                valid.replace("\"serviceTier\":\"default\"", "\"serviceTier\":null"),
                valid.replace("\"actualModel\":\"gpt-6-luna\"", "\"actualModel\":null"),
                valid.replace("\"cached_tokens\":20", "\"other_tokens\":20")})
            assertNull(ResponsesUsageNormalizer.normalize("platform", json.readTree(invalid)), invalid);
    }
}
