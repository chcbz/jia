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
    String LIVE_LEGACY_CONVERSATION_OWNER_SCOPE = """
              AND c.deleted_at IS NULL
              AND c.tenant_id = '0'
              AND c.data_scope_mode = 'LEGACY_CLIENT'
              AND c.jiacn = #{ownerJiacn}
              AND c.client_id = #{clientId}
              AND CAST(c.tenant_id AS BINARY) = CAST('0' AS BINARY)
              AND OCTET_LENGTH(c.tenant_id) = OCTET_LENGTH('0')
              AND CAST(c.data_scope_mode AS BINARY) = CAST('LEGACY_CLIENT' AS BINARY)
              AND OCTET_LENGTH(c.data_scope_mode) = OCTET_LENGTH('LEGACY_CLIENT')
              AND CAST(c.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(c.jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(c.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(c.client_id) = OCTET_LENGTH(#{clientId})
            """;

    String LIVE_DOMAIN_CONVERSATION_OWNER_SCOPE = """
              AND c.deleted_at IS NULL
              AND c.data_scope_mode = 'DOMAIN'
              AND c.tenant_id = #{tenantId}
              AND c.data_domain_id = #{dataDomainId}
              AND c.jiacn = #{ownerJiacn}
              AND CAST(c.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND CAST(c.data_scope_mode AS BINARY) = CAST('DOMAIN' AS BINARY)
              AND OCTET_LENGTH(c.data_scope_mode) = OCTET_LENGTH('DOMAIN')
              AND OCTET_LENGTH(c.tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(c.data_domain_id AS BINARY) = CAST(#{dataDomainId} AS BINARY)
              AND OCTET_LENGTH(c.data_domain_id) = OCTET_LENGTH(#{dataDomainId})
              AND CAST(c.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(c.jiacn) = OCTET_LENGTH(#{ownerJiacn})
            """;

    String EXACT_MESSAGE_CONVERSATION_SCOPE = """
              AND m.conversation_id = #{conversationId}
              AND CAST(m.conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(m.conversation_id) = OCTET_LENGTH(#{conversationId})
            """;

    String EXACT_LEGACY_MESSAGE_OWNER_SCOPE = """
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

    String EXACT_DOMAIN_MESSAGE_OWNER_SCOPE = """
              AND m.jiacn = c.jiacn
              AND CAST(m.jiacn AS BINARY) = CAST(c.jiacn AS BINARY)
              AND OCTET_LENGTH(m.jiacn) = OCTET_LENGTH(c.jiacn)
              AND m.tenant_id = c.tenant_id
              AND CAST(m.tenant_id AS BINARY) = CAST(c.tenant_id AS BINARY)
              AND OCTET_LENGTH(m.tenant_id) = OCTET_LENGTH(c.tenant_id)
              AND m.data_domain_id = c.data_domain_id
              AND CAST(m.data_domain_id AS BINARY) = CAST(c.data_domain_id AS BINARY)
              AND OCTET_LENGTH(m.data_domain_id) = OCTET_LENGTH(c.data_domain_id)
            """;

    String EXACT_MAINTENANCE_CONVERSATION = """
              AND m.conversation_id = #{conversationId}
              AND CAST(m.conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(m.conversation_id) = OCTET_LENGTH(#{conversationId})
            """;

    String AREA_ZERO_MAINTENANCE_SCOPE = """
              AND m.tenant_id = '0'
              AND CAST(m.tenant_id AS BINARY) = CAST('0' AS BINARY)
              AND OCTET_LENGTH(m.tenant_id) = OCTET_LENGTH('0')
              AND (m.data_domain_id IS NULL OR m.data_domain_id = '' OR (
                    m.data_domain_id = 'cyf'
                AND CAST(m.data_domain_id AS BINARY) = CAST('cyf' AS BINARY)
                AND OCTET_LENGTH(m.data_domain_id) = OCTET_LENGTH('cyf')))
            """;

    String NO_OUT_OF_SCOPE_MAINTENANCE_ROWS = """
              AND NOT EXISTS (
                    SELECT 1
                    FROM chat_message contaminated
                    WHERE contaminated.conversation_id = m.conversation_id
                      AND CAST(contaminated.conversation_id AS BINARY) = CAST(m.conversation_id AS BINARY)
                      AND OCTET_LENGTH(contaminated.conversation_id) = OCTET_LENGTH(m.conversation_id)
                      AND CASE WHEN contaminated.tenant_id = '0'
                            AND CAST(contaminated.tenant_id AS BINARY) = CAST('0' AS BINARY)
                            AND OCTET_LENGTH(contaminated.tenant_id) = OCTET_LENGTH('0')
                            AND (contaminated.data_domain_id IS NULL
                              OR contaminated.data_domain_id = ''
                              OR (contaminated.data_domain_id = 'cyf'
                                AND CAST(contaminated.data_domain_id AS BINARY) = CAST('cyf' AS BINARY)
                                AND OCTET_LENGTH(contaminated.data_domain_id) = OCTET_LENGTH('cyf')))
                          THEN 0 ELSE 1 END = 1)
            """;

    @Select("""
            SELECT m.*
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_LEGACY_CONVERSATION_OWNER_SCOPE
            + EXACT_LEGACY_MESSAGE_OWNER_SCOPE + """
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
            + LIVE_DOMAIN_CONVERSATION_OWNER_SCOPE
            + EXACT_DOMAIN_MESSAGE_OWNER_SCOPE + """
            ORDER BY m.create_time ASC, m.id ASC
            """)
    List<ChatMessageEntity> findExactDomainOwnedConversationMessages(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Select("""
            SELECT m.*
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_LEGACY_CONVERSATION_OWNER_SCOPE
            + EXACT_LEGACY_MESSAGE_OWNER_SCOPE + """
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
            SELECT m.*
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_DOMAIN_CONVERSATION_OWNER_SCOPE
            + EXACT_DOMAIN_MESSAGE_OWNER_SCOPE + """
            ORDER BY m.create_time DESC, m.id DESC
            LIMIT #{limit}
            """)
    List<ChatMessageEntity> findExactDomainOwnedConversationMessagesWithLimit(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_LEGACY_CONVERSATION_OWNER_SCOPE + """
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

    @Select("""
            SELECT COUNT(*)
            FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_DOMAIN_CONVERSATION_OWNER_SCOPE + """
              AND (m.jiacn IS NULL OR m.jiacn <> c.jiacn
                OR CAST(m.jiacn AS BINARY) <> CAST(c.jiacn AS BINARY)
                OR OCTET_LENGTH(m.jiacn) <> OCTET_LENGTH(c.jiacn)
                OR m.tenant_id IS NULL OR m.tenant_id <> c.tenant_id
                OR CAST(m.tenant_id AS BINARY) <> CAST(c.tenant_id AS BINARY)
                OR OCTET_LENGTH(m.tenant_id) <> OCTET_LENGTH(c.tenant_id)
                OR m.data_domain_id IS NULL OR m.data_domain_id <> c.data_domain_id
                OR CAST(m.data_domain_id AS BINARY) <> CAST(c.data_domain_id AS BINARY)
                OR OCTET_LENGTH(m.data_domain_id) <> OCTET_LENGTH(c.data_domain_id))
            """)
    int countContaminatedDomainOwnedConversationMessages(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Delete("""
            DELETE m FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_LEGACY_CONVERSATION_OWNER_SCOPE
            + EXACT_LEGACY_MESSAGE_OWNER_SCOPE)
    int deleteExactOwnedConversationMessages(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("numericConversationId") Long numericConversationId);

    @Delete("""
            DELETE m FROM chat_message m
            JOIN chat_conversation c ON c.id = #{numericConversationId}
            WHERE 1 = 1
            """ + EXACT_MESSAGE_CONVERSATION_SCOPE
            + LIVE_DOMAIN_CONVERSATION_OWNER_SCOPE
            + EXACT_DOMAIN_MESSAGE_OWNER_SCOPE)
    int deleteExactDomainOwnedConversationMessages(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
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

    @Select("""
            SELECT m.*
            FROM chat_message m
            WHERE 1 = 1
            """ + EXACT_MAINTENANCE_CONVERSATION
            + AREA_ZERO_MAINTENANCE_SCOPE
            + NO_OUT_OF_SCOPE_MAINTENANCE_ROWS + """
            ORDER BY m.create_time ASC, m.id ASC
            """)
    List<ChatMessageEntity> findAreaZeroMaintenanceMessages(
            @Param("conversationId") String conversationId);

    @Select("""
            SELECT COUNT(*)
            FROM chat_message m
            WHERE 1 = 1
            """ + EXACT_MAINTENANCE_CONVERSATION)
    int countExactConversationMessages(@Param("conversationId") String conversationId);

    @Select("""
            <script>
            SELECT m.conversation_id
            FROM chat_message m
            WHERE m.sync_status = #{syncStatus}
              AND CAST(m.sync_status AS BINARY) = CAST(#{syncStatus} AS BINARY)
              AND OCTET_LENGTH(m.sync_status) = OCTET_LENGTH(#{syncStatus})
            """ + AREA_ZERO_MAINTENANCE_SCOPE
            + NO_OUT_OF_SCOPE_MAINTENANCE_ROWS + """
            <if test="excludedConversationIds != null and not excludedConversationIds.isEmpty()">
              AND NOT (
                <foreach collection="excludedConversationIds" item="excludedId" separator=" OR ">
                  (m.conversation_id = #{excludedId}
                   AND CAST(m.conversation_id AS BINARY) = CAST(#{excludedId} AS BINARY)
                   AND OCTET_LENGTH(m.conversation_id) = OCTET_LENGTH(#{excludedId}))
                </foreach>
              )
            </if>
            GROUP BY m.conversation_id, CAST(m.conversation_id AS BINARY),
                     OCTET_LENGTH(m.conversation_id)
            ORDER BY MIN(m.create_time) ASC, MIN(m.id) ASC
            LIMIT #{limit}
            </script>
            """)
    List<String> findAreaZeroPendingConversationIds(
            @Param("syncStatus") String syncStatus,
            @Param("excludedConversationIds") List<String> excludedConversationIds,
            @Param("limit") int limit);

    @Update("""
            UPDATE chat_message m
            SET m.sync_status = #{syncStatus}
            WHERE m.sync_status = 'PENDING'
              AND CAST(m.sync_status AS BINARY) = CAST('PENDING' AS BINARY)
              AND OCTET_LENGTH(m.sync_status) = OCTET_LENGTH('PENDING')
            """ + EXACT_MAINTENANCE_CONVERSATION
            + AREA_ZERO_MAINTENANCE_SCOPE)
    int updateAreaZeroSyncStatusByConversationId(
            @Param("conversationId") String conversationId,
            @Param("syncStatus") String syncStatus);

    @Update("""
            <script>
            UPDATE chat_message m
            SET m.sync_status = 'SYNCED'
            WHERE m.sync_status = 'PENDING'
              AND CAST(m.sync_status AS BINARY) = CAST('PENDING' AS BINARY)
              AND OCTET_LENGTH(m.sync_status) = OCTET_LENGTH('PENDING')
            """ + EXACT_MAINTENANCE_CONVERSATION
            + AREA_ZERO_MAINTENANCE_SCOPE + """
              AND m.jiacn = #{ownerJiacn}
              AND CAST(m.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(m.jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND m.client_id = #{clientId}
              AND CAST(m.client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(m.client_id) = OCTET_LENGTH(#{clientId})
              AND m.id IN
              <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
              </foreach>
            </script>
            """)
    int compareAndSetAreaZeroPendingMessagesSynced(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("conversationId") String conversationId,
            @Param("messageIds") List<Long> messageIds);
    @Update("""
            <script>
            UPDATE chat_message m
            SET m.sync_status = 'SYNCED'
            WHERE m.sync_status = 'PENDING'
              AND m.tenant_id = #{tenantId}
              AND m.data_domain_id = #{dataDomainId}
              AND m.jiacn = #{ownerJiacn}
              AND m.conversation_id = #{conversationId}
              AND CAST(m.tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND CAST(m.data_domain_id AS BINARY) = CAST(#{dataDomainId} AS BINARY)
              AND CAST(m.jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND CAST(m.conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND m.id IN
              <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
              </foreach>
            </script>
            """)
    int compareAndSetDomainPendingMessagesSynced(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId,
            @Param("messageIds") List<Long> messageIds);


    @Select("""
            SELECT m.id
            FROM chat_message m
            WHERE m.create_time < #{beforeTime}
            """ + AREA_ZERO_MAINTENANCE_SCOPE + """
            ORDER BY m.id ASC
            LIMIT #{limit}
            """)
    List<Long> findAreaZeroExpiredMessageIds(
            @Param("beforeTime") long beforeTime, @Param("limit") int limit);

    @Delete("""
            <script>
            DELETE FROM chat_message
            WHERE tenant_id = '0'
              AND CAST(tenant_id AS BINARY) = CAST('0' AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH('0')
              AND (data_domain_id IS NULL OR data_domain_id = '' OR (
                    data_domain_id = 'cyf'
                AND CAST(data_domain_id AS BINARY) = CAST('cyf' AS BINARY)
                AND OCTET_LENGTH(data_domain_id) = OCTET_LENGTH('cyf')))
              AND id IN
              <foreach collection="messageIds" item="messageId" open="(" separator="," close=")">
                #{messageId}
              </foreach>
            </script>
            """)
    int deleteAreaZeroMaintenanceMessageIds(@Param("messageIds") List<Long> messageIds);

    @Select("""
            SELECT m.jiacn
            FROM chat_message m
            WHERE m.create_time > #{sinceTime}
              AND m.jiacn IS NOT NULL
              AND m.jiacn <> ''
            """ + AREA_ZERO_MAINTENANCE_SCOPE + """
            GROUP BY m.jiacn, CAST(m.jiacn AS BINARY), OCTET_LENGTH(m.jiacn)
            """)
    List<String> findAreaZeroActiveJiacns(@Param("sinceTime") long sinceTime);
}
