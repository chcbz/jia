package cn.jia.agent.service;

import cn.jia.agent.entity.AgentOutboxClaimToken;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.core.tenant.WorkerScope;

/** Rabbit network boundary guarded by a current control-plane worker admission lock. */
public interface AgentOutboxPublisher {
    AgentRabbitPublishResult publish(
            WorkerScope scope, AgentOutboxClaimToken token, long confirmTimeoutMillis);

    /** Legacy unscoped publishing is fail-closed. */
    @Deprecated
    default AgentRabbitPublishResult publish(
            AgentOutboxClaimToken token, long confirmTimeoutMillis) {
        return new AgentRabbitPublishResult(
                AgentRabbitPublishResult.Type.EXCEPTION,
                "NONE", "NOT_RETURNED", null, null,
                "WORKER_AUTHORITY_REQUIRED");
    }
}
