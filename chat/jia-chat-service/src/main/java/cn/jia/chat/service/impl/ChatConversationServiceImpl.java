package cn.jia.chat.service.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.exception.AgentTaskThreadException.Reason;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.mybatis.TenantScopeHelper;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 聊天会话服务实现类
 *
 * @author chc
 * @since 2026-04-19
 */
@Service
@Slf4j
public class ChatConversationServiceImpl implements ChatConversationService {

    private final ChatConversationDao chatConversationDao;

    private final ChatMessageDao chatMessageDao;
    private final AgentTaskThreadDao taskThreadDao;

    public ChatConversationServiceImpl(
            ChatConversationDao chatConversationDao,
            ChatMessageDao chatMessageDao,
            AgentTaskThreadDao taskThreadDao) {
        this.chatConversationDao = chatConversationDao;
        this.chatMessageDao = chatMessageDao;
        this.taskThreadDao = taskThreadDao;
    }

    @Override
    public PageInfo<ChatConversationEntity> findPage(ChatConversationEntity example, int pageNum, int pageSize, String orderBy) {
        PageHelper.startPage(pageNum, pageSize, orderBy);
        // 如果未指定查询条件，默认按当前用户过滤
        if (example == null) {
            example = new ChatConversationEntity();
        }
        if (example.getJiacn() == null || example.getJiacn().isEmpty()) {
            example.setJiacn(EsContextHolder.getContext().getJiacn());
        }
        List<ChatConversationEntity> conversations = chatConversationDao.selectNonTaskThreadByEntity(example);
        if (conversations != null) {
            conversations = conversations.stream()
                    .filter(conversation -> !isTaskThreadEvidence(conversation))
                    .toList();
        }
        return PageInfo.of(conversations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String conversationId) {
        ChatConversationEntity conversation = requireGenericConversation(conversationId);
        String authorizedConversationId = String.valueOf(conversation.getId());
        // Only delete messages after the scoped ownership check and conversation deletion both succeed.
        if (chatConversationDao.deleteById(conversation.getId()) != 1) {
            throw unavailable();
        }
        chatMessageDao.deleteByConversationId(authorizedConversationId);
    }

    @Override
    public List<ChatMessageEntity> findByConversationId(String conversationId) {
        ChatConversationEntity conversation = requireGenericConversation(conversationId);
        return chatMessageDao.findByConversationId(String.valueOf(conversation.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity create(ChatConversationEntity entity) {
        chatConversationDao.insert(entity);
        return entity;
    }

    @Override
    public ChatConversationEntity get(String conversationId) {
        return requireGenericConversation(conversationId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity update(ChatConversationEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw unavailable();
        }
        ChatConversationEntity existing = requireGenericConversation(String.valueOf(entity.getId()));
        ChatConversationEntity allowedUpdate = new ChatConversationEntity()
                .setId(existing.getId())
                .setTitle(entity.getTitle())
                .setStatus(entity.getStatus())
                .setJiacn(existing.getJiacn())
                .setConversationType(existing.getConversationType())
                .setConversationScopeType(existing.getConversationScopeType())
                .setConversationScopeKey(existing.getConversationScopeKey())
                .setTaskId(existing.getTaskId())
                .setTargetAgentId(existing.getTargetAgentId());
        allowedUpdate.setTenantId(existing.getTenantId());
        allowedUpdate.setClientId(existing.getClientId());
        chatConversationDao.updateById(allowedUpdate);
        return allowedUpdate;
    }

    private ChatConversationEntity requireGenericConversation(String conversationId) {
        Long requestedId = parseConversationId(conversationId);
        EsContext context = EsContextHolder.getContext();
        String jiacn = context.getJiacn();
        String clientId = context.getClientId();
        if (!isCanonicalIdentityPart(jiacn) || !isCanonicalIdentityPart(clientId)) {
            throw unavailable();
        }

        ChatConversationEntity conversation;
        try {
            conversation = chatConversationDao.findScopedById(
                    jiacn, clientId, String.valueOf(requestedId));
        } catch (RuntimeException exception) {
            log.warn("Unable to verify generic conversation ownership; denying access. conversationId={}",
                    requestedId, exception);
            throw unavailable();
        }
        if (!belongsToCurrentIdentity(conversation, requestedId, jiacn, clientId)
                || isTaskThreadEvidence(conversation)) {
            throw unavailable();
        }
        return conversation;
    }

    private Long parseConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()
                || !conversationId.equals(conversationId.strip())
                || conversationId.chars().anyMatch(Character::isISOControl)) {
            throw unavailable();
        }
        try {
            long id = Long.parseLong(conversationId);
            if (id <= 0) {
                throw unavailable();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw unavailable();
        }
    }

    private boolean belongsToCurrentIdentity(
            ChatConversationEntity conversation, Long requestedId, String jiacn, String clientId) {
        if (conversation == null
                || !requestedId.equals(conversation.getId())
                || !jiacn.equals(conversation.getJiacn())
                || !clientId.equals(conversation.getClientId())) {
            return false;
        }
        String tenantId = conversation.getTenantId();
        return jiacn.equals(tenantId) || TenantScopeHelper.DEFAULT_TENANT.equals(tenantId);
    }

    private boolean isCanonicalIdentityPart(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private AgentTaskThreadException unavailable() {
        return new AgentTaskThreadException(
                Reason.NOT_FOUND_OR_FORBIDDEN,
                "Conversation is not available through the generic chat API");
    }

    private boolean isTaskThreadEvidence(ChatConversationEntity conversation) {
        if (AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(conversation)) {
            return true;
        }
        if (conversation == null || conversation.getId() == null) {
            return false;
        }
        try {
            return taskThreadDao.findAnyByConversationId(String.valueOf(conversation.getId())) != null;
        } catch (RuntimeException exception) {
            log.warn("Unable to prove conversation is outside task-thread scope; denying generic access. conversationId={}",
                    conversation.getId(), exception);
            return true;
        }
    }
}
