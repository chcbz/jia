package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandManualReissueResult;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;

/** Sole owner of WAITING_AGENT/SENT discovery, expiry, and fenced reissue transactions. */
public interface AgentCommandReissueService {
    AgentCommandManualReissueResult reissueManually(
            AgentCommandOperationRequest request, long now);

    AgentCommandReissueScanResult reissueForReconnect(
            AgentCommandReconnectScope scope, int limit, long now);

    AgentCommandReissueScanResult reissueDue(
            int limit, long afterDeliveryId, long now);
}
