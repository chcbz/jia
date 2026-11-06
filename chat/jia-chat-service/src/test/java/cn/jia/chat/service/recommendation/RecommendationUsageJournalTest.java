package cn.jia.chat.service.recommendation;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostConflict;
import cn.jia.economy.usage.UsageCostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecommendationUsageJournalTest {
    @TempDir Path root;
    private final UsageCostService costs = mock(UsageCostService.class);
    private RecommendationUsageJournal journal(String tenant) {
        return new RecommendationUsageJournal(costs, new FixedTenantAdmissionPolicy(tenant, "client"), root.toString());
    }
    private UsageCostService.Observation observation(String attempt) {
        return new UsageCostService.Observation(new UsageCostService.Binding(new EconomyScope("tenant", "client"),
                "owner", "task", "ASSIGNEE_RECOMMENDATION", attempt), "responded", 1,
                "{\"usage\":{\"prompt_tokens\":100}}",
                new TokenUsageCost.Usage("channel", "model", "standard", "synthetic", Map.of(INPUT, 100L, OUTPUT, 20L)),
                new ModelTokenPrice("channel", "model", "standard", "v1", "synthetic",
                        Map.of(INPUT, new BigDecimal("1.20"), OUTPUT, new BigDecimal("10.00")),
                        1000L, Map.of(INPUT, new BigDecimal("2.40"), OUTPUT, new BigDecimal("15.00"))), 123456L);
    }
    private List<Path> files() throws Exception {
        try (var stream = Files.walk(root)) { return stream.filter(p -> p.toString().endsWith(".json")).toList(); }
    }
    @Test void successDeletesOnlyAfterLedgerAcknowledgementAndUsesPrivatePermissions() throws Exception {
        var journal = journal("tenant"); journal.prepare(); var receipt = observation("a");
        when(costs.record(any())).thenAnswer(call -> {
            var stored = files(); assertEquals(1, stored.size());
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stored.getFirst()));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(stored.getFirst().getParent()));
            return null;
        });
        journal.record(receipt); assertTrue(files().isEmpty()); verify(costs).record(receipt);
    }
    @Test void databaseFailureSurvivesNewProcessWithOriginalBindingPriceRevisionAndTimestamp() throws Exception {
        var journal = journal("tenant"); journal.prepare(); var receipt = observation("a");
        when(costs.record(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertDoesNotThrow(() -> journal.record(receipt)); assertEquals(1, files().size());
        reset(costs); journal("tenant").recover();
        verify(costs).record(receipt); assertTrue(files().isEmpty());
    }
    @Test void unknownCommitAcknowledgementReplaysExactlyTheSameReceipt() throws Exception {
        var journal = journal("tenant"); journal.prepare(); var receipt = observation("a");
        when(costs.record(any())).thenThrow(new IllegalStateException("committed but connection lost"))
                .thenReturn(new UsageCostService.Receipt("attempt", 2, TokenUsageCost.Status.PRICED, new BigDecimal("0.000320"), true));
        journal.record(receipt); journal.recover();
        var captured = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs, times(2)).record(captured.capture());
        assertEquals(List.of(receipt, receipt), captured.getAllValues()); assertTrue(files().isEmpty());
    }
    @Test void anotherProcessScopeDoesNotDiscoverOrDeliverThisTenantsReceipts() throws Exception {
        var journal = journal("tenant"); journal.prepare();
        when(costs.record(any())).thenThrow(new IllegalStateException("offline")); journal.record(observation("a"));
        reset(costs); journal("other").recover(); verifyNoInteractions(costs); assertEquals(1, files().size());
        var other = journal("other"); other.prepare();
        assertThrows(IllegalArgumentException.class, () -> other.record(observation("b"))); verifyNoInteractions(costs);
    }
    @Test void unavailableDatabaseStopsRecoveryAfterOneAttemptWithoutDeletingBacklog() throws Exception {
        var journal = journal("tenant"); journal.prepare();
        when(costs.record(any())).thenThrow(new IllegalStateException("offline"));
        journal.record(observation("a")); journal.record(observation("b"));
        clearInvocations(costs); journal.recover(); verify(costs).record(any()); assertEquals(2, files().size());
    }
    @Test void corruptedSourceIsRetainedButDoesNotBlockIndependentReceipt() throws Exception {
        var journal = journal("tenant"); journal.prepare();
        when(costs.record(any())).thenThrow(new IllegalStateException("offline")); journal.record(observation("a"));
        Path corrupted = files().getFirst(); Files.writeString(corrupted, "{}");
        journal.record(observation("b")); reset(costs); journal.recover();
        verify(costs).record(observation("b")); assertEquals(List.of(corrupted), files());
    }
    @Test void ledgerConflictDoesNotDeleteSourceOrBlockIndependentReceipt() throws Exception {
        var journal = journal("tenant"); journal.prepare();
        when(costs.record(any())).thenThrow(new IllegalStateException("offline"));
        journal.record(observation("a")); journal.record(observation("b")); reset(costs);
        when(costs.record(observation("a"))).thenThrow(new UsageCostConflict(UsageCostConflict.Reason.RECEIPT_CONTENT));
        journal.recover(); verify(costs).record(observation("a")); verify(costs).record(observation("b"));
        assertEquals(1, files().size());
    }
    @Test void missingOrUnusableStorageFailsBeforeAnyCostDelivery() throws Exception {
        var unconfigured = new RecommendationUsageJournal(costs, new FixedTenantAdmissionPolicy("tenant", "client"), "");
        assertThrows(IllegalStateException.class, unconfigured::prepare);
        Path file = root.resolve("not-a-directory"); Files.writeString(file, "existing");
        var unusable = new RecommendationUsageJournal(costs, new FixedTenantAdmissionPolicy("tenant", "client"), file.toString());
        assertThrows(java.io.UncheckedIOException.class, unusable::prepare);
        verifyNoInteractions(costs);
    }
}
