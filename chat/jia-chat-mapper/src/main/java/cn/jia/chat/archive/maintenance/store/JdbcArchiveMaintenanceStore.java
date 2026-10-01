package cn.jia.chat.archive.maintenance.store;

import cn.jia.chat.archive.maintenance.model.*;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Named
public class JdbcArchiveMaintenanceStore implements ArchiveMaintenanceStore {
    private final JdbcTemplate jdbc;

    public JdbcArchiveMaintenanceStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    private static final RowMapper<ArchiveManagerGrantRecord> MANAGER = (rs, n) ->
            new ArchiveManagerGrantRecord(rs.getString("collection_id"), rs.getString("tenant_id"),
                    rs.getString("client_id"), rs.getString("owner_jiacn"), rs.getString("permissions"),
                    rs.getLong("revision"), rs.getString("state"));
    private static final RowMapper<ArchiveAppointmentRecord> APPOINTMENT = (rs, n) ->
            new ArchiveAppointmentRecord(rs.getString("appointment_id"), rs.getString("collection_id"),
                    rs.getString("role_code"), rs.getString("tenant_id"), rs.getString("client_id"),
                    rs.getString("owner_jiacn"), rs.getString("agent_id"), rs.getString("binding_version"),
                    rs.getString("work_scope_mode"), rs.getString("work_ids"),
                    rs.getString("permission_profile"), rs.getString("required_skill_key"),
                    rs.getString("required_skill_version"), rs.getString("required_skill_sha256"),
                    rs.getString("status"), rs.getLong("revision"), instant(rs.getTimestamp("created_at")),
                    instant(rs.getTimestamp("revoked_at")));
    private static final RowMapper<ArchiveMaintenanceJobRecord> JOB = (rs, n) -> {
        long appointmentRevision = rs.getLong("appointment_revision");
        Long nullableAppointmentRevision = rs.wasNull() ? null : appointmentRevision;
        return new ArchiveMaintenanceJobRecord(rs.getString("job_id"), rs.getString("run_id"),
                rs.getString("collection_id"), rs.getString("tenant_id"), rs.getString("client_id"),
                rs.getString("owner_jiacn"), rs.getString("appointment_id"),
                nullableAppointmentRevision, rs.getString("agent_id"),
                rs.getString("binding_version"), rs.getString("permission_profile"),
                rs.getLong("manager_authorization_revision"), rs.getString("publication_mode"), rs.getString("operation_code"),
                rs.getString("work_id"), rs.getString("canonical_key"), rs.getString("title"),
                rs.getString("source_id"), rs.getString("source_sha256"), rs.getString("source_summary"),
                rs.getString("rights_basis"), rs.getString("state"), rs.getString("wait_reason"),
                rs.getLong("revision"), rs.getString("draft_id"), rs.getString("publication_id"),
                rs.getString("request_intent_id"), rs.getString("request_sha256"),
                rs.getString("target_agent_id"));
    };
    private static final RowMapper<ArchiveJobRunRecord> RUN = (rs, n) -> {
        long retryable = rs.getLong("failure_retryable");
        Boolean nullableRetryable = rs.wasNull() ? null : retryable == 1;
        return new ArchiveJobRunRecord(rs.getString("run_id"), rs.getString("job_id"),
                rs.getLong("execution_epoch"), rs.getString("runtime_instance_id"),
                rs.getLong("grant_revision"), rs.getString("started_message_id"),
                rs.getString("failure_phase"), rs.getString("failure_code"), nullableRetryable,
                rs.getString("state"), rs.getLong("revision"));
    };
    private static final RowMapper<ArchiveExecutionGrantRecord> EXECUTION = (rs, n) ->
            new ArchiveExecutionGrantRecord(rs.getString("grant_ref"), rs.getString("run_id"),
                    rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("owner_jiacn"),
                    rs.getString("appointment_id"), rs.getLong("appointment_revision"),
                    rs.getLong("manager_authorization_revision"), rs.getString("agent_id"), rs.getLong("binding_version"), rs.getString("execution_ref"),
                    rs.getString("command_id"), rs.getInt("active_attempt"), rs.getLong("execution_epoch"),
                    rs.getString("runtime_instance_id"), rs.getBytes("registration_hash"),
                    rs.getString("skill_origin"), rs.getString("installation_ref"),
                    rs.getLong("installation_revision"), rs.getString("skill_key"),
                    rs.getString("skill_version"), rs.getString("package_sha256"),
                    rs.getString("dispatch_key"), rs.getString("request_sha256"),
                    rs.getString("context_ref"), rs.getLong("expires_at"), rs.getString("state"),
                    rs.getLong("revision"));
    private static final RowMapper<ArchiveDraftRecord> DRAFT = (rs, n) -> {
        long validated = rs.getLong("validated_revision");
        Long nullable = rs.wasNull() ? null : validated;
        return new ArchiveDraftRecord(rs.getString("draft_id"), rs.getString("job_id"),
                rs.getLong("revision"), rs.getString("state"), rs.getString("content_json"),
                rs.getString("content_sha256"), nullable, rs.getString("validation_id"));
    };
    private static final RowMapper<ArchiveValidationRecord> VALIDATION = (rs, n) ->
            new ArchiveValidationRecord(rs.getString("validation_id"), rs.getString("draft_id"),
                    rs.getLong("draft_revision"), rs.getString("outcome"),
                    rs.getString("validation_digest"), rs.getString("findings_json"));
    private static final RowMapper<ArchivePublicationRecord> PUBLICATION = (rs, n) ->
            new ArchivePublicationRecord(rs.getString("publication_id"), rs.getString("job_id"),
                    rs.getString("collection_id"), rs.getString("work_id"), rs.getString("edition_id"),
                    rs.getLong("draft_revision"), rs.getString("manifest_sha256"),
                    rs.getString("source_sha256"), rs.getString("state"), rs.getString("actor_type"),
                    rs.getString("actor_id"), rs.getLong("authorization_revision"));
    private static final RowMapper<ArchiveWithdrawalRecord> WITHDRAWAL = (rs, n) ->
            new ArchiveWithdrawalRecord(rs.getString("withdrawal_id"), rs.getString("publication_id"),
                    rs.getString("collection_id"), rs.getString("work_id"), rs.getString("edition_id"),
                    rs.getString("reason"), rs.getString("tenant_id"), rs.getString("client_id"),
                    rs.getString("owner_jiacn"), rs.getString("withdraw_actor_type"),
                    rs.getString("withdraw_actor_id"), rs.getLong("withdraw_authorization_revision"),
                    rs.getString("requested_replacement_active_edition_id"),
                    rs.getString("resulting_active_edition_id"), rs.getLong("resulting_work_revision"),
                    rs.getString("operation_key"), instant(rs.getTimestamp("withdrawn_at")),
                    rs.getString("outbox_state"));
    private static final RowMapper<ArchiveEditionVersionRecord> VERSION = (rs, n) -> {
        ArchiveWithdrawalRecord withdrawal = rs.getString("withdrawal_id") == null ? null : WITHDRAWAL.mapRow(rs, n);
        return new ArchiveEditionVersionRecord(rs.getString("publication_id"), rs.getString("job_id"),
                rs.getString("collection_id"), rs.getString("work_id"), rs.getString("edition_id"),
                rs.getLong("draft_revision"), rs.getString("manifest_sha256"),
                rs.getString("source_sha256"), rs.getString("state"), rs.getString("actor_type"),
                rs.getString("actor_id"), rs.getLong("authorization_revision"),
                instant(rs.getTimestamp("published_at")), withdrawal);
    };

    @Override
    public ArchiveManagerGrantRecord findManagerGrant(ArchiveActorScope actor, String collectionId, boolean lock) {
        return first(jdbc.query("""
                SELECT * FROM archive_collection_manager
                WHERE collection_id=? AND tenant_id=? AND client_id=? AND owner_jiacn=?
                  AND CAST(collection_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY)
                """ + (lock ? " FOR UPDATE" : ""), MANAGER, collectionId, actor.tenantId(),
                actor.clientId(), actor.ownerJiacn(), collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn()));
    }

    @Override
    public List<ArchiveManagerGrantRecord> lockManagerGrants() {
        return jdbc.query("""
                SELECT * FROM archive_collection_manager
                ORDER BY collection_id,tenant_id,client_id,owner_jiacn
                FOR UPDATE
                """, MANAGER);
    }

    @Override
    public void insertManagerGrant(ArchiveManagerGrantRecord grant) {
        if (jdbc.update("""
                INSERT INTO archive_collection_manager
                (collection_id,tenant_id,client_id,owner_jiacn,permissions,state,revision)
                VALUES (?,?,?,?,?,?,?)
                """, grant.collectionId(), grant.tenantId(), grant.clientId(), grant.ownerJiacn(),
                grant.permissions(), grant.state(), grant.revision()) != 1) {
            throw new IllegalStateException("Archive manager authorization insert failed");
        }
    }

    @Override
    public int activateConfiguredManagerGrant(ArchiveManagerGrantRecord grant, long expectedRevision) {
        return jdbc.update("""
                UPDATE archive_collection_manager
                SET permissions=?, state='ACTIVE', revision=?, revoked_at=NULL
                WHERE collection_id=? AND tenant_id=? AND client_id=? AND owner_jiacn=?
                  AND revision=? AND revision<?
                """, grant.permissions(), grant.revision(), grant.collectionId(), grant.tenantId(),
                grant.clientId(), grant.ownerJiacn(), expectedRevision, grant.revision());
    }

    @Override
    public int revokeManagerGrant(ArchiveActorScope actor, String collectionId, long expectedRevision) {
        return jdbc.update("""
                UPDATE archive_collection_manager
                SET state='REVOKED', revision=revision+1, revoked_at=CURRENT_TIMESTAMP(6)
                WHERE collection_id=? AND tenant_id=? AND client_id=? AND owner_jiacn=?
                  AND revision=? AND state='ACTIVE'
                """, collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn(), expectedRevision);
    }

    @Override
    public Slot findSlot(String collectionId, String roleCode) {
        return first(jdbc.query("SELECT collection_id,role_code,current_appointment_id,revision "
                        + "FROM archive_appointment_slot WHERE collection_id=? AND role_code=?",
                (rs, n) -> new Slot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                collectionId, roleCode));
    }

    @Override
    public Slot lockSlot(String collectionId, String roleCode) {
        return first(jdbc.query("""
                SELECT collection_id,role_code,current_appointment_id,revision
                FROM archive_appointment_slot WHERE collection_id=? AND role_code=? FOR UPDATE
                """, (rs, n) -> new Slot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)),
                collectionId, roleCode));
    }

    @Override public void ensureSlot(String collectionId, String roleCode) {
        jdbc.update("INSERT IGNORE INTO archive_appointment_slot(collection_id,role_code,revision) VALUES (?,?,0)",
                collectionId, roleCode);
    }
    @Override public ArchiveAppointmentRecord findAppointment(String id, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_appointment WHERE appointment_id=?" + (lock ? " FOR UPDATE" : ""), APPOINTMENT, id));
    }
    @Override public ArchiveAppointmentRecord findCurrentAppointment(String collectionId, boolean lock) {
        return first(jdbc.query("""
                SELECT a.* FROM archive_appointment_slot s JOIN archive_appointment a
                  ON a.appointment_id=s.current_appointment_id
                WHERE s.collection_id=? AND s.role_code='ARCHIVE_EDITOR' AND a.status='ACTIVE'
                """ + (lock ? " FOR UPDATE" : ""), APPOINTMENT, collectionId));
    }
    @Override public List<ArchiveAppointmentRecord> listAppointments(ArchiveActorScope actor, String collectionId) {
        return jdbc.query("SELECT * FROM archive_appointment WHERE collection_id=? AND tenant_id=? AND client_id=? AND owner_jiacn=? ORDER BY created_at DESC",
                APPOINTMENT, collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn());
    }
    @Override public void insertAppointment(ArchiveAppointmentRecord a) {
        jdbc.update("""
                INSERT INTO archive_appointment
                (appointment_id,collection_id,role_code,tenant_id,client_id,owner_jiacn,agent_id,binding_version,
                 work_scope_mode,work_ids,permission_profile,required_skill_key,required_skill_version,
                 required_skill_sha256,status,revision,created_at,revoked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, a.appointmentId(),a.collectionId(),a.roleCode(),a.tenantId(),a.clientId(),a.ownerJiacn(),
                a.agentId(),a.bindingVersion(),a.workScopeMode(),a.workIds(),a.permissionProfile(),a.requiredSkillKey(),
                a.requiredSkillVersion(),a.requiredSkillSha256(),a.status(),a.revision(), Timestamp.from(a.createdAt()),
                a.revokedAt()==null?null:Timestamp.from(a.revokedAt()));
    }
    @Override public int activateSlot(String collectionId, String roleCode, long expected, String appointmentId) {
        return jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=?,revision=revision+1 WHERE collection_id=? AND role_code=? AND revision=? AND current_appointment_id IS NULL",
                appointmentId, collectionId, roleCode, expected);
    }
    @Override public int revokeAppointment(String id, long expected) {
        return jdbc.update("UPDATE archive_appointment SET status='REVOKED',revision=revision+1,revoked_at=CURRENT_TIMESTAMP(6) WHERE appointment_id=? AND revision=? AND status='ACTIVE'", id, expected);
    }
    @Override public int clearSlot(String collectionId,String roleCode,String appointmentId,long expected) {
        return jdbc.update("UPDATE archive_appointment_slot SET current_appointment_id=NULL,revision=revision+1 WHERE collection_id=? AND role_code=? AND current_appointment_id=? AND revision=?",
                collectionId,roleCode,appointmentId,expected);
    }

    private static final RowMapper<ArchiveSourceSnapshotRecord> SOURCE = (rs, n) ->
            new ArchiveSourceSnapshotRecord(rs.getString("source_id"), rs.getString("collection_id"),
                    rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("owner_jiacn"),
                    rs.getString("storage_uri"), rs.getString("raw_sha256"), rs.getLong("raw_byte_length"),
                    rs.getString("source_name"), rs.getString("source_version"), rs.getString("rights_basis"),
                    rs.getString("normalization_rule"), rs.getString("state"));

    @Override public ArchiveSourceSnapshotRecord findSource(String sourceId) {
        return first(jdbc.query("SELECT * FROM archive_source_snapshot WHERE source_id=?", SOURCE, sourceId));
    }
    @Override public void insertSource(ArchiveSourceSnapshotRecord source) {
        jdbc.update("""
                INSERT INTO archive_source_snapshot
                (source_id,collection_id,tenant_id,client_id,owner_jiacn,storage_uri,raw_sha256,raw_byte_length,
                 source_name,source_version,rights_basis,normalization_rule,state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, source.sourceId(), source.collectionId(), source.tenantId(), source.clientId(),
                source.ownerJiacn(), source.storageUri(), source.rawSha256(), source.rawByteLength(),
                source.sourceName(), source.sourceVersion(), source.rightsBasis(),
                source.normalizationRule(), source.state());
    }
    private static final RowMapper<ArchiveConfirmedRequestRecord> CONFIRMATION = (rs, n) -> {
        long generation = rs.getLong("conversation_generation");
        Long nullableGeneration = rs.wasNull() ? null : generation;
        return new ArchiveConfirmedRequestRecord(rs.getString("confirmation_ref"),
                rs.getString("request_intent_id"), rs.getString("tenant_id"),
                rs.getString("client_id"), rs.getString("owner_jiacn"),
                rs.getString("collection_id"), rs.getString("request_json"),
                rs.getString("request_sha256"), rs.getString("publication_mode_ceiling"),
                rs.getString("conversation_id"), rs.getString("canonical_message_id"),
                nullableGeneration, rs.getString("turn_sha256"), rs.getString("entry_point"),
                rs.getString("target_agent_id"), rs.getLong("revision"));
    };

    @Override
    public ArchiveConfirmedRequestRecord findConfirmedRequest(ArchiveActorScope actor,
            String confirmationRef, boolean lock) {
        return first(jdbc.query("""
                SELECT * FROM archive_confirmed_request
                WHERE confirmation_ref=? AND tenant_id=? AND client_id=? AND owner_jiacn=?
                  AND CAST(confirmation_ref AS BINARY)=CAST(? AS BINARY)
                  AND CAST(tenant_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(client_id AS BINARY)=CAST(? AS BINARY)
                  AND CAST(owner_jiacn AS BINARY)=CAST(? AS BINARY)
                """ + (lock ? " FOR UPDATE" : ""), CONFIRMATION, confirmationRef,
                actor.tenantId(), actor.clientId(), actor.ownerJiacn(), confirmationRef,
                actor.tenantId(), actor.clientId(), actor.ownerJiacn()));
    }

    @Override
    public void insertConfirmedRequest(ArchiveConfirmedRequestRecord confirmation) {
        if (jdbc.update("""
                INSERT INTO archive_confirmed_request
                (confirmation_ref,request_intent_id,tenant_id,client_id,owner_jiacn,collection_id,
                 request_json,request_sha256,publication_mode_ceiling,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, confirmation.confirmationRef(), confirmation.requestIntentId(),
                confirmation.tenantId(), confirmation.clientId(), confirmation.ownerJiacn(),
                confirmation.collectionId(), confirmation.requestJson(),
                confirmation.requestSha256(), confirmation.publicationModeCeiling(),
                confirmation.revision()) != 1) {
            throw new IllegalStateException("Archive confirmation insert failed");
        }
    }

    @Override
    public int bindConfirmedRequest(ArchiveActorScope actor, String confirmationRef,
            long expectedRevision, String conversationId, String canonicalMessageId,
            long conversationGeneration, String turnSha256, String entryPoint,
            String targetAgentId) {
        return jdbc.update("""
                UPDATE archive_confirmed_request
                SET conversation_id=?,canonical_message_id=?,conversation_generation=?,
                    turn_sha256=?,entry_point=?,target_agent_id=?,revision=revision+1,
                    bound_at=CURRENT_TIMESTAMP(6)
                WHERE confirmation_ref=? AND tenant_id=? AND client_id=? AND owner_jiacn=?
                  AND revision=? AND conversation_id IS NULL AND canonical_message_id IS NULL
                  AND conversation_generation IS NULL AND turn_sha256 IS NULL
                  AND entry_point IS NULL AND target_agent_id IS NULL
                """, conversationId, canonicalMessageId, conversationGeneration, turnSha256,
                entryPoint, targetAgentId, confirmationRef, actor.tenantId(), actor.clientId(),
                actor.ownerJiacn(), expectedRevision);
    }

    @Override public ArchiveMaintenanceJobRecord findJob(String id, boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_maintenance_job WHERE job_id=?" + (lock ? " FOR UPDATE" : ""), JOB, id));
    }
    @Override public ArchiveMaintenanceJobRecord findJobByIntent(ArchiveActorScope actor,String intent,boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_maintenance_job WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND request_intent_id=?" + (lock ? " FOR UPDATE" : ""), JOB,
                actor.tenantId(),actor.clientId(),actor.ownerJiacn(),intent));
    }
    @Override public List<ArchiveMaintenanceJobRecord> listJobs(ArchiveActorScope actor,String collectionId,int limit) {
        return jdbc.query("SELECT * FROM archive_maintenance_job WHERE collection_id=? AND tenant_id=? AND client_id=? AND owner_jiacn=? ORDER BY updated_at DESC,job_id DESC LIMIT ?",
                JOB, collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn(), limit);
    }
    @Override public void insertJob(ArchiveMaintenanceJobRecord j) {
        jdbc.update("""
                INSERT INTO archive_maintenance_job
                (job_id,run_id,collection_id,tenant_id,client_id,owner_jiacn,appointment_id,appointment_revision,
                 agent_id,binding_version,permission_profile,manager_authorization_revision,publication_mode,operation_code,work_id,canonical_key,title,
                 source_id,source_sha256,source_summary,rights_basis,state,wait_reason,revision,draft_id,publication_id,
                 request_intent_id,request_sha256,target_agent_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,j.jobId(),j.runId(),j.collectionId(),j.tenantId(),j.clientId(),j.ownerJiacn(),j.appointmentId(),
                j.appointmentRevision(),j.agentId(),j.bindingVersion(),j.permissionProfile(),j.managerAuthorizationRevision(),j.publicationMode(),j.operation(),
                j.workId(),j.canonicalKey(),j.title(),j.sourceId(),j.sourceSha256(),j.sourceSummary(),j.rightsBasis(),j.state(),j.waitReason(),
                j.revision(),j.draftId(),j.publicationId(),j.requestIntentId(),j.requestSha256(),j.targetAgentId());
    }
    @Override public int bindWaitingJobTarget(String jobId,long expectedRevision,String targetAgentId) {
        return jdbc.update("""
                UPDATE archive_maintenance_job
                SET target_agent_id=?,revision=revision+1
                WHERE job_id=? AND revision=? AND target_agent_id IS NULL
                  AND state IN ('WAITING_INPUT','WAITING_ASSIGNEE')
                  AND run_id IS NULL AND draft_id IS NULL AND appointment_id IS NULL
                  AND publication_id IS NULL
                """,targetAgentId,jobId,expectedRevision);
    }
    @Override public int resolveWaitingJob(ArchiveMaintenanceJobRecord j,long expectedRevision) {
        return jdbc.update("""
                UPDATE archive_maintenance_job
                SET run_id=?,appointment_id=?,appointment_revision=?,agent_id=?,binding_version=?,
                    permission_profile=?,manager_authorization_revision=?,work_id=?,canonical_key=?,title=?,
                    source_id=?,source_sha256=?,source_summary=?,rights_basis=?,state=?,wait_reason=?,
                    draft_id=?,revision=revision+1
                WHERE job_id=? AND revision=? AND state IN ('WAITING_INPUT','WAITING_ASSIGNEE')
                  AND run_id IS NULL AND draft_id IS NULL AND publication_id IS NULL
                """, j.runId(),j.appointmentId(),j.appointmentRevision(),j.agentId(),j.bindingVersion(),
                j.permissionProfile(),j.managerAuthorizationRevision(),j.workId(),j.canonicalKey(),j.title(),
                j.sourceId(),j.sourceSha256(),j.sourceSummary(),j.rightsBasis(),j.state(),j.waitReason(),
                j.draftId(),j.jobId(),expectedRevision);
    }
    @Override public void insertRun(String runId,String jobId,long executionEpoch,long grantRevision) {
        if (jdbc.update("INSERT INTO archive_job_run(run_id,job_id,execution_epoch,grant_revision,state,revision) VALUES (?,?,?,?,'WAITING',1)",
                runId,jobId,executionEpoch,grantRevision) != 1) {
            throw new IllegalStateException("Archive run insert failed");
        }
    }
    @Override public ArchiveJobRunRecord findRun(String runId,boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_job_run WHERE run_id=?"+(lock?" FOR UPDATE":""),RUN,runId));
    }
    @Override public int activateRun(String runId,long expected,String runtimeId) {
        return jdbc.update("UPDATE archive_job_run SET runtime_instance_id=?,state='AUTHORIZED',revision=revision+1 WHERE run_id=? AND revision=? AND state='WAITING' AND runtime_instance_id IS NULL",runtimeId,runId,expected);
    }
    @Override public int startRun(String runId,long expected,String messageId) {
        return jdbc.update("UPDATE archive_job_run SET started_message_id=?,state='RUNNING',revision=revision+1 WHERE run_id=? AND revision=? AND state='AUTHORIZED' AND started_message_id IS NULL",messageId,runId,expected);
    }
    @Override public int failRun(String runId,long expected,String phase,String code,boolean retryable) {
        return jdbc.update("UPDATE archive_job_run SET failure_phase=?,failure_code=?,failure_retryable=?,state='FAILED',revision=revision+1 WHERE run_id=? AND revision=? AND state='RUNNING' AND started_message_id IS NOT NULL AND failure_phase IS NULL AND failure_code IS NULL AND failure_retryable IS NULL",phase,code,retryable?1:0,runId,expected);
    }
    @Override public int completeRun(String runId,long expected) {
        return jdbc.update("UPDATE archive_job_run SET state='COMPLETED',revision=revision+1 WHERE run_id=? AND revision=? AND state='RUNNING' AND started_message_id IS NOT NULL",runId,expected);
    }
    @Override public ArchiveExecutionGrantRecord findExecutionGrant(String runId,boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_execution_grant WHERE run_id=?"+(lock?" FOR UPDATE":""),EXECUTION,runId));
    }
    @Override public List<ArchiveExecutionGrantRecord> listActiveExecutionGrants(String appointmentId,boolean lock) {
        return jdbc.query("SELECT g.* FROM archive_execution_grant g WHERE g.appointment_id=? AND g.state='ACTIVE' ORDER BY g.run_id"+(lock?" FOR UPDATE":""),EXECUTION,appointmentId);
    }
    @Override public List<String> lockRunIdsForAppointment(String appointmentId) {
        return jdbc.queryForList("""
                SELECT r.run_id FROM archive_job_run r
                JOIN archive_maintenance_job j ON j.run_id=r.run_id
                WHERE j.appointment_id=? AND r.state<>'FENCED'
                ORDER BY r.run_id FOR UPDATE
                """, String.class, appointmentId);
    }
    @Override public List<String> lockRunIdsForManager(ArchiveActorScope actor,String collectionId) {
        return jdbc.queryForList("""
                SELECT r.run_id FROM archive_job_run r
                JOIN archive_maintenance_job j ON j.run_id=r.run_id
                WHERE j.collection_id=? AND j.tenant_id=? AND j.client_id=? AND j.owner_jiacn=?
                  AND r.state<>'FENCED'
                ORDER BY r.run_id FOR UPDATE
                """, String.class, collectionId, actor.tenantId(), actor.clientId(), actor.ownerJiacn());
    }
    @Override public List<ManagerRunTarget> listUnfencedRunTargetsForManager(
            ArchiveActorScope actor,String collectionId,boolean lock) {
        return jdbc.query("""
                SELECT r.run_id,j.agent_id,j.binding_version FROM archive_job_run r
                JOIN archive_maintenance_job j ON j.run_id=r.run_id
                WHERE j.collection_id=? AND j.tenant_id=? AND j.client_id=? AND j.owner_jiacn=?
                  AND r.state<>'FENCED'
                ORDER BY j.agent_id,j.binding_version,r.run_id
                """ + (lock ? " FOR UPDATE" : ""),
                (rs,n) -> new ManagerRunTarget(rs.getString(1),rs.getString(2),rs.getString(3)),
                collectionId,actor.tenantId(),actor.clientId(),actor.ownerJiacn());
    }
    @Override public void insertExecutionGrant(ArchiveExecutionGrantRecord g) {
        int rows=jdbc.update("""
                INSERT INTO archive_execution_grant
                (grant_ref,run_id,tenant_id,client_id,owner_jiacn,appointment_id,appointment_revision,
                 manager_authorization_revision,agent_id,binding_version,execution_ref,command_id,active_attempt,execution_epoch,
                 runtime_instance_id,registration_hash,skill_origin,installation_ref,installation_revision,
                 skill_key,skill_version,package_sha256,dispatch_key,request_sha256,context_ref,
                 expires_at,state,revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',1)
                """,g.grantRef(),g.runId(),g.tenantId(),g.clientId(),g.ownerJiacn(),g.appointmentId(),
                g.appointmentRevision(),g.managerAuthorizationRevision(),g.agentId(),g.bindingVersion(),g.executionRef(),g.commandId(),
                g.activeAttempt(),g.executionEpoch(),g.runtimeInstanceId(),g.registrationHash(),g.skillOrigin(),
                g.installationRef(),g.installationRevision(),g.skillKey(),g.skillVersion(),g.packageSha256(),
                g.dispatchKey(),g.requestSha256(),g.contextRef(),g.expiresAt());
        if(rows!=1) throw new IllegalStateException("Archive execution grant insert failed");
    }
    @Override public int releaseExecutionGrant(String runId,long expected) {
        return jdbc.update("UPDATE archive_execution_grant SET state='READ_ONLY',revision=revision+1 WHERE run_id=? AND revision=? AND state='ACTIVE'",runId,expected);
    }
    @Override public int fenceRun(String runId) {
        ArchiveJobRunRecord run=findRun(runId,true);
        if(run==null || "FENCED".equals(run.state())) return 0;
        ArchiveExecutionGrantRecord grant=findExecutionGrant(runId,true);
        if(grant!=null) {
            if(!java.util.Set.of("ACTIVE","READ_ONLY").contains(grant.state())
                    || grant.executionEpoch()!=run.executionEpoch()) return 0;
            if(jdbc.update("UPDATE archive_execution_grant SET state='FENCED',execution_epoch=execution_epoch+1,revision=revision+1 WHERE run_id=? AND revision=? AND execution_epoch=? AND state=?",
                    runId,grant.revision(),grant.executionEpoch(),grant.state())!=1) return 0;
        }
        return jdbc.update("UPDATE archive_job_run SET state='FENCED',execution_epoch=execution_epoch+1,revision=revision+1 WHERE run_id=? AND revision=? AND execution_epoch=? AND state=?",
                runId,run.revision(),run.executionEpoch(),run.state());
    }
    @Override public int updateJobState(String id,long expected,String state,String wait,String publicationId) {
        return jdbc.update("UPDATE archive_maintenance_job SET state=?,wait_reason=?,publication_id=COALESCE(?,publication_id),revision=revision+1 WHERE job_id=? AND revision=?",state,wait,publicationId,id,expected);
    }
    @Override public int replaceCurrentRun(String jobId,long expectedRevision,String expectedRunId,
            String newRunId,ArchiveAppointmentRecord appointment,long managerAuthorizationRevision,
            String state,String waitReason) {
        return jdbc.update("""
                UPDATE archive_maintenance_job
                SET run_id=?,appointment_id=?,appointment_revision=?,agent_id=?,binding_version=?,
                    permission_profile=?,manager_authorization_revision=?,state=?,wait_reason=?,revision=revision+1
                WHERE job_id=? AND revision=? AND run_id=? AND publication_id IS NULL
                  AND state NOT IN ('PUBLISHED','CANCELLED')
                """,newRunId,appointment.appointmentId(),appointment.revision(),appointment.agentId(),
                appointment.bindingVersion(),appointment.permissionProfile(),managerAuthorizationRevision,
                state,waitReason,jobId,expectedRevision,expectedRunId);
    }

    @Override public ArchiveDraftRecord findDraftByJob(String jobId,boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_draft WHERE job_id=?"+(lock?" FOR UPDATE":""),DRAFT,jobId));
    }
    @Override public ArchiveDraftRecord findDraft(String draftId,boolean lock) {
        return first(jdbc.query("SELECT * FROM archive_draft WHERE draft_id=?"+(lock?" FOR UPDATE":""),DRAFT,draftId));
    }
    @Override public void insertDraft(ArchiveDraftRecord d) {
        jdbc.update("INSERT INTO archive_draft(draft_id,job_id,revision,state,content_json,content_sha256,validated_revision,validation_id) VALUES (?,?,?,?,?,?,?,?)",
                d.draftId(),d.jobId(),d.revision(),d.state(),d.contentJson(),d.contentSha256(),d.validatedRevision(),d.validationId());
    }
    @Override public int updateDraft(String id,long expected,long next,String state,String json,String sha,Long validated,String validationId) {
        return jdbc.update("UPDATE archive_draft SET revision=?,state=?,content_json=?,content_sha256=?,validated_revision=?,validation_id=? WHERE draft_id=? AND revision=?",
                next,state,json,sha,validated,validationId,id,expected);
    }
    @Override public void insertValidation(ArchiveValidationRecord v) {
        jdbc.update("INSERT INTO archive_validation(validation_id,draft_id,draft_revision,outcome,validation_digest,findings_json) VALUES (?,?,?,?,?,?)",
                v.validationId(),v.draftId(),v.draftRevision(),v.outcome(),v.validationDigest(),v.findingsJson());
    }
    @Override public ArchiveValidationRecord findValidation(String id) {
        return first(jdbc.query("SELECT * FROM archive_validation WHERE validation_id=?",VALIDATION,id));
    }
    @Override public ArchiveValidationRecord findCurrentValidation(String draftId,long revision) {
        return first(jdbc.query("SELECT * FROM archive_validation WHERE draft_id=? AND draft_revision=? AND outcome='PASSED' ORDER BY created_at DESC,validation_id DESC LIMIT 1",VALIDATION,draftId,revision));
    }
    @Override public ArchiveValidationRecord findLatestValidation(String draftId,long revision) {
        return first(jdbc.query("SELECT * FROM archive_validation WHERE draft_id=? AND draft_revision=? ORDER BY created_at DESC,validation_id DESC LIMIT 1",VALIDATION,draftId,revision));
    }

    @Override public CollectionWork lockCollectionWork(String collectionId,String workId) {
        return first(jdbc.query("SELECT collection_id,work_id,canonical_key,revision FROM archive_collection_work WHERE collection_id=? AND work_id=? FOR UPDATE",
                (rs,n)->new CollectionWork(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)),collectionId,workId));
    }
    @Override public CollectionWork findCollectionWork(String collectionId,String workId) {
        return first(jdbc.query("SELECT collection_id,work_id,canonical_key,revision FROM archive_collection_work WHERE collection_id=? AND work_id=?",
                (rs,n)->new CollectionWork(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)),collectionId,workId));
    }
    @Override public void insertCollectionWork(String collectionId,String workId,String canonicalKey) {
        jdbc.update("INSERT INTO archive_collection_work(collection_id,work_id,canonical_key,revision) VALUES (?,?,?,1)",collectionId,workId,canonicalKey);
    }
    @Override public int bumpCollectionWork(String collectionId,String workId,long expected) {
        return jdbc.update("UPDATE archive_collection_work SET revision=revision+1 WHERE collection_id=? AND work_id=? AND revision=?",collectionId,workId,expected);
    }
    @Override public void insertPublication(ArchivePublicationRecord p) {
        jdbc.update("""
                INSERT INTO archive_publication(publication_id,job_id,collection_id,work_id,edition_id,draft_revision,
                manifest_sha256,source_sha256,state,actor_type,actor_id,authorization_revision)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,p.publicationId(),p.jobId(),p.collectionId(),p.workId(),p.editionId(),p.draftRevision(),
                p.manifestSha256(),p.sourceSha256(),p.state(),p.actorType(),p.actorId(),p.authorizationRevision());
    }
    @Override public ArchivePublicationRecord findPublicationByJob(String jobId) {
        return first(jdbc.query("SELECT * FROM archive_publication WHERE job_id=?",PUBLICATION,jobId));
    }
    @Override public ArchiveEditionVersionRecord findPublication(String workId,String editionId,boolean lock) {
        return first(jdbc.query(versionSelect() + " WHERE p.work_id=? AND p.edition_id=?" + (lock ? " FOR UPDATE" : ""),
                VERSION, workId, editionId));
    }
    @Override public List<ArchiveEditionVersionRecord> listPublications(String workId,boolean lock) {
        return jdbc.query(versionSelect() + " WHERE p.work_id=? ORDER BY p.published_at,p.publication_id" + (lock ? " FOR UPDATE" : ""),
                VERSION, workId);
    }
    @Override public int withdrawPublication(String publicationId) {
        return jdbc.update("UPDATE archive_publication SET state='WITHDRAWN' WHERE publication_id=? AND state='PUBLISHED'", publicationId);
    }
    @Override public void insertWithdrawal(ArchiveWithdrawalRecord w) {
        jdbc.update("""
                INSERT INTO archive_edition_withdrawal(withdrawal_id,publication_id,collection_id,work_id,edition_id,
                reason,tenant_id,client_id,owner_jiacn,actor_type,actor_id,authorization_revision,
                requested_replacement_active_edition_id,resulting_active_edition_id,resulting_work_revision,
                operation_key,withdrawn_at,outbox_state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, w.withdrawalId(), w.publicationId(), w.collectionId(), w.workId(), w.editionId(),
                w.reason(), w.tenantId(), w.clientId(), w.ownerJiacn(), w.actorType(), w.actorId(),
                w.authorizationRevision(), w.requestedReplacementActiveEditionId(), w.resultingActiveEditionId(),
                w.resultingWorkRevision(), w.operationKey(), Timestamp.from(w.withdrawnAt()), w.outboxState());
    }
    @Override public ArchiveWithdrawalRecord findWithdrawal(String withdrawalId) {
        return first(jdbc.query("""
                SELECT w.*, w.actor_type AS withdraw_actor_type, w.actor_id AS withdraw_actor_id,
                       w.authorization_revision AS withdraw_authorization_revision
                FROM archive_edition_withdrawal w WHERE w.withdrawal_id=?
                """, WITHDRAWAL, withdrawalId));
    }

    private static String versionSelect() {
        return """
                SELECT p.*, w.withdrawal_id,w.reason,w.tenant_id,w.client_id,w.owner_jiacn,
                       w.actor_type AS withdraw_actor_type,w.actor_id AS withdraw_actor_id,
                       w.authorization_revision AS withdraw_authorization_revision,
                       w.requested_replacement_active_edition_id,w.resulting_active_edition_id,
                       w.resulting_work_revision,w.operation_key,w.withdrawn_at,w.outbox_state
                FROM archive_publication p
                LEFT JOIN archive_edition_withdrawal w ON w.publication_id=p.publication_id
                """;
    }

    @Override public void appendJobEvent(String jobId, long jobRevision, String eventType, String dataJson) {
        // Caller holds archive_maintenance_job FOR UPDATE (or has inserted that row in this transaction).
        // The job row serializes sequence allocation; do not call this outside its write transaction.
        Long next = jdbc.queryForObject("SELECT COALESCE(MAX(sequence),0)+1 FROM archive_event WHERE job_id=?",
                Long.class, jobId);
        if (next == null || next < 1) throw new IllegalStateException("Archive event sequence unavailable");
        jdbc.update("INSERT INTO archive_event(job_id,sequence,schema_version,event_type,job_revision,data_json,outbox_state) "
                        + "VALUES (?,?,1,?,?,?,'PENDING')",
                jobId, next, eventType, jobRevision, dataJson);
    }

    @Override public List<ArchiveJobEventRecord> listJobEvents(String jobId, long afterSequence, int limit) {
        return jdbc.query("SELECT job_id,sequence,schema_version,event_type,job_revision,data_json,occurred_at "
                        + "FROM archive_event WHERE job_id=? AND sequence>? ORDER BY sequence LIMIT ?",
                (rs, n) -> new ArchiveJobEventRecord(rs.getString(1), rs.getLong(2),
                        rs.getLong(3), rs.getString(4), rs.getLong(5),
                        rs.getString(6), rs.getTimestamp(7).toInstant().toString()),
                jobId, afterSequence, limit);
    }
    @Override public Operation findOperation(ArchiveActorScope actor, String key) {
        return first(jdbc.query("SELECT http_method,canonical_path,request_sha256,target_type,target_id,state "
                + "FROM archive_operation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND operation_key=?",
                (rs,n) -> new Operation(false, rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6)),
                actor.tenantId(), actor.clientId(), actor.ownerJiacn(), key));
    }
    @Override public Operation beginOperation(ArchiveActorScope actor,String key,String method,String path,String sha,String type,String targetId) {
        boolean created=true;
        try {
            jdbc.update("INSERT INTO archive_operation(tenant_id,client_id,owner_jiacn,operation_key,http_method,canonical_path,request_sha256,target_type,target_id,state) VALUES (?,?,?,?,?,?,?,?,?,'PENDING')",
                    actor.tenantId(),actor.clientId(),actor.ownerJiacn(),key,method,path,sha,type,targetId);
        } catch (DuplicateKeyException duplicate) { created=false; }
        Operation found=first(jdbc.query("SELECT http_method,canonical_path,request_sha256,target_type,target_id,state FROM archive_operation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND operation_key=? FOR UPDATE",
                (rs,n)->new Operation(false,rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),actor.tenantId(),actor.clientId(),actor.ownerJiacn(),key));
        return new Operation(created,found.httpMethod(),found.canonicalPath(),found.requestSha256(),
                found.targetType(),found.targetId(),found.state());
    }
    @Override public void commitOperation(ArchiveActorScope actor,String key,String targetId) {
        if (jdbc.update("UPDATE archive_operation SET state='COMMITTED',target_id=? WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND operation_key=? AND state='PENDING'",
                targetId,actor.tenantId(),actor.clientId(),actor.ownerJiacn(),key)!=1) throw new IllegalStateException("Archive operation commit failed");
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static <T> T first(List<T> values) { return values.isEmpty() ? null : values.getFirst(); }
}
