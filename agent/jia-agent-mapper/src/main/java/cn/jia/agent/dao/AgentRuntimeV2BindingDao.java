package cn.jia.agent.dao;

import cn.jia.agent.entity.AgentRuntimeV2BindingEntity;
import cn.jia.agent.entity.RuntimeReceiverScope;

public interface AgentRuntimeV2BindingDao {
    int insertTrusted(RuntimeReceiverScope scope, AgentRuntimeV2BindingEntity binding);
    AgentRuntimeV2BindingEntity findExact(RuntimeReceiverScope scope, String runtimeBindingId);
    AgentRuntimeV2BindingEntity lockExact(RuntimeReceiverScope scope, String runtimeBindingId);
    int transition(RuntimeReceiverScope scope, AgentRuntimeV2BindingEntity current,
            String newStatus, Long disconnectedAt, Long revokedAt, String revokeReason, long now);
    int moveRoutingLease(RuntimeReceiverScope scope, AgentRuntimeV2BindingEntity current,
            String expectedSocketOwnerInstanceId, long expectedSocketOwnerEpoch,
            long expectedSocketOwnerLeaseUntil, String socketOwnerInstanceId,
            long socketOwnerEpoch, long socketOwnerLeaseUntil, long now);
}
