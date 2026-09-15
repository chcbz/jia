package cn.jia.agent.output.service;

import cn.jia.agent.config.OutputDeliveryProperties;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputAuthorizationException;
import cn.jia.agent.output.OutputDeliveryException;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputTicketAuthorization;
import cn.jia.agent.output.OutputVersionProvider;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.TaskDeliveryDao;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.dto.OutputPublishDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutputDeliveryServiceImplTest {
    private static final byte[] BYTES="hello world!\n".getBytes(StandardCharsets.UTF_8);
    private static final String HASH="ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a";
    private static final String BEARER="Bearer "+"a".repeat(43);
    private OutputUploadDao dao;
    private FakeProvider provider;
    private OutputDeliveryServiceImpl service;
    private OutputRunAuthorizationService auth;
    private JdbcTemplate jdbc;
    @TempDir Path tempDirectory;

    @BeforeEach void setup() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:od03_"+System.nanoTime()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc=new JdbcTemplate(ds);createTables();
        dao=new OutputUploadDaoImpl(jdbc);provider=new FakeProvider();
        auth=mock(OutputRunAuthorizationService.class);
        when(auth.authorizeTicket("a".repeat(43),OutputConstants.OP_STATUS,true))
                .thenReturn(new OutputTicketAuthorization("owner","client","run-1","TASK",
                        "task-1","agent-1","binding-1","runtime-1",
                        List.of("upload","publish","status"),System.currentTimeMillis()+60_000,"ACTIVE"));
        when(auth.authorizeTicket("a".repeat(43),OutputConstants.OP_PUBLISH,false))
                .thenReturn(new OutputTicketAuthorization("owner","client","run-1","TASK",
                        "task-1","agent-1","binding-1","runtime-1",
                        List.of("upload","publish","status"),System.currentTimeMillis()+60_000,"ACTIVE"));
        service=new OutputDeliveryServiceImpl(auth,dao,new MemoryStorage(),
                new DataSourceTransactionManager(ds),properties(false),List.of(provider));
        long now=System.currentTimeMillis();
        dao.insertObject(new OutputUploadDao.ObjectRow("owner","client","object-1","run-1",
                "bucket","key",null,MessageDigest.getInstance("SHA-256").digest(BYTES),13L,
                "text/plain","PASSED","READY","test",now,now+60_000,null,0,null,null,null,null),now);
        dao.insertUpload(new OutputUploadDao.UploadRow("owner","client","upload-1","run-1",
                "binding-1","object-1","hello.txt",13L,MessageDigest.getInstance("SHA-256").digest(BYTES),
                "text/plain","READY",1L,now,null,now+60_000,now+60_000,13L,true,0,null,null,null,null),now);
    }

    @Test void objectPublishReplayOwnerReadDownloadAndReleasePin() throws Exception {
        OutputPublishDTO request=request("artifact-1","1",true,"Object title","object-1",null);
        var published=service.publish(BEARER,"publish-key-00001","TASK","task-1",request);
        assertEquals(HASH,published.sha256());assertEquals("OWNER_SHARE",published.publicationKind());
        assertEquals(published,service.publish(BEARER,"publish-key-00001","TASK","task-1",request));
        assertEquals(1,provider.rows.size());
        var detail=service.getVersion("owner","client","owner","TASK","task-1","artifact-1","1");
        assertEquals("Object title",detail.item().title());
        assertEquals(new String(BYTES, StandardCharsets.UTF_8), detail.content());
        var download=service.downloadVersion("owner","client","owner","TASK","task-1","artifact-1","1");
        try(InputStream in=download.stream()){assertArrayEquals(BYTES,in.readAllBytes());}
        assertEquals(2,jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='RELEASED'",
                Integer.class));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='OWNER_SHARE'",Integer.class));
    }

    @Test void terminalStatusTicketReplaysExistingPublicationWithoutNewMutation() {
        OutputPublishDTO request=request("replay-1","1",true,"Replay title",null,"done");
        var published=service.publish(BEARER,"publish-replay-key","TASK","task-1",request);
        OutputRunAuthorizationService terminalAuth=mock(OutputRunAuthorizationService.class);
        when(terminalAuth.authorizeTicket("a".repeat(43),OutputConstants.OP_STATUS,true))
                .thenReturn(new OutputTicketAuthorization("owner","client","run-1","TASK",
                        "task-1","agent-1","binding-1","runtime-1",List.of("status"),
                        System.currentTimeMillis()+60_000,"CLOSED"));
        when(terminalAuth.authorizeTicket("a".repeat(43),OutputConstants.OP_PUBLISH,false))
                .thenThrow(new OutputAuthorizationException("OUTPUT_AUTH_FORBIDDEN","closed"));
        OutputDeliveryServiceImpl terminal=new OutputDeliveryServiceImpl(terminalAuth,dao,
                new MemoryStorage(),new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false),List.of(provider));
        OutputDeliveryServiceImpl paused=new OutputDeliveryServiceImpl(terminalAuth,dao,
                new MemoryStorage(),new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(true),List.of(provider));

        assertEquals(published,terminal.publish(BEARER,"publish-replay-key","TASK","task-1",request));
        assertEquals(published,paused.publish(BEARER,"publish-replay-key","TASK","task-1",request));
        assertEquals(1,provider.rows.size());
        assertEquals("OUTPUT_AUTH_FORBIDDEN",assertThrows(OutputAuthorizationException.class,
                ()->terminal.publish(BEARER,"publish-replay-miss","TASK","task-1",request)).getCode());
    }

    @Test void privateTaskOutputAndCrossOwnerStayHidden() {
        service.publish(BEARER,"publish-key-00002","TASK","task-1",
                request("private-1","1",false,"Secret title",null,"secret"));
        assertEquals(0,service.list("owner","client","owner","TASK","task-1",null,20).items().size());
        assertEquals(404,assertThrows(OutputDeliveryException.class,()->service.getVersion(
                "owner","client","owner","TASK","task-1","private-1","1")).status());
        assertEquals(404,assertThrows(OutputDeliveryException.class,()->service.list(
                "other","client","other","TASK","task-1",null,20)).status());
    }

    @Test void exactFormalDeliveryItemGrantsOwnerReadWithoutOwnerShare() throws Exception {
        service.publish(BEARER,"publish-key-delivery-1","TASK","task-1",
                request("delivered-private","1",false,"Delivered title",null,"formal result"));
        TaskDeliveryDao deliveries=mock(TaskDeliveryDao.class);
        when(deliveries.containsTaskItem(
                "owner","client","task-1","delivered-private",1L)).thenReturn(true);
        OutputDeliveryServiceImpl formal=new OutputDeliveryServiceImpl(auth,dao,
                new MemoryStorage(),new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false),List.of(provider),deliveries);

        var detail=formal.getVersion("owner","client","owner","TASK","task-1",
                "delivered-private","1");
        assertEquals("DELIVERY",detail.item().publicationKind());
        assertEquals("formal result",detail.content());
        try(InputStream input=formal.downloadVersion("owner","client","owner","TASK","task-1",
                "delivered-private","1").stream()){
            assertArrayEquals("formal result".getBytes(StandardCharsets.UTF_8),input.readAllBytes());
        }
        assertEquals(404,assertThrows(OutputDeliveryException.class,()->formal.getVersion(
                "owner","client","owner","TASK","task-1","other-private","1")).status());
    }

    @Test void idempotencyConflictPredecessorConflictAndWritePauseFailClosed() {
        OutputPublishDTO request=request("artifact-2","1",true,"First",null,"one");
        service.publish(BEARER,"publish-key-00003","TASK","task-1",request);
        assertEquals(409,assertThrows(OutputDeliveryException.class,()->service.publish(BEARER,
                "publish-key-00003","TASK","task-1",request("artifact-2","1",true,"Changed",null,"two"))).status());
        assertEquals(409,assertThrows(OutputDeliveryException.class,()->service.publish(BEARER,
                "publish-key-00004","TASK","task-1",request("artifact-2","1",true,"Again",null,"two"))).status());
        OutputRunAuthorizationService pausedAuth=mock(OutputRunAuthorizationService.class);
        when(pausedAuth.authorizeTicket("a".repeat(43),OutputConstants.OP_STATUS,true))
                .thenReturn(new OutputTicketAuthorization("owner","client","run-1","TASK",
                        "task-1","agent-1","binding-1","runtime-1",
                        List.of("upload","publish","status"),System.currentTimeMillis()+60_000,"ACTIVE"));
        OutputDeliveryServiceImpl paused=new OutputDeliveryServiceImpl(pausedAuth,
                dao,new MemoryStorage(),new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(true),List.of(provider));
        assertFalse(paused.capabilities().outputUploadV1());
        assertEquals(1,paused.list("owner","client","owner","TASK","task-1",null,20).items().size());
        assertEquals(503,assertThrows(OutputDeliveryException.class,()->paused.publish(BEARER,
                "publish-key-00005","TASK","task-1",request("new-1","1",true,"New",null,"x"))).status());
    }

    @Test void canonicalReceiptHashDistinguishesNullFromLiteralNullString() {
        OutputPublishDTO literalNull = request("null-collision","1",true,
                "Literal null",null,"<null>");
        OutputPublishDTO actualNull = request("null-collision","1",true,
                "Literal null","<null>",null);

        var published = service.publish(BEARER,"publish-null-key01","TASK","task-1",literalNull);

        assertEquals(published,service.publish(BEARER,"publish-null-key01",
                "TASK","task-1",literalNull));
        OutputDeliveryException conflict = assertThrows(OutputDeliveryException.class,
                () -> service.publish(BEARER,"publish-null-key01",
                        "TASK","task-1",actualNull));
        assertEquals(409,conflict.status());
        assertEquals("OUTPUT_IDEMPOTENCY_CONFLICT",conflict.code());
        assertEquals(1,provider.rows.size());
    }

    @Test void signedCursorTraversesMoreThanOneHundredAndRejectsTampering() {
        long now=System.currentTimeMillis();
        for(int i=0;i<105;i++)provider.rows.add(new OutputVersionProvider.PublishRow("owner","client",
                "task-1",String.format("out-%03d",i),1,"run-1","agent-1","T"+i,null,"summary",
                "x",null,sha("x"),1,"text/plain","OWNER_SHARE","task_members",null,now,
                now+60_000,now-i));
        var first=service.list("owner","client","owner","TASK","task-1",null,100);
        assertEquals(100,first.items().size());
        var second=service.list("owner","client","owner","TASK","task-1",first.nextCursor(),100);
        assertEquals(5,second.items().size());
        String tampered=first.nextCursor().substring(0,first.nextCursor().length()-1)+"A";
        assertEquals(400,assertThrows(OutputDeliveryException.class,()->service.list(
                "owner","client","owner","TASK","task-1",tampered,100)).status());
        assertEquals(400,assertThrows(OutputDeliveryException.class,()->service.listVersions(
                "owner","client","owner","TASK","task-1","out-001",first.nextCursor(),100)).status());
        assertEquals(400,assertThrows(OutputDeliveryException.class,()->service.list(
                "owner","client","owner","TASK","task-1",expiredCursor(),100)).status());
    }

    @Test void inlineOutputUsesSameOwnerAclAndDownloadsWithoutReadPin() throws Exception {
        OutputPublishDTO request=request("inline-1","1",true,"Inline title",null,"正文内容");

        var published=service.publish(BEARER,"publish-key-inline-01","TASK","task-1",request);
        var detail=service.getVersion("owner","client","owner","TASK","task-1","inline-1","1");
        var download=service.downloadVersion("owner","client","owner","TASK","task-1","inline-1","1");

        assertEquals("正文内容",detail.content());
        assertEquals("text/plain",published.mime());
        try(InputStream in=download.stream()){
            assertArrayEquals("正文内容".getBytes(StandardCharsets.UTF_8),in.readAllBytes());
        }
        assertEquals(0,jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN'",Integer.class));
    }

    @Test void uploadedMarkdownPreviewReadsARealFileWithBoundedUtf8AndReadPin() throws Exception {
        byte[] markdown = "# 交付结果\n\n已完成授权后的 Markdown 预览。\n".getBytes(StandardCharsets.UTF_8);
        Path stored = tempDirectory.resolve("uploaded-readme.md");
        Files.write(stored, markdown);
        insertReadyObject("object-md", "readme.md", markdown, "text/markdown");
        OutputDeliveryServiceImpl fileService = new OutputDeliveryServiceImpl(auth, dao,
                new PathStorage(stored),
                new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false), List.of(provider));

        var published = fileService.publish(BEARER, "publish-markdown-01", "TASK", "task-1",
                request("markdown-1", "1", true, "Markdown", "object-md", null));
        var detail = fileService.getVersion(
                "owner", "client", "owner", "TASK", "task-1", "markdown-1", "1");

        assertEquals("TEXT", published.previewKind());
        assertEquals("TEXT", detail.item().previewKind());
        assertEquals(new String(markdown, StandardCharsets.UTF_8), detail.content());
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT state FROM output_object_reference WHERE reference_kind='READ_PIN'",
                String.class));
    }

    @Test void oversizedUploadedTextStaysDownloadOnlyWithoutOpeningStorageOrCreatingReadPin()
            throws Exception {
        byte[] digest = sha("oversized-text-placeholder");
        long oversized = 262_145L;
        long now = System.currentTimeMillis();
        dao.insertObject(new OutputUploadDao.ObjectRow("owner", "client", "object-large", "run-1",
                "bucket", "large-key", null, digest, oversized, "text/plain",
                "PASSED", "READY", "test", now, now + 60_000, null, 0,
                null, null, null, null), now);
        dao.insertUpload(new OutputUploadDao.UploadRow("owner", "client", "upload-large", "run-1",
                "binding-1", "object-large", "large.txt", oversized, digest, "text/plain",
                "READY", 1L, now, null, now + 60_000, now + 60_000, oversized, true,
                0, null, null, null, null), now);
        OutputDeliveryServiceImpl unopened = new OutputDeliveryServiceImpl(auth, dao,
                new OutputObjectStorage() {
                    @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
                    @Override public InputStream open(String b,String k,String v){throw new AssertionError("oversized preview must not open storage");}
                    @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
                    @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
                    @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}
                }, new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false), List.of(provider));

        var published = unopened.publish(BEARER, "publish-large-text", "TASK", "task-1",
                request("large-text", "1", true, "Large text", "object-large", null));
        var detail = unopened.getVersion(
                "owner", "client", "owner", "TASK", "task-1", "large-text", "1");

        assertEquals("NONE", published.previewKind());
        assertEquals("NONE", detail.item().previewKind());
        assertNull(detail.content());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN'",
                Integer.class));
    }

    @Test void uploadedTextPreviewRejectsLengthAndHashDriftAndReleasesReadPins()
            throws Exception {
        byte[] expectedLength = "expected-length".getBytes(StandardCharsets.UTF_8);
        insertReadyObject("object-length", "length.txt", expectedLength, "text/plain");
        Path shortFile = tempDirectory.resolve("short.txt");
        Files.write(shortFile, "short".getBytes(StandardCharsets.UTF_8));
        OutputDeliveryServiceImpl shortService = fileService(shortFile);
        shortService.publish(BEARER, "publish-length-drift", "TASK", "task-1",
                request("length-drift", "1", true, "Length drift", "object-length", null));
        assertPreviewFailure(shortService, "length-drift", "OUTPUT_OBJECT_UNAVAILABLE");

        byte[] expectedHash = "same-size-good".getBytes(StandardCharsets.UTF_8);
        byte[] wrongHash = "same-size-evil".getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedHash.length, wrongHash.length);
        insertReadyObject("object-hash", "hash.txt", expectedHash, "text/plain");
        Path changedFile = tempDirectory.resolve("changed.txt");
        Files.write(changedFile, wrongHash);
        OutputDeliveryServiceImpl changedService = fileService(changedFile);
        changedService.publish(BEARER, "publish-hash-drift", "TASK", "task-1",
                request("hash-drift", "1", true, "Hash drift", "object-hash", null));
        assertPreviewFailure(changedService, "hash-drift", "OUTPUT_OBJECT_UNAVAILABLE");

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='ACTIVE'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='RELEASED'",
                Integer.class));
    }

    @Test void uploadedTextPreviewRejectsInvalidUtf8AndReadFailureWithoutLeakingPins()
            throws Exception {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        insertReadyObject("object-utf8", "invalid.txt", invalidUtf8, "text/plain");
        Path invalidFile = tempDirectory.resolve("invalid.txt");
        Files.write(invalidFile, invalidUtf8);
        OutputDeliveryServiceImpl invalidService = fileService(invalidFile);
        invalidService.publish(BEARER, "publish-invalid-utf8", "TASK", "task-1",
                request("invalid-utf8", "1", true, "Invalid UTF-8", "object-utf8", null));
        assertPreviewFailure(invalidService, "invalid-utf8", "OUTPUT_OBJECT_UNAVAILABLE");

        byte[] readable = "read-failure".getBytes(StandardCharsets.UTF_8);
        insertReadyObject("object-read", "read.txt", readable, "text/plain");
        OutputDeliveryServiceImpl failingService = new OutputDeliveryServiceImpl(auth, dao,
                new FailingReadStorage(),
                new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false), List.of(provider));
        failingService.publish(BEARER, "publish-read-failure", "TASK", "task-1",
                request("read-failure", "1", true, "Read failure", "object-read", null));
        assertPreviewFailure(failingService, "read-failure", "OUTPUT_STORAGE_UNAVAILABLE");

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='ACTIVE'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='READ_PIN' AND state='RELEASED'",
                Integer.class));
    }

    @Test void expiredVersionStaysGoneEvenWhenSameObjectHasAnotherLiveReference() {
        long now=System.currentTimeMillis();
        provider.rows.add(new OutputVersionProvider.PublishRow("owner","client","task-1",
                "expired-1",1,"run-1","agent-1","Expired","hello.txt","document",
                null,"object-1",sha("hello world!\n"),13,"text/plain","OWNER_SHARE",
                "task_members",null,now-10,now-1,now-100));
        dao.insertReference(new OutputUploadDao.ReferenceRow("owner","client",sha("other-live-ref"),
                "object-1","TASK","task-1","other-output",1,"DELIVERY_PIN","delivery-1",
                "ACTIVE",now+60_000,true,"accepted",null),now);

        assertEquals(410,assertThrows(OutputDeliveryException.class,()->service.getVersion(
                "owner","client","owner","TASK","task-1","expired-1","1")).status());
        assertEquals(410,assertThrows(OutputDeliveryException.class,()->service.downloadVersion(
                "owner","client","owner","TASK","task-1","expired-1","1")).status());
        assertEquals(1L,dao.activeObjectReferences("owner","client","object-1",now));
    }

    private OutputPublishDTO request(String id,String version,boolean share,String title,
            String object,String content){return new OutputPublishDTO("run-1","0",title,"summary",
            content,object,id,version,null,"task_members",share);}
    private void insertReadyObject(String objectId, String fileName, byte[] bytes, String mime)
            throws Exception {
        long now = System.currentTimeMillis();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        dao.insertObject(new OutputUploadDao.ObjectRow("owner", "client", objectId, "run-1",
                "bucket", "key-" + objectId, null, digest, (long) bytes.length, mime,
                "PASSED", "READY", "test", now, now + 60_000, null, 0,
                null, null, null, null), now);
        dao.insertUpload(new OutputUploadDao.UploadRow("owner", "client", "upload-" + objectId,
                "run-1", "binding-1", objectId, fileName, bytes.length, digest, mime,
                "READY", 1L, now, null, now + 60_000, now + 60_000, bytes.length, true,
                0, null, null, null, null), now);
    }
    private OutputDeliveryServiceImpl fileService(Path path) {
        return new OutputDeliveryServiceImpl(auth, dao, new PathStorage(path),
                new DataSourceTransactionManager(jdbc.getDataSource()),
                properties(false), List.of(provider));
    }
    private void assertPreviewFailure(
            OutputDeliveryServiceImpl target, String outputId, String expectedCode) {
        OutputDeliveryException failure = assertThrows(OutputDeliveryException.class,
                () -> target.getVersion("owner", "client", "owner", "TASK", "task-1",
                        outputId, "1"));
        assertEquals(expectedCode, failure.code());
    }
    private OutputDeliveryProperties properties(boolean paused){return new OutputDeliveryProperties(true,
            null,null,null,"bucket","localhost",3310,10,50L*1024*1024,90L*1024*1024,null,
            paused,"test-cursor-key-with-at-least-thirty-two-bytes");}
    private String expiredCursor(){
        try{
            long snapshot=System.currentTimeMillis()-1000;
            String payload=String.join("|","1",b64("owner"),b64("client"),"TASK",b64("task-1"),
                    b64("*"),"1",Long.toString(snapshot),Long.toString(snapshot),b64("out-100"),
                    "1",Long.toString(System.currentTimeMillis()-1));
            byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sha("test-cursor-key-with-at-least-thirty-two-bytes"),
                    "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)+"."
                    +Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(bytes));
        }catch(Exception error){throw new IllegalStateException(error);}
    }
    private static String b64(String value){return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private void createTables(){
        jdbc.execute("""
                CREATE TABLE output_object(tenant_id VARBINARY(200),client_id VARBINARY(200),
                object_id VARBINARY(100),run_id VARBINARY(100),bucket VARCHAR(100),storage_key VARCHAR(512),
                storage_version VARCHAR(200),actual_sha256 BINARY(32),actual_size BIGINT,actual_mime VARCHAR(100),
                verification_status VARCHAR(16),lifecycle_status VARCHAR(16),scan_engine_version VARCHAR(100),
                verified_at BIGINT,delete_after BIGINT,deleted_at BIGINT,delete_attempts INT,delete_next_at BIGINT,
                delete_lease_owner VARBINARY(100),delete_lease_until BIGINT,error_code VARCHAR(80),created_at BIGINT,
                updated_at BIGINT,row_version BIGINT,PRIMARY KEY(tenant_id,client_id,object_id))
                """);
        jdbc.execute("""
                CREATE TABLE output_upload_session(tenant_id VARBINARY(200),client_id VARBINARY(200),
                upload_id VARBINARY(100),run_id VARBINARY(100),binding_id VARBINARY(400),object_id VARBINARY(100),
                file_name VARCHAR(255),expected_size BIGINT,expected_sha256 BINARY(32),declared_mime VARCHAR(100),
                state VARCHAR(24),writer_epoch BIGINT,writer_started_at BIGINT,writer_until BIGINT,
                writer_deadline_at BIGINT,expires_at BIGINT,reserved_bytes BIGINT,slot_released BOOLEAN,
                verification_attempts INT,verification_next_at BIGINT,verification_lease_owner VARBINARY(100),
                verification_lease_until BIGINT,error_code VARCHAR(80),created_at BIGINT,updated_at BIGINT,
                row_version BIGINT,PRIMARY KEY(tenant_id,client_id,upload_id),UNIQUE(tenant_id,client_id,object_id))
                """);
        jdbc.execute("""
                CREATE TABLE output_object_reference(tenant_id VARBINARY(200),client_id VARBINARY(200),
                reference_key BINARY(32),object_id VARBINARY(100),source_type VARCHAR(20),source_id VARBINARY(400),
                output_id VARBINARY(400),output_version BIGINT,reference_kind VARCHAR(24),delivery_id VARBINARY(100),
                state VARCHAR(16),retain_until BIGINT,hold BOOLEAN,hold_reason VARCHAR(255),released_at BIGINT,
                created_at BIGINT,updated_at BIGINT,row_version BIGINT,PRIMARY KEY(tenant_id,client_id,reference_key))
                """);
        jdbc.execute("""
                CREATE TABLE output_mutation_receipt(tenant_id VARBINARY(200),client_id VARBINARY(200),
                actor_kind VARCHAR(16),actor_id VARBINARY(400),operation VARCHAR(80),idempotency_key VARBINARY(100),
                request_hash BINARY(32),http_status INT,response_json VARCHAR(8000),retain_until BIGINT,created_at BIGINT,
                updated_at BIGINT,row_version BIGINT,PRIMARY KEY(tenant_id,client_id,actor_kind,actor_id,operation,idempotency_key))
                """);
    }
    private static byte[] sha(String value){try{return MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}

    private static final class MemoryStorage implements OutputObjectStorage {
        @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
        @Override public InputStream open(String b,String k,String v){return new ByteArrayInputStream(BYTES);}
        @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
        @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
        @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}
    }

    private static final class PathStorage implements OutputObjectStorage {
        private final Path path;
        private PathStorage(Path path) { this.path = path; }
        @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
        @Override public InputStream open(String b,String k,String v) throws java.io.IOException { return Files.newInputStream(path); }
        @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
        @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
        @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}
    }

    private static final class FailingReadStorage implements OutputObjectStorage {
        @Override public Stored putCreateOnly(String b,String k,InputStream i,long l,String t,long d){throw new UnsupportedOperationException();}
        @Override public InputStream open(String b,String k,String v) {
            return new InputStream() {
                @Override public int read() throws java.io.IOException {
                    throw new java.io.IOException("injected preview read failure");
                }
            };
        }
        @Override public void putTombstone(String b,String k,String t,long d){throw new UnsupportedOperationException();}
        @Override public Head head(String b,String k){throw new UnsupportedOperationException();}
        @Override public void delete(String b,String k,String v){throw new UnsupportedOperationException();}
    }

    private static final class FakeProvider implements OutputVersionProvider {
        private final List<PublishRow> rows=new ArrayList<>();
        @Override public String sourceType(){return "TASK";}
        @Override public void requireOwner(String t,String c,String j,String s,boolean lock){
            if(!"owner".equals(t)||!"client".equals(c)||!t.equals(j)||!"task-1".equals(s))
                throw new OutputDeliveryException("OUTPUT_NOT_FOUND","hidden",404,false);}
        @Override public PublishRow findLatestForUpdate(String t,String c,String s,String o){return rows.stream()
                .filter(r->r.outputId().equals(o)).max(Comparator.comparingLong(PublishRow::version)).orElse(null);}
        @Override public int insert(PublishRow r){rows.add(r);return 1;}
        @Override public void appendPublicationEvent(PublishRow r){}
        @Override public PublishRow findVersion(String t,String c,String s,String o,long v){return rows.stream()
                .filter(r->r.outputId().equals(o)&&r.version()==v).findFirst().orElse(null);}
        @Override public List<PublishRow> list(String t,String c,String s,String output,boolean latest,
                long snapshot,CursorBoundary after,int limit){Map<String,PublishRow> newest=new HashMap<>();
            for(PublishRow r:rows)if(r.createdAt()<=snapshot&&r.retainUntil()>System.currentTimeMillis()
                    &&r.ownerSharedAt()!=null&&(output==null||output.equals(r.outputId())))
                newest.merge(r.outputId(),r,(a,b)->a.version()>b.version()?a:b);
            var stream=(latest?newest.values():rows).stream().filter(r->output==null||output.equals(r.outputId()))
                    .filter(r->r.createdAt()<=snapshot&&r.retainUntil()>System.currentTimeMillis()&&r.ownerSharedAt()!=null)
                    .sorted(Comparator.comparingLong(PublishRow::createdAt).reversed()
                            .thenComparing(PublishRow::outputId,Comparator.reverseOrder())
                            .thenComparing(Comparator.comparingLong(PublishRow::version).reversed()));
            if(after!=null)stream=stream.filter(r->r.createdAt()<after.createdAt()
                    ||r.createdAt()==after.createdAt()&&(r.outputId().compareTo(after.outputId())<0
                    ||r.outputId().equals(after.outputId())&&r.version()<after.version()));
            return stream.limit(limit).toList();}
    }
}
