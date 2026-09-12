package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Chat message mapper. */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
    String LIVE_CONVERSATION_OWNER_SCOPE = """
              AND c.deleted_at IS NULL
              AND c.jiacn = #{ownerJiacn}
              AND c.client_id = #{clientId}
              AND (c.tenant_id = #{ownerJiacn} OR c.tenant_id = '0')
              AND CAST(c.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(c.jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(c.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(c.client_id) = OCTET_LENGTH(#{clientId})
              AND (CAST(c.tenant_id AS BINARY) = CAST(#{ownerJiacn} AS BINARY) OR c.tenant_id = '0')
              AND (OCTET_LENGTH(c.tenant_id) = OCTET_LENGTH(#{ownerJiacn}) OR c.tenant_id = '0')
            """;

    String EXACT_MESSAGE_CONVERSATION_SCOPE = """
              AND m.conversation_id = #{conversationId}
              AND CAST(m.conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(m.conversation_id) = OCTET_LENGTH(#{conversationId})
            """;

    String EXACT_MESSAGE_OWNER_SCOPE = """
              AND m.jiacn = c.jiacn
              AND CAST(m.jiacn AS BINARY) = CAST(c.jiacn AS BINARY)
              AND OCTET_LENGTH(m.jiacn) = OCTET_LENGTH(c.jiacn)
              AND m.client_id = c.client_id
              AND CAST(m.client_id AS BINARY) = CAST(c.client_id AS BINARY)
              AND OCTET_LENGTH(m.client_id) = OCTET_LENGTH(c.client_id)
              AND m.tenant_id = c.tenant_id
              AND CAST(m.tenant_id AS BINARY) = CAST(c.tenant_id AS BINARY)
              AND OCTET_LENGTH(m.tenant_id) = OCTET_LENGTH(c.tenant_id)
            """;

    @Select("""
            SELECT m.*
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
            ORDER BY m.create_time ASC, m.id ASC
            """)
    List<ChatMessageEntity> findExactOwnedConversationMessages(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Select("""
            SELECT m.*
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
            ORDER BY m.create_time DESC, m.id DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> findExactOwnedConversationMessagesWithLimit(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_CONVERSATION_OWNER_SCOPE + """
              AND (m.jiacn IS NULL OR m.jiacn <> c.jiacn
                OR CAST(m.jiacn AS BINARY) <> CAST(c.jiacn AS BINARY)
                OR OCTET_LENGTH(m.jiacn) <> OCTET_LENGTH(c.jiacn)
                OR m.client_id IS NULL OR m.client_id <> c.client_id
                OR CAST(m.client_id AS BINARY) <> CAST(c.client_id AS BINARY)
                OR OCTET_LENGTH(m.client_id) <> OCTET_LENGTH(c.client_id)
                OR m.tenant_id IS NULL OR m.tenant_id <> c.tenant_id
                OR CAST(m.tenant_id AS BINARY) <> CAST(c.tenant_id AS BINARY)
                OR OCTET_LENGTH(m.tenant_id) <> OCTET_LENGTH(c.tenant_id))
            """)
    int countContaminatedOwnedConversationMessages(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Delete("""
            DELETE FROM chat_message
            WHERE conversation_id = #{conversationId}
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id) = OCTET_LENGTH(#{conversationId})
            """)
    int deleteExactConversationMessages(@Param("conversationId") String conversationId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE tenant_id = #{tenantId}
              AND client_id = #{clientId}
              AND conversation_id = #{conversationId}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id) = OCTET_LENGTH(#{conversationId})
            ORDER BY create_time DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> findExactByConversationScope(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("limit") int limit);
}
