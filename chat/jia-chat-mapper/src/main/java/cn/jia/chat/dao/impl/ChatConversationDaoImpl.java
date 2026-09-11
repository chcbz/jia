package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;

import java.util.List;

/** Chat conversation DAO implementation. */
@Named
public class ChatConversationDaoImpl extends BaseDaoImpl<ChatConversationMapper, ChatConversationEntity>
        implements ChatConversationDao {

    @Override
    public ChatConversationEntity findScopedById(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        return baseMapper.findExactScopedById(ownerJiacn, clientId, parseId(conversationId));
    }

    @Override
    public ChatConversationEntity lockScopedById(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        return baseMapper.lockExactScopedById(ownerJiacn, clientId, parseId(conversationId));
    }

    @Override
    public ChatConversationEntity lockScopedByIdIncludingDeleted(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        return baseMapper.lockExactScopedByIdIncludingDeleted(
                ownerJiacn, clientId, parseId(conversationId));
    }

    @Override
    public int softDeleteScopedById(
            String ownerJiacn, String clientId, String conversationId, long deletedAt) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        if (deletedAt <= 0) {
            throw new IllegalArgumentException("deletedAt is invalid");
        }
        return baseMapper.softDeleteExactScopedById(
                ownerJiacn, clientId, parseId(conversationId), deletedAt);
    }

    @Override
    public boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        if (expectedGeneration < 1) {
            throw new IllegalArgumentException("expectedGeneration is invalid");
        }
        return baseMapper.countExactLiveGeneration(
                ownerJiacn, clientId, parseId(conversationId), expectedGeneration) == 1;
    }

    @Override
    public List<ChatConversationEntity> selectNonTaskThreadByEntity(ChatConversationEntity example) {
        if (example == null) {
            throw new IllegalArgumentException("scoped conversation example is required");
        }
        requireIdentity(example.getJiacn(), "jiacn");
        requireIdentity(example.getClientId(), "clientId");
        requireIdentity(example.getTenantId(), "tenantId");
        if (!example.getTenantId().equals(example.getJiacn())) {
            throw new IllegalArgumentException("tenantId must be the authenticated owner scope");
        }

        QueryWrapper<ChatConversationEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("jiacn", example.getJiacn())
                .apply("CAST(jiacn AS BINARY) = CAST({0} AS BINARY)", example.getJiacn())
                .apply("OCTET_LENGTH(jiacn) = OCTET_LENGTH({0})", example.getJiacn())
                .eq("client_id", example.getClientId())
                .apply("CAST(client_id AS BINARY) = CAST({0} AS BINARY)", example.getClientId())
                .apply("OCTET_LENGTH(client_id) = OCTET_LENGTH({0})", example.getClientId())
                .and(scope -> scope.eq("tenant_id", example.getTenantId()).or().eq("tenant_id", "0"))
                .apply("(CAST(tenant_id AS BINARY) = CAST({0} AS BINARY) OR tenant_id = '0')",
                        example.getTenantId())
                .apply("(OCTET_LENGTH(tenant_id) = OCTET_LENGTH({0}) OR tenant_id = '0')",
                        example.getTenantId())
                .isNull("deleted_at");
        addAllowedFilters(example, wrapper);
        wrapper.and(nested -> nested.isNull("conversation_scope_type")
                        .or().ne("conversation_scope_type",
                                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE))
                .notExists("SELECT 1 FROM agent_task_thread t "
                        + "WHERE t.conversation_id = CAST(chat_conversation.id AS CHAR)");
        return baseMapper.selectList(wrapper);
    }

    private void addAllowedFilters(
            ChatConversationEntity example, QueryWrapper<ChatConversationEntity> wrapper) {
        wrapper.eq(example.getId() != null, "id", example.getId())
                .eq(example.getStatus() != null, "status", example.getStatus())
                .eq(StringUtil.isNotBlank(example.getTitle()), "title", example.getTitle())
                .eq(StringUtil.isNotBlank(example.getConversationType()),
                        "conversation_type", example.getConversationType())
                .eq(StringUtil.isNotBlank(example.getConversationScopeType()),
                        "conversation_scope_type", example.getConversationScopeType())
                .eq(StringUtil.isNotBlank(example.getConversationScopeKey()),
                        "conversation_scope_key", example.getConversationScopeKey())
                .eq(StringUtil.isNotBlank(example.getTaskId()), "task_id", example.getTaskId())
                .eq(StringUtil.isNotBlank(example.getTargetAgentId()),
                        "target_agent_id", example.getTargetAgentId())
                .eq(StringUtil.isNotBlank(example.getTargetAgentIds()),
                        "target_agent_ids", example.getTargetAgentIds())
                .eq(example.getCreateTime() != null, "create_time", example.getCreateTime())
                .eq(example.getUpdateTime() != null, "update_time", example.getUpdateTime());
    }

    private Long parseId(String value) {
        requireIdentity(value, "conversationId");
        try {
            long id = Long.parseLong(value);
            if (id <= 0 || !Long.toString(id).equals(value)) {
                throw new IllegalArgumentException("conversationId is invalid");
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("conversationId is invalid");
        }
    }

    private void requireIdentity(String value, String field) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
