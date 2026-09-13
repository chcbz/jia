package cn.jia.agent.output.dao.impl;

import cn.jia.agent.output.dao.TaskDeliveryDao;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import jakarta.inject.Named;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

@Named
public final class TaskDeliveryDaoImpl implements TaskDeliveryDao {
    private static final RowMapper<DeliveryRow> DELIVERY = (row, number) -> new DeliveryRow(
            row.getString("tenant_id"), row.getString("client_id"),
            row.getString("delivery_id"), row.getString("task_id"),
            row.getString("work_item_id"), row.getLong("revision"),
            row.getString("supersedes_delivery_id"), row.getString("producer_agent_id"),
            row.getString("run_id"), row.getString("summary"), row.getString("state"),
            row.getLong("submitted_at"), nullableLong(row, "reviewed_at"),
            row.getString("manifest_artifact_id"), row.getLong("manifest_artifact_version"),
            row.getLong("row_version"));
    private static final RowMapper<ItemRow> ITEM = (row, number) -> new ItemRow(
            row.getString("tenant_id"), row.getString("client_id"),
            row.getString("delivery_id"), row.getString("artifact_id"),
            row.getLong("artifact_version"), row.getBytes("content_hash"),
            row.getString("object_id"), row.getString("purpose"),
            row.getInt("item_order"), row.getLong("row_version"));

    private final JdbcTemplate jdbc;

    public TaskDeliveryDaoImpl(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public int insertDelivery(DeliveryRow row, long now) {
        requireDelivery(row, now);
        return jdbc.update("""
                INSERT INTO task_delivery
                    (tenant_id,client_id,delivery_id,task_id,work_item_id,revision,
                     supersedes_delivery_id,producer_agent_id,run_id,summary,state,
                     submitted_at,reviewed_at,manifest_artifact_id,manifest_artifact_version,
                     created_at,updated_at,row_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, ?,NULL,?,?, ?,?,0)
                """, row.tenantId(), row.clientId(), row.deliveryId(), row.taskId(),
                row.workItemId(), row.revision(), row.supersedesDeliveryId(),
                row.producerAgentId(), row.runId(), row.summary(), row.state(),
                row.submittedAt(), row.manifestArtifactId(), row.manifestArtifactVersion(),
                now, now);
    }

    @Override
    public int insertItem(ItemRow row, long now) {
        requireItem(row, now);
        return jdbc.update("""
                INSERT INTO task_delivery_item
                    (tenant_id,client_id,delivery_id,artifact_id,artifact_version,
                     content_hash,object_id,purpose,item_order,created_at,updated_at,row_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,0)
                """, row.tenantId(), row.clientId(), row.deliveryId(), row.artifactId(),
                row.artifactVersion(), row.contentHash(), row.objectId(), row.purpose(),
                row.itemOrder(), now, now);
    }

    @Override
    public DeliveryRow findExact(
            String tenantId, String clientId, String deliveryId, boolean forUpdate) {
        requireScope(tenantId, clientId);
        requireId(deliveryId, "deliveryId", 100);
        return one("""
                SELECT * FROM task_delivery
                WHERE tenant_id=? AND client_id=? AND delivery_id=?
                """ + (forUpdate ? " FOR UPDATE" : ""), DELIVERY,
                tenantId, clientId, deliveryId);
    }

    @Override
    public DeliveryRow findTaskRevision(
            String tenantId, String clientId, String taskId, long revision,
            boolean forUpdate) {
        requireScope(tenantId, clientId);
        requireId(taskId, "taskId", 400);
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        return one("""
                SELECT * FROM task_delivery
                WHERE tenant_id=? AND client_id=? AND task_id=? AND revision=?
                """ + (forUpdate ? " FOR UPDATE" : ""), DELIVERY,
                tenantId, clientId, taskId, revision);
    }

    @Override
    public List<ItemRow> listItems(
            String tenantId, String clientId, String deliveryId, boolean forUpdate) {
        requireScope(tenantId, clientId);
        requireId(deliveryId, "deliveryId", 100);
        return jdbc.query("""
                SELECT * FROM task_delivery_item
                WHERE tenant_id=? AND client_id=? AND delivery_id=?
                ORDER BY item_order,CAST(artifact_id AS BINARY),artifact_version
                """ + (forUpdate ? " FOR UPDATE" : ""), ITEM,
                tenantId, clientId, deliveryId);
    }

    private static void requireDelivery(DeliveryRow row, long now) {
        if (row == null) throw new IllegalArgumentException("delivery is required");
        requireScope(row.tenantId(), row.clientId());
        requireId(row.deliveryId(), "deliveryId", 100);
        requireId(row.taskId(), "taskId", 400);
        requireId(row.workItemId(), "workItemId", 400);
        requireId(row.producerAgentId(), "producerAgentId", 400);
        requireId(row.runId(), "runId", 100);
        requireId(row.manifestArtifactId(), "manifestArtifactId", 400);
        if (row.revision() <= 0 || row.manifestArtifactVersion() <= 0
                || row.submittedAt() <= 0 || now <= 0 || !"SUBMITTED".equals(row.state())
                || row.summary() == null || row.summary().isBlank()
                || row.reviewedAt() != null || row.rowVersion() != 0) {
            throw new IllegalArgumentException("delivery snapshot is invalid");
        }
    }

    private static void requireItem(ItemRow row, long now) {
        if (row == null) throw new IllegalArgumentException("delivery item is required");
        requireScope(row.tenantId(), row.clientId());
        requireId(row.deliveryId(), "deliveryId", 100);
        requireId(row.artifactId(), "artifactId", 400);
        if (row.artifactVersion() <= 0 || row.contentHash() == null
                || row.contentHash().length != 32 || row.purpose() == null
                || row.purpose().isBlank() || row.purpose().length() > 255
                || row.itemOrder() < 0 || row.rowVersion() != 0 || now <= 0) {
            throw new IllegalArgumentException("delivery item snapshot is invalid");
        }
        if (row.objectId() != null) requireId(row.objectId(), "objectId", 100);
    }

    private static void requireScope(String tenantId, String clientId) {
        requireId(tenantId, "tenantId", 200);
        requireId(clientId, "clientId", 200);
    }

    private static void requireId(String value, String field, int max) {
        if (value == null || value.isEmpty() || value.length() > max
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private <T> T one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = jdbc.query(sql, mapper, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Long nullableLong(ResultSet row, String name) throws SQLException {
        long value = row.getLong(name);
        return row.wasNull() ? null : value;
    }
}
