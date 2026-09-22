package cn.jia.agent.service.impl;

import cn.jia.agent.dao.HallPrivateCaseDao;
import cn.jia.agent.dao.HallPrivateMarkDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.HallPrivateMarkEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.HallPrivateMarkService;
import cn.jia.agent.service.HallReadService;
import cn.jia.agent.service.HallRequestDraftService;
import cn.jia.agent.service.HallRequestDraftService.OwnerScope;
import cn.jia.agent.service.HallRequestDraftService.Reason;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

@Named
public class HallPrivateMarkServiceImpl implements HallPrivateMarkService {
    private static final long MAX_REVISION = 9_007_199_254_740_991L; // existing Hall JSON integer contract
    private final HallPrivateMarkDao marks;
    private final HallPrivateCaseDao cases;
    private final PersonalWorkspaceExecutionDao executions;
    private final HallRequestDraftService results;
    private final LongSupplier clock;
    @Inject public HallPrivateMarkServiceImpl(HallPrivateMarkDao marks, HallPrivateCaseDao cases,
            PersonalWorkspaceExecutionDao executions, HallRequestDraftService results) {
        this(marks, cases, executions, results, System::currentTimeMillis);
    }
    HallPrivateMarkServiceImpl(HallPrivateMarkDao marks, HallPrivateCaseDao cases,
            PersonalWorkspaceExecutionDao executions, HallRequestDraftService results, LongSupplier clock) {
        this.marks=Objects.requireNonNull(marks); this.cases=Objects.requireNonNull(cases);
        this.executions=Objects.requireNonNull(executions); this.results=Objects.requireNonNull(results);
        this.clock=Objects.requireNonNull(clock);
    }
    @Override @Transactional(readOnly=true, rollbackFor=Exception.class)
    public View get(OwnerScope scope, String type, String id) {
        validScope(scope); validSource(type,id);
        Subject subject = subject(scope,type,id,false);
        HallPrivateMarkEntity current = marks.current(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),type,id);
        if (current == null) return new View(new HallReadService.Ref(type,id),0,false,null,subject.updatedAt());
        validStored(scope,type,id,current);
        return view(current, effectiveArchive(current,subject));
    }
    @Override @Transactional(isolation=Isolation.READ_COMMITTED,rollbackFor=Exception.class)
    public View mark(OwnerScope scope, String type, String id, Command command, String key) {
        validScope(scope); validSource(type,id); validId(key,100);
        if (command==null || command.expectedRevision()<0 || command.expectedRevision()>=MAX_REVISION) throw failure(Reason.BAD_REQUEST);
        ResultRef viewed=command.viewedResultRef();
        if (viewed!=null) { validId(viewed.executionId(),100); validId(viewed.manifestId(),100); }
        String hash=hash(type,id,Long.toString(command.expectedRevision()),Boolean.toString(command.archived()),
                viewed==null?null:viewed.executionId(),viewed==null?null:viewed.manifestId());
        // Source ACL and source lock always precede mark operations, including replay. No file/task writes.
        Subject subject=subject(scope,type,id,true);
        HallPrivateMarkEntity replay=marks.byKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),key);
        if (replay!=null) return replay(scope,type,id,key,hash,replay);
        HallPrivateMarkEntity current=marks.current(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),type,id);
        if (current!=null) validStored(scope,type,id,current);
        long revision=current==null?0:current.getRevision();
        if (revision!=command.expectedRevision()) throw failure(Reason.REVISION_CHANGED);
        if (viewed!=null) {
            if (!subject.executionId().equals(viewed.executionId()) || !"OUTPUT_COMMITTED".equals(subject.state())) throw failure(Reason.EXECUTION_CONFLICT);
            // Reuse B03's exact canonical manifest, file/version visibility and immutable output checks.
            var output=results.getExecutionResults(scope,viewed.executionId());
            if (!viewed.executionId().equals(output.executionId()) || !viewed.manifestId().equals(output.manifestId())
                    || !"OUTPUT_COMMITTED".equals(output.state()) || output.items().isEmpty()
                    || output.items().stream().anyMatch(item -> !"AVAILABLE".equals(item.availability()))) {
                throw failure(Reason.SOURCE_UNAVAILABLE);
            }
        }
        HallPrivateMarkEntity next=new HallPrivateMarkEntity().setTenantId(scope.tenantId()).setClientId(scope.clientId())
                .setOwnerJiacn(scope.ownerJiacn()).setSourceType(type).setSourceId(id).setRevision(revision+1)
                .setOperationKey(key).setRequestHash(hash).setArchived(command.archived())
                .setSnapshotExecutionId(subject.executionId()).setSnapshotState(subject.state()).setSnapshotUpdatedAt(subject.updatedAt())
                .setViewedExecutionId(viewed==null?(current==null?null:current.getViewedExecutionId()):viewed.executionId())
                .setViewedManifestId(viewed==null?(current==null?null:current.getViewedManifestId()):viewed.manifestId())
                .setUpdatedAt(clock.getAsLong());
        try { if (marks.insert(next)!=1) throw failure(Reason.STORAGE_UNAVAILABLE); }
        catch (DuplicateKeyException collision) {
            replay=marks.byKey(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),key);
            if (replay!=null) return replay(scope,type,id,key,hash,replay);
            throw failure(Reason.REVISION_CHANGED);
        }
        return view(next,command.archived());
    }
    private Subject subject(OwnerScope scope,String type,String id,boolean lock) {
        String executionId=id; long caseUpdated=0;
        if ("PRIVATE_CASE".equals(type)) {
            var privateCase=lock?cases.lock(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),id)
                    :cases.find(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),id);
            if (privateCase==null) throw failure(Reason.NOT_FOUND);
            if (!id.equals(privateCase.getCaseId()) || !scope.tenantId().equals(privateCase.getTenantId())
                    || !scope.clientId().equals(privateCase.getClientId()) || !scope.ownerJiacn().equals(privateCase.getOwnerJiacn())
                    || privateCase.getRevision()==null || privateCase.getRevision()<1
                    || privateCase.getUpdatedAt()==null || privateCase.getUpdatedAt()<0) throw failure(Reason.STORAGE_UNAVAILABLE);
            var relations=cases.listExecutions(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),id);
            if (relations==null || relations.isEmpty()) throw failure(Reason.STORAGE_UNAVAILABLE);
            var last=relations.getLast();
            if (!id.equals(last.getCaseId()) || !scope.tenantId().equals(last.getTenantId())
                    || !scope.clientId().equals(last.getClientId()) || !scope.ownerJiacn().equals(last.getOwnerJiacn())
                    || !privateCase.getRevision().equals(last.getRevisionNo())) throw failure(Reason.STORAGE_UNAVAILABLE);
            executionId=last.getExecutionId();caseUpdated=privateCase.getUpdatedAt();
        }
        validId(executionId,100);
        PersonalWorkspaceExecutionEntity execution=lock?executions.lock(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),executionId)
                :executions.find(scope.tenantId(),scope.clientId(),scope.ownerJiacn(),executionId);
        if (execution==null || !"PRIVATE".equals(execution.getExecutionMode())) throw failure(Reason.NOT_FOUND);
        if (!executionId.equals(execution.getExecutionId()) || !scope.tenantId().equals(execution.getTenantId())
                || !scope.clientId().equals(execution.getClientId()) || !scope.ownerJiacn().equals(execution.getOwnerJiacn())
                || execution.getCreatedAt()==null || execution.getCreatedAt()<0 || execution.getExecutionState()==null
                || !Set.of("QUEUED","OUTPUT_STAGED","OUTPUT_COMMITTED","FAILED","INPUTS_REVOKED").contains(execution.getExecutionState())) throw failure(Reason.STORAGE_UNAVAILABLE);
        long time=Math.max(caseUpdated,execution.getCreatedAt());
        for (Long value : new Long[]{execution.getUpdateTime(),execution.getFailedAt(),execution.getRevokedAt()}) if (value!=null) time=Math.max(time,value);
        return new Subject(executionId,execution.getExecutionState(),time);
    }
    private static View replay(OwnerScope scope,String type,String id,String key,String hash,HallPrivateMarkEntity row) {
        // Cross-item use of one key is a conflict, not permission to read that item's receipt.
        if (!type.equals(row.getSourceType()) || !id.equals(row.getSourceId()) || !key.equals(row.getOperationKey()) || !hash.equals(row.getRequestHash())) throw failure(Reason.IDEMPOTENCY_CONFLICT);
        validStored(scope,type,id,row);return view(row,row.getArchived());
    }
    private static boolean effectiveArchive(HallPrivateMarkEntity row,Subject subject) {
        return row.getArchived() && subject.executionId().equals(row.getSnapshotExecutionId())
                && subject.state().equals(row.getSnapshotState()) && subject.updatedAt()==row.getSnapshotUpdatedAt();
    }
    private static View view(HallPrivateMarkEntity row,boolean archived) {
        return new View(new HallReadService.Ref(row.getSourceType(),row.getSourceId()),row.getRevision(),archived,
                row.getViewedExecutionId()==null?null:new ResultRef(row.getViewedExecutionId(),row.getViewedManifestId()),row.getUpdatedAt());
    }
    private static void validStored(OwnerScope scope,String type,String id,HallPrivateMarkEntity row) {
        if (!scope.tenantId().equals(row.getTenantId()) || !scope.clientId().equals(row.getClientId()) || !scope.ownerJiacn().equals(row.getOwnerJiacn())
                || !type.equals(row.getSourceType()) || !id.equals(row.getSourceId()) || row.getRevision()==null || row.getRevision()<1
                || row.getRevision()>MAX_REVISION || row.getArchived()==null
                || !storedId(row.getSnapshotExecutionId(),100)
                || !Set.of("QUEUED","OUTPUT_STAGED","OUTPUT_COMMITTED","FAILED","INPUTS_REVOKED").contains(Objects.toString(row.getSnapshotState(),""))
                || row.getSnapshotUpdatedAt()==null || row.getSnapshotUpdatedAt()<0
                || row.getUpdatedAt()==null || row.getUpdatedAt()<0
                || (row.getViewedExecutionId()==null)!=(row.getViewedManifestId()==null)
                || (row.getViewedExecutionId()!=null && (!storedId(row.getViewedExecutionId(),100)
                    || !storedId(row.getViewedManifestId(),100)))) throw failure(Reason.STORAGE_UNAVAILABLE);
    }
    private static void validScope(OwnerScope scope) {
        if (scope==null || !"0".equals(scope.tenantId()) || "0".equals(scope.ownerJiacn())) throw failure(Reason.BAD_REQUEST);
        validId(scope.clientId(),50);validId(scope.ownerJiacn(),50);
    }
    private static void validSource(String type,String id) {
        if (!"PRIVATE_CASE".equals(type) && !"LEGACY_EXECUTION".equals(type)) throw failure(Reason.SOURCE_UNAVAILABLE);
        validId(id,100);
    }
    private static void validId(String value,int max) {
        if (!storedId(value,max)) throw failure(Reason.BAD_REQUEST);
    }
    private static boolean storedId(String value,int max) {
        return value!=null && !value.isBlank() && value.equals(value.strip()) && value.codePointCount(0,value.length())<=max
                && value.codePoints().noneMatch(c -> Character.isISOControl(c) || (c>=0xd800 && c<=0xdfff));
    }
    private static String hash(String... values) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for (String value:values) {
                byte[] bytes=value==null?new byte[0]:value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(value==null?-1:bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static HallRequestDraftService.Failure failure(Reason reason) {return new HallRequestDraftService.Failure(reason);}
    private record Subject(String executionId,String state,long updatedAt) { }
}
