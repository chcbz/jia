package cn.jia.chat.deliberation;

import cn.jia.chat.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/** Dispatch-time history reads under the existing conversation lock; not a history replica. */
@Mapper
public interface ChatContextHistoryMapper {
    String REQUEST_SCOPE = """
            r.tenant_id=#{turn.tenantId} AND r.owner_jiacn=#{turn.ownerJiacn}
            AND r.client_id=#{turn.clientId} AND r.conversation_id=#{turn.conversationId}
            AND r.conversation_generation=#{turn.conversationGeneration}
            AND CAST(r.tenant_id AS BINARY)=CAST(#{turn.tenantId} AS BINARY)
            AND CAST(r.owner_jiacn AS BINARY)=CAST(#{turn.ownerJiacn} AS BINARY)
            AND CAST(r.client_id AS BINARY)=CAST(#{turn.clientId} AS BINARY)
            AND CAST(r.conversation_id AS BINARY)=CAST(#{turn.conversationId} AS BINARY)
            """;
    String MESSAGE_SCOPE = """
            m.tenant_id=#{turn.tenantId} AND m.jiacn=#{turn.ownerJiacn}
            AND m.client_id=#{turn.clientId} AND m.conversation_id=#{turn.conversationId}
            AND CAST(m.tenant_id AS BINARY)=CAST(#{turn.tenantId} AS BINARY)
            AND CAST(m.jiacn AS BINARY)=CAST(#{turn.ownerJiacn} AS BINARY)
            AND CAST(m.client_id AS BINARY)=CAST(#{turn.clientId} AS BINARY)
            AND CAST(m.conversation_id AS BINARY)=CAST(#{turn.conversationId} AS BINARY)
            """;
    String LANE_FROM = """
            FROM chat_request r JOIN chat_turn t
              ON t.tenant_id=r.tenant_id AND t.owner_jiacn=r.owner_jiacn AND t.client_id=r.client_id
              AND t.request_id=r.request_id AND t.request_revision=r.request_revision
              AND t.conversation_id=r.conversation_id AND t.conversation_generation=r.conversation_generation
            """;
    String LANE_WHERE = "WHERE " + REQUEST_SCOPE + """
              AND t.target_agent_id=#{turn.targetAgentId}
              AND CAST(t.target_agent_id AS BINARY)=CAST(#{turn.targetAgentId} AS BINARY)
              AND r.id < #{requestSequence}
            """;

    String LANE_JOIN = LANE_FROM + LANE_WHERE;

    record PreviousContext(String turnId, long requestSequence, String state, Long finalMessageId, String finalMetadata) { }

    @org.apache.ibatis.annotations.ConstructorArgs({
        @org.apache.ibatis.annotations.Arg(column="turn_id", javaType=String.class),
        @org.apache.ibatis.annotations.Arg(column="request_sequence", javaType=long.class),
        @org.apache.ibatis.annotations.Arg(column="state", javaType=String.class),
        @org.apache.ibatis.annotations.Arg(column="final_message_id", javaType=Long.class),
        @org.apache.ibatis.annotations.Arg(column="final_metadata", javaType=String.class)
    })
    @Select("""
            SELECT t.turn_id,r.id AS request_sequence,t.state,t.final_message_id,m.metadata AS final_metadata
            """ + LANE_FROM + """
            LEFT JOIN chat_message m ON m.id=t.final_message_id AND m.tenant_id=t.tenant_id
              AND m.jiacn=t.owner_jiacn AND m.client_id=t.client_id AND m.conversation_id=t.conversation_id
            """ + LANE_WHERE + " ORDER BY r.id DESC LIMIT 1")
    PreviousContext previousContext(@Param("turn") ChatTurnEntity turn, @Param("requestSequence") long requestSequence);

    @Select("SELECT EXISTS (SELECT 1 " + LANE_JOIN + " AND t.state NOT IN ('FINAL_PERSISTED','PUBLISHED','CANCELLED','FAILED'))")
    boolean hasUnfinishedPredecessor(@Param("turn") ChatTurnEntity turn, @Param("requestSequence") long requestSequence);

    @Select("SELECT COALESCE(MAX(m.id),0) FROM chat_message m WHERE " + MESSAGE_SCOPE)
    long messageHighWatermark(@Param("turn") ChatTurnEntity turn);

    /**
     * Two existing orders, not two copies: admission order selects requested USER input;
     * message order selects late replies/external facts. UNION deduplicates a reused USER ID.
     * Current USER is always delivered separately, even for an action continuing an old input.
     * Only the exact predecessor final already present in the native thread may be omitted.
     */
    @Select("""
            SELECT m.* FROM chat_message m JOIN (
              SELECT r.user_message_id AS id FROM chat_request r
              WHERE
            """ + REQUEST_SCOPE + """
                AND r.id > #{afterRequestSequence} AND r.id <= #{throughRequestSequence}
              UNION
              SELECT m.id FROM chat_message m
              WHERE
            """ + MESSAGE_SCOPE + """
                AND m.id > #{afterMessageId} AND m.id <= #{throughMessageId}
                AND NOT EXISTS (SELECT 1 FROM chat_request r WHERE
            """ + REQUEST_SCOPE + """
                    AND r.user_message_id=m.id)
            ) eligible ON eligible.id=m.id
            WHERE
            """ + MESSAGE_SCOPE + """
              AND m.id != #{currentUserMessageId} AND m.id != #{nativeFinalMessageId}
            ORDER BY m.id DESC LIMIT #{limit}
            """)
    List<ChatMessageEntity> historyCandidates(@Param("turn") ChatTurnEntity turn,
            @Param("afterRequestSequence") long afterRequestSequence,
            @Param("throughRequestSequence") long throughRequestSequence,
            @Param("afterMessageId") long afterMessageId, @Param("throughMessageId") long throughMessageId,
            @Param("currentUserMessageId") long currentUserMessageId,
            @Param("nativeFinalMessageId") long nativeFinalMessageId, @Param("limit") int limit);

    /** Bootstrap keeps the existing latest-message index walk instead of materializing all request IDs. */
    @Select("""
            SELECT m.* FROM chat_message m WHERE
            """ + MESSAGE_SCOPE + """
              AND m.id <= #{throughMessageId} AND m.id != #{currentUserMessageId}
              AND (NOT EXISTS (SELECT 1 FROM chat_request r WHERE
            """ + REQUEST_SCOPE + """
                    AND r.user_message_id=m.id)
                OR EXISTS (SELECT 1 FROM chat_request r WHERE
            """ + REQUEST_SCOPE + """
                    AND r.user_message_id=m.id AND r.id <= #{throughRequestSequence}))
            ORDER BY m.id DESC LIMIT #{limit}
            """)
    List<ChatMessageEntity> bootstrapCandidates(@Param("turn") ChatTurnEntity turn,
            @Param("throughRequestSequence") long throughRequestSequence,
            @Param("throughMessageId") long throughMessageId,
            @Param("currentUserMessageId") long currentUserMessageId, @Param("limit") int limit);

    /** The draft retains its ID; its input is frozen exactly once before any send. */
    @Update("""
            UPDATE chat_context_snapshot SET source_vector_json=#{snapshot.sourceVectorJson},
              facts_manifest_json=#{snapshot.factsManifestJson},context_digest=#{snapshot.contextDigest}
            WHERE snapshot_id=#{snapshot.snapshotId} AND context_digest=#{expectedDigest}
              AND tenant_id=#{snapshot.tenantId} AND owner_jiacn=#{snapshot.ownerJiacn} AND client_id=#{snapshot.clientId}
            """)
    int freezeSnapshot(@Param("snapshot") ChatContextSnapshotEntity snapshot, @Param("expectedDigest") String expectedDigest);

    @Update("""
            UPDATE chat_turn SET context_digest=#{contextDigest}
            WHERE turn_id=#{turn.turnId} AND snapshot_id=#{turn.snapshotId} AND context_digest=#{turn.contextDigest}
              AND tenant_id=#{turn.tenantId} AND owner_jiacn=#{turn.ownerJiacn} AND client_id=#{turn.clientId}
              AND state='RECEIVED' AND final_message_id IS NULL
            """)
    int freezeTurnContext(@Param("turn") ChatTurnEntity turn, @Param("contextDigest") String contextDigest);

    @Update("""
            UPDATE chat_dispatch_outbox SET payload_json=#{payload},version=version+1,updated_at=#{now}
            WHERE event_id=#{row.eventId} AND tenant_id=#{row.tenantId}
              AND owner_jiacn=#{row.ownerJiacn} AND client_id=#{row.clientId}
              AND version=#{row.version} AND status IN ('READY','RETRY')
              AND event_type='DISPATCH' AND sent_at IS NULL
            """)
    int freezeOutboxContext(@Param("row") ChatDispatchOutboxEntity row, @Param("payload") String payload,
            @Param("now") long now);
    /** A reported pre-turn rejection retires this immutable attempt, including any stale sender lease. */
    @Update("""
            UPDATE chat_dispatch_outbox SET status='DEAD',last_error='CHAT_CONTEXT_BOOTSTRAP_REQUIRED',
              lease_owner=NULL,lease_until=NULL,available_at=NULL,version=version+1,updated_at=#{now}
            WHERE event_id=#{row.eventId} AND dispatch_id=#{row.dispatchId}
              AND tenant_id=#{row.tenantId} AND owner_jiacn=#{row.ownerJiacn} AND client_id=#{row.clientId}
              AND version=#{row.version} AND event_type='DISPATCH' AND status!='DEAD'
            """)
    int rejectUnexecutedDispatch(@Param("row") ChatDispatchOutboxEntity row, @Param("now") long now);

    @Update("""
            UPDATE chat_turn SET dispatch_id=#{dispatchId},snapshot_id=#{snapshotId},context_digest=#{contextDigest},
              state='RECEIVED',terminal_reason=NULL,state_version=state_version+1,updated_at=#{now}
            WHERE turn_id=#{turn.turnId} AND tenant_id=#{turn.tenantId}
              AND owner_jiacn=#{turn.ownerJiacn} AND client_id=#{turn.clientId}
              AND dispatch_id=#{turn.dispatchId} AND state_version=#{turn.stateVersion}
              AND state IN ('RECEIVED','QUEUED','DISPATCHED') AND final_message_id IS NULL AND last_delta_seq=0
            """)
    int replaceUnexecutedDispatch(@Param("turn") ChatTurnEntity turn, @Param("dispatchId") String dispatchId,
            @Param("snapshotId") String snapshotId, @Param("contextDigest") String contextDigest, @Param("now") long now);

}
