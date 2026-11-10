package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatMessageEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Chat message mapper. */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
    String LIVE_CONVERSATION_OWNER_SCOPE = """
              AND c.deleted_at IS NULL
              AND c.tenant_id = #{tenantId}
              AND c.jiacn = #{ownerJiacn}
              AND c.client_id = #{clientId}
              AND CAST(c.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(c.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(c.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(c.jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(c.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(c.client_id) = OCTET_LENGTH(#{clientId})
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

    String MEMORY_PROCESS_SCOPE = """
              AND c.tenant_id = #{tenantId}
              AND CAST(c.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              <if test="clientId != null">
              AND c.client_id = #{clientId}
              AND CAST(c.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              </if>
            """;

    String MEMORY_SOURCE_JOIN = """
            FROM chat_conversation c
            JOIN chat_message m ON m.conversation_id = CAST(c.id AS CHAR)
              AND CAST(m.conversation_id AS BINARY) = CAST(CAST(c.id AS CHAR) AS BINARY)
            WHERE c.deleted_at IS NULL
              AND m.message_type IN ('USER', 'ASSISTANT')
            """ + EXACT_MESSAGE_OWNER_SCOPE + MEMORY_PROCESS_SCOPE;

    /** Process partition precedes cursor, grouping and LIMIT. */
    @Select("<script>SELECT CAST(c.id AS CHAR) " + MEMORY_SOURCE_JOIN + """
              AND c.id &gt; #{afterId} AND m.sync_status = #{syncStatus}
            GROUP BY c.id ORDER BY c.id ASC LIMIT #{limit}
            </script>
            """)
    List<String> findPendingMemoryConversations(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("syncStatus") String syncStatus,
            @Param("limit") int limit, @Param("afterId") long afterId);

    /** Do not collapse the same account in different clients or infer ownership from message data. */
    @Select("<script>SELECT DISTINCT c.client_id COLLATE utf8mb4_0900_bin AS client_id, "
            + "c.jiacn COLLATE utf8mb4_0900_bin AS jiacn " + MEMORY_SOURCE_JOIN + """
              AND m.create_time &gt; #{sinceTime}
              AND (m.sync_status IS NULL OR m.sync_status != 'EXCLUDED')
            </script>
            """)
    @org.apache.ibatis.annotations.ConstructorArgs({
        @org.apache.ibatis.annotations.Arg(column="client_id", javaType=String.class),
        @org.apache.ibatis.annotations.Arg(column="jiacn", javaType=String.class)
    })
    List<cn.jia.chat.memory.MemoryOwner> findActiveMemoryOwners(@Param("tenantId") String tenantId,
            @Param("clientId") String clientId, @Param("sinceTime") long sinceTime);

    /** Called with the live parent locked; never marks messages appended after the snapshot. */
    @Update("""
            <script>
            UPDATE chat_message m JOIN chat_conversation c ON c.id = #{numericConversationId}
            SET m.sync_status = 'SYNCED'
            WHERE m.sync_status = 'PENDING'
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
              AND m.id IN
              <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
              </foreach>
            </script>
            """)
    int markMemorySnapshotSynced(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("conversationId") String conversationId,
            @Param("numericConversationId") long numericConversationId,
            @Param("messageIds") List<Long> messageIds);

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
            @Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Select("""
            <script>
            SELECT m.* FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
            <if test="beforeTime != null">
              <choose>
                <when test="beforeTime &lt; 0">
                  AND m.create_time IS NULL AND m.id &lt; #{beforeId}
                </when>
                <otherwise>
                  AND (m.create_time IS NULL OR m.create_time &lt; #{beforeTime}
                    OR (m.create_time = #{beforeTime} AND m.id &lt; #{beforeId}))
                </otherwise>
              </choose>
            </if>
            ORDER BY m.create_time DESC, m.id DESC LIMIT #{limit}
            </script>
            """)
    List<ChatMessageEntity> findExactOwnedMessagePage(
            @Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId,
            @Param("beforeTime") Long beforeTime, @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    @Select("""
            <script>
            SELECT m.* FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
            <choose>
              <when test="anchorTime &lt; 0">
                AND m.create_time IS NULL AND m.id &lt;= #{anchorId}
              </when>
              <otherwise>
                AND (m.create_time IS NULL OR m.create_time &lt; #{anchorTime}
                  OR (m.create_time = #{anchorTime} AND m.id &lt;= #{anchorId}))
              </otherwise>
            </choose>
            ORDER BY m.create_time DESC, m.id DESC LIMIT #{limit}
            </script>
            """)
    List<ChatMessageEntity> findExactOwnedMessagePageAt(
            @Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId,
            @Param("anchorTime") Long anchorTime, @Param("anchorId") Long anchorId,
            @Param("limit") int limit);

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
            @Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId,
            @Param("limit") int limit);

    /** Same authorized latest window, without transferring history bodies or metadata. */
    @Select("""
            SELECT m.id
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE + """
            ORDER BY m.create_time DESC, m.id DESC
            LIMIT #{limit}
            """)
    List<Long> findExactOwnedConversationMessageIdsWithLimit(
            @Param("tenantId") String tenantId,
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
            @Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Delete("""
            DELETE m FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_CONVERSATION_OWNER_SCOPE
            + EXACT_MESSAGE_OWNER_SCOPE)
    int deleteExactOwnedConversationMessages(
            @Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

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
