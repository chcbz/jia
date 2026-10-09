package cn.jia.chat.deliberation;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** One durable, owner-scoped user-facing bounty conversation per task. Never a task-team thread. */
@Named
public class ChatBountyBindingStore {
    private final JdbcTemplate jdbc;
    public ChatBountyBindingStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }

    public record Scope(String tenantId,String ownerJiacn,String clientId) { }
    public record Binding(long assignmentRevision,Long conversationId) { }

    /** Transactional reserve: duplicate assignment actions lock the same task row. */
    public void reserve(Scope scope,String taskId,long assignmentRevision,long now) {
        jdbc.update("""
                INSERT INTO chat_bounty_binding
                  (tenant_id,owner_jiacn,client_id,task_id,conversation_id,assignment_revision,created_at,updated_at)
                VALUES (?,?,?,?,NULL,?,?,?)
                ON DUPLICATE KEY UPDATE task_id=task_id
                """,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId,assignmentRevision,now,now);
    }

    public Binding lock(Scope scope,String taskId) {
        List<Binding> rows=jdbc.query("""
                SELECT assignment_revision,conversation_id FROM chat_bounty_binding
                WHERE tenant_id=? AND owner_jiacn=? AND client_id=? AND task_id=? FOR UPDATE
                """,(rs,n)->new Binding(rs.getLong("assignment_revision"),
                rs.getObject("conversation_id",Long.class)),scope.tenantId(),scope.ownerJiacn(),
                scope.clientId(),taskId);
        if (rows.size()!=1) throw new IllegalStateException("Bounty binding is missing or duplicated");
        return rows.getFirst();
    }

    public int attach(Scope scope,String taskId,long assignmentRevision,long conversationId,long now) {
        return jdbc.update("""
                UPDATE chat_bounty_binding SET conversation_id=?,assignment_revision=?,updated_at=?
                WHERE tenant_id=? AND owner_jiacn=? AND client_id=? AND task_id=?
                  AND conversation_id IS NULL AND assignment_revision<=?
                """,conversationId,assignmentRevision,now,scope.tenantId(),scope.ownerJiacn(),
                scope.clientId(),taskId,assignmentRevision);
    }

    public int advance(Scope scope,String taskId,long previousRevision,long revision,long now) {
        return jdbc.update("""
                UPDATE chat_bounty_binding SET assignment_revision=?,updated_at=?
                WHERE tenant_id=? AND owner_jiacn=? AND client_id=? AND task_id=?
                  AND conversation_id IS NOT NULL AND assignment_revision=?
                """,revision,now,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId,previousRevision);
    }

    /** Adopt exactly one pre-existing user-facing bounty discussion, never internal team threads. */
    public List<Long> findExistingBountyConversationIds(Scope scope,String taskId) {
        return jdbc.queryForList("""
                SELECT id FROM chat_conversation
                WHERE tenant_id=? AND jiacn=? AND client_id=? AND task_id=?
                  AND conversation_type='juyiting' AND conversation_scope_type='bounty'
                  AND conversation_scope_key=CONCAT('task:',task_id) AND deleted_at IS NULL
                ORDER BY id LIMIT 2 FOR UPDATE
                """,Long.class,scope.tenantId(),scope.ownerJiacn(),scope.clientId(),taskId);
    }

    /** The target list changes on reassignment, never the task/conversation scope. */
    public int replaceAuthorizedTargets(Scope scope,String taskId,long conversationId,
            String targetIdsJson,long now) {
        return jdbc.update("""
                UPDATE chat_conversation SET target_agent_ids=?,update_time=?
                WHERE id=? AND tenant_id=? AND jiacn=? AND client_id=? AND task_id=?
                  AND conversation_type='juyiting' AND conversation_scope_type='bounty'
                  AND conversation_scope_key=CONCAT('task:',task_id) AND deleted_at IS NULL
                """,targetIdsJson,now,conversationId,scope.tenantId(),scope.ownerJiacn(),
                scope.clientId(),taskId);
    }
}
