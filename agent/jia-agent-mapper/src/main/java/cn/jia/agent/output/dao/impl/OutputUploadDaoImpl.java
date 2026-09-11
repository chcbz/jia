package cn.jia.agent.output.dao.impl;

import cn.jia.agent.output.dao.OutputUploadDao;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class OutputUploadDaoImpl implements OutputUploadDao {
    private final JdbcTemplate jdbc;

    public OutputUploadDaoImpl(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void ensureScopeQuota(String t,String c,long max,int active,long now) {
        jdbc.update("INSERT IGNORE INTO output_scope_quota VALUES (?,?,0,0,?,0,?,?,?,0)",t,c,max,active,now,now);
    }
    @Override public ScopeQuota lockScopeQuota(String t,String c) {
        return one("SELECT reserved_bytes,stored_bytes,max_bytes,active_uploads,max_active_uploads FROM output_scope_quota WHERE tenant_id=? AND client_id=? FOR UPDATE",
                (r,n)->new ScopeQuota(r.getLong(1),r.getLong(2),r.getLong(3),r.getInt(4),r.getInt(5)),t,c);
    }
    @Override public int changeScopeQuota(String t,String c,long rd,long sd,int ad,long now) {
        return jdbc.update("UPDATE output_scope_quota SET reserved_bytes=reserved_bytes+?,stored_bytes=stored_bytes+?,active_uploads=active_uploads+?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND reserved_bytes+?>=0 AND stored_bytes+?>=0 AND active_uploads+?>=0 AND reserved_bytes+?+stored_bytes+?<=max_bytes AND active_uploads+?<=max_active_uploads",
                rd,sd,ad,now,t,c,rd,sd,ad,rd,sd,ad);
    }
    @Override public void ensureBindingQuota(String t,String c,String b,int max,long now) {
        jdbc.update("INSERT IGNORE INTO output_binding_upload_quota VALUES (?,?,?,0,?,?,?,0)",t,c,b,max,now,now);
    }
    @Override public BindingQuota lockBindingQuota(String t,String c,String b) {
        return one("SELECT active_uploads,max_active_uploads FROM output_binding_upload_quota WHERE tenant_id=? AND client_id=? AND binding_id=? FOR UPDATE",
                (r,n)->new BindingQuota(r.getInt(1),r.getInt(2)),t,c,b);
    }
    @Override public int changeBindingQuota(String t,String c,String b,int delta,long now) {
        return jdbc.update("UPDATE output_binding_upload_quota SET active_uploads=active_uploads+?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND binding_id=? AND active_uploads+?>=0 AND active_uploads+?<=max_active_uploads",delta,now,t,c,b,delta,delta);
    }
    @Override public void ensureRunQuota(String t,String c,String run,long maxReq,long maxBytes,long now) {
        jdbc.update("INSERT IGNORE INTO output_run_upload_quota VALUES (?,?,?,0,?,0,?,?,?,0)",t,c,run,maxReq,maxBytes,now,now);
    }
    @Override public RunQuota lockRunQuota(String t,String c,String run) {
        return one("SELECT upload_requests,max_upload_requests,attempt_bytes,max_attempt_bytes FROM output_run_upload_quota WHERE tenant_id=? AND client_id=? AND run_id=? FOR UPDATE",
                (r,n)->new RunQuota(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4)),t,c,run);
    }
    @Override public int changeRunQuota(String t,String c,String run,long requests,long bytes,long now) {
        return jdbc.update("UPDATE output_run_upload_quota SET upload_requests=upload_requests+?,attempt_bytes=attempt_bytes+?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND run_id=? AND upload_requests+?<=max_upload_requests AND attempt_bytes+?<=max_attempt_bytes",
                requests,bytes,now,t,c,run,requests,bytes);
    }
    @Override public int activeRunFiles(String t,String c,String run) {
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM output_upload_session WHERE tenant_id=? AND client_id=? AND run_id=? AND state NOT IN ('REJECTED','EXPIRED')",Integer.class,t,c,run); return n==null?0:n;
    }
    @Override public long activeRunBytes(String t,String c,String run) {
        Long n=jdbc.queryForObject("SELECT COALESCE(SUM(reserved_bytes),0) FROM output_upload_session WHERE tenant_id=? AND client_id=? AND run_id=? AND state NOT IN ('REJECTED','EXPIRED')",Long.class,t,c,run); return n==null?0:n;
    }

    @Override public Receipt lockReceipt(String t,String c,String kind,String actor,String op,String key) {
        return one("SELECT request_hash,http_status,response_json FROM output_mutation_receipt WHERE tenant_id=? AND client_id=? AND actor_kind=? AND actor_id=? AND operation=? AND idempotency_key=? FOR UPDATE",
                (r,n)->new Receipt(r.getBytes(1),r.getInt(2),r.getString(3)),t,c,kind,actor,op,key);
    }
    @Override public int insertReceipt(String t,String c,String kind,String actor,String op,String key,byte[] hash,int status,String json,long retain,long now) {
        return jdbc.update("INSERT INTO output_mutation_receipt VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0)",t,c,kind,actor,op,key,hash,status,json,retain,now,now);
    }

    @Override public int insertObject(ObjectRow o,long now) {
        return jdbc.update("INSERT INTO output_object (tenant_id,client_id,object_id,run_id,bucket,storage_key,storage_version,actual_sha256,actual_size,actual_mime,verification_status,lifecycle_status,scan_engine_version,verified_at,delete_after,deleted_at,delete_attempts,delete_next_at,delete_lease_owner,delete_lease_until,error_code,created_at,updated_at,row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                o.tenantId(),o.clientId(),o.objectId(),o.runId(),o.bucket(),o.storageKey(),o.storageVersion(),o.actualSha256(),o.actualSize(),o.actualMime(),o.verificationStatus(),o.lifecycleStatus(),o.scanEngineVersion(),o.verifiedAt(),o.deleteAfter(),o.deletedAt(),o.deleteAttempts(),o.deleteNextAt(),o.deleteLeaseOwner(),o.deleteLeaseUntil(),o.errorCode(),now,now);
    }
    @Override public int insertUpload(UploadRow u,long now) {
        return jdbc.update("INSERT INTO output_upload_session (tenant_id,client_id,upload_id,run_id,binding_id,object_id,file_name,expected_size,expected_sha256,declared_mime,state,writer_epoch,writer_started_at,writer_until,writer_deadline_at,expires_at,reserved_bytes,slot_released,verification_attempts,verification_next_at,verification_lease_owner,verification_lease_until,error_code,created_at,updated_at,row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                u.tenantId(),u.clientId(),u.uploadId(),u.runId(),u.bindingId(),u.objectId(),u.fileName(),u.expectedSize(),u.expectedSha256(),u.declaredMime(),u.state(),u.writerEpoch(),u.writerStartedAt(),u.writerUntil(),u.writerDeadlineAt(),u.expiresAt(),u.reservedBytes(),u.slotReleased(),u.verificationAttempts(),u.verificationNextAt(),u.verificationLeaseOwner(),u.verificationLeaseUntil(),u.errorCode(),now,now);
    }
    @Override public UploadRow findUpload(String t,String c,String id,boolean lock) {
        return one("SELECT * FROM output_upload_session WHERE tenant_id=? AND client_id=? AND upload_id=?"+(lock?" FOR UPDATE":""),UPLOAD,t,c,id);
    }
    @Override public ObjectRow findObject(String t,String c,String id,boolean lock) {
        return one("SELECT * FROM output_object WHERE tenant_id=? AND client_id=? AND object_id=?"+(lock?" FOR UPDATE":""),OBJECT,t,c,id);
    }
    @Override public int beginWriter(String t,String c,String id,long old,long epoch,long start,long until,long deadline) {
        return jdbc.update("UPDATE output_upload_session SET state='UPLOADING',writer_epoch=?,writer_started_at=?,writer_until=?,writer_deadline_at=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state IN ('CREATED','UPLOADING') AND (writer_until IS NULL OR writer_until<?)",epoch,start,until,deadline,start,t,c,id,old,start);
    }
    @Override public int renewWriter(String t,String c,String id,long epoch,long now,long until) {
        return jdbc.update("UPDATE output_upload_session SET writer_until=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state='UPLOADING' AND writer_deadline_at>=? AND writer_until>=?",until,now,t,c,id,epoch,now,now);
    }
    @Override public int failWriter(String t,String c,String id,long epoch,long now) {
        return jdbc.update("UPDATE output_upload_session SET writer_until=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state='UPLOADING'",now,t,c,id,epoch);
    }
    @Override public int recordUploadedObject(String t,String c,String upload,long epoch,String object,String key,String version,byte[] hash,long size,String mime,long now) {
        int a=jdbc.update("UPDATE output_upload_session SET writer_until=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state='UPLOADING' AND writer_deadline_at>=?",now,t,c,upload,epoch,now);
        if(a!=1)return 0;
        return jdbc.update("UPDATE output_object SET storage_key=?,storage_version=?,actual_sha256=?,actual_size=?,actual_mime=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=? AND lifecycle_status='STAGED'",key,version,hash,size,mime,now,t,c,object);
    }
    @Override public int markVerifying(String t,String c,String id,long now) {
        return jdbc.update("UPDATE output_upload_session SET state='VERIFYING',writer_until=NULL,verification_next_at=?,error_code=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state='UPLOADING'",now,now,t,c,id);
    }
    @Override public int markReady(String t,String c,String id,String object,String scan,long now) {
        int a=jdbc.update("UPDATE output_upload_session SET state='READY',verification_lease_owner=NULL,verification_lease_until=NULL,verification_next_at=NULL,error_code=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state='VERIFYING'",now,t,c,id);
        if(a!=1)return 0;
        return jdbc.update("UPDATE output_object SET verification_status='PASSED',lifecycle_status='READY',scan_engine_version=?,verified_at=?,error_code=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=? AND lifecycle_status='STAGED'",scan,now,now,t,c,object);
    }
    @Override public int markRejected(String t,String c,String id,String object,String error,long now) {
        int a=jdbc.update("UPDATE output_upload_session SET state='REJECTED',writer_until=NULL,verification_lease_owner=NULL,verification_lease_until=NULL,verification_next_at=NULL,error_code=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state IN ('CREATED','UPLOADING','VERIFYING')",error,now,t,c,id);
        if(a!=1)return 0;
        jdbc.update("UPDATE output_object SET verification_status='REJECTED',error_code=?,delete_after=?,delete_next_at=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=?",error,now,now,now,t,c,object); return 1;
    }
    @Override public int releaseUploadSlot(String t,String c,String id,long now) {
        return jdbc.update("UPDATE output_upload_session SET slot_released=TRUE,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND slot_released=FALSE",now,t,c,id);
    }
    @Override public int markExpiredWithoutWriter(String t,String c,String id,String object,long now) {
        int a=jdbc.update("UPDATE output_upload_session SET state='EXPIRED',slot_released=TRUE,error_code='UPLOAD_EXPIRED',updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state='CREATED' AND writer_epoch=0",now,t,c,id);
        if(a==1)jdbc.update("UPDATE output_object SET verification_status='REJECTED',lifecycle_status='DELETED',deleted_at=?,error_code='UPLOAD_EXPIRED',updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=?",now,now,t,c,object); return a;
    }

    @Override public int insertCleanup(CleanupRow j,long now) {
        return jdbc.update("INSERT INTO output_storage_cleanup_job (cleanup_id,tenant_id,client_id,object_id,upload_id,writer_epoch,bucket,storage_key,storage_version,state,quota_charge_kind,quota_charge_bytes,safe_after,attempts,next_attempt_at,lease_owner,lease_until,last_error,created_at,updated_at,row_version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,NULL,?,?,0)",
                j.cleanupId(),j.tenantId(),j.clientId(),j.objectId(),j.uploadId(),j.writerEpoch(),j.bucket(),j.storageKey(),j.storageVersion(),j.state(),j.quotaChargeKind(),j.quotaChargeBytes(),j.safeAfter(),j.nextAttemptAt(),j.leaseOwner(),j.leaseUntil(),now,now);
    }
    @Override public int abandonCleanup(String t,String c,String upload,long epoch,String kind,long bytes,long safe,long now) {
        return jdbc.update("UPDATE output_storage_cleanup_job SET state='PENDING',quota_charge_kind=?,quota_charge_bytes=?,safe_after=?,next_attempt_at=GREATEST(next_attempt_at,?),updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state IN ('HELD','RETAINED')",kind,bytes,safe,safe,now,t,c,upload,epoch);
    }
    @Override public int retainCleanup(String t,String c,String upload,long epoch,String version,long now) {
        return jdbc.update("UPDATE output_storage_cleanup_job SET state='RETAINED',storage_version=?,quota_charge_kind='NONE',quota_charge_bytes=0,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND writer_epoch=? AND state='HELD'",version,now,t,c,upload,epoch);
    }
    @Override public int countOpenCleanup(String t,String c,String upload) {
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE tenant_id=? AND client_id=? AND upload_id=? AND state IN ('HELD','PENDING','CLAIMED')",Integer.class,t,c,upload);return n==null?0:n;
    }
    @Override public List<CleanupRow> findDueCleanup(long now,int limit) {
        return jdbc.query("SELECT * FROM output_storage_cleanup_job WHERE (state='PENDING' AND safe_after<=? AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?)) OR (state='CLAIMED' AND lease_until<?) ORDER BY next_attempt_at,cleanup_id LIMIT ?",CLEANUP,now,now,now,now,limit);
    }
    @Override public CleanupRow findCleanup(byte[] id,String t,String c,boolean lock) {
        return one("SELECT * FROM output_storage_cleanup_job WHERE tenant_id=? AND client_id=? AND cleanup_id=?"+(lock?" FOR UPDATE":""),CLEANUP,t,c,id);
    }
    @Override public int claimCleanup(byte[] id,String t,String c,String owner,long until,long now) {
        return jdbc.update("UPDATE output_storage_cleanup_job SET state='CLAIMED',lease_owner=?,lease_until=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND cleanup_id=? AND ((state='PENDING' AND safe_after<=? AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<?)) OR (state='CLAIMED' AND lease_until<?))",owner,until,now,t,c,id,now,now,now,now);
    }
    @Override public int finishCleanup(byte[] id,String t,String c,String owner,long now) {
        return jdbc.update("UPDATE output_storage_cleanup_job SET state='DONE',lease_owner=NULL,lease_until=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND cleanup_id=? AND state='CLAIMED' AND lease_owner=?",now,t,c,id,owner);
    }
    @Override public int finishRejectedObjectForCleanup(String t,String c,String object,String key,String version,long now) {
        return jdbc.update("UPDATE output_object o SET o.lifecycle_status='DELETED',o.deleted_at=?,o.updated_at=?,o.row_version=o.row_version+1 WHERE o.tenant_id=? AND o.client_id=? AND o.object_id=? AND o.lifecycle_status='STAGED' AND o.storage_key=? AND o.storage_version<=>? AND EXISTS (SELECT 1 FROM output_upload_session u WHERE u.tenant_id=o.tenant_id AND u.client_id=o.client_id AND u.object_id=o.object_id AND u.state IN ('REJECTED','EXPIRED'))",now,now,t,c,object,key,version);
    }
    @Override public int retryCleanup(byte[] id,String t,String c,String owner,long next,String error,long now) {
        return jdbc.update("UPDATE output_storage_cleanup_job SET state='PENDING',attempts=attempts+1,next_attempt_at=?,lease_owner=NULL,lease_until=NULL,last_error=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND cleanup_id=? AND state='CLAIMED' AND lease_owner=?",next,error,now,t,c,id,owner);
    }

    @Override public List<UploadRow> findDueVerification(long now,int limit) {
        return jdbc.query("SELECT * FROM output_upload_session WHERE state='VERIFYING' AND verification_next_at<=? AND (verification_lease_until IS NULL OR verification_lease_until<?) ORDER BY verification_next_at,upload_id LIMIT ?",UPLOAD,now,now,limit);
    }
    @Override public int claimVerification(String t,String c,String id,String owner,long until,long now) {
        return jdbc.update("UPDATE output_upload_session SET verification_lease_owner=?,verification_lease_until=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state='VERIFYING' AND verification_next_at<=? AND (verification_lease_until IS NULL OR verification_lease_until<?)",owner,until,now,t,c,id,now,now);
    }
    @Override public int retryVerification(String t,String c,String id,String owner,long next,String error,long now) {
        return jdbc.update("UPDATE output_upload_session SET verification_attempts=verification_attempts+1,verification_next_at=?,verification_lease_owner=NULL,verification_lease_until=NULL,error_code=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND upload_id=? AND state='VERIFYING' AND verification_lease_owner=?",next,error,now,t,c,id,owner);
    }
    @Override public List<UploadRow> findExpiredUploads(long now,int limit) {
        return jdbc.query("SELECT * FROM output_upload_session WHERE state IN ('CREATED','UPLOADING') AND expires_at<? ORDER BY expires_at,upload_id LIMIT ?",UPLOAD,now,limit);
    }

    @Override public List<ObjectRow> findDueObjects(long now,int limit) {
        return jdbc.query("SELECT * FROM output_object WHERE (lifecycle_status='READY' AND delete_after IS NOT NULL AND delete_after<=? AND (delete_next_at IS NULL OR delete_next_at<=?) AND (delete_lease_until IS NULL OR delete_lease_until<?)) OR (lifecycle_status='DELETING' AND (delete_next_at IS NULL OR delete_next_at<=?) AND (delete_lease_until IS NULL OR delete_lease_until<?)) ORDER BY COALESCE(delete_next_at,delete_after),object_id LIMIT ?",OBJECT,now,now,now,now,now,limit);
    }
    @Override public int claimObjectDelete(String t,String c,String id,String owner,long until,long now) {
        return jdbc.update("UPDATE output_object SET lifecycle_status='DELETING',delete_lease_owner=?,delete_lease_until=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=? AND ((lifecycle_status='READY' AND delete_after<=? AND (delete_next_at IS NULL OR delete_next_at<=?) AND (delete_lease_until IS NULL OR delete_lease_until<?)) OR (lifecycle_status='DELETING' AND (delete_next_at IS NULL OR delete_next_at<=?) AND (delete_lease_until IS NULL OR delete_lease_until<?)))",owner,until,now,t,c,id,now,now,now,now,now);
    }
    @Override public long activeObjectReferences(String t,String c,String id,long now) {
        Long n=jdbc.queryForObject("SELECT COUNT(*) FROM output_object_reference WHERE tenant_id=? AND client_id=? AND object_id=? AND state='ACTIVE' AND (hold=TRUE OR retain_until IS NULL OR retain_until>?)",Long.class,t,c,id,now);return n==null?0:n;
    }
    @Override public int finishObjectDelete(String t,String c,String id,String owner,long now) {
        return jdbc.update("UPDATE output_object SET lifecycle_status='DELETED',deleted_at=?,delete_lease_owner=NULL,delete_lease_until=NULL,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=? AND lifecycle_status='DELETING' AND delete_lease_owner=?",now,now,t,c,id,owner);
    }
    @Override public int retryObjectDelete(String t,String c,String id,String owner,long next,String error,long now) {
        return jdbc.update("UPDATE output_object SET delete_attempts=delete_attempts+1,delete_next_at=?,delete_lease_owner=NULL,delete_lease_until=NULL,error_code=?,updated_at=?,row_version=row_version+1 WHERE tenant_id=? AND client_id=? AND object_id=? AND lifecycle_status='DELETING' AND delete_lease_owner=?",next,error,now,t,c,id,owner);
    }

    private <T>T one(String sql,RowMapper<T> mapper,Object...args){List<T> rows=jdbc.query(sql,mapper,args);return rows.isEmpty()?null:rows.getFirst();}
    private static Long nullableLong(ResultSet r,String n)throws SQLException{long v=r.getLong(n);return r.wasNull()?null:v;}
    private static final RowMapper<UploadRow> UPLOAD=(r,n)->new UploadRow(r.getString("tenant_id"),r.getString("client_id"),r.getString("upload_id"),r.getString("run_id"),r.getString("binding_id"),r.getString("object_id"),r.getString("file_name"),r.getLong("expected_size"),r.getBytes("expected_sha256"),r.getString("declared_mime"),r.getString("state"),r.getLong("writer_epoch"),nullableLong(r,"writer_started_at"),nullableLong(r,"writer_until"),nullableLong(r,"writer_deadline_at"),r.getLong("expires_at"),r.getLong("reserved_bytes"),r.getBoolean("slot_released"),r.getInt("verification_attempts"),nullableLong(r,"verification_next_at"),r.getString("verification_lease_owner"),nullableLong(r,"verification_lease_until"),r.getString("error_code"));
    private static final RowMapper<ObjectRow> OBJECT=(r,n)->new ObjectRow(r.getString("tenant_id"),r.getString("client_id"),r.getString("object_id"),r.getString("run_id"),r.getString("bucket"),r.getString("storage_key"),r.getString("storage_version"),r.getBytes("actual_sha256"),nullableLong(r,"actual_size"),r.getString("actual_mime"),r.getString("verification_status"),r.getString("lifecycle_status"),r.getString("scan_engine_version"),nullableLong(r,"verified_at"),nullableLong(r,"delete_after"),nullableLong(r,"deleted_at"),r.getInt("delete_attempts"),nullableLong(r,"delete_next_at"),r.getString("delete_lease_owner"),nullableLong(r,"delete_lease_until"),r.getString("error_code"));
    private static final RowMapper<CleanupRow> CLEANUP=(r,n)->new CleanupRow(r.getBytes("cleanup_id"),r.getString("tenant_id"),r.getString("client_id"),r.getString("object_id"),r.getString("upload_id"),r.getLong("writer_epoch"),r.getString("bucket"),r.getString("storage_key"),r.getString("storage_version"),r.getString("state"),r.getString("quota_charge_kind"),r.getLong("quota_charge_bytes"),r.getLong("safe_after"),r.getInt("attempts"),r.getLong("next_attempt_at"),r.getString("lease_owner"),nullableLong(r,"lease_until"));
}
