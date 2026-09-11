package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.config.OutputObjectSchemaInitializer;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputMalwareScanner;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputUploadException;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.dto.OutputSourceDTO;
import cn.jia.agent.output.dto.OutputUploadCreateDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named="OD02_MYSQL_URL",matches=".+")
@EnabledIfEnvironmentVariable(named="OD02_S3_ENDPOINT",matches=".+")
class OutputUploadRealDependenciesTest {
    private static final byte[] FIXTURE="hello world!\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final String HASH="ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a";
    private static final String RUN="22222222222222222222222222222222",BEARER="Bearer "+"a".repeat(43);
    private JdbcTemplate admin,jdbc;private String database,bucket;private S3Client s3;private S3OutputObjectStorage storage;private OutputUploadServiceImpl service;private OutputMalwareScanner realScanner;

    @BeforeEach void setup()throws Exception{
        String base=env("OD02_MYSQL_URL"),user=env("OD02_MYSQL_USER"),password=env("OD02_MYSQL_PASSWORD");
        admin=new JdbcTemplate(ds(base,user,password));database="cyf_od02_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        var dataSource=ds(url(base,database),user,password);jdbc=new JdbcTemplate(dataSource);new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet();
        String endpoint=env("OD02_S3_ENDPOINT"),access=env("OD02_S3_ACCESS_KEY"),secret=env("OD02_S3_SECRET_KEY");bucket="od02-real-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        s3=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(access,secret))).serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build();s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());storage=new S3OutputObjectStorage(endpoint,access,secret,bucket);
        realScanner=new ClamAvOutputMalwareScanner(env("OD02_CLAM_HOST"),Integer.parseInt(env("OD02_CLAM_PORT")));
        service=service(dataSource,realScanner);
    }
    @AfterEach void cleanup(){if(storage!=null)storage.close();if(s3!=null&&bucket!=null){try{for(var o:s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build()).contents())s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(o.key()).build());s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());}catch(Exception ignored){}s3.close();}if(admin!=null&&database!=null)admin.execute("DROP DATABASE IF EXISTS `"+database+"`");}

    @Test void fixtureBecomesReadyAndReceiptsReplayExactOriginalResponses(){
        var created=service.create(BEARER,"create-key-0000001",request(HASH));assertEquals("CREATED",created.state());
        assertEquals(created,service.create(BEARER,"create-key-0000001",request(HASH)));
        assertEquals("UPLOADING",service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)).state());
        var completing=service.complete(BEARER,created.uploadId(),"complete-key-0001");assertEquals("VERIFYING",completing.state());
        assertEquals("READY",service.status(BEARER,created.uploadId()).state());
        assertEquals(completing,service.complete(BEARER,created.uploadId(),"complete-key-0001"));
        assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals(13L,longValue("SELECT stored_bytes FROM output_scope_quota"));assertEquals(0L,longValue("SELECT active_uploads FROM output_scope_quota"));
        assertEquals("READY",jdbc.queryForObject("SELECT lifecycle_status FROM output_object",String.class));
        for(String state:List.of("CLOSED","RESULT_SUBMITTED"))for(List<String> operations:List.of(List.of("upload","publish","status"),List.of("status"))){
            OutputUploadServiceImpl terminal=service(jdbc.getDataSource(),realScanner,state,operations);
            assertEquals(created,terminal.create(BEARER,"create-key-0000001",request(HASH)));
            assertEquals(completing,terminal.complete(BEARER,created.uploadId(),"complete-key-0001"));
            assertEquals(403,assertThrows(OutputUploadException.class,()->terminal.create(BEARER,"terminal-new-key-"+state,request(HASH))).status());
            assertEquals(403,assertThrows(OutputUploadException.class,()->terminal.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE))).status());
            assertEquals(403,assertThrows(OutputUploadException.class,()->terminal.complete(BEARER,created.uploadId(),"terminal-complete-"+state)).status());
        }
    }

    @Test void hashRejectionKeepsPhysicalChargeUntilVerifiedTombstone()throws Exception{
        String wrong="0".repeat(64);var created=service.create(BEARER,"create-key-0000002",request(wrong));
        OutputUploadException rejected=assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));assertEquals("OUTPUT_HASH_MISMATCH",rejected.code());
        assertEquals(13L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals("REJECTED",service.status(BEARER,created.uploadId()).state());
        service.recover(10);assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));
        String key=jdbc.queryForObject("SELECT storage_key FROM output_storage_cleanup_job WHERE state='DONE'",String.class);assertEquals(0,storage.head(bucket,key).size());
    }

    @Test void cleanupDoesNotReleaseQuotaForWrongTombstoneToken(){assertCleanupProofFailure(false);}
    @Test void cleanupDoesNotReleaseQuotaForNonzeroTombstone(){assertCleanupProofFailure(true);}

    @Test void scannerOutagePersistsVerifyingAndRecoversWithoutNewCompleteReceipt(){
        AtomicBoolean fail=new AtomicBoolean(true);OutputMalwareScanner flaky=(in,max)->{if(fail.getAndSet(false))throw new IOException("planned outage");return realScanner.scan(in,max);};service=service(jdbc.getDataSource(),flaky);
        var created=service.create(BEARER,"create-key-0000003",request(HASH));service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));service.complete(BEARER,created.uploadId(),"complete-key-0003");
        assertEquals("VERIFYING",service.status(BEARER,created.uploadId()).state());assertEquals(1L,longValue("SELECT verification_attempts FROM output_upload_session"));
        jdbc.update("UPDATE output_upload_session SET verification_next_at=0");
        service.recover(10);assertEquals("READY",service.status(BEARER,created.uploadId()).state());assertEquals(1L,longValue("SELECT COUNT(*) FROM output_mutation_receipt WHERE operation='completeUpload'"));
    }

    @Test void nestedArchiveTemporaryIoFailureRemainsDurablyVerifying()throws Exception{
        byte[] inner=zip("ok.txt","ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));byte[] outer=zip("nested.zip",inner);Path notDirectory=Files.createTempFile("od02-archive-not-dir-",".tmp");
        try{
            OutputDeliveryProperties properties=properties(notDirectory.toString());service=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),storage,properties);
            OutputUploadCreateDTO request=new OutputUploadCreateDTO(RUN,new OutputSourceDTO("TASK","task-1"),"nested.zip",Long.toString(outer.length),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outer)),"application/zip");
            var created=service.create(BEARER,"create-archive-io01",request);service.put(BEARER,created.uploadId(),new ByteArrayInputStream(outer));assertEquals("VERIFYING",service.complete(BEARER,created.uploadId(),"complete-archive-io").state());
            assertEquals("VERIFYING",service.status(BEARER,created.uploadId()).state());assertEquals(1L,longValue("SELECT verification_attempts FROM output_upload_session"));assertEquals("OUTPUT_ARCHIVE_IO_UNAVAILABLE",jdbc.queryForObject("SELECT error_code FROM output_upload_session",String.class));
        }finally{Files.deleteIfExists(notDirectory);}
    }

    @Test void concurrentCreateAtomicallyCapsOneBindingAtTwo()throws Exception{
        int attempts=8;ExecutorService pool=Executors.newFixedThreadPool(attempts);CountDownLatch start=new CountDownLatch(1);List<Callable<String>> calls=java.util.stream.IntStream.range(0,attempts).mapToObj(i->(Callable<String>)()->{start.await();try{return service.create(BEARER,"create-concurrent-"+i,request(HASH)).state();}catch(OutputUploadException e){return e.code();}}).toList();List<Future<String>> futures=calls.stream().map(pool::submit).toList();start.countDown();int success=0,limited=0;for(Future<String> f:futures){String value=f.get(30,TimeUnit.SECONDS);if("CREATED".equals(value))success++;if("OUTPUT_UPLOAD_CONCURRENCY_LIMIT".equals(value))limited++;}pool.shutdownNow();assertEquals(2,success);assertEquals(attempts-2,limited);assertEquals(2L,longValue("SELECT active_uploads FROM output_binding_upload_quota"));
    }

    @Test void oneCompleteKeyCannotReplayAcrossTwoUploads(){var first=service.create(BEARER,"create-cross-key-01",request(HASH));var second=service.create(BEARER,"create-cross-key-02",request(HASH));service.put(BEARER,first.uploadId(),new ByteArrayInputStream(FIXTURE));service.put(BEARER,second.uploadId(),new ByteArrayInputStream(FIXTURE));service.complete(BEARER,first.uploadId(),"complete-cross-key");OutputUploadException conflict=assertThrows(OutputUploadException.class,()->service.complete(BEARER,second.uploadId(),"complete-cross-key"));assertEquals("IDEMPOTENCY_CONFLICT",conflict.code());assertEquals("UPLOADING",service.status(BEARER,second.uploadId()).state());}

    @Test void gcWaitsForReferenceThenFencesAndReleasesStoredQuota()throws Exception{
        var created=service.create(BEARER,"create-gc-key-0001",request(HASH));service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));service.complete(BEARER,created.uploadId(),"complete-gc-key-01");
        String object=created.objectId();long now=System.currentTimeMillis();jdbc.update("UPDATE output_object SET delete_after=0,delete_next_at=0 WHERE object_id=?",object);
        jdbc.update("INSERT INTO output_object_reference (tenant_id,client_id,reference_key,object_id,source_type,source_id,output_id,output_version,reference_kind,delivery_id,state,retain_until,hold,hold_reason,released_at,created_at,updated_at,row_version) VALUES ('owner','client',?,?,'TASK','task-1','output-1',1,'DELIVERY_PIN',NULL,'ACTIVE',NULL,TRUE,'review',NULL,?,?,0)",new byte[32],object,now,now);
        service.recover(10);assertEquals("READY",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,object));assertEquals(13L,longValue("SELECT stored_bytes FROM output_scope_quota"));
        jdbc.update("UPDATE output_object_reference SET state='RELEASED',hold=FALSE,released_at=?",System.currentTimeMillis());service.recover(10);
        assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,object));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
        String key=jdbc.queryForObject("SELECT storage_key FROM output_object WHERE object_id=?",String.class,object);assertEquals(0,storage.head(bucket,key).size());
    }

    @Test void gcHeadFailureStaysDeletingAndAReplacementWorkerRecovers(){
        var created=ready("create-gc-head-001","complete-gc-head1");jdbc.update("UPDATE output_object SET delete_after=0,delete_next_at=0 WHERE object_id=?",created.objectId());
        service=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),new FaultyHeadStorage(storage,false));service.recover(10);
        assertEquals("DELETING",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));assertEquals(13L,longValue("SELECT stored_bytes FROM output_scope_quota"));assertEquals(1L,longValue("SELECT delete_attempts FROM output_object"));
        jdbc.update("UPDATE output_object SET delete_next_at=0");service=service(jdbc.getDataSource(),realScanner);service.recover(10);
        assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    @Test void replacementWorkerReclaimsExpiredCleanupAndDeletingLeases(){
        var rejected=service.create(BEARER,"create-stale-clean01",request("0".repeat(64)));assertThrows(OutputUploadException.class,()->service.put(BEARER,rejected.uploadId(),new ByteArrayInputStream(FIXTURE)));
        jdbc.update("UPDATE output_storage_cleanup_job SET state='CLAIMED',lease_owner='dead-worker',lease_until=0,next_attempt_at=0");service=service(jdbc.getDataSource(),realScanner);service.recover(10);
        assertEquals("DONE",jdbc.queryForObject("SELECT state FROM output_storage_cleanup_job",String.class));assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));
        var ready=ready("create-stale-gc-001","complete-stale-gc1");jdbc.update("UPDATE output_object SET lifecycle_status='DELETING',delete_after=0,delete_next_at=0,delete_lease_owner='dead-worker',delete_lease_until=0 WHERE object_id=?",ready.objectId());
        service=service(jdbc.getDataSource(),realScanner);service.recover(10);assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,ready.objectId()));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    @Test void oldEpochCleanupReleasesOnlyItsChargeAndCannotDeleteCurrentObject(){
        var created=service.create(BEARER,"create-two-epoch-01",request("0".repeat(64)));OutputObjectStorage writeThenFail=new WriteThenFailStorage(storage);OutputUploadServiceImpl first=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),writeThenFail);
        assertEquals("OUTPUT_STORAGE_WRITE_FAILED",assertThrows(OutputUploadException.class,()->first.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE))).code());
        jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=?,next_attempt_at=? WHERE writer_epoch=1",System.currentTimeMillis()+60_000,System.currentTimeMillis()+60_000);
        assertEquals("OUTPUT_HASH_MISMATCH",assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE))).code());assertEquals(26L,longValue("SELECT reserved_bytes FROM output_scope_quota"));
        jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=CASE writer_epoch WHEN 1 THEN 0 ELSE safe_after END,next_attempt_at=CASE writer_epoch WHEN 1 THEN 0 ELSE ? END",System.currentTimeMillis()+60_000);service.recover(1);
        assertEquals("STAGED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));assertEquals(13L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals("DONE",jdbc.queryForObject("SELECT state FROM output_storage_cleanup_job WHERE writer_epoch=1",String.class));
        jdbc.update("UPDATE output_storage_cleanup_job SET next_attempt_at=0 WHERE writer_epoch=2");service.recover(1);
        assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));
    }

    @Test void twoCleanupWorkersReleaseOnePhysicalChargeExactlyOnce()throws Exception{
        var created=service.create(BEARER,"create-two-cleaners",request("0".repeat(64)));assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));
        OutputUploadServiceImpl first=service(jdbc.getDataSource(),realScanner),second=service(jdbc.getDataSource(),realScanner);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Future<Integer> a=pool.submit(()->{start.await();return first.recover(1);}),b=pool.submit(()->{start.await();return second.recover(1);});start.countDown();assertEquals(1,a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS));pool.shutdownNow();
        assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals(1L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE state='DONE'"));
    }

    @Test void readPinInsertionAndGcSerializeOnObjectLock()throws Exception{
        var created=ready("create-pin-race-001","complete-pin-race1");jdbc.update("UPDATE output_object SET delete_after=0,delete_next_at=0 WHERE object_id=?",created.objectId());
        TransactionTemplate tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));CountDownLatch inserted=new CountDownLatch(1),commit=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);long now=System.currentTimeMillis();
        Future<?> pin=pool.submit(()->tx.executeWithoutResult(s->{jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=? FOR UPDATE",String.class,created.objectId());jdbc.update("INSERT INTO output_object_reference (tenant_id,client_id,reference_key,object_id,source_type,source_id,output_id,output_version,reference_kind,delivery_id,state,retain_until,hold,hold_reason,released_at,created_at,updated_at,row_version) VALUES ('owner','client',?,?,'TASK','task-1','output-pin',1,'READ_PIN',NULL,'ACTIVE',?,FALSE,NULL,NULL,?,?,0)",new byte[32],created.objectId(),now+60_000,now,now);inserted.countDown();try{commit.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}));
        assertTrue(inserted.await(10,TimeUnit.SECONDS));Future<?> gc=pool.submit(()->service.recover(10));Thread.sleep(200);assertTrue(!gc.isDone());commit.countDown();pin.get(10,TimeUnit.SECONDS);gc.get(10,TimeUnit.SECONDS);
        assertEquals("READY",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));assertEquals(13L,longValue("SELECT stored_bytes FROM output_scope_quota"));jdbc.update("UPDATE output_object_reference SET retain_until=0");service.recover(10);assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object WHERE object_id=?",String.class,created.objectId()));pool.shutdownNow();
    }

    @Test void oldWriterCannotFinalizeAfterAReplacementEpoch()throws Exception{
        BlockingStorage fake=new BlockingStorage();OutputUploadServiceImpl isolated=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),fake);
        var created=isolated.create(BEARER,"create-writer-key01",request(HASH));ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> old=pool.submit(()->isolated.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));
        assertTrue(fake.firstBodyRead.await(10,TimeUnit.SECONDS));jdbc.update("UPDATE output_upload_session SET writer_until=0 WHERE upload_id=?",created.uploadId());assertEquals("UPLOADING",isolated.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)).state());fake.releaseFirst.countDown();
        Exception failed=assertThrows(Exception.class,()->old.get(10,TimeUnit.SECONDS));assertTrue(failed.getCause() instanceof OutputUploadException);assertEquals(2L,longValue("SELECT writer_epoch FROM output_upload_session"));assertEquals(26L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals(1L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE writer_epoch=1 AND state='PENDING'"));pool.shutdownNow();
    }

    @Test void failedReplacementCannotCompleteUsingSupersededStorageIdentity(){
        var created=service.create(BEARER,"create-replace-fail1",request(HASH));service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));
        OutputUploadServiceImpl replacement=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),new WriteThenFailStorage(storage));
        assertEquals("OUTPUT_STORAGE_WRITE_FAILED",assertThrows(OutputUploadException.class,()->replacement.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE))).code());
        assertEquals(2L,longValue("SELECT writer_epoch FROM output_upload_session"));assertEquals(2L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE state='PENDING'"));
        assertEquals("OUTPUT_UPLOAD_INCOMPLETE",assertThrows(OutputUploadException.class,()->service.complete(BEARER,created.uploadId(),"complete-replace-fail")).code());
        jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=0,next_attempt_at=0 WHERE writer_epoch=1");service.recover(1);
        assertEquals("OUTPUT_UPLOAD_INCOMPLETE",assertThrows(OutputUploadException.class,()->service.complete(BEARER,created.uploadId(),"complete-replace-after-clean")).code());
        assertEquals(0L,longValue("SELECT COUNT(*) FROM output_upload_session WHERE state='READY'"));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    @Test void pendingPlusRetainedBlocksThirdPhysicalKeyUntilCleanupDone(){
        var created=service.create(BEARER,"create-two-key-cap1",request(HASH));OutputUploadServiceImpl failed=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),new WriteThenFailStorage(storage));
        assertThrows(OutputUploadException.class,()->failed.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=?,next_attempt_at=?",System.currentTimeMillis()+60_000,System.currentTimeMillis()+60_000);
        service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));assertEquals(2L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE state IN ('PENDING','RETAINED')"));
        OutputUploadException limited=assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));assertEquals("OUTPUT_STAGING_CLEANUP_REQUIRED",limited.code());assertEquals(2L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job"));
        jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=0,next_attempt_at=0 WHERE writer_epoch=1");service.recover(1);assertEquals("DONE",jdbc.queryForObject("SELECT state FROM output_storage_cleanup_job WHERE writer_epoch=1",String.class));
        OutputUploadServiceImpl third=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),new WriteThenFailStorage(storage));assertThrows(OutputUploadException.class,()->third.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));assertEquals(2L,longValue("SELECT COUNT(*) FROM output_storage_cleanup_job WHERE state<>'DONE'"));
    }

    @Test void cleanupAfterScanCannotApplyScannedVerdictToTombstonedIdentity()throws Exception{
        CountDownLatch finalAuthorization=new CountDownLatch(1),release=new CountDownLatch(1);OutputRunAuthorizationService auth=mock(OutputRunAuthorizationService.class);when(auth.authorizeTicket(anyString(),anyString(),anyBoolean())).thenReturn(new OutputTicketAuthorization("owner","client",RUN,"TASK","task-1","agent-1","7","runtime-1",List.of("upload","publish","status"),System.currentTimeMillis()+3_600_000,"ACTIVE"));when(auth.authorizePersistedMutation(anyString(),anyString(),anyString(),anyString())).thenAnswer(invocation->{finalAuthorization.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return true;});
        service=service(jdbc.getDataSource(),realScanner,auth,storage,properties(null));var created=service.create(BEARER,"create-scan-fence01",request(HASH));service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> completing=pool.submit(()->service.complete(BEARER,created.uploadId(),"complete-scan-fence"));assertTrue(finalAuthorization.await(15,TimeUnit.SECONDS));
        jdbc.update("UPDATE output_storage_cleanup_job SET state='PENDING',quota_charge_kind='RESERVED',quota_charge_bytes=13,safe_after=0,next_attempt_at=0 WHERE writer_epoch=1");OutputUploadServiceImpl cleanupWorker=service(jdbc.getDataSource(),realScanner);cleanupWorker.recover(1);release.countDown();completing.get(15,TimeUnit.SECONDS);pool.shutdownNow();
        assertEquals("REJECTED",jdbc.queryForObject("SELECT state FROM output_upload_session",String.class));assertEquals("DELETED",jdbc.queryForObject("SELECT lifecycle_status FROM output_object",String.class));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    @Test void concatenatedJsonIsRejectedAndNeverReady()throws Exception{
        byte[] content="{\"a\":1}{\"b\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));OutputUploadCreateDTO request=new OutputUploadCreateDTO(RUN,new OutputSourceDTO("TASK","task-1"),"bad.json",Long.toString(content.length),hash,"application/json");var created=service.create(BEARER,"create-json-tail01",request);service.put(BEARER,created.uploadId(),new ByteArrayInputStream(content));service.complete(BEARER,created.uploadId(),"complete-json-tail");assertEquals("REJECTED",service.status(BEARER,created.uploadId()).state());assertEquals("OUTPUT_JSON_INVALID",jdbc.queryForObject("SELECT error_code FROM output_upload_session",String.class));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    @Test void oversizedAndShortPutArePermanentSizeErrorsWithCleanupAccounting(){
        for(byte[] body:List.of("hello world!\nX".getBytes(java.nio.charset.StandardCharsets.UTF_8),"hello world!".getBytes(java.nio.charset.StandardCharsets.UTF_8))){var created=service.create(BEARER,"create-size-"+body.length+"-01",request(HASH));OutputUploadException mismatch=assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(body)));assertEquals("OUTPUT_SIZE_MISMATCH",mismatch.code());assertEquals(422,mismatch.status());assertTrue(mismatch.terminal());assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM output_storage_cleanup_job WHERE upload_id=?",String.class,created.uploadId()));assertTrue(longValue("SELECT reserved_bytes FROM output_scope_quota")>=13L);jdbc.update("UPDATE output_storage_cleanup_job SET safe_after=0,next_attempt_at=0 WHERE upload_id=?",created.uploadId());service.recover(1);}
        assertEquals(0L,longValue("SELECT reserved_bytes FROM output_scope_quota"));assertEquals(0L,longValue("SELECT stored_bytes FROM output_scope_quota"));
    }

    private OutputUploadServiceImpl service(javax.sql.DataSource ds,OutputMalwareScanner scanner){return service(ds,scanner,"ACTIVE",List.of("upload","publish","status"));}
    private OutputUploadServiceImpl service(javax.sql.DataSource ds,OutputMalwareScanner scanner,String state,List<String> operations){return service(ds,scanner,state,operations,storage);}
    private OutputUploadServiceImpl service(javax.sql.DataSource ds,OutputMalwareScanner scanner,String state,List<String> operations,OutputObjectStorage objectStorage){return service(ds,scanner,state,operations,objectStorage,properties(null));}
    private OutputUploadServiceImpl service(javax.sql.DataSource ds,OutputMalwareScanner scanner,String state,List<String> operations,OutputObjectStorage objectStorage,OutputDeliveryProperties properties){OutputRunAuthorizationService auth=mock(OutputRunAuthorizationService.class);when(auth.authorizeTicket(anyString(),anyString(),anyBoolean())).thenReturn(new OutputTicketAuthorization("owner","client",RUN,"TASK","task-1","agent-1","7","runtime-1",operations,System.currentTimeMillis()+3_600_000,state));when(auth.authorizePersistedMutation(anyString(),anyString(),anyString(),anyString())).thenReturn("ACTIVE".equals(state));return service(ds,scanner,auth,objectStorage,properties);}
    private OutputUploadServiceImpl service(javax.sql.DataSource ds,OutputMalwareScanner scanner,OutputRunAuthorizationService auth,OutputObjectStorage objectStorage,OutputDeliveryProperties properties){return new OutputUploadServiceImpl(auth,new OutputUploadDaoImpl(jdbc),objectStorage,scanner,new DataSourceTransactionManager(ds),properties);}
    private OutputDeliveryProperties properties(String tempDirectory){return new OutputDeliveryProperties(true,env("OD02_S3_ENDPOINT"),env("OD02_S3_ACCESS_KEY"),env("OD02_S3_SECRET_KEY"),bucket,env("OD02_CLAM_HOST"),Integer.parseInt(env("OD02_CLAM_PORT")),10,50L*1024*1024,90L*1024*1024,tempDirectory);}
    private OutputUploadCreateDTO request(String hash){return new OutputUploadCreateDTO(RUN,new OutputSourceDTO("TASK","task-1"),"report.md","13",hash,"text/markdown");}
    private cn.jia.agent.output.dto.OutputUploadDTO ready(String createKey,String completeKey){var created=service.create(BEARER,createKey,request(HASH));service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE));service.complete(BEARER,created.uploadId(),completeKey);assertEquals("READY",service.status(BEARER,created.uploadId()).state());return created;}
    private void assertCleanupProofFailure(boolean nonzero){
        var created=service.create(BEARER,"create-cleanup-"+(nonzero?"size0001":"token001"),request("0".repeat(64)));
        assertThrows(OutputUploadException.class,()->service.put(BEARER,created.uploadId(),new ByteArrayInputStream(FIXTURE)));
        service=service(jdbc.getDataSource(),realScanner,"ACTIVE",List.of("upload","publish","status"),new FaultyHeadStorage(storage,nonzero));
        service.recover(10);
        assertEquals(13L,longValue("SELECT reserved_bytes FROM output_scope_quota"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT state FROM output_storage_cleanup_job",String.class));
        assertEquals(1L,longValue("SELECT attempts FROM output_storage_cleanup_job"));
    }
    private long longValue(String sql){Number n=jdbc.queryForObject(sql,Number.class);return n.longValue();}
    private static DriverManagerDataSource ds(String url,String user,String pass){DriverManagerDataSource d=new DriverManagerDataSource();d.setDriverClassName("com.mysql.cj.jdbc.Driver");d.setUrl(url);d.setUsername(user);d.setPassword(pass);return d;}
    private static String url(String base,String db){int q=base.indexOf('?');String h=q<0?base:base.substring(0,q),tail=q<0?"":base.substring(q);int slash=h.indexOf('/',"jdbc:mysql://".length());return (slash<0?h+"/":h.substring(0,slash+1))+db+tail;}
    private static String env(String n){String v=System.getenv(n);if(v==null)throw new IllegalStateException(n+" missing");return v;}
    private static byte[] zip(String name,byte[] content)throws IOException{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out)){zip.putNextEntry(new ZipEntry(name));zip.write(content);zip.closeEntry();}return out.toByteArray();}
    private static final class BlockingStorage implements OutputObjectStorage{
        final CountDownLatch firstBodyRead=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);final java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();final Map<String,byte[]> objects=new ConcurrentHashMap<>();final Map<String,String> tokens=new ConcurrentHashMap<>();
        @Override public Stored putCreateOnly(String bucket,String key,java.io.InputStream input,long length,String type,long deadline)throws IOException{byte[] bytes=input.readAllBytes();if(writes.incrementAndGet()==1){firstBodyRead.countDown();try{if(!releaseFirst.await(10,TimeUnit.SECONDS))throw new IOException("writer gate timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}if(objects.putIfAbsent(key,bytes)!=null)throw new IOException("key exists");return new Stored(null,"etag");}
        @Override public java.io.InputStream open(String bucket,String key,String version){return new ByteArrayInputStream(objects.get(key));}
        @Override public void putTombstone(String bucket,String key,String token,long deadline){objects.put(key,new byte[0]);tokens.put(key,token);}
        @Override public Head head(String bucket,String key){byte[] bytes=objects.get(key);return new Head(bytes==null?0:bytes.length,null,tokens.get(key));}
        @Override public void delete(String bucket,String key,String version){objects.remove(key);}
    }
    private static final class FaultyHeadStorage implements OutputObjectStorage{
        private final OutputObjectStorage delegate;private final boolean nonzero;
        FaultyHeadStorage(OutputObjectStorage delegate,boolean nonzero){this.delegate=delegate;this.nonzero=nonzero;}
        @Override public Stored putCreateOnly(String bucket,String key,java.io.InputStream input,long length,String type,long deadline)throws IOException{return delegate.putCreateOnly(bucket,key,input,length,type,deadline);}
        @Override public java.io.InputStream open(String bucket,String key,String version)throws IOException{return delegate.open(bucket,key,version);}
        @Override public void putTombstone(String bucket,String key,String token,long deadline)throws IOException{delegate.putTombstone(bucket,key,token,deadline);}
        @Override public Head head(String bucket,String key)throws IOException{Head actual=delegate.head(bucket,key);return nonzero?new Head(1,actual.versionId(),actual.cleanupToken()):new Head(0,actual.versionId(),"wrong-token");}
        @Override public void delete(String bucket,String key,String version)throws IOException{delegate.delete(bucket,key,version);}
    }
    private static final class WriteThenFailStorage implements OutputObjectStorage{
        private final OutputObjectStorage delegate;WriteThenFailStorage(OutputObjectStorage delegate){this.delegate=delegate;}
        @Override public Stored putCreateOnly(String bucket,String key,java.io.InputStream input,long length,String type,long deadline)throws IOException{delegate.putCreateOnly(bucket,key,input,length,type,deadline);throw new IOException("lost acknowledgement");}
        @Override public java.io.InputStream open(String bucket,String key,String version)throws IOException{return delegate.open(bucket,key,version);}
        @Override public void putTombstone(String bucket,String key,String token,long deadline)throws IOException{delegate.putTombstone(bucket,key,token,deadline);}
        @Override public Head head(String bucket,String key)throws IOException{return delegate.head(bucket,key);}
        @Override public void delete(String bucket,String key,String version)throws IOException{delegate.delete(bucket,key,version);}
    }
}
