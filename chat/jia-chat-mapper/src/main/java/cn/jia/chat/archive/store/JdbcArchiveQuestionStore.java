package cn.jia.chat.archive.store;

import cn.jia.chat.archive.model.ArchiveOwnerScope;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Named
public class JdbcArchiveQuestionStore implements ArchiveQuestionStore {
    private static final String EXACT_SCOPE = """
            tenant_id=? AND client_id=? AND owner_jiacn=?
            AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
            AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
            AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY)
            AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(?)
            AND OCTET_LENGTH(client_id)=OCTET_LENGTH(?)
            AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(?)
            """;
    private static final RowMapper<QuestionRecord> QUESTION = (rs, ignored) -> new QuestionRecord(
            rs.getLong("row_id"), rs.getString("question_id"), rs.getString("edition_id"),
            rs.getString("edition_manifest_sha256"), rs.getString("block_type"), rs.getString("block_id"),
            rs.getString("anchor_json"), rs.getString("selected_text"), rs.getString("question_text"),
            rs.getString("status"), rs.getString("responder_id"), rs.getString("responder_name"),
            rs.getString("responder_mode"), rs.getString("answer"), rs.getInt("retry_count"),
            rs.getString("last_error_code"), rs.getLong("version"), rs.getLong("current_sequence"),
            instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
            instant(rs.getTimestamp("completed_at")));
    private static final RowMapper<EventRecord> EVENT = (rs, ignored) -> new EventRecord(
            rs.getLong("row_id"), rs.getString("question_id"), rs.getLong("sequence"),
            rs.getString("event_type"), rs.getString("payload_json"), instant(rs.getTimestamp("occurred_at")));
    private static final RowMapper<OutboxRecord> OUTBOX = (rs, ignored) -> new OutboxRecord(
            rs.getLong("row_id"), rs.getString("question_id"), rs.getString("state"),
            rs.getInt("attempt_count"), rs.getLong("fencing_token"), rs.getLong("published_sequence"),
            instant(rs.getTimestamp("available_at")), instant(rs.getTimestamp("lease_until")),
            rs.getString("last_error_code"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public JdbcArchiveQuestionStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public MutationRecord insertOrLockMutation(ArchiveOwnerScope owner, String questionId, String method,
                                               String path, String key, String hash, Instant expiresAt) {
        boolean inserted = false;
        try {
            jdbc.update("""
                    INSERT INTO archive_question_mutation
                    (tenant_id,client_id,owner_jiacn,question_id,http_method,canonical_path,
                     idempotency_key,request_sha256,state,expires_at)
                    VALUES (?,?,?,?,?,?,?,?,'PENDING',?)
                    """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), questionId,
                    method, path, key, hash, Timestamp.from(expiresAt));
            inserted = true;
        } catch (DuplicateKeyException ignored) {
            // Exact mutation row is locked below. Concurrent requests wait for commit/rollback.
        }
        boolean created = inserted;
        return first(jdbc.query("""
                SELECT row_id,question_id,request_sha256,state,question_row_id,response_status,
                       response_content_type,response_body
                FROM archive_question_mutation WHERE
                """ + EXACT_SCOPE + """
                  AND http_method=? AND canonical_path=? AND idempotency_key=?
                  AND CAST(http_method AS BINARY)=CAST(? AS BINARY)
                  AND CAST(canonical_path AS BINARY)=CAST(? AS BINARY)
                  AND CAST(idempotency_key AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(http_method)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(canonical_path)=OCTET_LENGTH(?)
                  AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(?)
                FOR UPDATE
                """, (rs, ignored) -> {
                    long questionRow = rs.getLong("question_row_id");
                    Long nullableQuestionRow = rs.wasNull() ? null : questionRow;
                    int status = rs.getInt("response_status");
                    Integer nullableStatus = rs.wasNull() ? null : status;
                    return new MutationRecord(rs.getLong("row_id"), created, rs.getString("question_id"),
                            rs.getString("request_sha256"), rs.getString("state"), nullableQuestionRow,
                            nullableStatus, rs.getString("response_content_type"), rs.getBytes("response_body"));
                }, concat(scopeArgs(owner), method, path, key, method, path, key, method, path, key)));
    }

    @Override
    public int completeMutation(ArchiveOwnerScope owner, long rowId, String hash, long questionRowId,
                                int status, String contentType, byte[] body) {
        return jdbc.update("""
                UPDATE archive_question_mutation
                SET state='COMPLETED',question_row_id=?,response_status=?,response_content_type=?,response_body=?
                WHERE
                """ + EXACT_SCOPE + """
                  AND row_id=? AND request_sha256=? AND state='PENDING'
                """, concat(new Object[]{questionRowId, status, contentType, body},
                scopeArgs(owner), rowId, hash));
    }

    @Override
    public long countRecentQuestions(ArchiveOwnerScope owner, Instant since) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM archive_question WHERE " + EXACT_SCOPE
                + " AND created_at>=?", Long.class, concat(scopeArgs(owner), Timestamp.from(since)));
        return count == null ? 0 : count;
    }

    @Override
    public QuestionRecord findQuestion(ArchiveOwnerScope owner, String questionId, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_question WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), QUESTION,
                concat(scopeArgs(owner), questionId, questionId, questionId)));
    }

    @Override
    public long insertQuestion(ArchiveOwnerScope owner, QuestionRecord row) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO archive_question
                        (tenant_id,client_id,owner_jiacn,question_id,edition_id,edition_manifest_sha256,
                         block_type,block_id,anchor_json,selected_text,question_text,status,responder_id,
                         responder_name,responder_mode,answer,retry_count,last_error_code,version,current_sequence,
                         created_at,updated_at,completed_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, Statement.RETURN_GENERATED_KEYS);
                Object[] values = {owner.tenantId(), owner.clientId(), owner.ownerJiacn(), row.questionId(),
                        row.editionId(), row.manifestSha256(), row.blockType(), row.blockId(), row.anchorJson(),
                        row.selectedText(), row.questionText(), row.status(), row.responderId(), row.responderName(),
                        row.responderMode(), row.answer(), row.retryCount(), row.lastErrorCode(), row.version(),
                        row.currentSequence(), timestamp(row.createdAt()), timestamp(row.updatedAt()),
                        timestamp(row.completedAt())};
                for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
                return statement;
            }, keys);
        } catch (DuplicateKeyException duplicate) {
            // A different idempotency key may race to create the same exact-scoped question.
            return 0;
        }
        Number key = keys.getKey();
        if (key == null) throw new IllegalStateException("Archive question generated key is missing");
        return key.longValue();
    }

    @Override
    public int updateQuestion(ArchiveOwnerScope owner, QuestionRecord row,
                              long expectedVersion, long expectedSequence) {
        return jdbc.update("""
                UPDATE archive_question SET status=?,answer=?,retry_count=?,last_error_code=?,version=?,
                  current_sequence=?,updated_at=?,completed_at=? WHERE
                """ + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                  AND version=? AND current_sequence=?
                """, concat(new Object[]{row.status(), row.answer(), row.retryCount(), row.lastErrorCode(),
                        row.version(), row.currentSequence(), timestamp(row.updatedAt()),
                        timestamp(row.completedAt())}, scopeArgs(owner), row.questionId(), row.questionId(),
                        row.questionId(), expectedVersion, expectedSequence));
    }

    @Override
    public void insertEvent(ArchiveOwnerScope owner, EventRecord event) {
        jdbc.update("""
                INSERT INTO archive_question_event
                (tenant_id,client_id,owner_jiacn,question_id,sequence,event_type,payload_json,occurred_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), event.questionId(),
                event.sequence(), event.eventType(), event.payloadJson(), Timestamp.from(event.occurredAt()));
    }

    @Override
    public List<EventRecord> listEvents(ArchiveOwnerScope owner, String questionId,
                                        long after, long through, int limit) {
        return jdbc.query("SELECT * FROM archive_question_event WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                  AND sequence>? AND sequence<=? ORDER BY sequence LIMIT ?
                """, EVENT, concat(scopeArgs(owner), questionId, questionId, questionId, after, through, limit));
    }

    @Override
    public Long earliestEventSequence(ArchiveOwnerScope owner, String questionId) {
        return jdbc.queryForObject("SELECT MIN(sequence) FROM archive_question_event WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                """, Long.class, concat(scopeArgs(owner), questionId, questionId, questionId));
    }

    @Override
    public void insertOutbox(ArchiveOwnerScope owner, OutboxRecord row) {
        jdbc.update("""
                INSERT INTO archive_outbox
                (tenant_id,client_id,owner_jiacn,question_id,state,attempt_count,fencing_token,
                 published_sequence,available_at,lease_until,last_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, owner.tenantId(), owner.clientId(), owner.ownerJiacn(), row.questionId(), row.state(),
                row.attemptCount(), row.fencingToken(), row.publishedSequence(), timestamp(row.availableAt()),
                timestamp(row.leaseUntil()), row.lastErrorCode(), timestamp(row.createdAt()), timestamp(row.updatedAt()));
    }

    @Override
    public OutboxRecord findOutbox(ArchiveOwnerScope owner, String questionId, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_outbox WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                """ + (lock ? " FOR UPDATE" : ""), OUTBOX,
                concat(scopeArgs(owner), questionId, questionId, questionId)));
    }

    @Override
    public int updateOutbox(ArchiveOwnerScope owner, OutboxRecord row,
                            long expectedToken, String expectedState) {
        return jdbc.update("""
                UPDATE archive_outbox SET state=?,attempt_count=?,fencing_token=?,published_sequence=?,
                  available_at=?,lease_until=?,last_error_code=?,updated_at=? WHERE
                """ + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                  AND fencing_token=? AND state=?
                """, concat(new Object[]{row.state(), row.attemptCount(), row.fencingToken(),
                        row.publishedSequence(), timestamp(row.availableAt()), timestamp(row.leaseUntil()),
                        row.lastErrorCode(), timestamp(row.updatedAt())}, scopeArgs(owner), row.questionId(),
                        row.questionId(), row.questionId(), expectedToken, expectedState));
    }

    @Override
    public int renewOutboxLease(ArchiveOwnerScope owner, String questionId, long fencingToken,
                                Instant leaseUntil, Instant updatedAt) {
        return jdbc.update("UPDATE archive_outbox SET lease_until=?,updated_at=? WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?)
                  AND fencing_token=? AND state='LEASED' AND lease_until>?
                """, concat(new Object[]{Timestamp.from(leaseUntil), Timestamp.from(updatedAt)}, scopeArgs(owner),
                questionId, questionId, questionId, fencingToken, Timestamp.from(updatedAt)));
    }

    @Override
    public List<ClaimCandidate> listClaimCandidates(Instant now, Instant afterCandidateAt,
                                                    long afterRowId, int limit) {
        Timestamp cursor = timestamp(afterCandidateAt);
        return jdbc.query("""
                SELECT tenant_id,client_id,owner_jiacn,question_id,row_id,
                       CASE WHEN state='READY' THEN available_at ELSE lease_until END AS candidate_at
                FROM archive_outbox
                WHERE attempt_count<3
                  AND ((state='READY' AND available_at<=?)
                       OR (state='LEASED' AND lease_until<=?))
                  AND (? IS NULL
                       OR (CASE WHEN state='READY' THEN available_at ELSE lease_until END)>?
                       OR ((CASE WHEN state='READY' THEN available_at ELSE lease_until END)=? AND row_id>?))
                ORDER BY candidate_at,row_id LIMIT ?
                """, (rs, ignored) -> new ClaimCandidate(new ArchiveOwnerScope(
                        rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("owner_jiacn")),
                        rs.getString("question_id"), instant(rs.getTimestamp("candidate_at")),
                        rs.getLong("row_id")), Timestamp.from(now), Timestamp.from(now), cursor, cursor,
                cursor, afterRowId, limit);
    }

    @Override
    public List<ClaimCandidate> listExhaustedCandidates(Instant now, Instant afterLeaseUntil,
                                                        long afterRowId, int limit) {
        Timestamp cursor = timestamp(afterLeaseUntil);
        return jdbc.query("""
                SELECT tenant_id,client_id,owner_jiacn,question_id,row_id,lease_until AS candidate_at
                FROM archive_outbox
                WHERE state='LEASED' AND attempt_count>=3 AND lease_until<=?
                  AND (? IS NULL OR lease_until>? OR (lease_until=? AND row_id>?))
                ORDER BY lease_until,row_id LIMIT ?
                """, (rs, ignored) -> new ClaimCandidate(new ArchiveOwnerScope(
                        rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("owner_jiacn")),
                        rs.getString("question_id"), instant(rs.getTimestamp("candidate_at")),
                        rs.getLong("row_id")), Timestamp.from(now), cursor, cursor, cursor, afterRowId, limit);
    }

    @Override
    public List<PublishCandidate> findPublishCandidates(long afterRowId, int limit) {
        return jdbc.query("""
                SELECT o.tenant_id,o.client_id,o.owner_jiacn,o.question_id,o.row_id,
                       o.published_sequence,q.current_sequence
                FROM archive_outbox o JOIN archive_question q
                  ON q.tenant_id=o.tenant_id AND q.client_id=o.client_id
                 AND q.owner_jiacn=o.owner_jiacn AND q.question_id=o.question_id
                WHERE o.published_sequence<q.current_sequence AND o.row_id>?
                ORDER BY o.row_id LIMIT ?
                """, (rs, ignored) -> new PublishCandidate(new ArchiveOwnerScope(
                        rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("owner_jiacn")),
                        rs.getString("question_id"), rs.getLong("row_id"),
                        rs.getLong("published_sequence"), rs.getLong("current_sequence")), afterRowId, limit);
    }

    @Override
    public int advancePublishedSequence(ArchiveOwnerScope owner, String questionId, long expected, long delivered) {
        return jdbc.update("UPDATE archive_outbox SET published_sequence=? WHERE " + EXACT_SCOPE + """
                  AND question_id=? AND CAST(question_id AS BINARY)=CAST(? AS BINARY)
                  AND OCTET_LENGTH(question_id)=OCTET_LENGTH(?) AND published_sequence=?
                """, concat(new Object[]{delivered}, scopeArgs(owner), questionId, questionId, questionId, expected));
    }

    private static Object[] scopeArgs(ArchiveOwnerScope owner) {
        return new Object[]{owner.tenantId(), owner.clientId(), owner.ownerJiacn(),
                owner.tenantId(), owner.clientId(), owner.ownerJiacn(),
                owner.tenantId(), owner.clientId(), owner.ownerJiacn()};
    }
    private static Object[] concat(Object[] first, Object... rest) {
        Object[] result = new Object[first.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }
    private static Object[] concat(Object[] first, Object[] second, Object... rest) {
        Object[] result = new Object[first.length + second.length + rest.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        System.arraycopy(rest, 0, result, first.length + second.length, rest.length);
        return result;
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static <T> T first(List<T> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
}
