package cn.jia.chat.dao.impl;

import cn.jia.chat.dao.ChatOutputDao;
import jakarta.inject.Named;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Named
public final class ChatOutputDaoImpl implements ChatOutputDao {
    private final JdbcTemplate jdbc;

    public ChatOutputDaoImpl(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Row findLatestForUpdate(String tenantId, String clientId, long conversationId,
            String outputId) {
        return one("""
                SELECT * FROM chat_output
                WHERE tenant_id=? AND client_id=? AND conversation_id=? AND output_id=?
                  AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(output_id AS BINARY)=CAST(? AS BINARY)
                ORDER BY output_version DESC LIMIT 1 FOR UPDATE
                """, tenantId, clientId, conversationId, outputId,
                tenantId, clientId, outputId);
    }

    @Override
    public int insert(Row r, long now) {
        return jdbc.update("""
                INSERT INTO chat_output
                (tenant_id,client_id,conversation_id,output_id,output_version,run_id,
                 producer_agent_id,title,file_name,artifact_type,content,object_id,content_hash,
                 content_byte_length,mime_type,state,retain_until,created_at,updated_at,row_version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
                """, r.tenantId(), r.clientId(), r.conversationId(), r.outputId(), r.version(),
                r.runId(), r.producerAgentId(), r.title(), r.fileName(), r.artifactType(),
                r.content(), r.objectId(), r.contentHash(), r.contentByteLength(), r.mimeType(),
                r.state(), r.retainUntil(), r.createdAt(), now);
    }

    @Override
    public Row findVersion(String tenantId, String clientId, long conversationId,
            String outputId, long version) {
        return one("""
                SELECT * FROM chat_output
                WHERE tenant_id=? AND client_id=? AND conversation_id=? AND output_id=?
                  AND output_version=? AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(output_id AS BINARY)=CAST(? AS BINARY)
                LIMIT 1
                """, tenantId, clientId, conversationId, outputId, version,
                tenantId, clientId, outputId);
    }

    @Override
    public List<Row> list(String tenantId, String clientId, long conversationId,
            String outputId, boolean latestOnly, long snapshotAt, Long afterCreatedAt,
            String afterOutputId, Long afterVersion, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.* FROM chat_output o
                WHERE o.tenant_id=? AND o.client_id=? AND o.conversation_id=?
                  AND o.created_at<=? AND o.state='AVAILABLE' AND o.retain_until>?
                """);
        List<Object> args = new ArrayList<>(List.of(
                tenantId, clientId, conversationId, snapshotAt, System.currentTimeMillis()));
        if (outputId != null) {
            sql.append(" AND o.output_id=? AND CAST(o.output_id AS BINARY)=CAST(? AS BINARY)");
            args.add(outputId); args.add(outputId);
        }
        if (latestOnly) {
            sql.append("""
                     AND NOT EXISTS (SELECT 1 FROM chat_output n
                       WHERE n.tenant_id=o.tenant_id AND n.client_id=o.client_id
                         AND n.conversation_id=o.conversation_id AND n.output_id=o.output_id
                         AND n.state='AVAILABLE' AND n.retain_until>?
                         AND n.created_at<=?
                         AND n.output_version>o.output_version)
                    """);
            args.add(System.currentTimeMillis()); args.add(snapshotAt);
        }
        if (afterCreatedAt != null) {
            sql.append("""
                     AND (o.created_at<? OR (o.created_at=? AND
                          (CAST(o.output_id AS BINARY)<CAST(? AS BINARY)
                           OR (CAST(o.output_id AS BINARY)=CAST(? AS BINARY)
                               AND o.output_version<?))))
                    """);
            args.add(afterCreatedAt); args.add(afterCreatedAt); args.add(afterOutputId);
            args.add(afterOutputId); args.add(afterVersion);
        }
        sql.append(" ORDER BY o.created_at DESC, CAST(o.output_id AS BINARY) DESC, o.output_version DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), ROW, args.toArray());
    }

    private Row one(String sql, Object... args) {
        List<Row> rows = jdbc.query(sql, ROW, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static final RowMapper<Row> ROW = (r, n) -> new Row(
            r.getString("tenant_id"), r.getString("client_id"), r.getLong("conversation_id"),
            r.getString("output_id"), r.getLong("output_version"), r.getString("run_id"),
            r.getString("producer_agent_id"), r.getString("title"), r.getString("file_name"),
            r.getString("artifact_type"), r.getString("content"), r.getString("object_id"),
            r.getBytes("content_hash"), r.getLong("content_byte_length"), r.getString("mime_type"),
            r.getString("state"), r.getLong("retain_until"), r.getLong("created_at"));
}
