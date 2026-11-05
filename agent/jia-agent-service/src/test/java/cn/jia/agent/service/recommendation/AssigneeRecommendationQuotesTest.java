package cn.jia.agent.service.recommendation;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.pricing.TaskWorkload;
import cn.jia.economy.task.TaskBillingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static cn.jia.agent.entity.AssigneeRecommendation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssigneeRecommendationQuotesTest {
    private final HostingRentHttp.Actor actor = new HostingRentHttp.Actor("jwt-user", "0", "client", "owner");
    private final AgentTaskMutationTransaction transactions = mock(AgentTaskMutationTransaction.class);
    private final AgentTaskRequirementSnapshotService requirements = mock(AgentTaskRequirementSnapshotService.class);
    private final AgentTaskExecutionGrantService grants = mock(AgentTaskExecutionGrantService.class);
    private final TaskBillingService billing = mock(TaskBillingService.class);
    private final AtomicBoolean locked = new AtomicBoolean();
    private AssigneeRecommendationQuotes service;
    @BeforeEach void setup() {
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"), eq("client"), eq("owner"), eq("task"), any()))
                .thenAnswer(call -> {
                    locked.set(true);
                    try { AgentTaskMutationTransaction.LockedTaskMutation<Result> work = call.getArgument(4); return work.apply(new AgentTaskMetaEntity()); }
                    finally { locked.set(false); }
                });
        when(requirements.readCurrent(any(), eq("task"))).thenAnswer(call -> {
            assertTrue(locked.get()); return new AgentTaskRequirementSnapshotService.CurrentSnapshot("task", 7, 3,
                    "title", "description", "requirement-sha", "CURRENT");
        });
        when(grants.snapshotTaskInputsWithinLockedTask(any(), eq("task"))).thenAnswer(call -> {
            assertTrue(locked.get()); return new AgentTaskExecutionGrantService.InputCatalogue("same-assignment-digest", List.of(
                    new AgentTaskExecutionGrantService.AuthorizedInput("file", 2, "INPUT", "text/plain", 13, "content-sha")));
        });
        var routes = new AssigneeQuoteRoutes(new AssigneeQuoteRoutes.Properties(List.of(
                new AssigneeQuoteRoutes.Route("a", Workload.Kind.DOCUMENT, "channel", "model", "standard", List.of()),
                new AssigneeQuoteRoutes.Route("b", Workload.Kind.DOCUMENT, "other-channel", "model", "standard", List.of()))));
        service = new AssigneeRecommendationQuotes(transactions, requirements, grants, routes, billing);
        when(billing.quote(any(), any(), anyString(), any(), any())).thenAnswer(call -> {
            assertTrue(locked.get()); return new TaskBillingService.Quote("quote-" + ((TaskBillingService.Binding) call.getArgument(1)).agentId(), 13, "{}");
        });
    }
    private static Recommendation candidate(String id) {
        return new Recommendation(new Agent(id, id, "online", List.of(), true, false, "display-provider"),
                "UNKNOWN", "unknown abilities", List.of(), List.of(), null);
    }
    private Result advice(String... ids) {
        assertTrue(locked.get());
        return new Result("task", "7", 1, "NO_MATCH", "summary", java.util.Arrays.stream(ids).map(AssigneeRecommendationQuotesTest::candidate).toList(),
                List.of(), true, new Workload(Workload.Kind.DOCUMENT, Workload.Complexity.STANDARD, 2));
    }
    @Test void quotesAllCandidatesFromOneLockedSnapshotAndTrustedPayer() {
        var result = service.prepare(actor, "task", () -> advice("a", "b"));
        var first = result.recommendations().getFirst().quote();
        assertEquals("AVAILABLE", first.state()); assertEquals("13", first.amount().amountMinor());
        assertEquals(first.recommendationId(), result.recommendations().getLast().quote().recommendationId());
        var bindings = ArgumentCaptor.forClass(TaskBillingService.Binding.class);
        var work = ArgumentCaptor.forClass(TaskWorkload.class);
        verify(billing, times(2)).quote(any(), bindings.capture(), eq("jwt-user"), work.capture(), any());
        for (var binding : bindings.getAllValues()) {
            assertEquals(3, binding.requirementRevision()); assertEquals("requirement-sha", binding.requirementSha256());
            assertEquals("same-assignment-digest", binding.inputSnapshotDigest()); assertEquals(first.recommendationId(), binding.recommendationId());
        }
        assertEquals(List.of(1L, 1L), work.getAllValues().stream().map(TaskWorkload::inputFiles).toList());
        verify(grants, times(1)).snapshotTaskInputsWithinLockedTask(any(), eq("task"));
        verify(billing, never()).reserve(any(), any(), any());
    }
    @Test void missingRouteOrPriceIsLocalToCandidateAndNeverZeroMoney() {
        doThrow(new TaskPricingService.Unavailable(TaskPricingService.Unavailable.Reason.MODEL_PRICE_MISSING))
                .when(billing).quote(any(), any(), anyString(), any(), eq(new TaskPricingService.ModelKey("other-channel", "model", "standard")));
        var result = service.prepare(actor, "task", () -> advice("a", "b", "unconfigured"));
        assertEquals("AVAILABLE", result.recommendations().get(0).quote().state());
        assertEquals("MODEL_PRICE_MISSING", result.recommendations().get(1).quote().unavailableReason());
        assertEquals("BILLING_ROUTE_MISSING", result.recommendations().get(2).quote().unavailableReason());
        for (var item : result.recommendations().subList(1, 3)) { assertNull(item.quote().amount()); assertNull(item.quote().quoteId()); }
    }
    @Test void staleAdvicePreventsAllSnapshotAndPriceWork() {
        assertThrows(Failure.class, () -> service.prepare(actor, "task", () -> { throw new Failure(409, "RECOMMENDATION_STALE"); }));
        verifyNoInteractions(requirements, grants, billing);
    }
    @Test void persistenceFailureIsNotMisrepresentedAsCandidateUnavailability() {
        doThrow(new IllegalStateException("database")).when(billing).quote(any(), any(), anyString(), any(), any());
        assertThrows(IllegalStateException.class, () -> service.prepare(actor, "task", () -> advice("a")));
    }
}
