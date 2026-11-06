package cn.jia.chat.service.recommendation;

import cn.jia.agent.service.AgentAssigneeRecommendationModel.Context;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.usage.TokenUsageCost;
import cn.jia.economy.usage.UsageCostService;
import com.openai.models.chat.completions.ChatCompletion;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RecommendationCostRecorderTest {
    private static final Context CONTEXT = new Context("tenant", "client", "owner", "task");
    private static final ModelTokenPrice PRICE = new ModelTokenPrice("channel", "model", "standard", "v1", "synthetic",
            Map.of(INPUT, BigDecimal.ONE, CACHED_INPUT, BigDecimal.ZERO, OUTPUT, BigDecimal.TEN), null, Map.of());
    private final UsageCostService costs = mock(UsageCostService.class);
    private final ModelPriceCatalog prices = mock(ModelPriceCatalog.class);
    private RecommendationCostRecorder recorder() {
        when(prices.find("channel", "model", "standard")).thenReturn(Optional.of(PRICE));
        when(costs.record(any())).thenReturn(new UsageCostService.Receipt("attempt", 1, TokenUsageCost.Status.PENDING_USAGE, null, false));
        return new RecommendationCostRecorder(costs, prices, "channel", "standard");
    }
    private static ChatCompletion response(String actualModel, String tier) throws Exception {
        String raw = "{\"id\":\"provider-receipt\",\"model\":\"" + actualModel + "\",\"object\":\"chat.completion\",\"created\":1,\"choices\":[],"
                + (tier == null ? "" : "\"service_tier\":\"" + tier + "\",")
                + "\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"total_tokens\":120,\"prompt_tokens_details\":{\"cached_tokens\":80}}}";
        return com.openai.core.ObjectMappers.jsonMapper().readValue(raw, ChatCompletion.class);
    }
    @Test void preservesTrustedBindingAndPriceBeforeCallAndActualUsageBeforeBusinessValidation() throws Exception {
        var recorder = recorder(); var attempt = recorder.begin(CONTEXT, "model");
        when(prices.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        recorder.complete(attempt, response("model", "default"));
        var records = ArgumentCaptor.forClass(UsageCostService.Observation.class);
        verify(costs, times(2)).record(records.capture());
        var started = records.getAllValues().getFirst(); var completed = records.getAllValues().getLast();
        assertEquals(CONTEXT.ownerJiacn(), started.binding().ownerJiacn());
        assertEquals(CONTEXT.taskId(), started.binding().taskId());
        assertEquals(CONTEXT.tenantId(), started.binding().scope().tenantId());
        assertEquals("ASSIGNEE_RECOMMENDATION", started.binding().source());
        assertEquals(started.binding(), completed.binding());
        assertEquals(0, started.expectedRevision()); assertEquals(1, completed.expectedRevision());
        assertNull(started.normalizedUsage()); assertSame(PRICE, started.price()); assertSame(PRICE, completed.price());
        assertEquals("started", started.receiptId()); assertEquals("responded", completed.receiptId());
        assertEquals(new BigDecimal("0.000220"), TokenUsageCost.assess(completed.normalizedUsage(), completed.price()).costYuan());
        assertTrue(completed.rawUsageJson().contains("provider-receipt"));
        verify(prices, times(1)).find(anyString(), anyString(), anyString());
    }
    @Test void missingActualTierKeepsRawUsagePendingInsteadOfSubstitutingRequestedTier() throws Exception {
        var recorder = recorder(); var attempt = recorder.begin(CONTEXT, "model");
        recorder.complete(attempt, response("model", null));
        var records = ArgumentCaptor.forClass(UsageCostService.Observation.class); verify(costs, times(2)).record(records.capture());
        var completed = records.getAllValues().getLast();
        assertNull(completed.normalizedUsage()); assertTrue(completed.rawUsageJson().contains("prompt_tokens"));
        assertSame(PRICE, completed.price());
    }
    @Test void differentActualModelCannotConsumeRequestedModelsPrice() throws Exception {
        var recorder = recorder(); var attempt = recorder.begin(CONTEXT, "model");
        recorder.complete(attempt, response("different-model", "default"));
        var records = ArgumentCaptor.forClass(UsageCostService.Observation.class); verify(costs, times(2)).record(records.capture());
        var completed = records.getAllValues().getLast();
        assertEquals(TokenUsageCost.Status.PENDING_PRICE, TokenUsageCost.assess(completed.normalizedUsage(), completed.price()).status());
    }
    @Test void realUserRetriesHaveDistinctAttemptIdentitiesAndMissingPriceIsNotZero() {
        var recorder = recorder(); when(prices.find(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        var first = recorder.begin(CONTEXT, "model"); var second = recorder.begin(CONTEXT, "model");
        assertNotEquals(first.binding().sourceAttemptId(), second.binding().sourceAttemptId());
        assertNull(first.price()); assertNull(second.price());
    }
    @Test void failedCompletionWritePropagatesWithoutProviderReplayOrZeroCost() throws Exception {
        var recorder = recorder(); var attempt = recorder.begin(CONTEXT, "model");
        when(costs.record(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> recorder.complete(attempt, response("model", "default")));
        verify(costs, times(2)).record(any());
    }
}
