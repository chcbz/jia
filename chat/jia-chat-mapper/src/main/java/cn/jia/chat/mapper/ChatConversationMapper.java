package cn.jia.chat.mapper;

import cn.jia.chat.entity.ChatConversationEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Chat conversation mapper. */
public interface ChatConversationMapper extends BaseMapper<ChatConversationEntity> {
    String EXACT_LEGACY_OWNER_SCOPE = """
              AND tenant_id = '0'
              AND data_scope_mode = 'LEGACY_CLIENT'
              AND jiacn = #{ownerJiacn}
              AND client_id = #{clientId}
              AND CAST(tenant_id AS BINARY) = CAST('0' AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH('0')
              AND CAST(data_scope_mode AS BINARY) = CAST('LEGACY_CLIENT' AS BINARY)
              AND OCTET_LENGTH(data_scope_mode) = OCTET_LENGTH('LEGACY_CLIENT')
              AND CAST(jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{ownerJiacn})
              AND CAST(client_id AS BINARY) = CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id) = OCTET_LENGTH(#{clientId})
            """;

    String EXACT_DOMAIN_OWNER_SCOPE = """
              AND tenant_id = #{tenantId}
              AND data_domain_id = #{dataDomainId}
              AND data_scope_mode = 'DOMAIN'
              AND jiacn = #{ownerJiacn}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{tenantId})
              AND CAST(data_domain_id AS BINARY) = CAST(#{dataDomainId} AS BINARY)
              AND OCTET_LENGTH(data_domain_id) = OCTET_LENGTH(#{dataDomainId})
              AND CAST(data_scope_mode AS BINARY) = CAST('DOMAIN' AS BINARY)
              AND OCTET_LENGTH(data_scope_mode) = OCTET_LENGTH('DOMAIN')
              AND CAST(jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(jiacn) = OCTET_LENGTH(#{ownerJiacn})
            """;

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
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
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
              AND deleted_at IS NULL
            LIMIT 1
            """)
    ChatConversationEntity findExactDomainScopedById(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
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
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
              AND deleted_at IS NULL
            LIMIT 1 FOR UPDATE
            """)
    ChatConversationEntity lockExactDomainScopedById(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
            LIMIT 1 FOR UPDATE
            """)
    ChatConversationEntity lockExactScopedByIdIncludingDeleted(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id);

    @Select("""
            SELECT * FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
            LIMIT 1 FOR UPDATE
            """)
    ChatConversationEntity lockExactDomainScopedByIdIncludingDeleted(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id);

    @Update("""
            UPDATE chat_conversation
            SET deleted_at = #{deletedAt},
                lifecycle_generation = lifecycle_generation + 1,
                memory_published_token = NULL,
                memory_published_revision = NULL,
                update_time = #{deletedAt}
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int softDeleteExactScopedById(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("deletedAt") long deletedAt);

    @Update("""
            UPDATE chat_conversation
            SET deleted_at = #{deletedAt},
                lifecycle_generation = lifecycle_generation + 1,
                memory_published_token = NULL,
                memory_published_revision = NULL,
                update_time = #{deletedAt}
            WHERE id = #{id}
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int softDeleteExactDomainScopedById(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id,
            @Param("deletedAt") long deletedAt);

    @Update("""
            UPDATE chat_conversation
            SET title = #{title},
                status = #{status},
                data_scope_mode = 'LEGACY_CLIENT'
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int updateExactOwnedFields(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("title") String title,
            @Param("status") Integer status);

    @Update("""
            UPDATE chat_conversation
            SET title = #{title},
                status = #{status},
                data_scope_mode = 'DOMAIN'
            WHERE id = #{id}
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
              AND deleted_at IS NULL
            """)
    int updateExactDomainOwnedFields(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id,
            @Param("title") String title,
            @Param("status") Integer status);

    @Select("""
            SELECT COUNT(*) FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_LEGACY_OWNER_SCOPE + """
              AND deleted_at IS NULL
              AND lifecycle_generation = #{expectedGeneration}
            """)
    int countExactLiveGeneration(
            @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId,
            @Param("id") Long id,
            @Param("expectedGeneration") long expectedGeneration);

    @Select("""
            SELECT COUNT(*) FROM chat_conversation
            WHERE id = #{id}
            """ + EXACT_DOMAIN_OWNER_SCOPE + """
              AND deleted_at IS NULL
              AND lifecycle_generation = #{expectedGeneration}
            """)
    int countExactDomainLiveGeneration(
            @Param("tenantId") String tenantId,
            @Param("dataDomainId") String dataDomainId,
            @Param("ownerJiacn") String ownerJiacn,
            @Param("id") Long id,
            @Param("expectedGeneration") long expectedGeneration);
}
