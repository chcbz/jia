package cn.jia.agent.service;

import cn.jia.agent.entity.AgentOutboxCandidate;
import cn.jia.agent.entity.AgentOutboxClaim;
import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.entity.AgentOutboxSettleResult;
import cn.jia.core.tenant.WorkerScope;

import java.util.List;

/** Transactional database half of the scoped Agent outbox relay. */
public interface AgentOutboxRelayService {
    List<AgentOutboxCandidate> discover(WorkerScope scope, long now, int requested);

    AgentOutboxClaim claim(
            WorkerScope scope, AgentOutboxCandidate candidate, String leaseOwner, long now);

    AgentOutboxSettleResult settle(
            WorkerScope scope, AgentOutboxClaimToken token,
            AgentRabbitPublishResult result, long now);

    /** Legacy unscoped entrypoints fail closed and exist only for source compatibility. */
    @Deprecated
    default List<AgentOutboxCandidate> discover(long now, int requested) {
        return List.of();
    }

    @Deprecated
    default AgentOutboxClaim claim(
            AgentOutboxCandidate candidate, String leaseOwner, long now) {
        return AgentOutboxClaim.disabled();
    }

    @Deprecated
    default AgentOutboxSettleResult settle(
            AgentOutboxClaimToken token, AgentRabbitPublishResult result, long now) {
        return AgentOutboxSettleResult.STALE;
    }
}
