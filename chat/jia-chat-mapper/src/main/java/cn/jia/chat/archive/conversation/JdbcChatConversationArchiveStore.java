package cn.jia.chat.archive.conversation;

import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Objects;

/** Exact binary-scope persistence for one immutable conversation asset archive operation. */
@Named
public final class JdbcChatConversationArchiveStore implements ChatConversationArchiveStore {
    private static final String EXACT_OPERATION_SCOPE = """
            tenant_id=? AND owner_jiacn=? AND client_id=?
              AND BINARY tenant_id=BINARY ? AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?)
              AND BINARY owner_jiacn=BINARY ? AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?)
              AND BINARY client_id=BINARY ? AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?)
            """;

    private static final RowMapper<Operation> OPERATION = (rs, ignored) -> {
        long generation = rs.getLong("conversation_generation");
        Long nullableGeneration = rs.wasNull() ? null : generation;
        int version = rs.getInt("file_version");
        Integer nullableVersion = rs.wasNull() ? null : version;
        return new Operation(rs.getString("operation_id"), rs.getString("tenant_id"),
                rs.getString("owner_jiacn"), rs.getString("client_id"),
                rs.getString("conversation_id"), nullableGeneration,
                rs.getString("idempotency_key"), rs.getString("request_sha256"),
                rs.getString("asset_id"), rs.getLong("asset_revision"), rs.getString("state"),
                rs.getString("workspace_operation_id"), rs.getString("file_id"), nullableVersion,
                rs.getString("error_code"), rs.getString("message"), rs.getLong("row_revision"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    };

    private final JdbcTemplate jdbc;

    public JdbcChatConversationArchiveStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean tryInsert(Operation row) {
        try {
            return jdbc.update("""
                    INSERT INTO chat_conversation_archive_operation
                      (operation_id,tenant_id,owner_jiacn,client_id,conversation_id,
                       conversation_generation,idempotency_key,request_sha256,asset_id,asset_revision,
                       state,workspace_operation_id,file_id,file_version,error_code,message,row_revision,
                       created_at,updated_at)
                    VALUES (?,?,?,?,?,NULL,?,?,?,?, 'PENDING',NULL,NULL,NULL,NULL,NULL,1,?,?)
                    """, row.operationId(), row.tenantId(), row.ownerJiacn(), row.clientId(),
                    row.conversationId(), row.idempotencyKey(), row.requestSha256(), row.assetId(),
                    row.assetRevision(), row.createdAt(), row.updatedAt()) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override
    public Operation lockByIdempotencyKey(Scope scope, String key) {
        return first(jdbc.query("SELECT * FROM chat_conversation_archive_operation WHERE "
                + EXACT_OPERATION_SCOPE + """
                  AND idempotency_key=? AND BINARY idempotency_key=BINARY ?
                  AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(?)
                FOR UPDATE
                """, OPERATION, concat(scopeArgs(scope), key, key, key)));
    }

    @Override
    public Operation lockBySource(Scope scope, String assetId, long revision) {
        return first(jdbc.query("SELECT * FROM chat_conversation_archive_operation WHERE "
                + EXACT_OPERATION_SCOPE + """
                  AND asset_id=? AND BINARY asset_id=BINARY ?
                  AND OCTET_LENGTH(asset_id)=OCTET_LENGTH(?) AND asset_revision=?
                FOR UPDATE
                """, OPERATION, concat(scopeArgs(scope), assetId, assetId, assetId, revision)));
    }

    @Override
    public Operation lockByOperationId(Scope scope, String conversationId, String operationId) {
        return operation(scope, conversationId, operationId, true);
    }

    @Override
    public Operation findByOperationId(Scope scope, String conversationId, String operationId) {
        return operation(scope, conversationId, operationId, false);
    }

    private Operation operation(Scope scope, String conversationId, String operationId, boolean lock) {
        return first(jdbc.query("SELECT * FROM chat_conversation_archive_operation WHERE "
                + EXACT_OPERATION_SCOPE + """
                  AND conversation_id=? AND BINARY conversation_id=BINARY ?
                  AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH(?)
                  AND operation_id=? AND BINARY operation_id=BINARY ?
                  AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), OPERATION,
                concat(scopeArgs(scope), conversationId, conversationId, conversationId,
                        operationId, operationId, operationId)));
    }

    @Override
    public Source findAuthorizedSource(Scope scope, String conversationId, String assetId, long revision) {
        List<Source> rows = jdbc.query("""
                SELECT a.asset_id,a.revision,a.conversation_id,a.conversation_generation,
                       a.request_id,s.request_revision,a.step_id,s.task_id,a.execution_id,
                       a.run_id,a.output_id,a.content_mime_type,a.sha256,a.byte_length
                FROM chat_conversation_asset a
                JOIN chat_interaction_step s
                  ON BINARY s.tenant_id=BINARY a.tenant_id
                 AND BINARY s.owner_jiacn=BINARY a.owner_jiacn
                 AND BINARY s.client_id=BINARY a.client_id
                 AND BINARY s.step_id=BINARY a.step_id
                 AND BINARY s.request_id=BINARY a.request_id
                 AND BINARY s.conversation_id=BINARY a.conversation_id
                 AND s.conversation_generation=a.conversation_generation
                JOIN chat_step_execution_link l
                  ON BINARY l.tenant_id=BINARY a.tenant_id
                 AND BINARY l.owner_jiacn=BINARY a.owner_jiacn
                 AND BINARY l.client_id=BINARY a.client_id
                 AND BINARY l.step_id=BINARY a.step_id
                 AND BINARY l.execution_id=BINARY a.execution_id
                JOIN chat_request r
                  ON BINARY r.tenant_id=BINARY a.tenant_id
                 AND BINARY r.owner_jiacn=BINARY a.owner_jiacn
                 AND BINARY r.client_id=BINARY a.client_id
                 AND BINARY r.request_id=BINARY a.request_id
                 AND r.request_revision=s.request_revision
                 AND BINARY r.conversation_id=BINARY a.conversation_id
                 AND r.conversation_generation=a.conversation_generation
                JOIN chat_conversation c
                  ON c.id=CAST(a.conversation_id AS UNSIGNED)
                 AND BINARY CAST(c.id AS CHAR)=BINARY a.conversation_id
                 AND BINARY c.tenant_id=BINARY a.tenant_id
                 AND BINARY c.jiacn=BINARY a.owner_jiacn
                 AND BINARY c.client_id=BINARY a.client_id
                 AND c.lifecycle_generation=a.conversation_generation
                 AND c.deleted_at IS NULL
                WHERE BINARY a.tenant_id=BINARY ? AND OCTET_LENGTH(a.tenant_id)=OCTET_LENGTH(?)
                  AND BINARY a.owner_jiacn=BINARY ? AND OCTET_LENGTH(a.owner_jiacn)=OCTET_LENGTH(?)
                  AND BINARY a.client_id=BINARY ? AND OCTET_LENGTH(a.client_id)=OCTET_LENGTH(?)
                  AND a.conversation_id=? AND BINARY a.conversation_id=BINARY ?
                  AND OCTET_LENGTH(a.conversation_id)=OCTET_LENGTH(?)
                  AND a.asset_id=? AND BINARY a.asset_id=BINARY ?
                  AND OCTET_LENGTH(a.asset_id)=OCTET_LENGTH(?) AND a.revision=?
                  AND s.kind='EXECUTE' AND s.state='OUTPUT_COMMITTED'
                  AND l.state='RUNNING' AND l.execution_id IS NOT NULL
                  AND r.aggregate_state='OUTPUT_COMMITTED'
                  AND c.conversation_type='juyiting' AND c.conversation_scope_type='bounty'
                  AND BINARY c.task_id=BINARY s.task_id
                  AND BINARY c.conversation_scope_key=BINARY CONCAT('task:',s.task_id)
                """, (rs, ignored) -> new Source(rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getLong(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getString(13), rs.getLong(14)), scope.tenantId(), scope.tenantId(),
                scope.ownerJiacn(), scope.ownerJiacn(), scope.clientId(), scope.clientId(),
                conversationId, conversationId, conversationId, assetId, assetId, assetId, revision);
        return rows.size() == 1 ? rows.getFirst() : null;
    }

    @Override
    public int markSaving(Scope scope, String operationId, long expectedRevision,
            long conversationGeneration, long now) {
        return jdbc.update("UPDATE chat_conversation_archive_operation SET state='SAVING',"
                + "conversation_generation=?,workspace_operation_id=NULL,file_id=NULL,file_version=NULL,"
                + "error_code=NULL,message=NULL,row_revision=row_revision+1,updated_at=? WHERE "
                + EXACT_OPERATION_SCOPE + """
                  AND operation_id=? AND BINARY operation_id=BINARY ?
                  AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(?)
                  AND row_revision=? AND state IN ('PENDING','SAVING','PARTIAL_FAILED')
                """, join(new Object[]{conversationGeneration, now}, scopeArgs(scope),
                new Object[]{operationId, operationId, operationId, expectedRevision}));
    }

    @Override
    public int markSaved(Scope scope, String operationId, long expectedRevision,
            String workspaceOperationId, String fileId, int fileVersion, long now) {
        return jdbc.update("UPDATE chat_conversation_archive_operation SET state='SAVED',"
                + "workspace_operation_id=?,file_id=?,file_version=?,error_code=NULL,message=NULL,"
                + "row_revision=row_revision+1,updated_at=? WHERE " + EXACT_OPERATION_SCOPE + """
                  AND operation_id=? AND BINARY operation_id=BINARY ?
                  AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(?)
                  AND row_revision=? AND state='SAVING'
                """, join(new Object[]{workspaceOperationId, fileId, fileVersion, now},
                scopeArgs(scope), new Object[]{operationId, operationId, operationId, expectedRevision}));
    }

    @Override
    public int markPartialFailed(Scope scope, String operationId, long expectedRevision,
            String errorCode, String message, long now) {
        return jdbc.update("UPDATE chat_conversation_archive_operation SET state='PARTIAL_FAILED',"
                + "workspace_operation_id=NULL,file_id=NULL,file_version=NULL,error_code=?,message=?,"
                + "row_revision=row_revision+1,updated_at=? WHERE " + EXACT_OPERATION_SCOPE + """
                  AND operation_id=? AND BINARY operation_id=BINARY ?
                  AND OCTET_LENGTH(operation_id)=OCTET_LENGTH(?)
                  AND row_revision=? AND state<>'SAVED'
                """, join(new Object[]{errorCode, message, now}, scopeArgs(scope),
                new Object[]{operationId, operationId, operationId, expectedRevision}));
    }

    private static Object[] scopeArgs(Scope scope) {
        return new Object[]{scope.tenantId(), scope.ownerJiacn(), scope.clientId(),
                scope.tenantId(), scope.tenantId(), scope.ownerJiacn(), scope.ownerJiacn(),
                scope.clientId(), scope.clientId()};
    }

    private static Object[] concat(Object[] first, Object... second) {
        Object[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static Object[] join(Object[]... arrays) {
        int size = 0;
        for (Object[] array : arrays) size += array.length;
        Object[] result = new Object[size];
        int offset = 0;
        for (Object[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }

    private static <T> T first(List<T> rows) {
        return rows.size() == 1 ? rows.getFirst() : null;
    }
}
