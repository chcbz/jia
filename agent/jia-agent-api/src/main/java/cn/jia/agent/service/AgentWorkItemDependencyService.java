package cn.jia.agent.service;

import cn.jia.agent.entity.AgentWorkItemDependencyResolutionDTO;

/**
 * Internal E04 dependency-graph validator and pending-to-ready scheduler.
 *
 * <p>The implementation must lock the scoped task root, read and validate the complete bounded
 * work-item graph in deterministic order, and append each {@code WORK_ITEM_READY} event in the
 * same REQUIRED transaction as its version CAS. This service never claims, assigns, dispatches,
 * invokes a provider, or performs billing.</p>
 */
public interface AgentWorkItemDependencyService {
    AgentWorkItemDependencyResolutionDTO resolveReady(
            String tenantId, String clientId, String taskId);
}
