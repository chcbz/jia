package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDlqPage;
import cn.jia.agent.entity.AgentCommandOperationAuditPage;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationResult;
import cn.jia.agent.entity.AgentCommandOperationV1View;
import cn.jia.agent.entity.AgentCommandOpsMetrics;


/**
 * Privileged, exact-scope Rabbit operations plane. Metrics {@code dlqCount} and {@link #listDlq}
 * expose only unexpired durable-state broker-redrive candidates. Terminal states remain visible
 * in status metrics, while prior privileged operations remain in audit. Every mutation re-locks
 * and fully revalidates its source.
 */
public interface AgentCommandOperationsService {
    AgentCommandOpsMetrics metrics(String tenantId, String clientId, long now);

    /** Stable, payload-redacted candidate page; discovery is never mutation authorization. */
    AgentCommandDlqPage listDlq(
            String tenantId, String clientId, long afterDeliveryId, int limit);

    AgentCommandOperationAuditPage listAudit(
            String tenantId, String clientId, long afterId, int limit);

    AgentCommandOperationV1View getOperationV1(
            String tenantId, String clientId, String requesterId, String operationId, long now);

    /**
     * Versioned asynchronous acceptance only, independently default-off from synchronous
     * redrive. The durable operation row is transactionally polled; no broker I/O occurs here.
     */
    AgentCommandOperationV1View acceptBrokerRedriveV1(
            AgentCommandOperationRequest request, String idempotencyKey, long now);

    AgentCommandOperationResult brokerRedrive(AgentCommandOperationRequest request, long now);

    AgentCommandOperationResult manualReissue(AgentCommandOperationRequest request, long now);
}
