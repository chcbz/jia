package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationResult;
import cn.jia.agent.entity.AgentCommandOpsMetrics;

import java.util.List;

/** Privileged, exact-scope Rabbit operations plane. */
public interface AgentCommandOperationsService {
    AgentCommandOpsMetrics metrics(String tenantId, String clientId, long now);

    List<AgentCommandDlqEntry> listDlq(
            String tenantId, String clientId, long afterDeliveryId, int limit);

    List<AgentCommandOperationAuditEntry> listAudit(
            String tenantId, String clientId, long afterId, int limit);

    AgentCommandOperationResult brokerRedrive(AgentCommandOperationRequest request, long now);

    AgentCommandOperationResult manualReissue(AgentCommandOperationRequest request, long now);
}
