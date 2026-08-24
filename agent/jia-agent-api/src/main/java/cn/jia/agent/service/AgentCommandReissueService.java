package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandReconnectScope;
import cn.jia.agent.entity.AgentCommandReissueScanResult;

/** Sole owner of WAITING_AGENT discovery and fenced reissue transactions. */
public interface AgentCommandReissueService {
    AgentCommandReissueScanResult reissueForReconnect(
            AgentCommandReconnectScope scope, int limit, long now);

    AgentCommandReissueScanResult reissueDue(
            int limit, long afterDeliveryId, long now);
}
