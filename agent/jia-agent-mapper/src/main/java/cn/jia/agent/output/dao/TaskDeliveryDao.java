package cn.jia.agent.output.dao;

import java.util.List;

/** Persistence boundary for immutable formal task-delivery batches. */
public interface TaskDeliveryDao {
    record DeliveryRow(
            String tenantId, String clientId, String deliveryId, String taskId,
            String workItemId, long revision, String supersedesDeliveryId,
            String producerAgentId, String runId, String summary, String state,
            long submittedAt, Long reviewedAt, String manifestArtifactId,
            long manifestArtifactVersion, long rowVersion) { }

    record ItemRow(
            String tenantId, String clientId, String deliveryId, String artifactId,
            long artifactVersion, byte[] contentHash, String objectId, String purpose,
            int itemOrder, long rowVersion) { }

    int insertDelivery(DeliveryRow row, long now);

    int insertItem(ItemRow row, long now);

    DeliveryRow findExact(
            String tenantId, String clientId, String deliveryId, boolean forUpdate);

    DeliveryRow findTaskRevision(
            String tenantId, String clientId, String taskId, long revision, boolean forUpdate);

    List<ItemRow> listItems(
            String tenantId, String clientId, String deliveryId, boolean forUpdate);
}
