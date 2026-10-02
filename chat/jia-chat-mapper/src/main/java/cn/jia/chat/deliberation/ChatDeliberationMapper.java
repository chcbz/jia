package cn.jia.chat.deliberation;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ChatDeliberationMapper {
    String EXACT_SCOPE = """
              tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND CAST(tenant_id AS BINARY)=CAST(#{tenantId} AS BINARY)
              AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND OCTET_LENGTH(owner_jiacn)=OCTET_LENGTH(#{ownerJiacn})
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
              AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})
            """;

    @Insert("""
            INSERT INTO chat_context_snapshot
              (snapshot_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
               request_id,request_revision,target_agent_id,route,source_vector_json,facts_manifest_json,
               context_digest,created_at)
            VALUES
              (#{snapshotId},#{tenantId},#{ownerJiacn},#{clientId},#{conversationId},#{conversationGeneration},
               #{requestId},#{requestRevision},#{targetAgentId},#{route},#{sourceVectorJson},#{factsManifestJson},
               #{contextDigest},#{createdAt})
            """)
    int insertSnapshot(ChatContextSnapshotEntity entity);

    @Insert("""
            INSERT INTO chat_request
              (tenant_id,owner_jiacn,client_id,request_id,request_revision,request_digest,
               conversation_id,conversation_generation,user_message_id,aggregate_state,state_version,
               created_at,updated_at)
            VALUES
              (#{tenantId},#{ownerJiacn},#{clientId},#{requestId},#{requestRevision},#{requestDigest},
               #{conversationId},#{conversationGeneration},#{userMessageId},#{aggregateState},#{stateVersion},
               #{createdAt},#{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRequest(ChatRequestEntity entity);

    @Insert("""
            INSERT INTO chat_turn
              (turn_id,tenant_id,owner_jiacn,client_id,request_id,request_revision,conversation_id,
               conversation_generation,target_agent_id,snapshot_id,context_digest,dispatch_id,route,state,
               state_version,last_delta_seq,last_delta_digest,final_digest,final_message_id,terminal_reason,created_at,updated_at)
            VALUES
              (#{turnId},#{tenantId},#{ownerJiacn},#{clientId},#{requestId},#{requestRevision},#{conversationId},
               #{conversationGeneration},#{targetAgentId},#{snapshotId},#{contextDigest},#{dispatchId},#{route},#{state},
               #{stateVersion},#{lastDeltaSeq},#{lastDeltaDigest},#{finalDigest},#{finalMessageId},#{terminalReason},#{createdAt},#{updatedAt})
            """)
    int insertTurn(ChatTurnEntity entity);

    @Insert("""
            INSERT INTO chat_dispatch_outbox
              (event_id,tenant_id,owner_jiacn,client_id,turn_id,dispatch_id,event_type,status,payload_json,
               version,available_at,lease_owner,lease_until,attempt_count,fencing_token,last_error,sent_at,created_at,updated_at)
            VALUES
              (#{eventId},#{tenantId},#{ownerJiacn},#{clientId},#{turnId},#{dispatchId},#{eventType},#{status},
               #{payloadJson},#{version},#{availableAt},#{leaseOwner},#{leaseUntil},#{attemptCount},#{fencingToken},
               #{lastError},#{sentAt},#{createdAt},#{updatedAt})
            """)
    int insertOutbox(ChatDispatchOutboxEntity entity);

    @Insert("""
            INSERT INTO chat_conversation_event
              (event_id,tenant_id,owner_jiacn,client_id,conversation_id,conversation_generation,
               request_id,turn_id,dispatch_id,event_type,event_version,payload_json,occurred_at)
            VALUES
              (#{eventId},#{tenantId},#{ownerJiacn},#{clientId},#{conversationId},#{conversationGeneration},
               #{requestId},#{turnId},#{dispatchId},#{eventType},0,#{payloadJson},#{occurredAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "eventSequence")
    int insertEvent(ChatConversationEventEntity entity);

    @Update("UPDATE chat_conversation_event SET event_version=event_sequence WHERE event_sequence=#{eventSequence} AND event_version=0")
    int assignEventVersion(@Param("eventSequence") long eventSequence);

    @Select("""
            SELECT * FROM chat_request WHERE
            """ + EXACT_SCOPE + """
              AND request_id=#{requestId} AND request_revision=#{requestRevision}
              AND CAST(request_id AS BINARY)=CAST(#{requestId} AS BINARY)
              AND OCTET_LENGTH(request_id)=OCTET_LENGTH(#{requestId})
            LIMIT 1 FOR UPDATE
            """)
    ChatRequestEntity lockRequest(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("requestId") String requestId, @Param("requestRevision") long requestRevision);

    @Select("""
            SELECT * FROM chat_request WHERE
            """ + EXACT_SCOPE + """
              AND request_id=#{requestId}
              AND CAST(request_id AS BINARY)=CAST(#{requestId} AS BINARY)
              AND OCTET_LENGTH(request_id)=OCTET_LENGTH(#{requestId})
            ORDER BY request_revision DESC LIMIT 1
            """)
    ChatRequestEntity findRequest(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("requestId") String requestId);

    @Select("""
            SELECT * FROM chat_turn WHERE
            """ + EXACT_SCOPE + """
              AND request_id=#{requestId}
              AND CAST(request_id AS BINARY)=CAST(#{requestId} AS BINARY)
              AND OCTET_LENGTH(request_id)=OCTET_LENGTH(#{requestId})
            ORDER BY created_at, target_agent_id
            """)
    List<ChatTurnEntity> findTurnsByRequest(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("requestId") String requestId);

    @Select("""
            SELECT * FROM chat_turn WHERE
            """ + EXACT_SCOPE + """
              AND turn_id=#{turnId}
              AND CAST(turn_id AS BINARY)=CAST(#{turnId} AS BINARY)
              AND OCTET_LENGTH(turn_id)=OCTET_LENGTH(#{turnId})
            LIMIT 1
            """)
    ChatTurnEntity findTurn(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("turnId") String turnId);

    @Select("""
            SELECT * FROM chat_turn WHERE
            """ + EXACT_SCOPE + """
              AND turn_id=#{turnId}
              AND CAST(turn_id AS BINARY)=CAST(#{turnId} AS BINARY)
              AND OCTET_LENGTH(turn_id)=OCTET_LENGTH(#{turnId})
            LIMIT 1 FOR UPDATE
            """)
    ChatTurnEntity lockTurn(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("turnId") String turnId);

    @Select("""
            SELECT * FROM chat_context_snapshot WHERE snapshot_id=#{snapshotId}
              AND tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND CAST(snapshot_id AS BINARY)=CAST(#{snapshotId} AS BINARY)
              AND OCTET_LENGTH(snapshot_id)=OCTET_LENGTH(#{snapshotId})
              AND CAST(owner_jiacn AS BINARY)=CAST(#{ownerJiacn} AS BINARY)
              AND CAST(client_id AS BINARY)=CAST(#{clientId} AS BINARY)
            LIMIT 1
            """)
    ChatContextSnapshotEntity findSnapshot(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("snapshotId") String snapshotId);


    @Select("""
            SELECT * FROM chat_dispatch_outbox WHERE
            """ + EXACT_SCOPE + """
              AND turn_id=#{turnId} AND event_type=#{eventType}
              AND CAST(turn_id AS BINARY)=CAST(#{turnId} AS BINARY)
              AND CAST(event_type AS BINARY)=CAST(#{eventType} AS BINARY)
            LIMIT 1 FOR UPDATE
            """)
    ChatDispatchOutboxEntity lockOutbox(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("turnId") String turnId, @Param("eventType") String eventType);

    @Select("""
            SELECT * FROM chat_dispatch_outbox WHERE
            """ + EXACT_SCOPE + """
            AND event_id=#{eventId}
            AND CAST(event_id AS BINARY)=CAST(#{eventId} AS BINARY)
            AND OCTET_LENGTH(event_id)=OCTET_LENGTH(#{eventId}) LIMIT 1 FOR UPDATE
            """)
    ChatDispatchOutboxEntity lockOutboxById(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("eventId") String eventId);

    @Select("""
            SELECT * FROM chat_dispatch_outbox WHERE
            """ + EXACT_SCOPE + """
              AND dispatch_id=#{dispatchId} AND event_type='DISPATCH'
              AND CAST(dispatch_id AS BINARY)=CAST(#{dispatchId} AS BINARY)
              AND OCTET_LENGTH(dispatch_id)=OCTET_LENGTH(#{dispatchId}) LIMIT 1 FOR UPDATE
            """)
    ChatDispatchOutboxEntity lockOutboxByDispatch(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("dispatchId") String dispatchId);

    @Select("""
            SELECT * FROM chat_dispatch_outbox
            WHERE ((status IN ('READY','RETRY','AWAITING_ACK') AND available_at<=#{now})
                OR (status='CLAIMED' AND lease_until<=#{now}))
            ORDER BY available_at,event_id LIMIT #{limit}
            """)
    List<ChatDispatchOutboxEntity> findDueOutbox(@Param("now") long now, @Param("limit") int limit);

    @Select("""
            SELECT * FROM chat_conversation_event WHERE
            """ + EXACT_SCOPE + """
              AND conversation_id=#{conversationId} AND conversation_generation=#{generation}
              AND event_sequence>#{afterSequence} AND event_sequence<=#{throughSequence}
              AND CAST(conversation_id AS BINARY)=CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH(#{conversationId})
            ORDER BY event_sequence LIMIT #{limit}
            """)
    List<ChatConversationEventEntity> replayEvents(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("conversationId") String conversationId, @Param("generation") long generation,
            @Param("afterSequence") long afterSequence, @Param("throughSequence") long throughSequence,
            @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(MAX(event_sequence),0) FROM chat_conversation_event WHERE
            """ + EXACT_SCOPE + """
              AND conversation_id=#{conversationId} AND conversation_generation=#{generation}
              AND CAST(conversation_id AS BINARY)=CAST(#{conversationId} AS BINARY)
              AND OCTET_LENGTH(conversation_id)=OCTET_LENGTH(#{conversationId})
            """)
    Long eventHighWatermark(@Param("tenantId") String tenantId,
            @Param("ownerJiacn") String ownerJiacn, @Param("clientId") String clientId,
            @Param("conversationId") String conversationId, @Param("generation") long generation);

    @Update("""
            UPDATE chat_turn SET state=#{state}, state_version=state_version+1,
              last_delta_seq=#{deltaSeq}, last_delta_digest=#{deltaDigest}, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND turn_id=#{turnId} AND state_version=#{stateVersion}
              AND state NOT IN ('FINAL_PERSISTED','PUBLISHED','FAILED','CANCELLED')
            """)
    int acceptDelta(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("turnId") String turnId, @Param("stateVersion") long stateVersion,
            @Param("state") String state, @Param("deltaSeq") long deltaSeq,
            @Param("deltaDigest") String deltaDigest, @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_turn SET state='FINAL_PERSISTED', state_version=state_version+1,
              final_digest=#{finalDigest}, final_message_id=#{finalMessageId}, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND turn_id=#{turnId} AND state_version=#{stateVersion}
              AND final_digest IS NULL AND final_message_id IS NULL
              AND state NOT IN ('FAILED','CANCELLED')
            """)
    int persistFinal(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("turnId") String turnId, @Param("stateVersion") long stateVersion,
            @Param("finalDigest") String finalDigest, @Param("finalMessageId") long finalMessageId,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_turn SET state=#{state}, state_version=state_version+1,
              terminal_reason=#{terminalReason}, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND turn_id=#{turnId} AND state_version=#{stateVersion}
              AND state NOT IN ('FINAL_PERSISTED','PUBLISHED','FAILED','CANCELLED')
            """)
    int updateTurnState(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("turnId") String turnId, @Param("stateVersion") long stateVersion,
            @Param("state") String state, @Param("terminalReason") String terminalReason,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_turn SET state='PUBLISHED', state_version=state_version+1, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND turn_id=#{turnId} AND state_version=#{stateVersion} AND state='FINAL_PERSISTED'
            """)
    int publishFinal(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("turnId") String turnId, @Param("stateVersion") long stateVersion,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_dispatch_outbox SET status=#{status}, version=version+1, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND turn_id=#{turnId} AND event_type=#{eventType} AND version=#{version}
            """)
    int updateOutbox(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("turnId") String turnId, @Param("eventType") String eventType,
            @Param("version") long version, @Param("status") String status,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_dispatch_outbox SET status='CLAIMED', lease_owner=#{leaseOwner}, lease_until=#{leaseUntil},
              attempt_count=attempt_count+1, fencing_token=#{fencingToken}, last_error=NULL,
              version=version+1, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND event_id=#{eventId} AND version=#{version}
              AND ((status IN ('READY','RETRY','AWAITING_ACK') AND available_at<=#{updatedAt})
                OR (status='CLAIMED' AND lease_until<=#{updatedAt}))
            """)
    int claimOutbox(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("eventId") String eventId, @Param("version") long version,
            @Param("leaseOwner") String leaseOwner, @Param("leaseUntil") long leaseUntil,
            @Param("fencingToken") long fencingToken, @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_dispatch_outbox SET lease_until=#{leaseUntil},version=version+1,updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND event_id=#{eventId} AND version=#{version} AND status='CLAIMED'
              AND lease_owner=#{leaseOwner} AND fencing_token=#{fencingToken}
            """)
    int renewOutbox(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("eventId") String eventId, @Param("version") long version,
            @Param("leaseOwner") String leaseOwner, @Param("fencingToken") long fencingToken,
            @Param("leaseUntil") long leaseUntil, @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_dispatch_outbox SET status=#{status}, available_at=#{availableAt},
              lease_owner=NULL, lease_until=NULL, last_error=#{lastError}, sent_at=#{sentAt},
              version=version+1, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND event_id=#{eventId} AND version=#{version} AND status='CLAIMED'
              AND lease_owner=#{leaseOwner} AND fencing_token=#{fencingToken}
            """)
    int settleOutbox(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("eventId") String eventId, @Param("version") long version,
            @Param("leaseOwner") String leaseOwner, @Param("fencingToken") long fencingToken,
            @Param("status") String status, @Param("availableAt") Long availableAt,
            @Param("lastError") String lastError, @Param("sentAt") Long sentAt,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_dispatch_outbox SET status='SENT',available_at=NULL,lease_owner=NULL,lease_until=NULL,
              last_error=NULL,sent_at=#{updatedAt},version=version+1,updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND event_id=#{eventId} AND version=#{version}
              AND status IN ('CLAIMED','AWAITING_ACK','RETRY')
            """)
    int acknowledgeOutbox(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("eventId") String eventId, @Param("version") long version,
            @Param("updatedAt") long updatedAt);

    @Update("""
            UPDATE chat_request SET aggregate_state=#{state}, state_version=state_version+1, updated_at=#{updatedAt}
            WHERE tenant_id=#{tenantId} AND owner_jiacn=#{ownerJiacn} AND client_id=#{clientId}
              AND id=#{id} AND state_version=#{stateVersion}
            """)
    int updateRequestState(@Param("tenantId") String tenantId, @Param("ownerJiacn") String ownerJiacn,
            @Param("clientId") String clientId, @Param("id") long id, @Param("stateVersion") long stateVersion,
            @Param("state") String state, @Param("updatedAt") long updatedAt);
}
