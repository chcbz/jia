package cn.jia.agent.service;

import cn.jia.agent.entity.AgentCommandDlqEntry;
import cn.jia.agent.entity.AgentCommandOperationAuditEntry;
import cn.jia.agent.entity.AgentCommandOperationRequest;
import cn.jia.agent.entity.AgentCommandOperationResult;
import cn.jia.agent.entity.AgentCommandOpsMetrics;

import java.util.List;

/**
 * Privileged, exact-scope Rabbit operations plane. Metrics {@code dlqCount} and {@link #listDlq}
 * expose only unexpired durable-state broker-redrive candidates. Terminal states remain visible
 * in status metrics, while prior privileged operations remain in audit. Every mutation re-locks
 * and fully revalidates its source.
 */
public interface AgentCommandOperationsService {
    AgentCommandOpsMetrics metrics(String tenantId, String clientId, long now);

    /** Stable, payload-redacted candidate page; discovery is never mutation authorization. */
    List<AgentCommandDlqEntry> listDlq(
            String tenantId, String clientId, long afterDeliveryId, int limit);

    List<AgentCommandOperationAuditEntry> listAudit(
            String tenantId, String clientId, long afterId, int limit);

    AgentCommandOperationResult brokerRedrive(AgentCommandOperationRequest request, long now);

    AgentCommandOperationResult manualReissue(AgentCommandOperationRequest request, long now);
}
