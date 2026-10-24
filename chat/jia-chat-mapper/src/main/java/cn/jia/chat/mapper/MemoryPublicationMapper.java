package cn.jia.chat.mapper;

import cn.jia.chat.entity.MemoryPublicationRecord;
import cn.jia.chat.entity.MemoryWorkItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** SQL authority and durable work queue for long-term-memory publication. */
public interface MemoryPublicationMapper {
    @Insert("""
            INSERT INTO chat_memory_work
                (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id,
                 lifecycle_generation, state, due_at, lease_owner, lease_until,
                 attempts, wake_requested, last_error, create_time, update_time)
            SELECT c.tenant_id, c.data_scope_mode,
                   CASE WHEN c.data_scope_mode = 'LEGACY_CLIENT'
                        THEN c.client_id ELSE c.data_domain_id END,
                   c.jiacn, CAST(c.id AS CHAR), c.lifecycle_generation,
                   'READY', #{now}, NULL, NULL, 0, 0, NULL, #{now}, #{now}
            FROM chat_message m
            JOIN chat_conversation c
              ON CAST(c.id AS CHAR) = m.conversation_id
             AND CAST(CAST(c.id AS CHAR) AS BINARY) = CAST(m.conversation_id AS BINARY)
             AND OCTET_LENGTH(CAST(c.id AS CHAR)) = OCTET_LENGTH(m.conversation_id)
            WHERE m.id = #{messageId}
              AND m.sync_status = 'PENDING'
              AND c.deleted_at IS NULL
              AND c.data_scope_mode IN ('LEGACY_CLIENT', 'DOMAIN')
              AND m.tenant_id = c.tenant_id
              AND m.jiacn = c.jiacn
              AND (c.data_scope_mode = 'DOMAIN' AND m.data_domain_id = c.data_domain_id
                   OR c.data_scope_mode = 'LEGACY_CLIENT' AND m.client_id = c.client_id)
            ON DUPLICATE KEY UPDATE
              lifecycle_generation = VALUES(lifecycle_generation),
              due_at = LEAST(due_at, VALUES(due_at)),
              wake_requested = CASE WHEN state = 'LEASED' THEN 1 ELSE wake_requested END,
              state = CASE WHEN state = 'LEASED' THEN state ELSE 'READY' END,
              last_error = CASE WHEN state = 'LEASED' THEN last_error ELSE NULL END,
              update_time = VALUES(update_time)
            """)
    int signalForInsertedMessage(@Param("messageId") long messageId, @Param("now") long now);

    @Select("""
            SELECT * FROM chat_memory_work
            WHERE (state IN ('READY', 'RETRY') AND due_at <= #{now})
               OR (state = 'LEASED' AND lease_until < #{now})
            ORDER BY due_at ASC, id ASC
            LIMIT 1
            """)
    MemoryWorkItem findClaimCandidate(@Param("now") long now);

    @Update("""
            UPDATE chat_memory_work
            SET state = 'LEASED', lease_owner = #{workerId}, lease_until = #{leaseUntil},
                attempts = attempts + 1, update_time = #{now}
            WHERE id = #{id}
              AND ((state IN ('READY', 'RETRY') AND due_at <= #{now})
                   OR (state = 'LEASED' AND lease_until < #{now}))
            """)
    int claim(@Param("id") long id, @Param("workerId") String workerId,
              @Param("now") long now, @Param("leaseUntil") long leaseUntil);

    @Select("SELECT * FROM chat_memory_work WHERE id = #{id} LIMIT 1")
    MemoryWorkItem findWorkById(@Param("id") long id);

    @Update("""
            UPDATE chat_memory_work
            SET state = CASE WHEN wake_requested = 1 THEN 'READY' ELSE 'DONE' END,
                due_at = CASE WHEN wake_requested = 1 THEN #{now} ELSE due_at END,
                wake_requested = 0, lease_owner = NULL, lease_until = NULL,
                last_error = NULL, update_time = #{now}
            WHERE id = #{id} AND state = 'LEASED' AND lease_owner = #{workerId}
            """)
    int completeWork(@Param("id") long id, @Param("workerId") String workerId,
                     @Param("now") long now);

    @Update("""
            UPDATE chat_memory_work
            SET state = #{state}, due_at = #{dueAt}, lease_owner = NULL, lease_until = NULL,
                last_error = #{lastError}, update_time = #{now}
            WHERE id = #{id} AND state = 'LEASED' AND lease_owner = #{workerId}
            """)
    int releaseWork(@Param("id") long id, @Param("workerId") String workerId,
                    @Param("state") String state, @Param("dueAt") long dueAt,
                    @Param("lastError") String lastError, @Param("now") long now);

    @Select("""
            <script>
            SELECT * FROM chat_memory_publication
            WHERE tenant_id = #{tenantId} AND scope_mode = #{scopeMode}
              AND scope_key = #{scopeKey} AND owner_jiacn = #{ownerJiacn}
              AND conversation_id = #{conversationId}
              AND lifecycle_generation = #{generation}
              AND snapshot_token = #{snapshotToken}
              AND CAST(tenant_id AS BINARY) = CAST(#{tenantId} AS BINARY)
              AND CAST(scope_mode AS BINARY) = CAST(#{scopeMode} AS BINARY)
              AND CAST(scope_key AS BINARY) = CAST(#{scopeKey} AS BINARY)
              AND CAST(owner_jiacn AS BINARY) = CAST(#{ownerJiacn} AS BINARY)
              AND CAST(conversation_id AS BINARY) = CAST(#{conversationId} AS BINARY)
              AND CAST(snapshot_token AS BINARY) = CAST(#{snapshotToken} AS BINARY)
            LIMIT 1
            </script>
            """)
    MemoryPublicationRecord findByToken(
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId, @Param("generation") long generation,
            @Param("snapshotToken") String snapshotToken);

    @Select("SELECT * FROM chat_memory_publication WHERE id = #{id} FOR UPDATE")
    MemoryPublicationRecord findByIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT COALESCE(MAX(es_revision), 0)
            FROM chat_memory_publication
            WHERE tenant_id = #{tenantId} AND scope_mode = #{scopeMode}
              AND scope_key = #{scopeKey} AND owner_jiacn = #{ownerJiacn}
              AND conversation_id = #{conversationId}
            """)
    long maxRevision(
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId);

    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    @Insert("""
            INSERT INTO chat_memory_publication
                (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id,
                 lifecycle_generation, snapshot_token, es_revision, document_id,
                 message_count, state, summary_content, embedding_bytes, payload_digest,
                 published_at, tombstoned_at, create_time, update_time)
            VALUES
                (#{tenantId}, #{scopeMode}, #{scopeKey}, #{ownerJiacn}, #{conversationId},
                 #{lifecycleGeneration}, #{snapshotToken}, #{esRevision}, #{documentId},
                 #{messageCount}, 'ALLOCATED', NULL, NULL, NULL,
                 NULL, NULL, #{createTime}, #{updateTime})
            """)
    int insertPublication(MemoryPublicationRecord publication);

    @Update("""
            UPDATE chat_memory_publication
            SET state = 'STAGED', summary_content = #{summaryContent},
                embedding_bytes = #{embeddingBytes}, payload_digest = #{payloadDigest},
                update_time = #{now}
            WHERE id = #{id} AND state = 'ALLOCATED'
              AND summary_content IS NULL AND embedding_bytes IS NULL
              AND payload_digest IS NULL
            """)
    int bindPayload(
            @Param("id") long id, @Param("summaryContent") String summaryContent,
            @Param("embeddingBytes") byte[] embeddingBytes,
            @Param("payloadDigest") String payloadDigest, @Param("now") long now);

    @Update("""
            UPDATE chat_memory_publication
            SET state = 'PUBLISHED', published_at = COALESCE(published_at, #{now}),
                update_time = #{now}
            WHERE id = #{id} AND state IN ('STAGED', 'PUBLISHED')
            """)
    int markPublished(@Param("id") long id, @Param("now") long now);

    @Update("""
            UPDATE chat_conversation
            SET memory_published_token = #{snapshotToken},
                memory_published_revision = #{revision}, update_time = #{now}
            WHERE id = #{numericConversationId} AND deleted_at IS NULL
              AND lifecycle_generation = #{generation}
              AND tenant_id = #{tenantId} AND jiacn = #{ownerJiacn}
              AND data_scope_mode = #{scopeMode}
              AND (#{scopeMode} = 'LEGACY_CLIENT' AND client_id = #{scopeKey}
                   OR #{scopeMode} = 'DOMAIN' AND data_domain_id = #{scopeKey})
            """)
    int pointConversationAtPublication(
            @Param("numericConversationId") long numericConversationId,
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("generation") long generation, @Param("snapshotToken") String snapshotToken,
            @Param("revision") long revision, @Param("now") long now);

    @Select("""
            <script>
            SELECT p.document_id
            FROM chat_memory_publication p
            JOIN chat_conversation c
              ON CAST(c.id AS CHAR) = p.conversation_id
             AND c.deleted_at IS NULL
             AND c.lifecycle_generation = p.lifecycle_generation
             AND c.memory_published_token = p.snapshot_token
             AND c.memory_published_revision = p.es_revision
            WHERE p.state = 'PUBLISHED'
              AND p.tenant_id = #{tenantId} AND p.scope_mode = #{scopeMode}
              AND p.scope_key = #{scopeKey} AND p.owner_jiacn = #{ownerJiacn}
              AND p.document_id IN
              <foreach collection="documentIds" item="documentId" open="(" separator="," close=")">
                #{documentId}
              </foreach>
            </script>
            """)
    List<String> findVisibleDocumentIds(
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("documentIds") List<String> documentIds);

    @Update("""
            UPDATE chat_memory_publication
            SET state = 'TOMBSTONED', tombstoned_at = #{now}, update_time = #{now}
            WHERE tenant_id = #{tenantId} AND scope_mode = #{scopeMode}
              AND scope_key = #{scopeKey} AND owner_jiacn = #{ownerJiacn}
              AND conversation_id = #{conversationId} AND state <> 'TOMBSTONED'
            """)
    int tombstonePublications(
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId, @Param("now") long now);

    @Update("""
            UPDATE chat_memory_work
            SET state = 'DEAD', lease_owner = NULL, lease_until = NULL,
                wake_requested = 0, last_error = 'conversation deleted', update_time = #{now}
            WHERE tenant_id = #{tenantId} AND scope_mode = #{scopeMode}
              AND scope_key = #{scopeKey} AND owner_jiacn = #{ownerJiacn}
              AND conversation_id = #{conversationId} AND state <> 'DONE'
            """)
    int cancelWork(
            @Param("tenantId") String tenantId, @Param("scopeMode") String scopeMode,
            @Param("scopeKey") String scopeKey, @Param("ownerJiacn") String ownerJiacn,
            @Param("conversationId") String conversationId, @Param("now") long now);
}
