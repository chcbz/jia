package cn.jia.chat.service;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import com.github.pagehelper.PageInfo;

import java.util.List;

/** Authenticated generic-conversation boundary. */
public interface ChatConversationService {
    PageInfo<ChatConversationEntity> findPage(
            ChatConversationEntity example, int pageNum, int pageSize, String orderBy);

    /** Idempotent, non-disclosing soft delete of an ordinary conversation. */
    void deleteConversation(String conversationId);

    List<ChatMessageEntity> findByConversationId(String conversationId);

    ChatConversationEntity create(ChatConversationEntity entity);

    ChatConversationEntity get(String conversationId);

    /** Tenant-zero compatibility for callbacks that predate explicit tenant propagation. */
    default ChatConversationEntity getOwned(
            String ownerJiacn, String clientId, String conversationId) {
        return getOwned("0", ownerJiacn, clientId, conversationId);
    }

    /** Exact tenant/owner/client live lookup for callbacks without thread-local context. */
    ChatConversationEntity getOwned(
            String tenantId, String ownerJiacn, String clientId, String conversationId);

    default boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration) {
        return isLiveGeneration("0", ownerJiacn, clientId, conversationId, expectedGeneration);
    }

    /** Fail-closed exact-scope generation probe for asynchronous publication fences. */
    boolean isLiveGeneration(String tenantId, String ownerJiacn, String clientId,
            String conversationId, long expectedGeneration);

    default List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId) {
        return findOwnedMessages("0", ownerJiacn, clientId, conversationId);
    }

    List<ChatMessageEntity> findOwnedMessages(
            String tenantId, String ownerJiacn, String clientId, String conversationId);

    default List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId, int limit) {
        return findOwnedMessages("0", ownerJiacn, clientId, conversationId, limit);
    }

    List<ChatMessageEntity> findOwnedMessages(
            String tenantId, String ownerJiacn, String clientId, String conversationId, int limit);

    default ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message) {
        return appendOwnedMessage("0", ownerJiacn, clientId, message);
    }

    ChatMessageEntity appendOwnedMessage(
            String tenantId, String ownerJiacn, String clientId, ChatMessageEntity message);

    default ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message, long expectedGeneration) {
        return appendOwnedMessage("0", ownerJiacn, clientId, message, expectedGeneration);
    }

    ChatMessageEntity appendOwnedMessage(String tenantId, String ownerJiacn, String clientId,
            ChatMessageEntity message, long expectedGeneration);

    default ChatConversationEntity updateOwnedTitle(String ownerJiacn, String clientId,
            String conversationId, String title, Integer status) {
        return updateOwnedTitle("0", ownerJiacn, clientId, conversationId, title, status);
    }

    ChatConversationEntity updateOwnedTitle(String tenantId, String ownerJiacn, String clientId,
            String conversationId, String title, Integer status);

    ChatConversationEntity update(ChatConversationEntity entity);
}
