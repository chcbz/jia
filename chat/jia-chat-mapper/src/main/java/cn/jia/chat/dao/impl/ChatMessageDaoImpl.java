package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.mapper.ChatMessageMapper;
import cn.jia.common.dao.BaseDaoImpl;
import cn.jia.core.mybatis.TenantScopeHelper;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.inject.Named;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Chat message DAO implementation. */
@Named
public class ChatMessageDaoImpl extends BaseDaoImpl<ChatMessageMapper, ChatMessageEntity>
        implements ChatMessageDao {

    @Override
    public int insertScoped(String tenantId, String clientId, ChatMessageEntity message) {
        requireScope(tenantId, clientId);
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
        requireScope(tenantId, clientId);
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
        requireScope(ownerJiacn, clientId);
        Long numericId = parseConversationId(conversationId);
        String canonicalId = Long.toString(numericId);
        requireNoContamination(ownerJiacn, clientId, canonicalId, numericId);
        return requireOwnedRows(baseMapper.findExactOwnedConversationMessages(
                        ownerJiacn, clientId, canonicalId, numericId),
                ownerJiacn, clientId, canonicalId);
    }

    @Override
    public List<ChatMessageEntity> findOwnedByConversationIdWithLimit(
            String ownerJiacn, String clientId, String conversationId, int limit) {
        requireScope(ownerJiacn, clientId);
        requireLimit(limit);
        Long numericId = parseConversationId(conversationId);
        String canonicalId = Long.toString(numericId);
        requireNoContamination(ownerJiacn, clientId, canonicalId, numericId);
        List<ChatMessageEntity> result = requireOwnedRows(
                baseMapper.findExactOwnedConversationMessagesWithLimit(
                        ownerJiacn, clientId, canonicalId, numericId, limit),
                ownerJiacn, clientId, canonicalId);
        Collections.reverse(result);
        return result;
    }

    @Override
    public int deleteExactConversationMessages(String conversationId) {
        String canonicalId = Long.toString(parseConversationId(conversationId));
        return baseMapper.deleteExactConversationMessages(canonicalId);
    }

    @Override
    public List<ChatMessageEntity> findByConversationIdForMaintenance(String conversationId) {
        requireIdentity(conversationId, "conversationId");
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId)
                .orderByAsc(ChatMessageEntity::getCreateTime);
        return baseMapper.selectList(wrapper);
    }

    @Override
    public List<String> findPendingConversationIds(String syncStatus, int limit) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getSyncStatus, syncStatus)
                .orderByAsc(ChatMessageEntity::getCreateTime)
                .last("LIMIT " + limit);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            return list.stream().map(ChatMessageEntity::getConversationId).distinct().toList();
        }
        return Collections.emptyList();
    }

    @Override
    public void updateSyncStatusByConversationId(String conversationId, String syncStatus) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessageEntity::getConversationId, conversationId);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list != null && !list.isEmpty()) {
            list.forEach(entity -> entity.setSyncStatus(syncStatus));
            updateBatchById(list);
        }
    }

    @Override
    public List<Long> findExpiredMessageIds(long beforeTime, int limit) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(ChatMessageEntity::getCreateTime, beforeTime)
                .select(ChatMessageEntity::getId)
                .orderByAsc(ChatMessageEntity::getId)
                .last("LIMIT " + limit);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        return list == null || list.isEmpty()
                ? Collections.emptyList()
                : list.stream().map(ChatMessageEntity::getId).toList();
    }

    @Override
    public List<String> findActiveJiacns(long sinceTime) {
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.gt(ChatMessageEntity::getCreateTime, sinceTime)
                .select(ChatMessageEntity::getJiacn);
        List<ChatMessageEntity> list = baseMapper.selectList(wrapper);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> jiacnSet = new HashSet<>();
        for (ChatMessageEntity entity : list) {
            if (entity.getJiacn() != null) {
                jiacnSet.add(entity.getJiacn());
            }
        }
        return List.copyOf(jiacnSet);
    }

    private void requireNoContamination(
            String ownerJiacn, String clientId, String conversationId, Long numericId) {
        if (baseMapper.countContaminatedOwnedConversationMessages(
                ownerJiacn, clientId, conversationId, numericId) != 0) {
            throw new IllegalStateException(
                    "Conversation messages contain rows outside the authenticated owner scope");
        }
    }

    private List<ChatMessageEntity> requireOwnedRows(
            List<ChatMessageEntity> result,
            String ownerJiacn,
            String clientId,
            String conversationId) {
        if (result == null) {
            throw new IllegalStateException("Owned conversation message query returned null");
        }
        for (ChatMessageEntity message : result) {
            if (message == null
                    || !ownerJiacn.equals(message.getJiacn())
                    || !clientId.equals(message.getClientId())
                    || !conversationId.equals(message.getConversationId())
                    || (!ownerJiacn.equals(message.getTenantId())
                    && !TenantScopeHelper.DEFAULT_TENANT.equals(message.getTenantId()))) {
                throw new IllegalStateException(
                        "Conversation message identity does not match its owner scope");
            }
        }
        return result;
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

    private void requireScope(String tenantId, String clientId) {
        requireIdentity(tenantId, "tenantId");
        requireIdentity(clientId, "clientId");
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
