package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputMalwareScanner;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputUploadException;
import cn.jia.agent.output.OutputUploadService;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dto.OutputUploadCreateDTO;
import cn.jia.agent.output.dto.OutputUploadDTO;
import cn.jia.core.util.JsonUtil;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.CannotAcquireLockException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** OD02 transaction coordinator. All storage and scanner I/O is outside SQL transactions. */
public final class OutputUploadServiceImpl implements OutputUploadService {
    private static final String ACTOR="RUN_TICKET", OP_CREATE="createUpload", OP_COMPLETE="completeUpload";
    private static final tools.jackson.databind.ObjectMapper STRICT_JSON=JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> ALLOWED_MIME=Set.of("text/plain","text/markdown","text/csv","application/json","image/png","image/jpeg","image/webp","application/pdf","application/zip","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation");
    private final OutputRunAuthorizationService authorization;
    private final OutputUploadDao dao;
    private final OutputObjectStorage storage;
    private final OutputMalwareScanner scanner;
    private final TransactionTemplate tx;
    private final String bucket;
    private final int retryMultiplier;
    private final long archiveMemberMaxBytes;
    private final long archiveTreeMaxBytes;
    private final Path archiveTempDirectory;
    private final String worker=UUID.randomUUID().toString();

    public OutputUploadServiceImpl(OutputRunAuthorizationService authorization,OutputUploadDao dao,
            OutputObjectStorage storage,OutputMalwareScanner scanner,
            PlatformTransactionManager transactionManager,OutputDeliveryProperties properties){
        this.authorization=Objects.requireNonNull(authorization);this.dao=Objects.requireNonNull(dao);
        this.storage=Objects.requireNonNull(storage);this.scanner=Objects.requireNonNull(scanner);
        this.tx=new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.bucket=properties.storageBucket();this.retryMultiplier=properties.retryMultiplier();
        this.archiveMemberMaxBytes=properties.archiveMemberMaxBytes();
        this.archiveTreeMaxBytes=properties.archiveTreeMaxBytes();
        this.archiveTempDirectory=properties.archiveTempDirectory()==null?null:Path.of(properties.archiveTempDirectory());
    }

    @Override public OutputUploadDTO create(String bearer,String key,OutputUploadCreateDTO request){
        requireKey(key);validateCreate(request);byte[] requestHash=sha256(canonicalCreate(request));
        OutputUploadDTO result=withLockRetry(()->tx.execute(s->{
            OutputTicketAuthorization auth=authorize(bearer);
            requireRoute(auth,request.runId(),request.source().type(),request.source().id());
            OutputUploadDao.Receipt receipt=dao.lockReceipt(auth.tenantId(),auth.clientId(),ACTOR,auth.runId(),OP_CREATE,key);
            if(receipt!=null)return replay(receipt,requestHash);
            requireMutable(auth);
            long now=System.currentTimeMillis(),size=parseSize(request.size());
            dao.ensureScopeQuota(auth.tenantId(),auth.clientId(),OutputConstants.MAX_SCOPE_BYTES,OutputConstants.MAX_SCOPE_UPLOADS,now);
            OutputUploadDao.ScopeQuota scope=required(dao.lockScopeQuota(auth.tenantId(),auth.clientId()));
            dao.ensureBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId(),OutputConstants.MAX_BINDING_UPLOADS,now);
            OutputUploadDao.BindingQuota binding=required(dao.lockBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId()));
            dao.ensureRunQuota(auth.tenantId(),auth.clientId(),auth.runId(),
                    Math.multiplyExact((long)100,retryMultiplier),Math.multiplyExact(209_715_200L,retryMultiplier),now);
            OutputUploadDao.RunQuota runQuota=required(dao.lockRunQuota(auth.tenantId(),auth.clientId(),auth.runId()));
            if(scope.activeUploads()>=scope.maxActiveUploads()||binding.activeUploads()>=binding.maxActiveUploads())throw rate("OUTPUT_UPLOAD_CONCURRENCY_LIMIT");
            if(scope.reservedBytes()+scope.storedBytes()+size>scope.maxBytes())throw tooLarge("OUTPUT_SCOPE_QUOTA_EXCEEDED");
            if(runQuota.uploadRequests()>=runQuota.maxUploadRequests())throw rate("OUTPUT_RUN_REQUEST_LIMIT");
            if(dao.activeRunFiles(auth.tenantId(),auth.clientId(),auth.runId())>=100||dao.activeRunBytes(auth.tenantId(),auth.clientId(),auth.runId())+size>209_715_200L)throw tooLarge("OUTPUT_RUN_QUOTA_EXCEEDED");
            String upload=id(),object=id();long expires=Math.addExact(now,OutputConstants.UPLOAD_SESSION_MILLIS);
            OutputUploadDao.ObjectRow objectRow=new OutputUploadDao.ObjectRow(auth.tenantId(),auth.clientId(),object,auth.runId(),bucket,null,null,null,null,null,"PENDING","STAGED",null,null,null,null,0,null,null,null,null);
            OutputUploadDao.UploadRow uploadRow=new OutputUploadDao.UploadRow(auth.tenantId(),auth.clientId(),upload,auth.runId(),auth.bindingId(),object,request.name(),size,HexFormat.of().parseHex(request.sha256()),request.mime().toLowerCase(),OutputConstants.UPLOAD_CREATED,0,null,null,null,expires,size,false,0,null,null,null,null);
            if(dao.insertObject(objectRow,now)!=1||dao.insertUpload(uploadRow,now)!=1||dao.changeScopeQuota(auth.tenantId(),auth.clientId(),size,0,1,now)!=1||dao.changeBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId(),1,now)!=1||dao.changeRunQuota(auth.tenantId(),auth.clientId(),auth.runId(),1,0,now)!=1)throw unavailable("OUTPUT_QUOTA_COMMIT_FAILED");
            OutputUploadDTO dto=wire(uploadRow,"/agent/output-uploads/"+upload+"/content");String json=JsonUtil.toJson(dto);
            if(dao.insertReceipt(auth.tenantId(),auth.clientId(),ACTOR,auth.runId(),OP_CREATE,key,requestHash,200,json,Math.addExact(expires,604_800_000L),now)!=1)throw conflict("IDEMPOTENCY_CONFLICT");
            return dto;
        }));
        if(result==null)throw unavailable("OUTPUT_TRANSACTION_EMPTY");return result;
    }

    @Override public OutputUploadDTO put(String bearer,String uploadId,InputStream bytes){
        requireId(uploadId);Objects.requireNonNull(bytes,"bytes");recoverCleanup(10);
        Writer writer=tx.execute(s->beginWriter(bearer,uploadId));if(writer==null)throw unavailable("OUTPUT_TRANSACTION_EMPTY");
        InspectingInputStream inspected=new InspectingInputStream(bytes,writer.expectedSize(),(now)->renew(writer,now));
        OutputObjectStorage.Stored stored;
        try{
            stored=storage.putCreateOnly(bucket,writer.storageKey(),inspected,writer.expectedSize(),writer.declaredMime(),writer.deadline());
            int extra=inspected.read();
            if(extra!=-1||inspected.count()!=writer.expectedSize())throw new InspectingInputStream.SizeLimitExceededException();
        }catch(Exception failure){
            abandonWriter(writer);
            if(failure instanceof InspectingInputStream.SizeLimitExceededException
                    ||(inspected.eof()&&inspected.count()!=writer.expectedSize()))throw sizeMismatch();
            throw unavailable("OUTPUT_STORAGE_WRITE_FAILED");
        }
        byte[] actualHash=inspected.hash();String actualMime;
        try{actualMime=OutputContentInspector.requireAllowed(writer.fileName(),writer.declaredMime(),inspected.sample(),false,archiveMemberMaxBytes,archiveTreeMaxBytes,archiveTempDirectory);}
        catch(OutputUploadException rejected){settleRejectedWriter(writer,stored,actualHash,inspected.count(),writer.declaredMime(),rejected.code());throw rejected;}
        if(!Arrays.equals(actualHash,writer.expectedHash())){settleRejectedWriter(writer,stored,actualHash,inspected.count(),actualMime,"OUTPUT_HASH_MISMATCH");throw new OutputUploadException("OUTPUT_HASH_MISMATCH","Output hash mismatch",422);}
        return settleUploaded(writer,stored,actualHash,inspected.count(),actualMime);
    }

    @Override public OutputUploadDTO complete(String bearer,String uploadId,String key){
        requireId(uploadId);requireKey(key);
        CompleteStart start=tx.execute(s->{
            OutputTicketAuthorization auth=authorize(bearer);
            byte[] hash=sha256(canonicalComplete(auth.runId(),uploadId));
            OutputUploadDao.Receipt receipt=dao.lockReceipt(auth.tenantId(),auth.clientId(),ACTOR,auth.runId(),OP_COMPLETE,key);
            if(receipt!=null)return new CompleteStart(replay(receipt,hash),false);
            requireMutable(auth);
            long now=System.currentTimeMillis();dao.ensureScopeQuota(auth.tenantId(),auth.clientId(),OutputConstants.MAX_SCOPE_BYTES,OutputConstants.MAX_SCOPE_UPLOADS,now);dao.lockScopeQuota(auth.tenantId(),auth.clientId());dao.ensureBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId(),OutputConstants.MAX_BINDING_UPLOADS,now);dao.lockBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId());
            OutputUploadDao.UploadRow upload=exactUpload(auth,uploadId,true);
            if(OutputConstants.UPLOAD_READY.equals(upload.state())||OutputConstants.UPLOAD_REJECTED.equals(upload.state()))throw conflict("OUTPUT_UPLOAD_TERMINAL");
            OutputUploadDao.ObjectRow object=required(dao.findObject(auth.tenantId(),auth.clientId(),upload.objectId(),true));
            OutputUploadDao.CleanupRow settled=dao.findCleanupByUploadEpoch(
                    auth.tenantId(),auth.clientId(),uploadId,upload.writerEpoch(),true);
            if(object.storageKey()==null||object.actualSha256()==null||upload.writerUntil()!=null
                    ||!isSettledIdentity(upload,object,settled))throw conflict("OUTPUT_UPLOAD_INCOMPLETE");
            if(!OutputConstants.UPLOAD_VERIFYING.equals(upload.state())&&dao.markVerifying(auth.tenantId(),auth.clientId(),uploadId,now)!=1)throw conflict("OUTPUT_UPLOAD_STATE_CONFLICT");
            if(!upload.slotReleased()&&dao.releaseUploadSlot(auth.tenantId(),auth.clientId(),uploadId,now)==1){if(dao.changeScopeQuota(auth.tenantId(),auth.clientId(),0,0,-1,now)!=1||dao.changeBindingQuota(auth.tenantId(),auth.clientId(),auth.bindingId(),-1,now)!=1)throw unavailable("OUTPUT_SLOT_RELEASE_FAILED");}
            OutputUploadDTO dto=new OutputUploadDTO(uploadId,OutputConstants.UPLOAD_VERIFYING,null,Long.toString(upload.expiresAt()),upload.objectId(),null);String json=JsonUtil.toJson(dto);
            if(dao.insertReceipt(auth.tenantId(),auth.clientId(),ACTOR,auth.runId(),OP_COMPLETE,key,hash,202,json,Math.addExact(upload.expiresAt(),604_800_000L),now)!=1)throw conflict("IDEMPOTENCY_CONFLICT");
            return new CompleteStart(dto,true);
        });
        if(start==null)throw unavailable("OUTPUT_TRANSACTION_EMPTY");if(start.verify())processVerification(uploadId);return start.dto();
    }

    @Override public OutputUploadDTO status(String bearer,String uploadId){
        requireId(uploadId);OutputUploadDTO dto=tx.execute(s->{OutputTicketAuthorization auth=authorize(bearer);return wire(exactUpload(auth,uploadId,false),null);});if(dto==null)throw unavailable("OUTPUT_TRANSACTION_EMPTY");return dto;
    }

    @Override public int recover(int limit){if(limit<1||limit>100)throw new IllegalArgumentException("limit");int count=recoverCleanup(limit);for(OutputUploadDao.UploadRow row:dao.findDueVerification(System.currentTimeMillis(),limit-count)){processVerification(row.uploadId());count++;}recoverExpired(Math.max(0,limit-count));recoverObjects(Math.max(0,limit-count));return count;}

    private Writer beginWriter(String bearer,String uploadId){
        OutputTicketAuthorization auth=authorize(bearer);requireMutable(auth);long now=System.currentTimeMillis();
        dao.ensureScopeQuota(auth.tenantId(),auth.clientId(),OutputConstants.MAX_SCOPE_BYTES,OutputConstants.MAX_SCOPE_UPLOADS,now);OutputUploadDao.ScopeQuota scope=required(dao.lockScopeQuota(auth.tenantId(),auth.clientId()));
        dao.ensureRunQuota(auth.tenantId(),auth.clientId(),auth.runId(),1_000,Math.multiplyExact(209_715_200L,retryMultiplier),now);OutputUploadDao.RunQuota run=required(dao.lockRunQuota(auth.tenantId(),auth.clientId(),auth.runId()));
        OutputUploadDao.UploadRow upload=exactUpload(auth,uploadId,true);
        if(upload.expiresAt()<now)throw gone("OUTPUT_UPLOAD_EXPIRED");if(upload.writerEpoch()>=10)throw rate("OUTPUT_WRITER_EPOCH_LIMIT");if(run.attemptBytes()+upload.expectedSize()>run.maxAttemptBytes())throw rate("OUTPUT_RUN_RETRY_BYTES_LIMIT");if(dao.countOpenCleanup(auth.tenantId(),auth.clientId(),uploadId)>=2)throw rate("OUTPUT_STAGING_CLEANUP_REQUIRED");
        long next=Math.addExact(upload.writerEpoch(),1),deadline=Math.addExact(now,OutputConstants.UPLOAD_HARD_DEADLINE_MILLIS),until=Math.min(deadline,Math.addExact(now,OutputConstants.WRITER_LEASE_MILLIS));
        if(upload.writerEpoch()>0){if(scope.reservedBytes()+scope.storedBytes()+upload.expectedSize()>scope.maxBytes())throw tooLarge("OUTPUT_SCOPE_QUOTA_EXCEEDED");if(dao.changeScopeQuota(auth.tenantId(),auth.clientId(),upload.expectedSize(),0,0,now)!=1)throw tooLarge("OUTPUT_SCOPE_QUOTA_EXCEEDED");ensureCleanupPending(upload,now);}
        if(dao.changeRunQuota(auth.tenantId(),auth.clientId(),auth.runId(),0,upload.expectedSize(),now)!=1)throw rate("OUTPUT_RUN_RETRY_BYTES_LIMIT");
        String storageKey="staging/"+hex(sha256(auth.tenantId()+"\0"+auth.clientId()))+"/"+uploadId+"/epoch-"+next;
        byte[] cleanupId=cleanupId(auth.tenantId(),auth.clientId(),bucket,storageKey,"");
        OutputUploadDao.CleanupRow job=new OutputUploadDao.CleanupRow(cleanupId,auth.tenantId(),auth.clientId(),upload.objectId(),uploadId,next,bucket,storageKey,null,"HELD","NONE",0,now,0,now,null,null);
        if(dao.insertCleanup(job,now)!=1||dao.beginWriter(auth.tenantId(),auth.clientId(),uploadId,upload.writerEpoch(),next,now,until,deadline)!=1)throw conflict("OUTPUT_WRITER_BUSY");
        return new Writer(auth.tenantId(),auth.clientId(),auth.runId(),uploadId,upload.objectId(),upload.fileName(),upload.expectedSize(),upload.expectedSha256(),upload.declaredMime(),next,deadline,storageKey,bearer);
    }

    private void renew(Writer w,long now)throws IOException{Boolean ok=tx.execute(s->{authorize(w.bearer());long until=Math.min(w.deadline(),Math.addExact(now,OutputConstants.WRITER_LEASE_MILLIS));return dao.renewWriter(w.tenant(),w.client(),w.upload(),w.epoch(),now,until)==1;});if(!Boolean.TRUE.equals(ok))throw new IOException("Writer lease lost");}
    private void abandonWriter(Writer w){tx.executeWithoutResult(s->{long now=System.currentTimeMillis();dao.lockScopeQuota(w.tenant(),w.client());OutputUploadDao.UploadRow current=dao.findUpload(w.tenant(),w.client(),w.upload(),true);if(current!=null&&current.writerEpoch()==w.epoch()){if(dao.failWriter(w.tenant(),w.client(),w.upload(),w.epoch(),now)!=1)throw conflict("OUTPUT_WRITER_FENCED");ensureCleanupPending(current,now);}});}
    private OutputUploadDTO settleUploaded(Writer w,OutputObjectStorage.Stored stored,byte[] hash,long size,String mime){OutputUploadDTO dto=tx.execute(s->{authorize(w.bearer());long now=System.currentTimeMillis();OutputUploadDao.UploadRow current=dao.findUpload(w.tenant(),w.client(),w.upload(),true);if(current==null||current.writerEpoch()!=w.epoch()||current.writerDeadlineAt()<now)throw conflict("OUTPUT_WRITER_FENCED");if(dao.recordUploadedObject(w.tenant(),w.client(),w.upload(),w.epoch(),w.object(),w.storageKey(),stored.versionId(),hash,size,mime,now)!=1||dao.retainCleanup(w.tenant(),w.client(),w.upload(),w.epoch(),stored.versionId(),now)!=1)throw conflict("OUTPUT_WRITER_FENCED");return new OutputUploadDTO(w.upload(),OutputConstants.UPLOAD_UPLOADING,null,Long.toString(current.expiresAt()),w.object(),null);});if(dto==null)throw unavailable("OUTPUT_TRANSACTION_EMPTY");return dto;}
    private void settleRejectedWriter(Writer w,OutputObjectStorage.Stored stored,byte[] hash,long size,String mime,String error){tx.executeWithoutResult(s->{authorize(w.bearer());long now=System.currentTimeMillis();dao.lockScopeQuota(w.tenant(),w.client());OutputUploadDao.UploadRow current=dao.findUpload(w.tenant(),w.client(),w.upload(),true);if(current==null||current.writerEpoch()!=w.epoch())return;if(dao.recordUploadedObject(w.tenant(),w.client(),w.upload(),w.epoch(),w.object(),w.storageKey(),stored.versionId(),hash,size,mime,now)==1){dao.retainCleanup(w.tenant(),w.client(),w.upload(),w.epoch(),stored.versionId(),now);rejectLocked(current,error,now);}});}

    private void processVerification(String uploadId){
        OutputUploadDao.UploadRow claimed=tx.execute(s->{long now=System.currentTimeMillis();for(OutputUploadDao.UploadRow r:dao.findDueVerification(now,100))if(r.uploadId().equals(uploadId)&&dao.claimVerification(r.tenantId(),r.clientId(),r.uploadId(),worker,now+OutputConstants.JOB_LEASE_MILLIS,now)==1)return r;return null;});if(claimed==null)return;
        OutputUploadDao.ObjectRow object=dao.findObject(claimed.tenantId(),claimed.clientId(),claimed.objectId(),false);String error=null,scanVersion=null;
        if(object==null||object.storageKey()==null){rejectClaimedVerification(claimed,"OUTPUT_STORAGE_IDENTITY_INVALID");return;}
        try{byte[] bytes;try(InputStream in=storage.open(object.bucket(),object.storageKey(),object.storageVersion())){bytes=readLimited(in,claimed.expectedSize()+1);}if(bytes.length!=claimed.expectedSize()||!Arrays.equals(sha256(bytes),claimed.expectedSha256()))error="OUTPUT_HASH_MISMATCH";else{String mime=OutputContentInspector.requireAllowed(claimed.fileName(),claimed.declaredMime(),bytes,true,archiveMemberMaxBytes,archiveTreeMaxBytes,archiveTempDirectory);if("application/json".equals(mime))STRICT_JSON.readTree(bytes);if(!mime.equals(object.actualMime()))error="OUTPUT_TYPE_MISMATCH";else{OutputMalwareScanner.ScanResult scan;try(InputStream in=storage.open(object.bucket(),object.storageKey(),object.storageVersion())){scan=scanner.scan(in,claimed.expectedSize()+1);}scanVersion=scan.engineVersion();if(!scan.clean())error=scan.signature()!=null&&scan.signature().contains("Heuristics.Limits.Exceeded")?"OUTPUT_SCAN_LIMIT_EXCEEDED":"OUTPUT_MALWARE_DETECTED";}}}catch(OutputUploadException rejected){error=rejected.code();}catch(tools.jackson.core.JacksonException invalidJson){error="OUTPUT_JSON_INVALID";}catch(OutputContentInspector.ArchiveIoException archiveIo){retryVerification(claimed,"OUTPUT_ARCHIVE_IO_UNAVAILABLE");return;}catch(Exception outage){retryVerification(claimed,"OUTPUT_VERIFICATION_DEPENDENCY_UNAVAILABLE");return;}
        final String rejection=error,version=scanVersion;
        tx.executeWithoutResult(s->{
                boolean authorized=authorization.authorizePersistedMutation(claimed.tenantId(),claimed.clientId(),claimed.runId(),claimed.bindingId());
                long now=System.currentTimeMillis();dao.lockScopeQuota(claimed.tenantId(),claimed.clientId());OutputUploadDao.UploadRow current=dao.findUpload(claimed.tenantId(),claimed.clientId(),claimed.uploadId(),true);if(current==null||!worker.equals(current.verificationLeaseOwner()))return;
                if(!authorized){rejectLocked(current,"OUTPUT_AUTH_REVOKED",now);return;}
                OutputUploadDao.ObjectRow locked=dao.findObject(claimed.tenantId(),claimed.clientId(),claimed.objectId(),true);
                OutputUploadDao.CleanupRow settled=dao.findCleanupByUploadEpoch(claimed.tenantId(),claimed.clientId(),claimed.uploadId(),claimed.writerEpoch(),true);
                if(!isSettledIdentity(current,locked,settled)||!sameStorageIdentity(object,locked)){rejectLocked(current,"OUTPUT_STORAGE_IDENTITY_INVALID",now);return;}
                if(rejection==null){if(dao.markReady(claimed.tenantId(),claimed.clientId(),claimed.uploadId(),claimed.objectId(),version,now)!=1||dao.changeScopeQuota(claimed.tenantId(),claimed.clientId(),-claimed.expectedSize(),claimed.expectedSize(),0,now)!=1)throw unavailable("OUTPUT_READY_COMMIT_FAILED");}else rejectLocked(current,rejection,now);
            });
    }
    private void retryVerification(OutputUploadDao.UploadRow r,String error){tx.executeWithoutResult(s->{long now=System.currentTimeMillis();long delay=Math.min(300_000L,1_000L<<(Math.min(18,r.verificationAttempts())));dao.retryVerification(r.tenantId(),r.clientId(),r.uploadId(),worker,now+delay,error,now);});}
    private void rejectClaimedVerification(OutputUploadDao.UploadRow claimed,String error){tx.executeWithoutResult(s->{long now=System.currentTimeMillis();dao.lockScopeQuota(claimed.tenantId(),claimed.clientId());OutputUploadDao.UploadRow current=dao.findUpload(claimed.tenantId(),claimed.clientId(),claimed.uploadId(),true);if(current!=null&&worker.equals(current.verificationLeaseOwner()))rejectLocked(current,error,now);});}
    private void rejectLocked(OutputUploadDao.UploadRow upload,String error,long now){if(dao.markRejected(upload.tenantId(),upload.clientId(),upload.uploadId(),upload.objectId(),error,now)!=1)return;OutputUploadDao.CleanupRow cleanup=ensureCleanupPending(upload,now);if("DONE".equals(cleanup.state()))dao.finishRejectedObjectForCleanup(upload.tenantId(),upload.clientId(),upload.objectId(),cleanup.storageKey(),cleanup.storageVersion(),now);if(!upload.slotReleased()){if(dao.releaseUploadSlot(upload.tenantId(),upload.clientId(),upload.uploadId(),now)!=1||dao.changeScopeQuota(upload.tenantId(),upload.clientId(),0,0,-1,now)!=1||dao.changeBindingQuota(upload.tenantId(),upload.clientId(),upload.bindingId(),-1,now)!=1)throw unavailable("OUTPUT_SLOT_RELEASE_FAILED");}}

    private OutputUploadDao.CleanupRow ensureCleanupPending(OutputUploadDao.UploadRow upload,long now){OutputUploadDao.CleanupRow cleanup=dao.findCleanupByUploadEpoch(upload.tenantId(),upload.clientId(),upload.uploadId(),upload.writerEpoch(),true);if(cleanup==null)throw unavailable("OUTPUT_CLEANUP_MISSING");if(Set.of("PENDING","CLAIMED","DONE").contains(cleanup.state()))return cleanup;if(!Set.of("HELD","RETAINED").contains(cleanup.state())||dao.abandonCleanup(upload.tenantId(),upload.clientId(),upload.uploadId(),upload.writerEpoch(),"RESERVED",upload.expectedSize(),now,now)!=1)throw unavailable("OUTPUT_CLEANUP_STATE_CONFLICT");return required(dao.findCleanupByUploadEpoch(upload.tenantId(),upload.clientId(),upload.uploadId(),upload.writerEpoch(),true));}
    private static boolean isSettledIdentity(OutputUploadDao.UploadRow upload,OutputUploadDao.ObjectRow object,OutputUploadDao.CleanupRow cleanup){return upload!=null&&object!=null&&cleanup!=null&&upload.writerEpoch()>0&&upload.writerEpoch()==cleanup.writerEpoch()&&upload.uploadId().equals(cleanup.uploadId())&&upload.objectId().equals(cleanup.objectId())&&"RETAINED".equals(cleanup.state())&&Objects.equals(object.bucket(),cleanup.bucket())&&Objects.equals(object.storageKey(),cleanup.storageKey())&&Objects.equals(object.storageVersion(),cleanup.storageVersion());}
    private static boolean sameStorageIdentity(OutputUploadDao.ObjectRow scanned,OutputUploadDao.ObjectRow locked){return scanned!=null&&locked!=null&&Objects.equals(scanned.bucket(),locked.bucket())&&Objects.equals(scanned.storageKey(),locked.storageKey())&&Objects.equals(scanned.storageVersion(),locked.storageVersion())&&Arrays.equals(scanned.actualSha256(),locked.actualSha256())&&Objects.equals(scanned.actualSize(),locked.actualSize())&&Objects.equals(scanned.actualMime(),locked.actualMime());}

    private int recoverCleanup(int limit){
        int done=0;
        for(OutputUploadDao.CleanupRow candidate:dao.findDueCleanup(System.currentTimeMillis(),limit)){
            long now=System.currentTimeMillis();
            OutputUploadDao.CleanupRow job=tx.execute(s->{
                if(dao.claimCleanup(candidate.cleanupId(),candidate.tenantId(),candidate.clientId(),worker,
                        now+OutputConstants.JOB_LEASE_MILLIS,now)!=1)return null;
                return dao.findCleanup(candidate.cleanupId(),candidate.tenantId(),candidate.clientId(),false);
            });
            if(job==null)continue;
            String token=hex(job.cleanupId());
            try{
                storage.putTombstone(job.bucket(),job.storageKey(),token,System.currentTimeMillis()+60_000L);
                OutputObjectStorage.Head head=storage.head(job.bucket(),job.storageKey());
                if(head.size()!=0||!token.equals(head.cleanupToken()))throw new IOException("Tombstone verification failed");
                tx.executeWithoutResult(s->{
                    long at=System.currentTimeMillis();
                    dao.lockScopeQuota(job.tenantId(),job.clientId());
                    dao.findObject(job.tenantId(),job.clientId(),job.objectId(),true);
                    OutputUploadDao.CleanupRow locked=dao.findCleanup(job.cleanupId(),job.tenantId(),job.clientId(),true);
                    if(!sameClaim(job,locked))throw conflict("OUTPUT_CLEANUP_FENCED");
                    if("RESERVED".equals(locked.quotaChargeKind())&&dao.changeScopeQuota(job.tenantId(),job.clientId(),-locked.quotaChargeBytes(),0,0,at)!=1)
                        throw unavailable("OUTPUT_CLEANUP_QUOTA_FAILED");
                    if(dao.finishCleanup(job.cleanupId(),job.tenantId(),job.clientId(),worker,at)!=1)
                        throw conflict("OUTPUT_CLEANUP_FENCED");
                    dao.finishRejectedObjectForCleanup(job.tenantId(),job.clientId(),job.objectId(),job.storageKey(),job.storageVersion(),at);
                });
                done++;
            }catch(Exception e){
                tx.executeWithoutResult(s->dao.retryCleanup(job.cleanupId(),job.tenantId(),job.clientId(),worker,
                        System.currentTimeMillis()+backoff(job.attempts()),"OUTPUT_STORAGE_UNAVAILABLE",System.currentTimeMillis()));
            }
        }
        return done;
    }
    private void recoverExpired(int limit){if(limit<1)return;for(OutputUploadDao.UploadRow u:dao.findExpiredUploads(System.currentTimeMillis(),limit))tx.executeWithoutResult(s->{long now=System.currentTimeMillis();dao.lockScopeQuota(u.tenantId(),u.clientId());dao.lockBindingQuota(u.tenantId(),u.clientId(),u.bindingId());OutputUploadDao.UploadRow current=dao.findUpload(u.tenantId(),u.clientId(),u.uploadId(),true);if(current==null)return;if(current.writerEpoch()==0&&dao.markExpiredWithoutWriter(u.tenantId(),u.clientId(),u.uploadId(),u.objectId(),now)==1){dao.changeScopeQuota(u.tenantId(),u.clientId(),-u.expectedSize(),0,-1,now);dao.changeBindingQuota(u.tenantId(),u.clientId(),u.bindingId(),-1,now);}else rejectLocked(current,"OUTPUT_UPLOAD_EXPIRED",now);});}
    private void recoverObjects(int limit){
        if(limit<1)return;
        for(OutputUploadDao.ObjectRow candidate:dao.findDueObjects(System.currentTimeMillis(),limit)){
            long now=System.currentTimeMillis();
            OutputUploadDao.ObjectRow object=tx.execute(s->{
                dao.lockScopeQuota(candidate.tenantId(),candidate.clientId());
                OutputUploadDao.ObjectRow locked=dao.findObject(candidate.tenantId(),candidate.clientId(),candidate.objectId(),true);
                if(locked==null)return null;
                if("READY".equals(locked.lifecycleStatus())&&dao.activeObjectReferences(locked.tenantId(),locked.clientId(),locked.objectId(),now)>0)return null;
                if(dao.claimObjectDelete(locked.tenantId(),locked.clientId(),locked.objectId(),worker,
                        now+OutputConstants.JOB_LEASE_MILLIS,now)!=1)return null;
                return locked;
            });
            if(object==null)continue;
            String token=hex(sha256(object.tenantId()+"\0"+object.clientId()+"\0"+object.objectId()+"\0GC"));
            try{
                storage.putTombstone(object.bucket(),object.storageKey(),token,System.currentTimeMillis()+60_000);
                OutputObjectStorage.Head head=storage.head(object.bucket(),object.storageKey());
                if(head.size()!=0||!token.equals(head.cleanupToken()))throw new IOException("GC tombstone verification failed");
                tx.executeWithoutResult(s->{
                    long at=System.currentTimeMillis();
                    dao.lockScopeQuota(object.tenantId(),object.clientId());
                    OutputUploadDao.ObjectRow locked=dao.findObject(object.tenantId(),object.clientId(),object.objectId(),true);
                    if(locked==null||!Objects.equals(worker,locked.deleteLeaseOwner())||locked.actualSize()==null)
                        throw conflict("OUTPUT_GC_FENCED");
                    if(dao.finishObjectDelete(object.tenantId(),object.clientId(),object.objectId(),worker,at)!=1
                            ||dao.changeScopeQuota(object.tenantId(),object.clientId(),0,-locked.actualSize(),0,at)!=1)
                        throw unavailable("OUTPUT_GC_COMMIT_FAILED");
                });
            }catch(Exception e){
                tx.executeWithoutResult(s->dao.retryObjectDelete(object.tenantId(),object.clientId(),object.objectId(),worker,
                        System.currentTimeMillis()+backoff(object.deleteAttempts()),"OUTPUT_STORAGE_UNAVAILABLE",System.currentTimeMillis()));
            }
        }
    }

    private OutputTicketAuthorization authorize(String bearer){return authorization.authorizeTicket(rawBearer(bearer),OutputConstants.OP_STATUS,true);}
    private static String rawBearer(String bearer){if(bearer==null||!bearer.startsWith("Bearer ")||bearer.length()<48)throw new OutputUploadException("OUTPUT_AUTH_UNAUTHORIZED","Output ticket is required",401);return bearer.substring(7);}
    private OutputUploadDao.UploadRow exactUpload(OutputTicketAuthorization a,String id,boolean lock){OutputUploadDao.UploadRow u=dao.findUpload(a.tenantId(),a.clientId(),id,lock);if(u==null||!u.runId().equals(a.runId())||!u.bindingId().equals(a.bindingId()))throw new OutputUploadException("OUTPUT_UPLOAD_NOT_FOUND","Output upload is unavailable",404);return u;}
    private static void requireMutable(OutputTicketAuthorization a){if(!OutputConstants.RUN_ACTIVE.equals(a.runState())||!a.operations().contains(OutputConstants.OP_UPLOAD))throw new OutputUploadException("OUTPUT_AUTH_FORBIDDEN","Output ticket cannot mutate",403);}
    private static void requireRoute(OutputTicketAuthorization a,String run,String type,String id){if(!a.runId().equals(run)||!a.sourceType().equals(type)||!a.sourceId().equals(id))throw new OutputUploadException("OUTPUT_AUTH_FORBIDDEN","Output route mismatch",403);}
    private static void validateCreate(OutputUploadCreateDTO r){if(r==null||r.source()==null)throw bad("OUTPUT_REQUEST_INVALID");requireId(r.runId());if(!Set.of(OutputConstants.SOURCE_TASK,OutputConstants.SOURCE_CONVERSATION).contains(r.source().type())||!exact(r.source().id(),400))throw bad("OUTPUT_SOURCE_INVALID");if(!exact(r.name(),255)||r.name().contains("/")||r.name().contains("\\")||r.name().equals(".")||r.name().equals(".."))throw bad("OUTPUT_NAME_INVALID");long size=parseSize(r.size());if(size>OutputConstants.DEFAULT_MAX_FILE_BYTES)throw tooLarge("OUTPUT_FILE_TOO_LARGE");if(r.sha256()==null||!r.sha256().matches("[0-9a-f]{64}"))throw bad("OUTPUT_HASH_INVALID");if(r.mime()==null||!ALLOWED_MIME.contains(r.mime().toLowerCase()))throw new OutputUploadException("OUTPUT_MIME_UNSUPPORTED","Output MIME unsupported",415);}
    private static long parseSize(String size){try{if(size==null||!size.matches("0|[1-9][0-9]{0,18}"))throw bad("OUTPUT_SIZE_INVALID");return Long.parseLong(size);}catch(NumberFormatException e){throw bad("OUTPUT_SIZE_INVALID");}}
    private static void requireKey(String k){if(k==null||!k.matches("[\\x21-\\x7e]{16,100}"))throw bad("IDEMPOTENCY_KEY_INVALID");}
    private static void requireId(String id){if(id==null||id.isBlank()||id.length()>100||id.codePoints().anyMatch(Character::isISOControl))throw bad("OUTPUT_ID_INVALID");}
    private static boolean exact(String s,int max){return s!=null&&!s.isEmpty()&&s.length()<=max&&s.equals(s.strip())&&s.codePoints().noneMatch(Character::isISOControl);}
    private static String canonicalCreate(OutputUploadCreateDTO r){return "{\"mime\":"+quote(r.mime())+",\"name\":"+quote(r.name())+",\"runId\":"+quote(r.runId())+",\"sha256\":"+quote(r.sha256())+",\"size\":"+quote(r.size())+",\"source\":{\"id\":"+quote(r.source().id())+",\"type\":"+quote(r.source().type())+"}}";}
    private static String canonicalComplete(String run,String id){return "{\"operation\":\"completeUpload\",\"runId\":"+quote(run)+",\"uploadId\":"+quote(id)+"}";}
    private static String quote(String s){return JsonUtil.toJson(s);}
    private static OutputUploadDTO replay(OutputUploadDao.Receipt r,byte[] hash){if(!Arrays.equals(r.requestHash(),hash))throw conflict("IDEMPOTENCY_CONFLICT");try{OutputUploadDTO dto=JsonUtil.getMapper().readValue(r.responseJson(),OutputUploadDTO.class);if(dto==null)throw unavailable("OUTPUT_RECEIPT_INVALID");return dto;}catch(OutputUploadException e){throw e;}catch(Exception e){throw unavailable("OUTPUT_RECEIPT_INVALID");}}
    private static OutputUploadDTO wire(OutputUploadDao.UploadRow u,String url){return new OutputUploadDTO(u.uploadId(),u.state(),url,Long.toString(u.expiresAt()),u.objectId(),u.errorCode());}
    private static byte[] cleanupId(String...parts){try{MessageDigest d=MessageDigest.getInstance("SHA-256");for(String p:parts){byte[] b=p.getBytes(StandardCharsets.UTF_8);d.update(ByteBuffer.allocate(4).putInt(b.length).array());d.update(b);}return d.digest();}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private boolean sameClaim(OutputUploadDao.CleanupRow expected,OutputUploadDao.CleanupRow actual){return actual!=null&&"CLAIMED".equals(actual.state())&&worker.equals(actual.leaseOwner())&&Arrays.equals(expected.cleanupId(),actual.cleanupId())&&expected.objectId().equals(actual.objectId())&&expected.uploadId().equals(actual.uploadId())&&expected.writerEpoch()==actual.writerEpoch()&&expected.bucket().equals(actual.bucket())&&expected.storageKey().equals(actual.storageKey())&&Objects.equals(expected.storageVersion(),actual.storageVersion());}
    private static byte[] sha256(String s){return sha256(s.getBytes(StandardCharsets.UTF_8));}private static byte[] sha256(byte[] b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static byte[] readLimited(InputStream in,long limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[64*1024];long n=0;for(int r;(r=in.read(b))!=-1;){n+=r;if(n>limit)throw new IOException("Object read limit exceeded");out.write(b,0,r);}return out.toByteArray();}
    private static long backoff(int attempts){return Math.min(300_000L,1_000L<<(Math.min(18,attempts)));}
    private static <T>T withLockRetry(Supplier<T> action){CannotAcquireLockException last=null;for(int attempt=0;attempt<10;attempt++){try{return action.get();}catch(CannotAcquireLockException deadlock){last=deadlock;try{Thread.sleep(5L*(attempt+1));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw deadlock;}}}throw last;}
    private static String id(){return UUID.randomUUID().toString().replace("-","");}private static String hex(byte[] b){return HexFormat.of().formatHex(b);}
    private static <T>T required(T v){if(v==null)throw unavailable("OUTPUT_PERSISTENCE_MISSING");return v;}
    private static OutputUploadException bad(String c){return new OutputUploadException(c,"Invalid output request",400);}private static OutputUploadException conflict(String c){return new OutputUploadException(c,"Output conflict",409);}private static OutputUploadException rate(String c){return new OutputUploadException(c,"Output limit exceeded",429);}private static OutputUploadException tooLarge(String c){return new OutputUploadException(c,"Output quota exceeded",413);}private static OutputUploadException gone(String c){return new OutputUploadException(c,"Output upload expired",410);}private static OutputUploadException sizeMismatch(){return new OutputUploadException("OUTPUT_SIZE_MISMATCH","Upload size does not match the declared size",422);}private static OutputUploadException unavailable(String c){return new OutputUploadException(c,"Output delivery unavailable",503,false);}
    private record Writer(String tenant,String client,String run,String upload,String object,String fileName,long expectedSize,byte[] expectedHash,String declaredMime,long epoch,long deadline,String storageKey,String bearer){}
    private record CompleteStart(OutputUploadDTO dto,boolean verify){}
}
