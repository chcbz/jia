package cn.jia.chat.service;

import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.core.tenant.UserAdmissionScope;
import com.github.pagehelper.PageInfo;

import java.util.List;

/** Authenticated generic-conversation boundary. */
public interface ChatConversationService {
    PageInfo<ChatConversationEntity> findPage(
            UserAdmissionScope scope, ChatConversationEntity example,
            int pageNum, int pageSize, String orderBy);

    void deleteConversation(UserAdmissionScope scope, String conversationId);

    List<ChatMessageEntity> findByConversationId(
            UserAdmissionScope scope, String conversationId);

    ChatConversationEntity create(
            UserAdmissionScope scope, ChatConversationEntity entity);

    ChatConversationEntity get(UserAdmissionScope scope, String conversationId);

    boolean isLiveGeneration(
            UserAdmissionScope scope, String conversationId, long expectedGeneration);

    List<ChatMessageEntity> findOwnedMessages(
            UserAdmissionScope scope, String conversationId);

    List<ChatMessageEntity> findOwnedMessages(
            UserAdmissionScope scope, String conversationId, int limit);

    ChatMessageEntity appendOwnedMessage(
            UserAdmissionScope scope, ChatMessageEntity message);

    ChatMessageEntity appendOwnedMessage(
            UserAdmissionScope scope, ChatMessageEntity message, long expectedGeneration);

    ChatConversationEntity updateOwnedTitle(
            UserAdmissionScope scope, String conversationId, String title, Integer status);

    ChatConversationEntity update(
            UserAdmissionScope scope, ChatConversationEntity entity);

    PageInfo<ChatConversationEntity> findPage(
            ChatConversationEntity example, int pageNum, int pageSize, String orderBy);

    /** Idempotent, non-disclosing soft delete of an ordinary conversation. */
    void deleteConversation(String conversationId);

    List<ChatMessageEntity> findByConversationId(String conversationId);

    ChatConversationEntity create(ChatConversationEntity entity);

    ChatConversationEntity get(String conversationId);

    /** Exact owner-scoped live lookup for callbacks that cannot use thread-local context. */
    ChatConversationEntity getOwned(String ownerJiacn, String clientId, String conversationId);

    /** Fail-closed live-generation probe for asynchronous publication fences. */
    boolean isLiveGeneration(
            String ownerJiacn, String clientId, String conversationId, long expectedGeneration);

    /** Exact owner-scoped full history read for authenticated maintenance initiated by a user. */
    List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId);

    /** Exact owner-scoped bounded history read for advisors running after a thread switch. */
    List<ChatMessageEntity> findOwnedMessages(
            String ownerJiacn, String clientId, String conversationId, int limit);

    /**
     * Atomically locks a live owned conversation, derives all message identity from it, and inserts.
     * Lock order is chat_conversation row then chat_message insert.
     */
    ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message);

    /** Append only when the locked conversation still has the captured lifecycle generation. */
    ChatMessageEntity appendOwnedMessage(
            String ownerJiacn, String clientId, ChatMessageEntity message, long expectedGeneration);

    /** Exact owner-scoped title update for asynchronous summary callbacks. */
    ChatConversationEntity updateOwnedTitle(
            String ownerJiacn, String clientId, String conversationId, String title, Integer status);

    ChatConversationEntity update(ChatConversationEntity entity);
}
