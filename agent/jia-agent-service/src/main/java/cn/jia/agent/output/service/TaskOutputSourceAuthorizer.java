package cn.jia.agent.output.service;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorizer;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import org.springframework.stereotype.Component;

@Component
public final class TaskOutputSourceAuthorizer implements OutputSourceAuthorizer {
    private final AgentTaskCollaborationAccessService accessService;

    public TaskOutputSourceAuthorizer(AgentTaskCollaborationAccessService accessService) {
        this.accessService = accessService;
    }

    @Override
    public String sourceType() {
        return OutputConstants.SOURCE_TASK;
    }

    @Override
    public OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceId, String producerAgentId) {
        return lockAndAuthorize(tenantId, clientId, sourceId, producerAgentId,
                OutputSourceAccessMode.MUTATION);
    }

    @Override
    public OutputSourceAuthorization lockAndAuthorize(
            String tenantId, String clientId, String sourceId, String producerAgentId,
            OutputSourceAccessMode accessMode) {
        requireExact(tenantId, "tenantId", 200);
        requireExact(clientId, "clientId", 200);
        requireExact(sourceId, "sourceId", 400);
        requireExact(producerAgentId, "producerAgentId", 400);
        AgentTaskAccessLevel access = accessService.resolveMemberAccessForUpdate(
                tenantId, clientId, sourceId, producerAgentId);
        boolean allowed = accessMode == OutputSourceAccessMode.MUTATION
                ? access.canWrite()
                : accessMode == OutputSourceAccessMode.RECEIPT_READ && access.canRead();
        if (!allowed) {
            throw new OutputAuthorizationException("OUTPUT_SOURCE_FORBIDDEN",
                    "Task output source is unavailable");
        }
        return new OutputSourceAuthorization(tenantId, clientId, sourceType(), sourceId,
                tenantId, producerAgentId, access.canWrite());
    }

    private void requireExact(String value, String field, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new OutputAuthorizationException("OUTPUT_ID_INVALID", field + " is invalid");
        }
    }
}
