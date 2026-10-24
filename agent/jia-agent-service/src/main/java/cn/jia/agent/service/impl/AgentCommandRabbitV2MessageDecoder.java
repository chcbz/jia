package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.common.AgentProtocolConstants;
import cn.jia.agent.config.AgentRabbitV2TopologyManifest;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Strict v2-only decoder. Legacy headers or missing persisted provenance fail closed. */
final class AgentCommandRabbitV2MessageDecoder {
    private static final Set<String> TYPES = Set.of("SKILL_INSTALL", "TASK_INVITE",
            "WORK_ITEM_EXECUTE", "WORK_ITEM_RESUME", "WORK_ITEM_CANCEL",
            "REQUEST_RESPOND", "REVIEW_EXECUTE", "CONTEXT_REFRESH");
    private static final Set<String> HEADERS = Set.of(
            AgentCommandAmqpContract.HEADER_WIRE_VERSION, AgentCommandAmqpContract.HEADER_EVENT_ID,
            AgentCommandAmqpContract.HEADER_DELIVERY_ID, AgentCommandAmqpContract.HEADER_COMMAND_ID,
            AgentCommandAmqpContract.HEADER_TENANT_ID, AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID,
            AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID,
            AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID,
            AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH, AgentCommandAmqpContract.HEADER_MODULE_ID,
            AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH, AgentCommandAmqpContract.HEADER_CLIENT_ID,
            AgentCommandAmqpContract.HEADER_TASK_ID, AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID,
            AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT, AgentCommandAmqpContract.HEADER_EXPIRES_AT,
            AgentCommandAmqpContract.HEADER_WIRE_SHA256, AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256,
            AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);
    private static final Set<String> COMMON_BODY_FIELDS = Set.of(
            "schemaVersion", "messageType", "messageId", "commandId", "correlationId",
            "causationId", "deliveryId", "tenantId", "dataDomainId", "executionPoolId",
            "producerDeploymentId", "admissionEpoch", "moduleId", "migrationEpoch",
            "clientId", "ownerJiacn", "taskId", "workItemId", "targetAgentId",
            "commandType", "issuedAt", "expiresAt", "attempt", "payload");
    private static final Set<String> SKILL_BODY_FIELDS = Set.of(
            "requestId", "fencingToken", "deliveryEpoch", "orderId", "installationId",
            "productVersionId", "skillKey", "skillVersion", "packageSize", "packageDigest",
            "downloadPath");
    private static final Set<String> HALL_COMPATIBILITY_FIELDS = Set.of(
            "type", "agentId", "actionType", "content", "metadata");
    private static final Set<String> TASK_INVITE_PAYLOAD_FIELDS = Set.of(
            "actionType", "reason", "instruction", "taskTitle", "requiredAbilities",
            "coordinatorAgentId", "collaboratorAgentIds", "assignmentRole", "acceptance",
            "conversationType");
    private static final Set<String> HALL_PAYLOAD_FIELDS = Set.of(
            "actionType", "instruction", "conversationType", "reason", "conversationId",
            "triggerEventId", "autonomyLevel", "requiresApproval", "context");
    private static final Set<String> HALL_CONTEXT_FIELDS = Set.of(
            "taskTitle", "workItemTitle", "requestSummary", "reviewSummary", "contextVersion",
            "referenceIds", "tags");
    private static final Set<String> HALL_BOUND_CONTEXT_FIELDS = union(
            HALL_CONTEXT_FIELDS, Set.of("bindingVersion", "reassignmentId"));
    private static final Set<String> SKILL_PAYLOAD_FIELDS = Set.of(
            "orderId", "installationId", "productVersionId", "skillKey", "skillVersion",
            "packageSize", "packageDigest", "downloadPath");
    private static final Set<String> METADATA_FIELDS = Set.of(
            "taskId", "reason", "autonomyLevel", "requiresApproval", "context", "autonomy");
    private static final Set<String> RESERVED_PAYLOAD_FIELDS = Set.of(
            "schemaVersion", "messageType", "messageId", "requestId", "eventId", "deliveryId",
            "commandId", "commandType", "correlationId", "causationId", "intentId",
            "tenantId", "dataDomainId", "executionPoolId", "producerDeploymentId",
            "admissionEpoch", "moduleId", "migrationEpoch", "claimantDeploymentId",
            "claimantWorkerGeneration", "clientId", "ownerJiacn", "taskId", "workItemId",
            "targetAgentId", "agentId", "sourceAgentId", "receiverAgentId", "consumerName",
            "destination", "routingKey", "attempt", "issuedAt", "expiresAt", "runtimeInstanceId",
            "type", "fencingToken", "deliveryEpoch");
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final AgentRabbitV2TopologyManifest manifest;
    AgentCommandRabbitV2MessageDecoder(AgentRabbitV2TopologyManifest manifest) { this.manifest=Objects.requireNonNull(manifest); }

    DecodedAgentCommandMessage decode(Message message) {
        if (message==null || message.getMessageProperties()==null) throw bad("MESSAGE_MISSING");
        var p=message.getMessageProperties();
        if (!AgentCommandAmqpContract.CONTENT_TYPE.equals(p.getContentType())
                || !AgentCommandAmqpContract.CONTENT_ENCODING.equals(p.getContentEncoding())
                || !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(p.getType())
                || !manifest.dispatchQueue().equals(p.getConsumerQueue())
                || p.getReceivedDeliveryMode()!=MessageDeliveryMode.PERSISTENT
                || !manifest.allowsCommandPublish(p.getReceivedExchange(),p.getReceivedRoutingKey())) throw bad("TRANSPORT_INVALID");
        MessageDeliveryMode sent = p.getDeliveryMode();
        if (sent != null && sent != p.getReceivedDeliveryMode()) {
            throw bad("DELIVERY_MODE_CONFLICT");
        }
        Map<String,Object> h=p.getHeaders();
        if (h==null || !h.keySet().containsAll(HEADERS)) throw bad("HEADER_MISSING");
        for (String n:h.keySet()) if (n!=null && n.startsWith("x-jia-agent-") && !HEADERS.contains(n)) throw bad("UNKNOWN_AGENT_HEADER");
        int version=integer(h,AgentCommandAmqpContract.HEADER_WIRE_VERSION);
        String event=str(h,AgentCommandAmqpContract.HEADER_EVENT_ID,100);
        long delivery=deliveryId(h,AgentCommandAmqpContract.HEADER_DELIVERY_ID);
        String command=str(h,AgentCommandAmqpContract.HEADER_COMMAND_ID,100);
        String tenant=canonical(str(h,AgentCommandAmqpContract.HEADER_TENANT_ID,50),"0|[1-9][0-9]{0,49}");
        String domain=canonical(str(h,AgentCommandAmqpContract.HEADER_DATA_DOMAIN_ID,64),"[a-z][a-z0-9-]{0,63}");
        String pool=canonical(str(h,AgentCommandAmqpContract.HEADER_EXECUTION_POOL_ID,100),"[a-z0-9-]{1,100}");
        String producer=str(h,AgentCommandAmqpContract.HEADER_PRODUCER_DEPLOYMENT_ID,100);
        long admission=number(h,AgentCommandAmqpContract.HEADER_ADMISSION_EPOCH);
        String module=canonical(str(h,AgentCommandAmqpContract.HEADER_MODULE_ID,64),"[a-z][a-z0-9_-]{0,63}");
        long migration=number(h,AgentCommandAmqpContract.HEADER_MIGRATION_EPOCH);
        String client=str(h,AgentCommandAmqpContract.HEADER_CLIENT_ID,50);
        String task=str(h,AgentCommandAmqpContract.HEADER_TASK_ID,100);
        String target=str(h,AgentCommandAmqpContract.HEADER_TARGET_AGENT_ID,100);
        int attempt=integer(h,AgentCommandAmqpContract.HEADER_ACTIVE_ATTEMPT);
        long expires=number(h,AgentCommandAmqpContract.HEADER_EXPIRES_AT);
        String hashHex=str(h,AgentCommandAmqpContract.HEADER_WIRE_SHA256,64);
        String topology=str(h,AgentCommandAmqpContract.HEADER_TOPOLOGY_SHA256,64);
        int retry=integer(h,AgentCommandAmqpContract.HEADER_SOURCE_SETTLEMENT_RETRY);
        if (!manifest.matchesScope(tenant, domain, pool)
                || version!=2 || delivery<=0 || admission<0 || migration<0 || attempt<=0 || expires<=0 || retry<0 || retry>1000
                || !hashHex.matches("[0-9a-f]{64}") || !manifest.sha256().equals(topology)) throw bad("PROVENANCE_INVALID");
        byte[] raw=message.getBody();
        if (raw==null || raw.length==0 || raw.length>AgentCommandAmqpContract.MAX_WIRE_BYTES) throw bad("WIRE_SIZE_INVALID");
        byte[] hash=AgentCommandAmqpContract.sha256(raw);
        if (!MessageDigest.isEqual(hash,HexFormat.of().parseHex(hashHex))) throw bad("WIRE_HASH_CONFLICT");
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)); }
        catch (CharacterCodingException e) { throw bad("WIRE_ENCODING_INVALID"); }
        JsonNode root; try { root=JSON.readTree(raw); } catch(Exception e) { throw bad("BODY_JSON_INVALID"); }
        String messageId=exact(p.getMessageId(),100);
        if (root==null || !root.isObject() || !num(root,"schemaVersion",2)
                || !AgentProtocolConstants.TYPE_COMMAND_DISPATCH.equals(text(root,"messageType"))
                || !messageId.equals(text(root,"messageId")) || !command.equals(text(root,"commandId"))
                || !deliveryId(root,"deliveryId",delivery)
                || !tenant.equals(text(root,"tenantId")) || !domain.equals(text(root,"dataDomainId"))
                || !pool.equals(text(root,"executionPoolId")) || !producer.equals(text(root,"producerDeploymentId"))
                || !num(root,"admissionEpoch",admission) || !module.equals(text(root,"moduleId"))
                || !num(root,"migrationEpoch",migration) || !client.equals(text(root,"clientId"))
                || !task.equals(text(root,"taskId")) || !target.equals(text(root,"targetAgentId"))
                || !num(root,"attempt",attempt) || !num(root,"expiresAt",expires)) {
            throw bad("HEADER_BODY_CONFLICT");
        }
        String owner=exact(text(root,"ownerJiacn"),50), type=exact(text(root,"commandType"),64);
        if (!TYPES.contains(type)) throw bad("COMMAND_TYPE_NOT_ALLOWED");
        validateEnvelope(root, type, messageId, task, target);
        return new DecodedAgentCommandMessage(messageId,event,delivery,command,tenant,domain,pool,producer,
                admission,module,migration,version,client,owner,task,target,type,attempt,expires,topology,retry,raw,hash);
    }

    private static void validateEnvelope(
            JsonNode root, String commandType, String messageId, String taskId,
            String targetAgentId) {
        boolean skill = "SKILL_INSTALL".equals(commandType);
        boolean hall = root.has("intentId");
        boolean plainTaskInvite = AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)
                && !hall;
        if ((skill && hall) || (!skill && !hall && !plainTaskInvite)) {
            throw bad("COMMAND_ENVELOPE_INVALID");
        }

        Set<String> expected = COMMON_BODY_FIELDS;
        if (skill) expected = union(expected, SKILL_BODY_FIELDS);
        if (hall) expected = union(expected, Set.of("intentId"));
        if (hall && AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)) {
            expected = union(expected, HALL_COMPATIBILITY_FIELDS);
        }
        requireExactObject(root, expected, "BODY_FIELDS_INVALID");

        exact(text(root, "correlationId"), 100);
        exact(text(root, "causationId"), 100);
        nullableExact(root, "workItemId", 100);
        if (!positive(root, "issuedAt")) throw bad("BODY_VALUE_INVALID");

        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) throw bad("PAYLOAD_INVALID");
        rejectReservedPayloadFields(payload);
        if (skill) {
            validateSkill(root, payload, messageId);
        } else if (hall) {
            validateHall(root, payload, commandType, taskId, targetAgentId);
        } else {
            validatePlainTaskInvite(payload);
        }
    }

    private static void validateSkill(JsonNode root, JsonNode payload, String messageId) {
        requireExactObject(payload, SKILL_PAYLOAD_FIELDS, "SKILL_PAYLOAD_FIELDS_INVALID");
        for (String field : SKILL_PAYLOAD_FIELDS) {
            exact(text(payload, field), 1_000);
            if (!Objects.equals(root.get(field), payload.get(field))) {
                throw bad("SKILL_COMPATIBILITY_CONFLICT");
            }
        }
        if (!messageId.equals(text(root, "requestId"))
                || !"1".equals(text(root, "fencingToken"))
                || !"1".equals(text(root, "deliveryEpoch"))) {
            throw bad("SKILL_COMPATIBILITY_CONFLICT");
        }
    }

    private static void validatePlainTaskInvite(JsonNode payload) {
        requireExactObject(payload, TASK_INVITE_PAYLOAD_FIELDS,
                "TASK_INVITE_PAYLOAD_FIELDS_INVALID");
        requireTextFields(payload, Set.of(
                "actionType", "reason", "instruction", "taskTitle", "coordinatorAgentId",
                "assignmentRole", "acceptance", "conversationType"));
        requireStringArray(payload, "requiredAbilities");
        requireStringArray(payload, "collaboratorAgentIds");
    }

    private static void validateHall(JsonNode root, JsonNode payload, String commandType,
            String taskId, String targetAgentId) {
        exact(text(root, "intentId"), 100);
        requireExactObject(payload, HALL_PAYLOAD_FIELDS, "HALL_PAYLOAD_FIELDS_INVALID");
        requireTextFields(payload, Set.of("actionType", "instruction", "conversationType"));
        requireNullableText(payload, "reason");
        requireNullableText(payload, "conversationId");
        requireNullableText(payload, "triggerEventId");
        requireNullableText(payload, "autonomyLevel");
        JsonNode approval = payload.get("requiresApproval");
        if (approval == null || (!approval.isNull() && !approval.isBoolean())) {
            throw bad("HALL_PAYLOAD_TYPE_INVALID");
        }
        validateHallContext(payload.get("context"));
        String mapped;
        try {
            mapped = AgentCommandCanonicalCodec.hallCommandTypeForAction(
                    text(payload, "actionType"));
        } catch (IllegalArgumentException invalid) {
            throw bad("HALL_ACTION_CONFLICT");
        }
        if (!commandType.equals(mapped)) throw bad("HALL_ACTION_CONFLICT");

        if (!AgentProtocolConstants.COMMAND_TASK_INVITE.equals(commandType)) return;
        JsonNode metadata = root.get("metadata");
        requireExactObject(metadata, METADATA_FIELDS, "HALL_COMPATIBILITY_FIELDS_INVALID");
        if (!AgentProtocolConstants.LEGACY_AGENT_DIRECT_MESSAGE.equals(text(root, "type"))
                || !targetAgentId.equals(text(root, "agentId"))
                || !Objects.equals(root.get("actionType"), payload.get("actionType"))
                || !Objects.equals(root.get("content"), payload.get("instruction"))
                || !taskId.equals(text(metadata, "taskId"))
                || !Objects.equals(metadata.get("reason"), payload.get("reason"))
                || !Objects.equals(metadata.get("autonomyLevel"), payload.get("autonomyLevel"))
                || !Objects.equals(metadata.get("requiresApproval"), payload.get("requiresApproval"))
                || !Objects.equals(metadata.get("context"), payload.get("context"))
                || !metadata.path("autonomy").isBoolean()
                || !metadata.path("autonomy").booleanValue()) {
            throw bad("HALL_COMPATIBILITY_CONFLICT");
        }
    }

    private static void validateHallContext(JsonNode context) {
        if (context == null) throw bad("HALL_CONTEXT_MISSING");
        if (context.isNull()) return;
        boolean bound = context.has("bindingVersion") || context.has("reassignmentId");
        requireExactObject(context, bound ? HALL_BOUND_CONTEXT_FIELDS : HALL_CONTEXT_FIELDS,
                "HALL_CONTEXT_FIELDS_INVALID");
        for (String field : Set.of(
                "taskTitle", "workItemTitle", "requestSummary", "reviewSummary",
                "contextVersion")) {
            requireNullableText(context, field);
        }
        requireStringArray(context, "referenceIds");
        requireStringArray(context, "tags");
        if (bound) {
            exact(text(context, "bindingVersion"), 100);
            exact(text(context, "reassignmentId"), 100);
        }
    }

    private static void rejectReservedPayloadFields(JsonNode node) {
        if (node.isObject()) {
            for (String field : node.propertyNames()) {
                if (RESERVED_PAYLOAD_FIELDS.contains(field)) {
                    throw bad("PAYLOAD_RESERVED_FIELD");
                }
                rejectReservedPayloadFields(node.get(field));
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                rejectReservedPayloadFields(node.get(index));
            }
        }
    }

    private static void requireExactObject(JsonNode node, Set<String> fields, String reason) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw bad(reason);
        for (String field : node.propertyNames()) if (!fields.contains(field)) throw bad(reason);
        for (String field : fields) if (!node.has(field)) throw bad(reason);
    }

    private static void requireTextFields(JsonNode node, Set<String> fields) {
        for (String field : fields) exact(text(node, field), 8_000);
    }

    private static void requireNullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || (!value.isNull() && !value.isTextual())) {
            throw bad("PAYLOAD_FIELD_TYPE_INVALID");
        }
        if (value.isTextual()) exact(value.textValue(), 8_000);
    }

    private static void nullableExact(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || (!value.isNull() && !value.isTextual())) {
            throw bad("BODY_VALUE_INVALID");
        }
        if (value.isTextual()) exact(value.textValue(), max);
    }

    private static void requireStringArray(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw bad("PAYLOAD_FIELD_TYPE_INVALID");
        for (int index = 0; index < value.size(); index++) {
            JsonNode item = value.get(index);
            if (item == null || !item.isTextual()) throw bad("PAYLOAD_FIELD_TYPE_INVALID");
            exact(item.textValue(), 100);
        }
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> result = new LinkedHashSet<>();
        for (Set<String> set : sets) result.addAll(set);
        return Set.copyOf(result);
    }

    private static boolean positive(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>0;}
    private static String str(Map<String,Object> h,String n,int m){Object v=h.get(n);if(!(v instanceof String s))throw bad("HEADER_TYPE_INVALID");return exact(s,m);}
    private static int integer(Map<String,Object> h,String n){Object v=h.get(n);if(!(v instanceof Integer i))throw bad("HEADER_TYPE_INVALID");return i;}
    private static long number(Map<String,Object> h,String n){Object v=h.get(n);if(!(v instanceof Long l))throw bad("HEADER_TYPE_INVALID");return l;}
    private static long deliveryId(Map<String,Object> h,String n){
        Object v=h.get(n);
        if(!(v instanceof String s))throw bad("HEADER_TYPE_INVALID");
        try{return AgentCommandAmqpContract.parseCanonicalDeliveryId(s);}
        catch(IllegalArgumentException invalid){throw bad("DELIVERY_ID_INVALID");}
    }
    private static String exact(String v,int m){if(v==null||v.isEmpty()||v.length()>m||!v.equals(v.strip())||v.codePoints().anyMatch(Character::isISOControl))throw bad("VALUE_INVALID");return v;}
    private static String canonical(String v,String r){if(!v.matches(r))throw bad("CANONICAL_INVALID");return v;}
    private static String text(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isTextual()?v.textValue():null;}
    private static boolean num(JsonNode n,String f,long e){JsonNode v=n.get(f);return v!=null&&v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()==e;}
    private static boolean deliveryId(JsonNode n,String f,long e){
        JsonNode v=n.get(f);
        if(v==null||!v.isTextual())return false;
        try{return AgentCommandAmqpContract.parseCanonicalDeliveryId(v.textValue())==e;}
        catch(IllegalArgumentException invalid){return false;}
    }
    private static AgentCommandRabbitDecodeException bad(String r){return new AgentCommandRabbitDecodeException(r);}
}
