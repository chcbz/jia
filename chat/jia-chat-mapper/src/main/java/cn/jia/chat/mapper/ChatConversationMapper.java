package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Chat conversation mapper. */
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {
    String EXACT_OWNER_SCOPE = """
              AND jiacn = #{ownerJiacn}
              AND client_id = #{clientId}
              AND (tenant_id = #{ownerJiacn} OR tenant_id = '0')
              AND CAST(jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
              AND (CAST(tenant_id AS BINARY) = CAST(#{ownerJiacn} AS BINARY) OR tenant_id = '0')
              AND (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{ownerJiacn}) OR tenant_id = '0')
            """;

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
            LIMIT 1
            """)
    ChatConversationEntity findExactScopedById(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
            LIMIT 1 FOR UPDATE
            """)
    ChatConversationEntity lockExactScopedById(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
            LIMIT 1 FOR UPDATE
            """)
    ChatConversationEntity lockExactScopedByIdIncludingDeleted(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id);

    @Update("""
            UPDATE chat_conversation
            SET deleted_at = #{deletedAt}, update_time = #{deletedAt}
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int softDeleteExactScopedById(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("deletedAt") long deletedAt);
}
