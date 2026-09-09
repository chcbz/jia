package cn.jia.chat.output;

import cn.jia.agent.access.AgentTaskAccessLevel;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputSourceAuthorization;
import cn.jia.agent.output.OutputSourceAccessMode;
import cn.jia.agent.output.OutputSourceAuthorizer;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.ChatConversationEntity;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ConversationOutputSourceAuthorizer implements OutputSourceAuthorizer {
    private final ChatConversationDao conversationDao;
    private final AgentTaskCollaborationAccessService taskAccessService;

    public ConversationOutputSourceAuthorizer(
            ChatConversationDao conversationDao,
            AgentTaskCollaborationAccessService taskAccessService) {
        this.conversationDao = conversationDao;
        this.taskAccessService = taskAccessService;
    }

    @Override
    public String sourceType() {
        return OutputConstants.SOURCE_CONVERSATION;
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
        ChatConversationEntity preview;
        try {
            preview = conversationDao.findExactOwnedById(
                    tenantId, clientId, tenantId, sourceId, false);
        } catch (IllegalArgumentException invalid) {
            throw denied();
        }
        requireConversation(preview, tenantId, clientId, sourceId, producerAgentId);
        AgentTaskAccessLevel taskAccess = AgentTaskAccessLevel.READ_WRITE;
        if (preview.getTaskId() != null && !preview.getTaskId().isEmpty()) {
            taskAccess = taskAccessService.resolveMemberAccessForUpdate(
                    tenantId, clientId, preview.getTaskId(), producerAgentId);
            boolean allowed = accessMode == OutputSourceAccessMode.MUTATION
                    ? taskAccess.canWrite()
                    : accessMode == OutputSourceAccessMode.RECEIPT_READ && taskAccess.canRead();
            if (!allowed) throw denied();
        }
        ChatConversationEntity conversation = conversationDao.findExactOwnedById(
                tenantId, clientId, tenantId, sourceId, true);
        requireConversation(conversation, tenantId, clientId, sourceId, producerAgentId);
        if (!Objects.equals(preview.getTaskId(), conversation.getTaskId())
                || !Objects.equals(preview.getTargetAgentId(), conversation.getTargetAgentId())) {
            throw denied();
        }
        return new OutputSourceAuthorization(tenantId, clientId, sourceType(), sourceId,
                tenantId, producerAgentId, taskAccess.canWrite());
    }

    private void requireConversation(
            ChatConversationEntity conversation, String tenantId, String clientId,
            String sourceId, String producerAgentId) {
        if (conversation == null
                || conversation.getId() == null
                || !Objects.equals(tenantId, conversation.getTenantId())
                || !Objects.equals(clientId, conversation.getClientId())
                || !Objects.equals(tenantId, conversation.getJiacn())
                || !Objects.equals(Long.toString(conversation.getId()), sourceId)) {
            throw denied();
        }
        if (conversation.getTargetAgentId() != null
                && !conversation.getTargetAgentId().isEmpty()
                && !Objects.equals(conversation.getTargetAgentId(), producerAgentId)) {
            throw denied();
        }
    }

    private OutputAuthorizationException denied() {
        return new OutputAuthorizationException(
                "OUTPUT_SOURCE_FORBIDDEN", "Conversation output source is unavailable");
    }
}
