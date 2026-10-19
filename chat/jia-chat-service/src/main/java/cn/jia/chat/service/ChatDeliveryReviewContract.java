package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskFormalDeliveryItemDTO;
import cn.jia.agent.entity.AgentTaskFormalDeliveryViewDTO;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Frozen review bytes. Long versions are canonical decimal strings, never doubles. */
public final class ChatDeliveryReviewContract {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Set<String> FIELDS = Set.of("schemaVersion", "deliveryId", "expectedTaskVersion",
            "expectedDeliveryVersion", "expectedAssignmentRevision", "batchSha256", "decision",
            "conversationId", "conversationGeneration", "instruction", "sourceSelectors");
    private ChatDeliveryReviewContract() { }

    public record Scope(String tenantId, String clientId, String ownerJiacn) {
        public Scope { id(tenantId, 50); id(clientId, 50); id(ownerJiacn, 50); }
    }
    public record Accept(String deliveryId, long taskVersion, long deliveryVersion,
            long assignmentRevision, String batchSha256) {
        public Accept {
            id(deliveryId, 100);
            if (taskVersion < 0 || deliveryVersion < 0 || assignmentRevision < 0) throw new BadRequest();
            hash(batchSha256);
        }
    }
    public record Receipt(int schemaVersion, String operationId, String taskId, String deliveryId,
            String decision, String state, String stateVersion, String taskState, String taskVersion,
            String deliveryState, String deliveryVersion, String requestId, String sourceRequestId,
            String conversationId, String errorCode, boolean retryable) { }

    public static Accept parse(JsonNode node) {
        if (node == null || !node.isObject() || node.size() != FIELDS.size()) throw new BadRequest();
        for (String field : FIELDS) if (!node.has(field)) throw new BadRequest();
        if (!node.get("schemaVersion").isInt() || node.get("schemaVersion").intValue() != 1
                || !"ACCEPT".equals(text(node, "decision"))
                || !node.get("conversationId").isNull() || !node.get("conversationGeneration").isNull()
                || !node.get("instruction").isNull() || !node.get("sourceSelectors").isArray()
                || !node.get("sourceSelectors").isEmpty()) throw new BadRequest();
        return new Accept(text(node, "deliveryId"), version(node, "expectedTaskVersion"),
                version(node, "expectedDeliveryVersion"), version(node, "expectedAssignmentRevision"), text(node, "batchSha256"));
    }
    public static java.util.Map<String,Object> body(Accept command) {
        var result = new java.util.LinkedHashMap<String,Object>();
        result.put("schemaVersion", 1); result.put("deliveryId", command.deliveryId());
        result.put("expectedTaskVersion", Long.toString(command.taskVersion()));
        result.put("expectedDeliveryVersion", Long.toString(command.deliveryVersion()));
        result.put("expectedAssignmentRevision", Long.toString(command.assignmentRevision()));
        result.put("batchSha256", command.batchSha256()); result.put("decision", "ACCEPT");
        result.put("conversationId", null); result.put("conversationGeneration", null);
        result.put("instruction", null); result.put("sourceSelectors", List.of());
        return java.util.Collections.unmodifiableMap(result);
    }
    public static String requestDigest(Accept command) {
        return sha(json(Arrays.asList("hall-delivery-review-v1", 1, command.deliveryId(),
                Long.toString(command.taskVersion()), Long.toString(command.deliveryVersion()),
                Long.toString(command.assignmentRevision()), command.batchSha256(), "ACCEPT", null, null, null, List.of())));
    }
    public static String batchDigest(String taskId, AgentTaskFormalDeliveryViewDTO delivery) {
        id(taskId, 100);
        if (delivery == null || !taskId.equals(delivery.getTaskId()) || delivery.getDeliveryVersion() == null
                || delivery.getDeliveryVersion() < 0 || delivery.getItems() == null || delivery.getItems().isEmpty()) throw new Conflict();
        id(delivery.getDeliveryId(), 100);
        List<AgentTaskFormalDeliveryItemDTO> sorted = new ArrayList<>(delivery.getItems());
        Set<String> ids = new HashSet<>();
        for (var item : sorted) {
            if (item == null || item.getArtifactVersion() == null || item.getArtifactVersion() < 1
                    || item.getPurpose() == null || !validUnicode(item.getPurpose())) throw new Conflict();
            id(item.getArtifactId(), 100); hash(item.getContentHash());
            if (!ids.add(item.getArtifactId())) throw new Conflict();
        }
        sorted.sort((a, b) -> {
            int id = Arrays.compareUnsigned(a.getArtifactId().getBytes(StandardCharsets.UTF_8), b.getArtifactId().getBytes(StandardCharsets.UTF_8));
            return id != 0 ? id : Integer.compare(a.getArtifactVersion(), b.getArtifactVersion());
        });
        List<List<String>> items = sorted.stream().map(item -> List.of(item.getArtifactId(),
                Integer.toString(item.getArtifactVersion()), item.getContentHash(), item.getPurpose())).toList();
        return sha(json(List.of("hall-delivery-batch-v1", taskId, delivery.getDeliveryId(),
                Long.toString(delivery.getDeliveryVersion()), items)));
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception failure) { throw new Storage(failure); }
    }
    public static Receipt receipt(String json) {
        try {
            JsonNode node=JSON.readTree(json);
            var fields=Set.of("schemaVersion","operationId","taskId","deliveryId","decision","state","stateVersion",
                    "taskState","taskVersion","deliveryState","deliveryVersion","requestId","sourceRequestId","conversationId","errorCode","retryable");
            if(node==null || !node.isObject() || node.size()!=fields.size() || fields.stream().anyMatch(f->!node.has(f))) throw new BadRequest();
            var value=JSON.treeToValue(node,Receipt.class);
            id(value.operationId(),100); id(value.taskId(),100); id(value.deliveryId(),100);
            if(value.schemaVersion()!=1 || !"ACCEPT".equals(value.decision()) || !Set.of("COMMITTED","REJECTED").contains(value.state())
                    || version(node,"stateVersion")<2 || !Set.of("OPEN","PLANNING","ASSIGNED","RUNNING","BLOCKED","REVIEWING","COMPLETED","FAILED","CANCELLED","ARCHIVED").contains(value.taskState())
                    || !Set.of("SUBMITTED","ACCEPTED","CHANGES_REQUESTED").contains(value.deliveryState())
                    || !node.get("retryable").isBoolean() || value.retryable() || value.requestId()!=null || value.sourceRequestId()!=null || value.conversationId()!=null) throw new BadRequest();
            version(node,"taskVersion"); version(node,"deliveryVersion");
            if("COMMITTED".equals(value.state()) ? !"COMPLETED".equals(value.taskState()) || !"ACCEPTED".equals(value.deliveryState()) || value.errorCode()!=null
                    : value.errorCode()==null || !value.errorCode().matches("DELIVERY_[A-Z_]+")) throw new BadRequest();
            return value;
        } catch (Exception failure) { throw new Storage(failure); }
    }
    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static void id(String value, int max) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0," + (max - 1) + "}")) throw new BadRequest();
    }
    public static void key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,99}")) throw new BadRequest();
    }
    private static void hash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw new BadRequest(); }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field); if (value == null || !value.isTextual()) throw new BadRequest(); return value.textValue();
    }
    private static long version(JsonNode node, String field) {
        String value = text(node, field); if (!value.matches("0|[1-9][0-9]*")) throw new BadRequest();
        try { return Long.parseLong(value); } catch (NumberFormatException failure) { throw new BadRequest(); }
    }
    private static boolean validUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c) || c == 0) return false;
        }
        return true;
    }
    public static final class BadRequest extends RuntimeException { }
    public static final class Conflict extends RuntimeException { }
    public static final class IdempotencyConflict extends RuntimeException { }
    public static final class Missing extends RuntimeException { }
    public static final class SourceUnavailable extends RuntimeException { }
    public static final class Storage extends RuntimeException { public Storage(Throwable cause) { super(cause); } }
}
