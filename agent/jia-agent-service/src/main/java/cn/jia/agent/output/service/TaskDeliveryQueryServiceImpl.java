package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.TaskDeliveryQueryService;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import cn.jia.agent.output.dto.TaskDeliveryPageDTO;
import cn.jia.agent.output.dto.TaskDeliveryViewDTO;
import cn.jia.agent.output.dto.TaskDeliveryViewItemDTO;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Fail-closed owner query for immutable formal deliveries. */
@Service
@ConditionalOnProperty(prefix = "agent.output-delivery", name = "enabled", havingValue = "true")
public final class TaskDeliveryQueryServiceImpl implements TaskDeliveryQueryService {
    private final AgentTaskMetaDao taskDao;
    private final TaskDeliveryDao deliveryDao;
    private final TransactionTemplate transaction;
    private final byte[] cursorKey;

    public TaskDeliveryQueryServiceImpl(
            AgentTaskMetaDao taskDao, TaskDeliveryDao deliveryDao,
            PlatformTransactionManager transactionManager,
            OutputDeliveryProperties properties) {
        this.taskDao = Objects.requireNonNull(taskDao);
        this.deliveryDao = Objects.requireNonNull(deliveryDao);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        String configured = Objects.requireNonNull(properties).cursorSigningKey();
        if (configured == null) configured = properties.storageSecretKey();
        if (configured == null) {
            throw new IllegalStateException(
                    "Formal delivery reads require a stable cursor signing key");
        }
        this.cursorKey = sha256(configured.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TaskDeliveryPageDTO list(
            String tenantId, String clientId, String jiacn,
            String taskId, String cursor, Integer requestedLimit) {
        requireScope(tenantId, clientId, jiacn, taskId);
        int limit = requestedLimit == null ? 20 : requestedLimit;
        if (limit < 1 || limit > 100) throw bad("OUTPUT_LIMIT_INVALID");
        Cursor decoded = cursor == null ? null
                : decode(cursor, tenantId, clientId, taskId);
        long snapshotAt = decoded == null ? System.currentTimeMillis() : decoded.snapshotAt();
        TaskDeliveryPageDTO page = transaction.execute(status -> {
            AgentTaskMetaEntity task = taskDao.findByTaskId(tenantId, clientId, taskId);
            requireOwner(task, tenantId, clientId, jiacn, taskId);
            List<TaskDeliveryDao.DeliveryRow> rows = deliveryDao.listTaskDeliveries(
                    tenantId, clientId, taskId, snapshotAt,
                    decoded == null ? null : decoded.submittedAt(),
                    decoded == null ? null : decoded.deliveryId(), limit + 1);
            if (rows == null) throw unavailable();
            boolean more = rows.size() > limit;
            List<TaskDeliveryDao.DeliveryRow> selected = rows.subList(
                    0, Math.min(limit, rows.size()));
            List<TaskDeliveryViewDTO> items = new ArrayList<>(selected.size());
            for (TaskDeliveryDao.DeliveryRow row : selected) {
                requireDelivery(row, tenantId, clientId, taskId, snapshotAt);
                List<TaskDeliveryDao.ItemRow> itemRows = deliveryDao.listItems(
                        tenantId, clientId, row.deliveryId(), false);
                if (itemRows == null || itemRows.isEmpty()) throw unavailable();
                List<TaskDeliveryViewItemDTO> deliveryItems = new ArrayList<>(itemRows.size());
                for (int itemOrder = 0; itemOrder < itemRows.size(); itemOrder++) {
                    TaskDeliveryDao.ItemRow item = itemRows.get(itemOrder);
                    requireItem(item, tenantId, clientId, row.deliveryId(), itemOrder);
                    deliveryItems.add(new TaskDeliveryViewItemDTO(
                            item.artifactId(), Long.toString(item.artifactVersion()),
                            item.purpose()));
                }
                List<String> actions = "SUBMITTED".equals(row.state())
                        && row.deliveryId().equals(task.getCurrentDeliveryId())
                        && "reviewing".equals(task.getRewardStatus())
                        ? List.of("accept", "request_changes") : List.of();
                items.add(new TaskDeliveryViewDTO(
                        row.deliveryId(), row.taskId(), Long.toString(row.revision()),
                        Long.toString(row.rowVersion()), Long.toString(task.getTaskVersion()),
                        row.state(), row.summary(), List.copyOf(deliveryItems), actions));
            }
            String next = null;
            if (more && !selected.isEmpty()) {
                TaskDeliveryDao.DeliveryRow last = selected.getLast();
                next = encode(tenantId, clientId, taskId, snapshotAt,
                        last.submittedAt(), last.deliveryId());
            }
            return new TaskDeliveryPageDTO(List.copyOf(items), next,
                    Long.toString(snapshotAt));
        });
        if (page == null) throw unavailable();
        return page;
    }

    private String encode(String tenantId, String clientId, String taskId,
            long snapshotAt, long submittedAt, String deliveryId) {
        long expiresAt = Math.addExact(snapshotAt, OutputConstants.CURSOR_TTL_MILLIS);
        String payload = String.join("|", "1", b64(tenantId), b64(clientId), b64(taskId),
                Long.toString(snapshotAt), Long.toString(submittedAt), b64(deliveryId),
                Long.toString(expiresAt));
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(bytes));
    }

    private Cursor decode(String token, String tenantId, String clientId, String taskId) {
        try {
            if (token.isEmpty() || token.length() > 4096 || !token.equals(token.strip())) {
                throw bad("OUTPUT_CURSOR_INVALID");
            }
            String[] signed = token.split("\\.", -1);
            if (signed.length != 2) throw bad("OUTPUT_CURSOR_INVALID");
            byte[] payload = Base64.getUrlDecoder().decode(signed[0]);
            byte[] signature = Base64.getUrlDecoder().decode(signed[1]);
            if (!MessageDigest.isEqual(signature, hmac(payload))) {
                throw bad("OUTPUT_CURSOR_INVALID");
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 8 || !"1".equals(parts[0])
                    || !tenantId.equals(unb64(parts[1]))
                    || !clientId.equals(unb64(parts[2]))
                    || !taskId.equals(unb64(parts[3]))) throw bad("OUTPUT_CURSOR_INVALID");
            long snapshotAt = positive(parts[4]);
            long submittedAt = positive(parts[5]);
            String deliveryId = unb64(parts[6]);
            long expiresAt = positive(parts[7]);
            if (submittedAt > snapshotAt
                    || expiresAt != Math.addExact(snapshotAt, OutputConstants.CURSOR_TTL_MILLIS)
                    || expiresAt < System.currentTimeMillis()) {
                throw bad("OUTPUT_CURSOR_INVALID");
            }
            requireId(deliveryId, 100);
            return new Cursor(snapshotAt, submittedAt, deliveryId);
        } catch (OutputDeliveryException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw bad("OUTPUT_CURSOR_INVALID");
        }
    }

    private void requireOwner(AgentTaskMetaEntity task, String tenantId, String clientId,
            String jiacn, String taskId) {
        if (task == null || !tenantId.equals(jiacn)
                || !tenantId.equals(task.getTenantId())
                || !clientId.equals(task.getClientId())
                || !taskId.equals(task.getTaskId())
                || task.getTaskVersion() == null || task.getTaskVersion() < 0
                || !Integer.valueOf(1).equals(task.getDeliveryPolicyVersion())
                || task.getDeliveryRevision() == null || task.getDeliveryRevision() < 0) {
            throw new OutputDeliveryException(
                    "OUTPUT_NOT_FOUND", "Formal delivery source is unavailable", 404, false);
        }
    }

    private static void requireDelivery(
            TaskDeliveryDao.DeliveryRow row, String tenantId, String clientId,
            String taskId, long snapshotAt) {
        if (row == null || !tenantId.equals(row.tenantId())
                || !clientId.equals(row.clientId()) || !taskId.equals(row.taskId())
                || row.revision() <= 0 || row.rowVersion() < 0
                || row.submittedAt() <= 0 || row.submittedAt() > snapshotAt
                || !Set.of("SUBMITTED", "ACCEPTED", "CHANGES_REQUESTED").contains(row.state())
                || row.summary() == null || row.summary().isBlank()
                || row.summary().length() > 16_384
                || row.manifestArtifactVersion() <= 0) {
            throw unavailable();
        }
        requireId(row.deliveryId(), 100);
        requireId(row.workItemId(), 400);
        requireId(row.producerAgentId(), 400);
        requireId(row.runId(), 100);
        requireId(row.manifestArtifactId(), 400);
        if (row.supersedesDeliveryId() != null) requireId(row.supersedesDeliveryId(), 100);
        if ("SUBMITTED".equals(row.state()) != (row.reviewedAt() == null)) {
            throw unavailable();
        }
        if (row.reviewedAt() != null && row.reviewedAt() < row.submittedAt()) {
            throw unavailable();
        }
    }

    private static void requireItem(
            TaskDeliveryDao.ItemRow item, String tenantId, String clientId,
            String deliveryId, int expectedOrder) {
        if (item == null || !tenantId.equals(item.tenantId())
                || !clientId.equals(item.clientId())
                || !deliveryId.equals(item.deliveryId())
                || item.artifactVersion() <= 0 || item.rowVersion() < 0
                || item.contentHash() == null || item.contentHash().length != 32
                || item.purpose() == null || item.purpose().isBlank()
                || item.purpose().length() > 255 || item.itemOrder() != expectedOrder) {
            throw unavailable();
        }
        requireId(item.artifactId(), 400);
        if (item.objectId() != null) requireId(item.objectId(), 100);
    }

    private void requireScope(
            String tenantId, String clientId, String jiacn, String taskId) {
        requireId(tenantId, 200);
        requireId(clientId, 200);
        requireId(jiacn, 200);
        requireId(taskId, 400);
    }

    private static void requireId(String value, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw bad("OUTPUT_ID_INVALID");
        }
    }

    private static long positive(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) throw bad("OUTPUT_CURSOR_INVALID");
        return Long.parseLong(value);
    }

    private String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String unb64(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private byte[] hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Formal delivery cursor signing unavailable", failure);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static OutputDeliveryException bad(String code) {
        return new OutputDeliveryException(code, "Invalid formal delivery query", 400, false);
    }

    private static OutputDeliveryException unavailable() {
        return new OutputDeliveryException("OUTPUT_DELIVERY_UNAVAILABLE",
                "Formal delivery unavailable", 503, true);
    }

    private record Cursor(long snapshotAt, long submittedAt, String deliveryId) { }
}
