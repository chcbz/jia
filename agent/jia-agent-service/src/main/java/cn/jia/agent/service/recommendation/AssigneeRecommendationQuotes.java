package cn.jia.agent.service.recommendation;

import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import cn.jia.economy.common.Money;
import cn.jia.economy.pricing.TaskPricingService;
import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.task.TaskBillingService;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;
import static cn.jia.agent.entity.AssigneeRecommendation.*;

/** Agent owns the task transaction and authorized facts; economy owns immutable quotation/prices.
 * The supplier only rechecks observations and decodes an already-returned response. No model I/O here. */
@Service
public final class AssigneeRecommendationQuotes {
    private final AgentTaskMutationTransaction transactions;
    private final AgentTaskRequirementSnapshotService requirements;
    private final AgentTaskExecutionGrantService grants;
    private final AssigneeQuoteRoutes routes;
    private final TaskBillingService billing;
    public AssigneeRecommendationQuotes(AgentTaskMutationTransaction transactions,
            AgentTaskRequirementSnapshotService requirements, AgentTaskExecutionGrantService grants,
            AssigneeQuoteRoutes routes, TaskBillingService billing) {
        this.transactions = transactions; this.requirements = requirements; this.grants = grants;
        this.routes = routes; this.billing = billing;
    }
    public Result prepare(HostingRentHttp.Actor actor, String taskId, Supplier<Result> verifiedAdvice) {
        return transactions.executeWithLockedTaskRootInOwnerScope(actor.tenantId(), actor.clientId(), actor.ownerJiacn(), taskId, root -> {
            Result advice = verifiedAdvice.get();
            if (advice.recommendations().isEmpty()) return advice;
            var scope = new AgentTaskExecutionGrantService.Scope(actor.tenantId(), actor.clientId(), actor.ownerJiacn());
            var requirement = requirements.readCurrent(scope, taskId);
            var inputs = grants.snapshotTaskInputsWithinLockedTask(scope, taskId);
            var task = new TaskBillingService.Task(new EconomyScope(actor.tenantId(), actor.clientId()), actor.ownerJiacn(), taskId);
            String recommendationId = "rec_" + UUID.randomUUID();
            var priced = new ArrayList<Recommendation>();
            for (var item : advice.recommendations()) {
                var route = routes.find(item.agent().agentId(), advice.workload().kind());
                Quotation quotation;
                if (route == null) quotation = unavailable(recommendationId, "BILLING_ROUTE_MISSING");
                else {
                    var binding = new TaskBillingService.Binding(requirement.requirementRevision(), requirement.sha256(),
                            inputs.digest(), item.agent().agentId(), recommendationId);
                    try {
                        var quote = billing.quote(task, binding, actor.actorId(),
                                route.workload(advice.workload(), inputs.inputs().size()), route.modelKey());
                        quotation = new Quotation("AVAILABLE", quote.quoteId(), recommendationId, Money.wen(quote.amountWen()), null);
                    } catch (TaskPricingService.Unavailable expected) {
                        quotation = unavailable(recommendationId, expected.reason().name());
                    }
                }
                priced.add(new Recommendation(item.agent(), item.recommendationLevel(), item.reason(), item.evidence(), item.gaps(), quotation));
            }
            return new Result(advice.taskId(), advice.taskVersion(), advice.observedAt(), advice.state(), advice.requirementSummary(),
                    java.util.List.copyOf(priced), advice.questions(), advice.candidateSetComplete(), advice.workload());
        });
    }
    private static Quotation unavailable(String id, String reason) { return new Quotation("UNAVAILABLE", null, id, null, reason); }
}
