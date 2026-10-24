package cn.jia.agent.service.impl;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.entity.AgentInboxClaimToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Independent execution-boundary verification of the byte-exact protocol-v2 envelope. */
final class AgentCommandV2WireVerifier {
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private AgentCommandV2WireVerifier() {
    }

    static boolean matches(AgentInboxClaimToken token, String ownerJiacn, byte[] raw) {
        if (token == null || raw == null || raw.length == 0
                || raw.length > AgentCommandAmqpContract.MAX_WIRE_BYTES) return false;
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw));
            JsonNode root = JSON.readTree(raw);
            return root != null && root.isObject()
                    && number(root, "schemaVersion", token.protocolVersion())
                    && text(root, "messageType", "command.dispatch")
                    && text(root, "messageId", token.messageId())
                    && text(root, "commandId", token.commandId())
                    && deliveryId(root, "deliveryId", token.deliveryId())
                    && text(root, "tenantId", token.tenantId())
                    && text(root, "dataDomainId", token.dataDomainId())
                    && text(root, "executionPoolId", token.executionPoolId())
                    && text(root, "producerDeploymentId", token.producerDeploymentId())
                    && number(root, "admissionEpoch", token.admissionEpoch())
                    && text(root, "moduleId", token.moduleId())
                    && number(root, "migrationEpoch", token.migrationEpoch())
                    && text(root, "clientId", token.clientId())
                    && text(root, "ownerJiacn", ownerJiacn)
                    && text(root, "taskId", token.taskId())
                    && text(root, "targetAgentId", token.targetAgentId())
                    && text(root, "commandType", token.commandType())
                    && number(root, "attempt", token.activeAttempt())
                    && number(root, "expiresAt", token.expiresAt());
        } catch (CharacterCodingException | RuntimeException invalid) {
            return false;
        }
    }

    private static boolean text(JsonNode root, String name, String expected) {
        JsonNode value = root.get(name);
        return expected != null && value != null && value.isTextual()
                && expected.equals(value.textValue());
    }

    private static boolean number(JsonNode root, String name, long expected) {
        JsonNode value = root.get(name);
        return value != null && value.isIntegralNumber() && value.canConvertToLong()
                && value.longValue() == expected;
    }

    private static boolean deliveryId(JsonNode root, String name, long expected) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) return false;
        try {
            return AgentCommandAmqpContract.parseCanonicalDeliveryId(value.textValue())
                    == expected;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
