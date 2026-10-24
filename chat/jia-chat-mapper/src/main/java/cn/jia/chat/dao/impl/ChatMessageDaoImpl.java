package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.chat.dao.MemoryPublicationDao;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.util.StringUtil;
import jakarta.inject.Named;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/** Chat message DAO implementation. */
@Named
@Slf4j
public class ChatMessageDaoImpl extends BaseDaoImpl<ChatMessageMapper, ChatMessageEntity>
        implements ChatMessageDao {
    private static final int MAX_PENDING_EXCLUSIONS = 10_000;

    @Inject
    private MemoryPublicationDao memoryPublicationDao;

    @Override
    public int insert(ChatMessageEntity message) {
        int inserted = super.insert(message);
        if (inserted == 1 && "PENDING".equals(message.getSyncStatus())) {
            if (message.getId() == null) {
                throw new IllegalStateException("Pending message ID was not generated");
            }
            memoryPublicationDao.signalInsertedMessage(message.getId(), message.getCreateTime());
        }
        return inserted;
    }

    @Override
    public List<ChatMessageEntity> findOwnedByConversationId(
            ConversationDataScope scope, String conversationId) {
        requireScope(scope);
        Long numericId = parseConversationId(conversationId);
        String canonicalId = Long.toString(numericId);
        observeContamination(scope, canonicalId, numericId);
        List<ChatMessageEntity> rows = scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.findExactDomainOwnedConversationMessages(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(),
                canonicalId, numericId)
                : baseMapper.findExactOwnedConversationMessagesInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), canonicalId, numericId);
        return requireOwnedRows(rows, scope, canonicalId);
    }

    @Override
    public List<ChatMessageEntity> findOwnedByConversationIdWithLimit(
            ConversationDataScope scope, String conversationId, int limit) {
        requireScope(scope);
        requireLimit(limit);
        Long numericId = parseConversationId(conversationId);
        String canonicalId = Long.toString(numericId);
        observeContamination(scope, canonicalId, numericId);
        List<ChatMessageEntity> rows = scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.findExactDomainOwnedConversationMessagesWithLimit(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(),
                canonicalId, numericId, limit)
                : baseMapper.findExactOwnedConversationMessagesWithLimitInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), canonicalId, numericId,
                limit);
        List<ChatMessageEntity> result = requireOwnedRows(rows, scope, canonicalId);
        Collections.reverse(result);
        return result;
    }

    @Override
    public int deleteExactOwnedConversationMessages(
            ConversationDataScope scope, String conversationId) {
        requireScope(scope);
        Long numericId = parseConversationId(conversationId);
        String canonicalId = Long.toString(numericId);
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.deleteExactDomainOwnedConversationMessages(
                scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(),
                canonicalId, numericId)
                : baseMapper.deleteExactOwnedConversationMessagesInTenant(
                scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), canonicalId,
                numericId);
    }

    @Override
    public int insertScoped(String tenantId, String clientId, ChatMessageEntity message) {
        requireIdentity(tenantId, "tenantId");
        requireIdentity(clientId, "clientId");
        if (message == null) {
            throw new IllegalArgumentException("message is required");
        }
        requireIdentity(message.getConversationId(), "conversationId");
        message.setTenantId(tenantId);
        message.setClientId(clientId);
        return insert(message);
    }

    @Override
    public List<ChatMessageEntity> findByConversationIdScoped(
            String tenantId, String clientId, String conversationId, int limit) {
        requireIdentity(tenantId, "tenantId");
        requireIdentity(clientId, "clientId");
        requireIdentity(conversationId, "conversationId");
        requireLimit(limit);
        List<ChatMessageEntity> result = baseMapper.findExactByConversationScope(
                tenantId, clientId, conversationId, limit);
        if (result == null) {
            throw new IllegalStateException("Task-thread message query returned null");
        }
        for (ChatMessageEntity message : result) {
            if (message == null
                    || !tenantId.equals(message.getTenantId())
                    || !clientId.equals(message.getClientId())
                    || !conversationId.equals(message.getConversationId())) {
                throw new IllegalStateException(
                        "Task-thread message identity does not match its exact scope");
            }
        }
        Collections.reverse(result);
        return result;
    }

    @Override
    public List<ChatMessageEntity> findOwnedByConversationId(
            String ownerJiacn, String clientId, String conversationId) {
        return findOwnedByConversationId(legacyScope(ownerJiacn, clientId), conversationId);
    }

    @Override
    public List<ChatMessageEntity> findOwnedByConversationIdWithLimit(
            String ownerJiacn, String clientId, String conversationId, int limit) {
        return findOwnedByConversationIdWithLimit(
                legacyScope(ownerJiacn, clientId), conversationId, limit);
    }

    @Override
    public int deleteExactOwnedConversationMessages(
            String ownerJiacn, String clientId, String conversationId) {
        return deleteExactOwnedConversationMessages(
                legacyScope(ownerJiacn, clientId), conversationId);
    }

    @Override
    public List<ChatMessageEntity> findByConversationIdForMaintenance(String conversationId) {
        requireIdentity(conversationId, "conversationId");
        List<ChatMessageEntity> messages = baseMapper.findAreaZeroMaintenanceMessages(conversationId);
        if (messages == null) {
            throw new IllegalStateException("Maintenance message query returned null");
        }
        if (messages.isEmpty() && baseMapper.countExactConversationMessages(conversationId) > 0) {
            throw new IllegalStateException(
                    "Conversation contains rows outside the area-zero memory scope");
        }
        return messages;
    }

    @Override
    public List<String> findPendingConversationIds(String syncStatus, int limit, long afterId) {
        requireLimit(limit);
        if (afterId < 0 || !"PENDING".equals(syncStatus)) {
            throw new IllegalArgumentException("Invalid memory scan cursor/status");
        }
        return baseMapper.findPendingMemoryConversations(syncStatus, limit, afterId);
    }

    @Override
    public int markMemorySnapshotSynced(String ownerJiacn, String clientId,
            String conversationId, List<Long> messageIds) {
        requireScope(legacyScope(ownerJiacn, clientId));
        long id = parseConversationId(conversationId);
        if (messageIds == null || messageIds.isEmpty()
                || messageIds.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException("A non-empty persisted message snapshot is required");
        }
        return baseMapper.markMemorySnapshotSynced(ownerJiacn, clientId, conversationId, id, messageIds);
    }

    @Override
    public List<String> findPendingConversationIds(
            String syncStatus, List<String> excludedConversationIds, int limit) {
        requireIdentity(syncStatus, "syncStatus");
        requireLimit(limit);
        if (excludedConversationIds == null) {
            throw new IllegalArgumentException("excludedConversationIds are required");
        }
        if (excludedConversationIds.size() > MAX_PENDING_EXCLUSIONS) {
            throw new IllegalArgumentException("too many excluded conversation IDs");
        }
        List<String> exclusions = excludedConversationIds.stream().distinct().toList();
        for (String excludedConversationId : exclusions) {
            requireIdentity(excludedConversationId, "excludedConversationId");
        }
        List<String> conversationIds = baseMapper.findAreaZeroPendingConversationIds(
                syncStatus, exclusions, limit);
        if (conversationIds == null) {
            throw new IllegalStateException("Pending conversation query returned null");
        }
        return conversationIds;
    }

    @Override
    public int markPendingMessagesSynced(
            ConversationDataScope scope, String conversationId, List<Long> messageIds) {
        requireScope(scope);
        requireIdentity(conversationId, "conversationId");
        List<Long> exactMessageIds = requireDistinctMessageIds(messageIds);
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? baseMapper.compareAndSetDomainPendingMessagesSynced(
                    scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(),
                    conversationId, exactMessageIds)
                : baseMapper.compareAndSetAreaZeroPendingMessagesSynced(
                    scope.ownerJiacn(), scope.callerClientId(),
                    conversationId, exactMessageIds);
    }

    @Override
    public int updateSyncStatusByConversationId(String conversationId, String syncStatus) {
        requireIdentity(conversationId, "conversationId");
        requireIdentity(syncStatus, "syncStatus");
        return baseMapper.updateAreaZeroSyncStatusByConversationId(conversationId, syncStatus);
    }

    @Override
    public int deleteMaintenanceMessageIds(List<Long> messageIds) {
        return baseMapper.deleteAreaZeroMaintenanceMessageIds(
                requireDistinctMessageIds(messageIds));
    }

    @Override
    public List<Long> findExpiredMessageIds(long beforeTime, int limit) {
        requireLimit(limit);
        List<Long> messageIds = baseMapper.findAreaZeroExpiredMessageIds(beforeTime, limit);
        if (messageIds == null) {
            throw new IllegalStateException("Expired message query returned null");
        }
        return messageIds;
    }

    @Override
    public List<String> findActiveJiacns(long sinceTime) {
        List<String> jiacns = baseMapper.findAreaZeroActiveJiacns(sinceTime);
        if (jiacns == null) {
            throw new IllegalStateException("Active owner query returned null");
        }
        return jiacns;
    }

    private void requireAreaZeroScope(BusinessScope scope) {
        if (scope == null
                || !"0".equals(scope.tenantId().value())
                || !"cyf".equals(scope.dataDomainId().value())) {
            throw new IllegalArgumentException("area-zero cyf scope is required");
        }
        requireIdentity(scope.ownerJiacn(), "ownerJiacn");
        requireIdentity(scope.callerClientId(), "clientId");
    }

    private List<Long> requireDistinctMessageIds(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            throw new IllegalArgumentException("messageIds are required");
        }
        for (Long messageId : messageIds) {
            if (messageId == null || messageId <= 0) {
                throw new IllegalArgumentException("messageId is invalid");
            }
        }
        List<Long> distinctIds = messageIds.stream().distinct().toList();
        if (distinctIds.size() != messageIds.size()) {
            throw new IllegalArgumentException("messageIds must be distinct");
        }
        return distinctIds;
    }

    private void observeContamination(
            ConversationDataScope scope, String conversationId, Long numericId) {
        try {
            int contaminated = scope.mode() == ConversationDataScope.Mode.DOMAIN
                    ? baseMapper.countContaminatedDomainOwnedConversationMessages(
                    scope.tenantId(), scope.dataDomainId(), scope.ownerJiacn(),
                    conversationId, numericId)
                    : baseMapper.countContaminatedOwnedConversationMessagesInTenant(
                    scope.tenantId(), scope.ownerJiacn(), scope.callerClientId(), conversationId,
                    numericId);
            if (contaminated > 0) {
                log.warn("Ignoring out-of-scope historical message rows while returning exact-scoped conversation messages. conversationId={}, rows={}",
                        conversationId, contaminated);
            }
        } catch (RuntimeException exception) {
            log.warn("Unable to inspect historical message scope anomaly; continuing with exact-scoped message query. conversationId={}",
                    conversationId, exception);
        }
    }

    private List<ChatMessageEntity> requireOwnedRows(
            List<ChatMessageEntity> result,
            ConversationDataScope scope,
            String conversationId) {
        if (result == null) {
            throw new IllegalStateException("Owned conversation message query returned null");
        }
        for (ChatMessageEntity message : result) {
            boolean commonMismatch = message == null
                    || !scope.ownerJiacn().equals(message.getJiacn())
                    || !conversationId.equals(message.getConversationId())
                    || !scope.tenantId().equals(message.getTenantId());
            boolean modeMismatch = message != null
                    && (scope.mode() == ConversationDataScope.Mode.DOMAIN
                    ? !scope.dataDomainId().equals(message.getDataDomainId())
                    : !scope.callerClientId().equals(message.getClientId()));
            if (commonMismatch || modeMismatch) {
                throw new IllegalStateException(
                        "Conversation message identity does not match its owner scope");
            }
        }
        return result;
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

    private Long parseConversationId(String conversationId) {
        requireIdentity(conversationId, "conversationId");
        try {
            long id = Long.parseLong(conversationId);
            if (id <= 0 || !Long.toString(id).equals(conversationId)) {
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

    private void requireLimit(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be between 1 and 500");
        }
    }
}
