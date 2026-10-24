package cn.jia.chat.service.impl;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.service.AgentService;
import cn.jia.chat.dao.AgentTaskThreadDao;
import cn.jia.chat.deliberation.ChatBountyBindingStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.AgentTaskThreadConstants;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.entity.ConversationDataScope;
import cn.jia.chat.exception.AgentTaskThreadException;
import cn.jia.chat.exception.AgentTaskThreadException.Reason;
import cn.jia.chat.service.ChatConversationService;
import cn.jia.chat.service.ConversationAdmissionService;
import cn.jia.chat.service.ChatConversationEventBroker;
import cn.jia.core.context.EsContext;
import cn.jia.core.context.EsContextHolder;
import cn.jia.core.mybatis.TenantScopeHelper;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.UserAdmissionScope;
import cn.jia.core.util.JsonUtil;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;

/** Authenticated generic-conversation service. */
@Service
@Slf4j
public class ChatConversationServiceImpl implements ChatConversationService {
    private static final int MAX_IDENTITY_LENGTH = 50;

    private final ChatConversationDao chatConversationDao;
    private final ChatMessageDao chatMessageDao;
    private final AgentTaskThreadDao taskThreadDao;
    private final ChatConversationEventBroker eventBroker;
    private ConversationAdmissionService conversationAdmissionService;
    private final ChatBountyBindingStore bountyBindings;
    private final AgentService agentService;

    public ChatConversationServiceImpl(
            ChatConversationDao chatConversationDao,
            ChatMessageDao chatMessageDao,
            AgentTaskThreadDao taskThreadDao,
            ChatConversationEventBroker eventBroker,
            ChatBountyBindingStore bountyBindings, AgentService agentService) {
        this.bountyBindings = Objects.requireNonNull(bountyBindings);
        this.agentService = Objects.requireNonNull(agentService);
        this.chatConversationDao = chatConversationDao;
        this.chatMessageDao = chatMessageDao;
        this.taskThreadDao = taskThreadDao;
        this.eventBroker = eventBroker;
    }

    @Autowired
    void setConversationAdmissionService(
            ConversationAdmissionService conversationAdmissionService) {
        this.conversationAdmissionService = conversationAdmissionService;
    }

    @Override
    public PageInfo<ChatConversationEntity> findPage(
            ChatConversationEntity example, int pageNum, int pageSize, String orderBy) {
        EsContext context = requireIdentity();
        ChatConversationEntity safe = copyAllowedSearch(example);
        safe.setJiacn(context.getJiacn());
        safe.setTenantId(TenantScopeHelper.DEFAULT_TENANT);
        safe.setDataScopeMode(ConversationDataScope.Mode.LEGACY_CLIENT.name());
        safe.setClientId(context.getClientId());
        List<ChatConversationEntity> conversations;
        try {
            PageHelper.startPage(pageNum, pageSize, orderBy);
            conversations = chatConversationDao.selectNonTaskThreadByEntity(safe);
        } catch (RuntimeException exception) {
            log.warn("Unable to query authenticated conversation scope; denying list", exception);
            throw unavailable();
        }
        if (conversations == null) {
            throw unavailable();
        }
        for (ChatConversationEntity conversation : conversations) {
            if (conversation == null || conversation.getId() == null
                    || conversation.getDeletedAt() != null
                    || !belongsToIdentity(conversation, conversation.getId(),
                    context.getJiacn(), context.getClientId())
                    || isTaskThreadEvidence(conversation)) {
                throw unavailable();
            }
        }
        return PageInfo.of(conversations);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String conversationId) {
        Long requestedId = parseConversationId(conversationId);
        EsContext context = requireIdentity();
        String canonicalId = Long.toString(requestedId);
        ChatConversationEventBroker.DeletionFence deletionFence =
                eventBroker.beginDeletion(canonicalId);
        boolean fenceHandedToTransaction = false;
        ChatConversationEntity conversation;
        try {
            try {
                conversation = chatConversationDao.lockScopedByIdIncludingDeleted(
                        context.getJiacn(), context.getClientId(), canonicalId);
            } catch (RuntimeException exception) {
                log.warn("Unable to lock conversation delete scope; denying mutation. conversationId={}",
                        canonicalId, exception);
                throw unavailable();
            }
            // A valid delete never discloses whether the row was missing or foreign.
            if (conversation == null
                    || !belongsToIdentity(conversation, requestedId,
                    context.getJiacn(), context.getClientId())
                    || isTaskThreadEvidence(conversation)) {
                return;
            }
            // A bounty is the stable user-facing discussion for its task. Generic delete cannot
            // tombstone the bound conversation and strand future point/replay; a separate
            // lifecycle operation may retire it only after explicit task/asset retention checks.
            if ("juyiting".equals(conversation.getConversationType())
                    && "bounty".equals(conversation.getConversationScopeType())) {
                throw unavailable();
            }
            long liveGeneration = requireLifecycleGeneration(conversation);
            if (conversation.getDeletedAt() == null) {
                long deletedAt = System.currentTimeMillis();
                if (chatConversationDao.softDeleteScopedById(
                        context.getJiacn(), context.getClientId(), canonicalId, deletedAt) != 1) {
                    throw unavailable();
                }
                // The exact row remains locked until commit. Every ordinary writer takes this row
                // lock first, so append-first/delete-first both converge without orphan messages.
                chatMessageDao.deleteExactOwnedConversationMessages(
                        context.getJiacn(), context.getClientId(), canonicalId);
            }
            fenceHandedToTransaction = completeDeletionFenceAfterCommit(
                    deletionFence, liveGeneration);
        } finally {
            if (!fenceHandedToTransaction) {
                deletionFence.close();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findByConversationId(String conversationId) {
        EsContext context = requireIdentity();
        return findOwnedMessagesInternal(
                context.getJiacn(), context.getClientId(), conversationId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity create(ChatConversationEntity entity) {
        if (entity == null) {
            throw unavailable();
        }
        EsContext context = requireIdentity();
        ChatConversationEntity safe = copyAllowedCreate(entity);
        if (AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(safe)) {
            throw unavailable();
        }
        safe.setJiacn(context.getJiacn());
        safe.setClientId(context.getClientId());
        safe.setTenantId(TenantScopeHelper.DEFAULT_TENANT);
        safe.setDataDomainId("cyf");
        safe.setDataScopeMode(ConversationDataScope.Mode.LEGACY_CLIENT.name());
        safe.setDeletedAt(null);
        safe.setLifecycleGeneration(1L);
        if ("bounty".equals(safe.getConversationScopeType())) {
            return createOrReuseBounty(safe, context);
        }
        if (chatConversationDao.insert(safe) != 1) {
            throw unavailable();
        }
        return safe;
    }

    /** Legacy /chat/stream must take the same task row lock as v2 bootstrap.
     * Ordinary legacy discussion never issues an execution grant or upgrades CHAT to EXECUTE.
     */
    private ChatConversationEntity createOrReuseBounty(ChatConversationEntity safe, EsContext context) {
        String taskId = safe.getTaskId();
        if (!"juyiting".equals(safe.getConversationType()) || !validBountyTaskId(taskId)
                || !("task:" + taskId).equals(safe.getConversationScopeKey())) throw unavailable();
        List<String> requestedTargets = bountyTargets(safe.getTargetAgentIds());
        AgentTaskDTO task;
        List<String> authorized;
        try {
            task = agentService.getTask(taskId);
            authorized = agentService.listTaskWritableMemberAgentIds("0", context.getClientId(), taskId);
        } catch (RuntimeException denied) {
            throw unavailable();
        }
        if (task == null || !taskId.equals(task.getId()) || !"0".equals(task.getTenantId())
                || !context.getClientId().equals(task.getClientId()) || authorized == null
                || authorized.isEmpty() || !authorized.containsAll(requestedTargets)) throw unavailable();
        ChatBountyBindingStore.Scope scope = new ChatBountyBindingStore.Scope("0",
                context.getJiacn(), context.getClientId());
        long now = System.currentTimeMillis();
        // Binding and conversation share the enclosing create transaction. A concurrent v2 ensure
        // locks the same (owner,client,task) row before looking at chat_conversation.
        bountyBindings.reserve(scope, taskId, 0L, now);
        ChatBountyBindingStore.Binding binding = bountyBindings.lock(scope, taskId);
        Long conversationId = binding.conversationId();
        if (conversationId == null) {
            List<Long> previous = bountyBindings.findExistingBountyConversationIds(scope, taskId);
            if (previous == null || previous.size() > 1) throw unavailable();
            if (previous.isEmpty()) {
                if (chatConversationDao.insert(safe) != 1 || safe.getId() == null) throw unavailable();
                conversationId = safe.getId();
            } else conversationId = previous.getFirst();
            if (bountyBindings.attach(scope, taskId, binding.assignmentRevision(), conversationId, now) != 1)
                throw unavailable();
        }
        ChatConversationEntity bound = chatConversationDao.lockScopedById(context.getJiacn(),
                context.getClientId(), Long.toString(conversationId));
        if (bound == null || bound.getDeletedAt() != null || !"0".equals(bound.getTenantId())
                || !"juyiting".equals(bound.getConversationType())
                || !"bounty".equals(bound.getConversationScopeType())
                || !taskId.equals(bound.getTaskId())
                || !("task:" + taskId).equals(bound.getConversationScopeKey())
                || bound.getLifecycleGeneration() == null || bound.getLifecycleGeneration() < 1
                || !bountyTargets(bound.getTargetAgentIds()).equals(requestedTargets)) throw unavailable();
        return bound;
    }

    private static boolean validBountyTaskId(String value) {
        return value != null && !value.isBlank() && value.equals(value.strip())
                && value.codePointCount(0, value.length()) <= 100
                && value.chars().noneMatch(Character::isISOControl);
    }

    private List<String> bountyTargets(String json) {
        try {
            if (json == null) throw unavailable();
            Object parsed = JsonUtil.getMapper().readValue(json, List.class);
            if (!(parsed instanceof List<?> values) || values.isEmpty() || values.size() > 20)
                throw unavailable();
            Set<String> unique = new LinkedHashSet<>();
            for (Object value : values) {
                if (!(value instanceof String id) || !validBountyTaskId(id) || !unique.add(id))
                    throw unavailable();
            }
            return List.copyOf(unique);
        } catch (Exception invalid) {
            throw unavailable();
        }
    }

    @Override
    public ChatConversationEntity get(String conversationId) {
        EsContext context = requireIdentity();
        return requireGenericConversationForIdentity(
                context.getJiacn(), context.getClientId(), conversationId);
    }

    @Override
    public ChatConversationEntity getOwned(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentityParts(ownerJiacn, clientId);
        return requireGenericConversationForIdentity(ownerJiacn, clientId, conversationId);
    }

    @Override
    public boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration) {
        return isLiveGeneration(TenantScopeHelper.DEFAULT_TENANT,
                ownerJiacn, clientId, conversationId, expectedGeneration);
    }

    @Override
    public boolean isLiveGeneration(
            String tenantId, String ownerJiacn, String clientId,
            String conversationId, long expectedGeneration) {
        try {
            requireIdentityParts(ownerJiacn, clientId);
            if (expectedGeneration < 1) {
                return false;
            }
            ConversationDataScope scope = new ConversationDataScope(
                    new BusinessScope(TenantId.of(tenantId), DataDomainId.of("cyf"),
                            ownerJiacn, clientId),
                    ConversationDataScope.Mode.LEGACY_CLIENT);
            return chatConversationDao.isLiveGeneration(
                    scope, Long.toString(parseConversationId(conversationId)),
                    expectedGeneration);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentityParts(ownerJiacn, clientId);
        return findOwnedMessagesInternal(ownerJiacn, clientId, conversationId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId, int limit) {
        requireIdentityParts(ownerJiacn, clientId);
        if (limit < 1 || limit > 500) {
            throw unavailable();
        }
        return findOwnedMessagesInternal(ownerJiacn, clientId, conversationId, limit);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message) {
        return appendOwnedMessageInternal(ownerJiacn, clientId, message, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message, long expectedGeneration) {
        if (expectedGeneration < 1) {
            throw unavailable();
        }
        return appendOwnedMessageInternal(
                ownerJiacn, clientId, message, expectedGeneration);
    }

    private ChatMessageEntity appendOwnedMessageInternal(
            String ownerJiacn, String clientId, ChatMessageEntity message, Long expectedGeneration) {
        requireIdentityParts(ownerJiacn, clientId);
        if (message == null) {
            throw unavailable();
        }
        ChatConversationEntity conversation = lockGenericConversationForIdentity(
                ownerJiacn, clientId, message.getConversationId());
        long actualGeneration = requireLifecycleGeneration(conversation);
        if (expectedGeneration != null && expectedGeneration != actualGeneration) {
            throw unavailable();
        }
        ChatMessageEntity safe = copyAllowedMessage(message);
        safe.setConversationId(Long.toString(conversation.getId()));
        safe.setJiacn(conversation.getJiacn());
        safe.setClientId(conversation.getClientId());
        safe.setTenantId(conversation.getTenantId());
        safe.setConversationType(conversation.getConversationType());
        safe.init4Creation();
        if (chatMessageDao.insert(safe) != 1) {
            throw unavailable();
        }
        return safe;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity updateOwnedTitle(
            String ownerJiacn, String clientId, String conversationId,
            String title, Integer status) {
        requireIdentityParts(ownerJiacn, clientId);
        ChatConversationEntity existing = lockGenericConversationForIdentity(
                ownerJiacn, clientId, conversationId);
        return persistOwnedUpdate(existing, title, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity update(ChatConversationEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw unavailable();
        }
        EsContext context = requireIdentity();
        ChatConversationEntity existing = lockGenericConversationForIdentity(
                context.getJiacn(), context.getClientId(), Long.toString(entity.getId()));
        return persistOwnedUpdate(existing, entity.getTitle(), entity.getStatus());
    }

    private ChatConversationEntity persistOwnedUpdate(
            ChatConversationEntity existing, String title, Integer status) {
        ChatConversationEntity allowedUpdate = new ChatConversationEntity()
                .setId(existing.getId())
                .setTitle(title)
                .setStatus(status)
                .setJiacn(existing.getJiacn())
                .setConversationType(existing.getConversationType())
                .setConversationScopeType(existing.getConversationScopeType())
                .setConversationScopeKey(existing.getConversationScopeKey())
                .setTaskId(existing.getTaskId())
                .setTargetAgentId(existing.getTargetAgentId())
                .setTargetAgentIds(existing.getTargetAgentIds())
                .setDeletedAt(null)
                .setLifecycleGeneration(requireLifecycleGeneration(existing));
        allowedUpdate.setTenantId(existing.getTenantId());
        allowedUpdate.setClientId(existing.getClientId());
        if (chatConversationDao.updateScopedFields(
                existing.getJiacn(), existing.getClientId(), allowedUpdate) != 1) {
            throw unavailable();
        }
        return allowedUpdate;
    }

    private List<ChatMessageEntity> findOwnedMessagesInternal(
            String ownerJiacn, String clientId, String conversationId, Integer limit) {
        ChatConversationEntity conversation = requireGenericConversationForIdentity(
                ownerJiacn, clientId, conversationId);
        try {
            return limit == null
                    ? chatMessageDao.findOwnedByConversationId(
                    ownerJiacn, clientId, Long.toString(conversation.getId()))
                    : chatMessageDao.findOwnedByConversationIdWithLimit(
                    ownerJiacn, clientId, Long.toString(conversation.getId()), limit);
        } catch (RuntimeException exception) {
            log.warn("Unable to read exact-scoped conversation messages; denying access. conversationId={}",
                    conversation.getId(), exception);
            throw unavailable();
        }
    }

    private ChatConversationEntity requireGenericConversationForIdentity(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentityParts(ownerJiacn, clientId);
        Long requestedId = parseConversationId(conversationId);
        ChatConversationEntity conversation;
        try {
            conversation = chatConversationDao.findScopedById(
                    ownerJiacn, clientId, Long.toString(requestedId));
        } catch (RuntimeException exception) {
            log.warn("Unable to verify generic conversation ownership; denying access. conversationId={}",
                    requestedId, exception);
            throw unavailable();
        }
        return validateGenericConversation(
                conversation, requestedId, ownerJiacn, clientId);
    }

    private ChatConversationEntity lockGenericConversationForIdentity(
            String ownerJiacn, String clientId, String conversationId) {
        requireIdentityParts(ownerJiacn, clientId);
        Long requestedId = parseConversationId(conversationId);
        ChatConversationEntity conversation;
        try {
            conversation = chatConversationDao.lockScopedById(
                    ownerJiacn, clientId, Long.toString(requestedId));
        } catch (RuntimeException exception) {
            log.warn("Unable to lock generic conversation ownership; denying mutation. conversationId={}",
                    requestedId, exception);
            throw unavailable();
        }
        return validateGenericConversation(
                conversation, requestedId, ownerJiacn, clientId);
    }

    private ChatConversationEntity validateGenericConversation(
            ChatConversationEntity conversation, Long requestedId,
            String ownerJiacn, String clientId) {
        if (conversation == null || conversation.getDeletedAt() != null
                || !belongsToIdentity(conversation, requestedId, ownerJiacn, clientId)
                || isTaskThreadEvidence(conversation)) {
            throw unavailable();
        }
        return conversation;
    }

    @Override
    @Transactional(readOnly = true)
    public PageInfo<ChatConversationEntity> findPage(
            UserAdmissionScope admission, ChatConversationEntity example,
            int pageNum, int pageSize, String orderBy) {
        return requireAdmissionService().read(admission, scope -> {
            ChatConversationEntity safe = copyAllowedSearch(example);
            safe.setJiacn(scope.ownerJiacn());
            safe.setTenantId(scope.tenantId());
            safe.setDataDomainId(scope.dataDomainId());
            safe.setDataScopeMode(scope.mode().name());
            safe.setClientId(scope.callerClientId());
            List<ChatConversationEntity> conversations;
            try {
                PageHelper.startPage(pageNum, pageSize, orderBy);
                conversations = chatConversationDao.selectNonTaskThreadByEntity(scope, safe);
            } catch (RuntimeException exception) {
                log.warn("Unable to query admitted conversation scope; denying list", exception);
                throw unavailable();
            }
            if (conversations == null) {
                throw unavailable();
            }
            for (ChatConversationEntity conversation : conversations) {
                if (conversation == null || conversation.getId() == null
                        || conversation.getDeletedAt() != null
                        || !belongsToScope(conversation, conversation.getId(), scope)
                        || isTaskThreadEvidence(conversation)) {
                    throw unavailable();
                }
            }
            return PageInfo.of(conversations);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(
            UserAdmissionScope admission, String conversationId) {
        Long requestedId = parseConversationId(conversationId);
        String canonicalId = Long.toString(requestedId);
        ChatConversationEventBroker.DeletionFence deletionFence =
                eventBroker.beginDeletion(canonicalId);
        boolean[] handedToTransaction = {false};
        try {
            requireAdmissionService().write(admission, scope -> {
                ChatConversationEntity conversation;
                try {
                    conversation = chatConversationDao.lockScopedByIdIncludingDeleted(
                            scope, canonicalId);
                } catch (RuntimeException exception) {
                    log.warn("Unable to lock admitted conversation delete scope; denying mutation. conversationId={}",
                            canonicalId, exception);
                    throw unavailable();
                }
                if (conversation == null
                        || !belongsToScope(conversation, requestedId, scope)
                        || isTaskThreadEvidence(conversation)) {
                    return null;
                }
                long liveGeneration = requireLifecycleGeneration(conversation);
                if (conversation.getDeletedAt() == null) {
                    long deletedAt = System.currentTimeMillis();
                    if (chatConversationDao.softDeleteScopedById(
                            scope, canonicalId, deletedAt) != 1) {
                        throw unavailable();
                    }
                    chatMessageDao.deleteExactOwnedConversationMessages(scope, canonicalId);
                }
                handedToTransaction[0] = completeDeletionFenceAfterCommit(
                        deletionFence, liveGeneration);
                return null;
            });
        } finally {
            if (!handedToTransaction[0]) {
                deletionFence.close();
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findByConversationId(
            UserAdmissionScope admission, String conversationId) {
        return requireAdmissionService().read(admission,
                scope -> findScopedMessagesInternal(scope, conversationId, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity create(
            UserAdmissionScope admission, ChatConversationEntity entity) {
        if (entity == null) {
            throw unavailable();
        }
        return requireAdmissionService().write(admission, scope -> {
            ChatConversationEntity safe = copyAllowedCreate(entity);
            if (AgentTaskThreadConstants.hasTaskThreadMarkerEvidence(safe)) {
                throw unavailable();
            }
            safe.setJiacn(scope.ownerJiacn());
            safe.setClientId(scope.callerClientId());
            safe.setTenantId(scope.tenantId());
            safe.setDataDomainId(scope.dataDomainId());
            safe.setDataScopeMode(scope.mode().name());
            safe.setDeletedAt(null);
            safe.setLifecycleGeneration(1L);
            if (chatConversationDao.insert(safe) != 1) {
                throw unavailable();
            }
            return safe;
        });
    }

    @Override
    @Transactional(readOnly = true)
    public ChatConversationEntity get(
            UserAdmissionScope admission, String conversationId) {
        return requireAdmissionService().read(admission,
                scope -> requireScopedConversation(scope, conversationId, false));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isLiveGeneration(
            UserAdmissionScope admission, String conversationId, long expectedGeneration) {
        try {
            if (expectedGeneration < 1) {
                return false;
            }
            return requireAdmissionService().read(admission,
                    scope -> chatConversationDao.isLiveGeneration(
                            scope, Long.toString(parseConversationId(conversationId)),
                            expectedGeneration));
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findOwnedMessages(
            UserAdmissionScope admission, String conversationId) {
        return requireAdmissionService().read(admission,
                scope -> findScopedMessagesInternal(scope, conversationId, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessageEntity> findOwnedMessages(
            UserAdmissionScope admission, String conversationId, int limit) {
        if (limit < 1 || limit > 500) {
            throw unavailable();
        }
        return requireAdmissionService().read(admission,
                scope -> findScopedMessagesInternal(scope, conversationId, limit));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendOwnedMessage(
            UserAdmissionScope admission, ChatMessageEntity message) {
        return appendOwnedMessage(admission, message, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatMessageEntity appendOwnedMessage(
            UserAdmissionScope admission, ChatMessageEntity message, long expectedGeneration) {
        if (expectedGeneration < 1) {
            throw unavailable();
        }
        return appendOwnedMessage(admission, message, Long.valueOf(expectedGeneration));
    }

    private ChatMessageEntity appendOwnedMessage(
            UserAdmissionScope admission, ChatMessageEntity message, Long expectedGeneration) {
        if (message == null) {
            throw unavailable();
        }
        return requireAdmissionService().write(admission,
                scope -> appendScopedMessage(scope, message, expectedGeneration));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity updateOwnedTitle(
            UserAdmissionScope admission, String conversationId,
            String title, Integer status) {
        return requireAdmissionService().write(admission, scope -> {
            ChatConversationEntity existing = requireScopedConversation(
                    scope, conversationId, true);
            return persistScopedUpdate(scope, existing, title, status);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationEntity update(
            UserAdmissionScope admission, ChatConversationEntity entity) {
        if (entity == null || entity.getId() == null) {
            throw unavailable();
        }
        return requireAdmissionService().write(admission, scope -> {
            ChatConversationEntity existing = requireScopedConversation(
                    scope, Long.toString(entity.getId()), true);
            return persistScopedUpdate(
                    scope, existing, entity.getTitle(), entity.getStatus());
        });
    }

    private List<ChatMessageEntity> findScopedMessagesInternal(
            ConversationDataScope scope, String conversationId, Integer limit) {
        ChatConversationEntity conversation = requireScopedConversation(
                scope, conversationId, false);
        try {
            return limit == null
                    ? chatMessageDao.findOwnedByConversationId(
                    scope, Long.toString(conversation.getId()))
                    : chatMessageDao.findOwnedByConversationIdWithLimit(
                    scope, Long.toString(conversation.getId()), limit);
        } catch (RuntimeException exception) {
            log.warn("Unable to read admitted conversation messages; denying access. conversationId={}",
                    conversation.getId(), exception);
            throw unavailable();
        }
    }

    private ChatMessageEntity appendScopedMessage(
            ConversationDataScope scope, ChatMessageEntity message, Long expectedGeneration) {
        ChatConversationEntity conversation = requireScopedConversation(
                scope, message.getConversationId(), true);
        long actualGeneration = requireLifecycleGeneration(conversation);
        if (expectedGeneration != null && expectedGeneration != actualGeneration) {
            throw unavailable();
        }
        ChatMessageEntity safe = copyAllowedMessage(message);
        safe.setConversationId(Long.toString(conversation.getId()));
        safe.setJiacn(conversation.getJiacn());
        safe.setClientId(scope.callerClientId());
        safe.setTenantId(conversation.getTenantId());
        safe.setDataDomainId(conversation.getDataDomainId());
        safe.setConversationType(conversation.getConversationType());
        safe.init4Creation();
        if (chatMessageDao.insert(safe) != 1) {
            throw unavailable();
        }
        return safe;
    }

    private ChatConversationEntity requireScopedConversation(
            ConversationDataScope scope, String conversationId, boolean lock) {
        Long requestedId = parseConversationId(conversationId);
        ChatConversationEntity conversation;
        try {
            conversation = lock
                    ? chatConversationDao.lockScopedById(
                    scope, Long.toString(requestedId))
                    : chatConversationDao.findScopedById(
                    scope, Long.toString(requestedId));
        } catch (RuntimeException exception) {
            log.warn("Unable to verify admitted conversation ownership; denying access. conversationId={}",
                    requestedId, exception);
            throw unavailable();
        }
        if (conversation == null || conversation.getDeletedAt() != null
                || !belongsToScope(conversation, requestedId, scope)
                || isTaskThreadEvidence(conversation)) {
            throw unavailable();
        }
        return conversation;
    }

    private ChatConversationEntity persistScopedUpdate(
            ConversationDataScope scope, ChatConversationEntity existing,
            String title, Integer status) {
        ChatConversationEntity allowedUpdate = new ChatConversationEntity()
                .setId(existing.getId())
                .setTitle(title)
                .setStatus(status)
                .setJiacn(existing.getJiacn())
                .setDataDomainId(existing.getDataDomainId())
                .setConversationType(existing.getConversationType())
                .setConversationScopeType(existing.getConversationScopeType())
                .setConversationScopeKey(existing.getConversationScopeKey())
                .setTaskId(existing.getTaskId())
                .setTargetAgentId(existing.getTargetAgentId())
                .setDataScopeMode(scope.mode().name())
                .setTargetAgentIds(existing.getTargetAgentIds())
                .setDeletedAt(null)
                .setLifecycleGeneration(requireLifecycleGeneration(existing));
        allowedUpdate.setTenantId(existing.getTenantId());
        allowedUpdate.setClientId(existing.getClientId());
        if (chatConversationDao.updateScopedFields(scope, allowedUpdate) != 1) {
            throw unavailable();
        }
        return allowedUpdate;
    }

    private boolean belongsToScope(
            ChatConversationEntity conversation, Long requestedId,
            ConversationDataScope scope) {
        if (conversation == null
                || !requestedId.equals(conversation.getId())
                || !scope.ownerJiacn().equals(conversation.getJiacn())
                || !scope.tenantId().equals(conversation.getTenantId())
                || !scope.mode().name().equals(conversation.getDataScopeMode())) {
            return false;
        }
        return scope.mode() == ConversationDataScope.Mode.DOMAIN
                ? scope.dataDomainId().equals(conversation.getDataDomainId())
                : scope.callerClientId().equals(conversation.getClientId());
    }

    private ConversationAdmissionService requireAdmissionService() {
        if (conversationAdmissionService == null) {
            throw unavailable();
        }
        return conversationAdmissionService;
    }

    private EsContext requireIdentity() {
        EsContext context = EsContextHolder.getContext();
        if (context == null) {
            throw unavailable();
        }
        requireIdentityParts(context.getJiacn(), context.getClientId());
        return context;
    }

    private void requireIdentityParts(String ownerJiacn, String clientId) {
        if (!isCanonicalIdentityPart(ownerJiacn) || !isCanonicalIdentityPart(clientId)) {
            throw unavailable();
        }
    }

    private ChatConversationEntity copyAllowedSearch(ChatConversationEntity requested) {
        if (requested == null) {
            return new ChatConversationEntity();
        }
        ChatConversationEntity safe = new ChatConversationEntity()
                .setId(requested.getId())
                .setTitle(requested.getTitle())
                .setStatus(requested.getStatus())
                .setConversationType(requested.getConversationType())
                .setConversationScopeType(requested.getConversationScopeType())
                .setConversationScopeKey(requested.getConversationScopeKey())
                .setTaskId(requested.getTaskId())
                .setTargetAgentId(requested.getTargetAgentId())
                .setTargetAgentIds(requested.getTargetAgentIds());
        safe.setCreateTime(requested.getCreateTime());
        safe.setUpdateTime(requested.getUpdateTime());
        return safe;
    }

    private ChatConversationEntity copyAllowedCreate(ChatConversationEntity requested) {
        return new ChatConversationEntity()
                .setTitle(requested.getTitle())
                .setStatus(requested.getStatus())
                .setConversationType(requested.getConversationType())
                .setConversationScopeType(requested.getConversationScopeType())
                .setConversationScopeKey(requested.getConversationScopeKey())
                .setTaskId(requested.getTaskId())
                .setTargetAgentId(requested.getTargetAgentId())
                .setTargetAgentIds(requested.getTargetAgentIds())
                .setLifecycleGeneration(1L);
    }

    private ChatMessageEntity copyAllowedMessage(ChatMessageEntity requested) {
        return new ChatMessageEntity()
                .setMessageType(requested.getMessageType())
                .setContent(requested.getContent())
                .setMetadata(requested.getMetadata())
                .setSyncStatus(requested.getSyncStatus())
                .setSenderType(requested.getSenderType())
                .setSenderName(requested.getSenderName());
    }

    private Long parseConversationId(String conversationId) {
        if (!isCanonicalIdentityPart(conversationId)) {
            throw unavailable();
        }
        try {
            long id = Long.parseLong(conversationId);
            if (id <= 0 || !Long.toString(id).equals(conversationId)) {
                throw unavailable();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw unavailable();
        }
    }

    private boolean belongsToIdentity(
            ChatConversationEntity conversation, Long requestedId,
            String ownerJiacn, String clientId) {
        if (conversation == null
                || !requestedId.equals(conversation.getId())
                || !ownerJiacn.equals(conversation.getJiacn())
                || !clientId.equals(conversation.getClientId())) {
            return false;
        }
        return TenantScopeHelper.DEFAULT_TENANT.equals(conversation.getTenantId());
    }

    private boolean isCanonicalIdentityPart(String value) {
        return value != null && value.length() <= MAX_IDENTITY_LENGTH
                && !value.isBlank() && value.equals(value.strip())
                && value.chars().noneMatch(Character::isISOControl);
    }

    private long requireLifecycleGeneration(ChatConversationEntity conversation) {
        Long generation = conversation == null ? null : conversation.getLifecycleGeneration();
        // Existing rows are backfilled/defaulted to generation 1 by the migration. Keeping this
        // compatibility default lets a rolling binary read a pre-migration fixture fail safely at
        // the first generation-aware write/probe rather than inventing a later generation.
        if (generation == null) {
            return 1L;
        }
        if (generation < 1) {
            throw unavailable();
        }
        return generation;
    }

    private boolean completeDeletionFenceAfterCommit(
            ChatConversationEventBroker.DeletionFence deletionFence, long deletedGeneration) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionFence.commitDeleted(deletedGeneration);
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletionFence.commitDeleted(deletedGeneration);
            }

            @Override
            public void afterCompletion(int status) {
                deletionFence.close();
            }
        });
        return true;
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
            return taskThreadDao.findAnyByConversationId(
                    Long.toString(conversation.getId())) != null;
        } catch (RuntimeException exception) {
            log.warn("Unable to prove conversation is outside task-thread scope; denying generic access. conversationId={}",
                    conversation.getId(), exception);
            return true;
        }
    }
}
