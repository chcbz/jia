package cn.jia.agent.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileResultDTO;

/**
 * Internal owner-scoped bridge from an assignment transaction to Chat admission.
 * Implementations never dispatch an Agent command or call a Provider.
 */
public interface AgentTaskBountyBootstrapOutboxService {
    AgentTaskBountyBootstrapClaimDTO claimNext(AgentTaskExecutionGrantService.Scope scope,
            String consumerId, long now);

    AgentTaskBountyBootstrapReconcileResultDTO reconcile(
            AgentTaskExecutionGrantService.Scope scope,
            AgentTaskBountyBootstrapReconcileDTO command, long now);
}
