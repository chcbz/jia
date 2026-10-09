package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.mapper.MemoryPublicationMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.inject.Named;
import jakarta.inject.Inject;

import java.util.List;

/** Chat conversation DAO implementation. */
@Named
public class ChatConversationDaoImpl extends BaseDaoImpl<ChatConversationMapper, ChatConversationEntity>
        implements ChatConversationDao {

    @Inject
    private MemoryPublicationMapper memoryPublicationMapper;

    @Override
    public ChatConversationEntity findScopedById(
            ConversationDataScope scope, String conversationId) {
        requireScope(scope);
        Long id = parseId(conversationId);
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.findExactDomainScopedById(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), id)
                : baseMapper.findExactScopedById(
                scope.ownerJiacn(), scope.callerClientId(), id);
    }

    @Override
    public ChatConversationEntity lockScopedById(
            ConversationDataScope scope, String conversationId) {
        requireScope(scope);
        Long id = parseId(conversationId);
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.lockExactDomainScopedById(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), id)
                : baseMapper.lockExactScopedById(
                scope.ownerJiacn(), scope.callerClientId(), id);
    }

    @Override
    public ChatConversationEntity lockScopedByIdIncludingDeleted(
            ConversationDataScope scope, String conversationId) {
        requireScope(scope);
        Long id = parseId(conversationId);
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.lockExactDomainScopedByIdIncludingDeleted(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), id)
                : baseMapper.lockExactScopedByIdIncludingDeleted(
                scope.ownerJiacn(), scope.callerClientId(), id);
    }

    @Override
    public int softDeleteScopedById(
            ConversationDataScope scope, String conversationId, long deletedAt) {
        requireScope(scope);
        if (deletedAt <= 0) {
            throw new IllegalArgumentException("deletedAt is invalid");
        }
        Long id = parseId(conversationId);
        int deleted = scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.softDeleteExactDomainScopedById(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), id, deletedAt)
                : baseMapper.softDeleteExactScopedById(
                scope.ownerJiacn(), scope.callerClientId(), id, deletedAt);
        if (deleted == 1) {
            String scopeKey = scope.mode() == ConversationDataScope.Mode.DOMAIN
                    ? scope.dataDomainId() : scope.callerClientId();
            memoryPublicationMapper.tombstonePublications(
                    scope.tenantId(), scope.mode().name(), scopeKey,
                    scope.ownerJiacn(), Long.toString(id), deletedAt);
            memoryPublicationMapper.cancelWork(
                    scope.tenantId(), scope.mode().name(), scopeKey,
                    scope.ownerJiacn(), Long.toString(id), deletedAt);
        }
        return deleted;
    }

    @Override
    public int updateScopedFields(
            ConversationDataScope scope, ChatConversationEntity entity) {
        requireScope(scope);
        if (entity == null || entity.getId() == null) {
            throw new IllegalArgumentException("conversation is required");
        }
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.updateExactDomainOwnedFields(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), entity.getId(),
                entity.getTitle(), entity.getStatus())
                : baseMapper.updateExactOwnedFields(
                scope.ownerJiacn(), scope.callerClientId(), entity.getId(),
                entity.getTitle(), entity.getStatus());
    }

    @Override
    public boolean isLiveGeneration(
            ConversationDataScope scope, String conversationId, long expectedGeneration) {
        requireScope(scope);
        if (expectedGeneration < 1) {
            throw new IllegalArgumentException("expectedGeneration is invalid");
        }
        Long id = parseId(conversationId);
        int count = scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.countExactDomainLiveGeneration(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(), id, expectedGeneration)
                : baseMapper.countExactLiveGeneration(
                scope.ownerJiacn(), scope.callerClientId(), id, expectedGeneration);
        return count == 1;
    }

    @Override
    public List<ChatConversationEntity> selectNonTaskThreadByEntity(
            ConversationDataScope scope, ChatConversationEntity example) {
        requireScope(scope);
        if (example == null) {
            throw new IllegalArgumentException("scoped conversation example is required");
        }
        QueryWrapper<ChatConversationEntity> wrapper = new QueryWrapper<>();
        exact(wrapper, "data_scope_mode", scope.mode().name());
        exact(wrapper, "tenant_id", scope.tenantId());
        exact(wrapper, "jiacn", scope.ownerJiacn());
        if (scope.mode() == ConversationDataScope.Mode.DOMAIN) {
            exact(wrapper, "data_domain_id", scope.dataDomainId());
        } else {
            exact(wrapper, "client_id", scope.callerClientId());
        }
        wrapper.isNull("deleted_at");
        addAllowedFilters(example, wrapper);
        wrapper.and(nested -> nested.isNull("conversation_scope_type")
                        .or().ne("conversation_scope_type",
                                AgentTaskThreadConstants.CONVERSATION_SCOPE_TYPE))
                .notExists("SELECT 1 FROM agent_task_thread t "
                        + "WHERE t.conversation_id = CAST(chat_conversation.id AS CHAR)");
        return baseMapper.selectList(wrapper);
    }

    @Override
    public ChatConversationEntity findScopedById(
            String ownerJiacn, String clientId, String conversationId) {
        return findScopedById(legacyScope(ownerJiacn, clientId), conversationId);
    }

    @Override
    public ChatConversationEntity lockScopedById(
            String ownerJiacn, String clientId, String conversationId) {
        return lockScopedById(legacyScope(ownerJiacn, clientId), conversationId);
    }

    @Override
    public ChatConversationEntity lockScopedByIdIncludingDeleted(
            String ownerJiacn, String clientId, String conversationId) {
        return lockScopedByIdIncludingDeleted(legacyScope(ownerJiacn, clientId), conversationId);
    }

    @Override
    public int softDeleteScopedById(
            String ownerJiacn, String clientId, String conversationId, long deletedAt) {
        return softDeleteScopedById(
                legacyScope(ownerJiacn, clientId), conversationId, deletedAt);
    }

    @Override
    public int updateScopedFields(
            String ownerJiacn, String clientId, ChatConversationEntity entity) {
        return updateScopedFields(legacyScope(ownerJiacn, clientId), entity);
    }

    @Override
    public boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration) {
        return isLiveGeneration(
                legacyScope(ownerJiacn, clientId), conversationId, expectedGeneration);
    }

    @Override
    public List<ChatConversationEntity> selectNonTaskThreadByEntity(
            ChatConversationEntity example) {
        if (example == null) {
            throw new IllegalArgumentException("scoped conversation example is required");
        }
        return selectNonTaskThreadByEntity(
                legacyScope(example.getJiacn(), example.getClientId()), example);
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

    private void exact(QueryWrapper<ChatConversationEntity> wrapper, String column, String value) {
        wrapper.eq(column, value)
                .apply("CAST(" + column + " AS BINARY) = CAST({0} AS BINARY)", value)
                .apply("OCTET_LENGTH(" + column + ") = OCTET_LENGTH({0})", value);
    }

    private ConversationDataScope legacyScope(String ownerJiacn, String clientId) {
        requireIdentity(ownerJiacn, "ownerJiacn");
        requireIdentity(clientId, "clientId");
        return new ConversationDataScope(
                new BusinessScope(TenantId.of("0"), DataDomainId.of("cyf"),
                        ownerJiacn, clientId),
                ConversationDataScope.Mode.LEGACY_CLIENT);
    }

    private void requireScope(ConversationDataScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("conversation scope is required");
        }
        requireIdentity(scope.tenantId(), "tenantId");
        requireIdentity(scope.dataDomainId(), "dataDomainId");
        requireIdentity(scope.ownerJiacn(), "ownerJiacn");
        requireIdentity(scope.callerClientId(), "clientId");
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
