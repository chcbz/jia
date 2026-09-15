package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Chat conversation mapper. */
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {
    String EXACT_OWNER_SCOPE = """
              AND tenant_id = '0'
              AND jiacn = #{ownerJiacn}
              AND client_id = #{clientId}
              AND CAST(tenant_id AS BINARY) = CAST('0' AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH('0')
              AND CAST(jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
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
            SET deleted_at = #{deletedAt},
                lifecycle_generation = lifecycle_generation + 1,
                update_time = #{deletedAt}
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int softDeleteExactScopedById(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("deletedAt") long deletedAt);

    @Update("""
            UPDATE chat_conversation
            SET title = #{title},
                status = #{status}
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int updateExactOwnedFields(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("title") String title,
            @Param("status") Integer status);

    @Select("""
            SELECT COUNT(*) FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_OWNER_SCOPE + """
              AND deleted_at IS NULL
              AND lifecycle_generation = #{expectedGeneration}
            """)
    int countExactLiveGeneration(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("expectedGeneration") long expectedGeneration);
}
