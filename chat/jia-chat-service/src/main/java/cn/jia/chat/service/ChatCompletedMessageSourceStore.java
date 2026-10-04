package cn.jia.chat.service;

import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Existing message bytes only. No new snapshot table or generated text file. */
@Named
public class ChatCompletedMessageSourceStore {
    private final JdbcTemplate jdbc;
    public ChatCompletedMessageSourceStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    public record Message(String content, String metadata, String messageType, String senderType) { }

    public Message find(String tenant, String owner, String client, String taskId,
            String conversationId, long generation, long messageId) {
        List<Message> rows=jdbc.query("""
            SELECT m.content,m.metadata,m.message_type,m.sender_type
            FROM chat_message m JOIN chat_conversation c ON c.id=CAST(m.conversation_id AS UNSIGNED)
             AND BINARY CAST(c.id AS CHAR)=BINARY m.conversation_id
             AND BINARY c.tenant_id=BINARY m.tenant_id AND BINARY c.jiacn=BINARY m.jiacn
             AND BINARY c.client_id=BINARY m.client_id
            WHERE BINARY m.tenant_id=BINARY ? AND BINARY m.jiacn=BINARY ? AND BINARY m.client_id=BINARY ?
             AND BINARY m.conversation_id=BINARY ? AND m.id=? AND c.deleted_at IS NULL
             AND c.lifecycle_generation=? AND BINARY c.task_id=BINARY ?
             AND BINARY c.conversation_type=BINARY 'juyiting'
             AND BINARY c.conversation_scope_type=BINARY 'bounty'
             AND BINARY c.conversation_scope_key=BINARY ?
            """,(rs,n)->new Message(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4)),
            tenant,owner,client,conversationId,messageId,generation,taskId,"task:"+taskId);
        return rows.size()==1 ? rows.getFirst() : null;
    }
}
