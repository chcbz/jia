package cn.jia.agent.config;

import cn.jia.agent.dao.AgentRuntimeV2RegistrationStore;
import cn.jia.agent.entity.*;
import cn.jia.agent.security.*;
import cn.jia.agent.service.AgentRuntimeV2RegistrationService;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.*;
import javax.crypto.spec.SecretKeySpec;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 transactions/current locks + production crypto. NOT a real MySQL catalog test. */
class AgentRuntimeV2RegistrationTransactionTest {
    static class MutableClock extends Clock {long value=1790776800000L;final Deque<Long> samples=new ArrayDeque<>();public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId ignored){return this;}public Instant instant(){return Instant.ofEpochMilli(value);}public long millis(){return samples.isEmpty()?value:samples.removeFirst();}}
    static class CountedCsprng extends SecureRandom {int calls;Runnable afterProof;@Override public void nextBytes(byte[] bytes){calls++;super.nextBytes(bytes);if(afterProof!=null){Runnable hook=afterProof;afterProof=null;hook.run();}}}
    static class TrackingDataSource extends DriverManagerDataSource {
        int connections,retryEntries;boolean observeRetry;final List<String> transactionEvents=new ArrayList<>();
        TrackingDataSource(String url){super(url,"sa","");}
        TrackingDataSource(String url,String user,String password){super(url,user,password);}
        @Override public java.sql.Connection getConnection() throws java.sql.SQLException{
            connections++;
            if(observeRetry){AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(this));assertEquals(retryEntries,transactionEvents.stream().filter(e->e.equals("rollback")).count());retryEntries++;transactionEvents.add("entry");}
            var connection=super.getConnection();
            if(observeRetry)try(var statement=connection.createStatement()) {for(String table:List.of(BINDING,FACT,RECEIPT,REQUEST,CHALLENGE,DECISION))try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"failed attempt must be fully rolled back before next entry");}}
            return connection;
        }
    }
    static final String AUTH="66666666-6666-4666-8666-666666666666",INSTANCE="22222222-2222-4222-8222-222222222222",FENCE="cccccccc-cccc-4ccc-8ccc-cccccccccccc",OWNER="dddddddd-dddd-4ddd-8ddd-dddddddddddd";
    static final String SECRET="test-only-api-key-never-production";
    static class TrackingJdbc extends JdbcTemplate {
        final List<String> locks=new ArrayList<>();int writeCalls,rollbacks,commits;boolean failMappingOnce,failProjection;
        final Deque<RuntimeException> mappingFailures=new ArrayDeque<>();int observedRetryEntry;
        String afterWritePrefix;Runnable afterWrite;
        TrackingJdbc(DriverManagerDataSource source){super(source);}
        @Override public <T> T query(String sql,org.springframework.jdbc.core.PreparedStatementSetter setter,org.springframework.jdbc.core.ResultSetExtractor<T> extractor){if(sql.endsWith("FOR UPDATE"))locks.add(sql);return super.query(sql,setter,extractor);}
        @Override public int update(String sql,Object... args){
            if(TransactionSynchronizationManager.isActualTransactionActive()&&(sql.startsWith("INSERT INTO agent_runtime_v2_")||sql.startsWith("UPDATE agent_runtime_v2_"))){writeCalls++;}
            var source=(TrackingDataSource)getDataSource();
            if(source.observeRetry&&observedRetryEntry!=source.retryEntries){observedRetryEntry=source.retryEntries;TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK){rollbacks++;source.transactionEvents.add("rollback");}else{assertEquals(STATUS_COMMITTED,status);commits++;source.transactionEvents.add("commit");}}});}
            if(!mappingFailures.isEmpty()&&sql.startsWith("INSERT INTO "+REQUEST))throw mappingFailures.removeFirst();
            if(failMappingOnce&&sql.startsWith("INSERT INTO "+REQUEST)){failMappingOnce=false;TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)rollbacks++;else commits++;}});throw new DuplicateKeyException("test unique collision");}
            if(failProjection&&sql.startsWith("UPDATE "+BINDING))throw new IllegalStateException("test projection fault");
            int changed=super.update(sql,args);
            if(afterWrite!=null&&sql.startsWith(afterWritePrefix)){assertEquals(1,changed);Runnable hook=afterWrite;afterWrite=null;hook.run();}
            return changed;
        }
    }
    static class Fixture {
        final MutableClock clock=new MutableClock();final TrackingJdbc jdbc;final TrackingDataSource dataSource;final AgentRuntimeV2RegistrationStore db;
        final AgentRuntimeV2RegistrationKeyRing keys;final AgentRuntimeV2RegistrationAuthorityResolver resolver;
        final AgentRuntimeV2RegistrationServiceImpl service;final AgentRuntimeV2RegistrationContext identity;
        final byte[] publicKey,privateKey;
        final CountedCsprng proofs=new CountedCsprng();
        final boolean privateMysql;
        Fixture() throws Exception {this(null);}
        Fixture(AgentRuntimeV2PrivateMysqlFixture nativeFixture) throws Exception {
            privateMysql=nativeFixture!=null;
            dataSource=privateMysql?nativeFixture.runtimeDataSource():new TrackingDataSource("jdbc:h2:mem:r2b2_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");jdbc=new TrackingJdbc(dataSource);
            var pair=KeyPairGenerator.getInstance("X25519").generateKeyPair();publicKey=pair.getPublic().getEncoded();privateKey=pair.getPrivate().getEncoded();
            var hmac=new SecretKeySpec(HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),"HmacSHA256");
            keys=new AgentRuntimeV2RegistrationKeyRing("test-key",Map.of("test-key",hmac),Set.of());
            source("core_tenant_control",row("tenant_id","1","state","ENABLED","control_revision",12L,"tenant_session_epoch",6L,"admission_epoch",7L,"drain_epoch",2L,"drain_started_at",null,"drain_deadline",null),"tenant_id");
            source("core_tenant_module_control",row("tenant_id","1","module_id","agent-command","mode_state","DOMAIN","mode_revision",4L,"migration_epoch",3L,"drain_sealed",1L,"schema_version",2L,"mapping_digest","a".repeat(64),"drain_cutoff",null,"drain_deadline",null),"tenant_id,module_id");
            source("oauth_client",row("client_id","runtime-client"),"client_id");
            source("oauth_client_tenant_grant",row("tenant_id","1","client_id","runtime-client","status","ACTIVE","grant_revision",8L),"tenant_id,client_id");
            source("oauth_client_tenant_domain_grant",row("tenant_id","1","client_id","runtime-client","data_domain_id","cyf","status","ACTIVE","domain_revision",3L),"tenant_id,client_id,data_domain_id");
            source("user_info",row("jiacn","owner-a","account_state","ACTIVE","auth_epoch",9L,"id",42L),"jiacn");
            source("oauth_api_key",row("id","api-key-1","tenant_id","1","client_id","runtime-client","jiacn","owner-a","status",1L,"expire_time",null,"api_key",SECRET),"id");
            source("agent_runtime",row("tenant_id","1","client_id","runtime-client","owner_jiacn","owner-a","agent_id","agent-a","binding_id",301L,"id",101L),"tenant_id,client_id,owner_jiacn,agent_id");
            source("agent_identity_registry",row("tenant_id","1","client_id","runtime-client","owner_jiacn","owner-a","canonical_agent_id","agent-a","lifecycle_status","ACTIVE","binding_id",301L,"id",201L,"update_time",clock.value-1000),"tenant_id,client_id,owner_jiacn,canonical_agent_id");
            source("agent_persona_binding",row("id",301L,"tenant_id","1","client_id","runtime-client","jiacn","owner-a","agent_id","agent-a","status",1L,"update_time",clock.value-1000),"id");
            source("agent_runtime_v2_registration_authority",row("registration_authority_id",AUTH,"credential_kind","API_KEY","credential_reference","api-key-1","tenant_id","1","client_id","runtime-client","owner_jiacn","owner-a","agent_id","agent-a","data_domain_id","cyf","module_id","agent-command","claimant_deployment_id","local-api-a","execution_pool_id","local-experiment-a","status","ACTIVE","credential_key_id","test-key","credential_digest",apiKeyCredential(keys.active(),"api-key-1",SECRET),"authority_revision",5L),"registration_authority_id");
            source("core_deployment_registration",row("deployment_id","local-api-a","status","ACTIVE","stage","LOCAL_TEST","artifact_digest","b".repeat(64),"worker_generation",4L,"registration_revision",11L),"deployment_id");
            source("core_deployment_tenant_grant",row("deployment_id","local-api-a","tenant_id","1","data_domain_id","cyf","status","ACTIVE","execution_pool_id","local-experiment-a","grant_revision",13L,"allow_read",1L,"allow_write",1L),"deployment_id,tenant_id,data_domain_id");
            source("core_deployment_module_admission",row("deployment_id","local-api-a","tenant_id","1","data_domain_id","cyf","module_id","agent-command","mode_state","DOMAIN","mode_revision",4L,"migration_epoch",3L,"admission_epoch",7L,"schema_version",2L,"registration_revision",11L,"grant_revision",13L,"allow_read",1L,"allow_write",1L,"status","ACTIVE"),"deployment_id,tenant_id,data_domain_id,module_id");
            if(!privateMysql){
            jdbc.execute("CREATE TABLE core_tenant_operation(tenant_id VARCHAR(50),operator_id VARCHAR(128),idempotency_key VARCHAR(128),to_state VARCHAR(32),result_revision BIGINT,result_snapshot VARCHAR(256),PRIMARY KEY(tenant_id,operator_id,idempotency_key))");
            for(var entry:AgentRuntimeV2PersistenceSchemaGate.registrationExtendedColumns().entrySet())if(Set.of(BINDING,FACT).contains(entry.getKey())) {
                List<String> columns=new ArrayList<>();for(var c:entry.getValue())columns.add(column(c.name(),c.columnType(),c.nullable(),c.extra()));jdbc.execute("CREATE TABLE "+entry.getKey()+"("+String.join(",",columns)+")");
            }
            for(var entry:AgentRuntimeV2RegistrationSchemaGate.expectedColumns().entrySet())if(Set.of(RECEIPT,REQUEST,CHALLENGE).contains(entry.getKey())) {
                List<String> columns=new ArrayList<>();for(var c:entry.getValue())columns.add(column(c.name(),c.columnType(),c.nullable(),c.extra()));jdbc.execute("CREATE TABLE "+entry.getKey()+"("+String.join(",",columns)+")");
            }
            for(var entry:AgentRuntimeV2RegistrationDeltaSchemaGate.expectedColumns().entrySet()) {
                List<String> columns=new ArrayList<>();for(var c:entry.getValue())columns.add(column(c.name(),c.columnType(),c.nullable(),c.extra()));jdbc.execute("CREATE TABLE "+entry.getKey()+"("+String.join(",",columns)+")");
            }
            unique(BINDING,"runtime_binding_id");unique(BINDING,"registration_subject_sha256");unique(FACT,"binding_generation_fact_id");unique(FACT,"runtime_binding_id,fact_sequence");unique(RECEIPT,"registration_receipt_id");unique(RECEIPT,"runtime_binding_id,binding_generation");unique(REQUEST,"registration_request_id");unique(CHALLENGE,"challenge_request_id");unique(CHALLENGE,"challenge_id");unique(DECISION,"challenge_request_id");unique(DECISION,"challenge_id");unique(OPERATION,"drain_operation_id");unique(COMPLETION,"drain_operation_id,runtime_binding_id");
            jdbc.execute("CREATE TABLE agent_command_v2_execution_grant(runtime_binding_id VARCHAR(36),evidence_provenance VARCHAR(24))");
            jdbc.execute("CREATE TABLE agent_command_v2_execution_lease_fact(runtime_binding_id VARCHAR(36),evidence_provenance VARCHAR(24))");
            jdbc.execute("CREATE TABLE agent_command_v2_execution_request(runtime_binding_id VARCHAR(36),evidence_provenance VARCHAR(24))");
            jdbc.execute("CREATE TABLE agent_command_v2_ack_receipt(runtime_binding_id VARCHAR(36),contract_version SMALLINT,evidence_provenance VARCHAR(24))");
            jdbc.execute("CREATE TABLE agent_consumer_inbox(runtime_binding_id VARCHAR(36),execution_holder_binding_id VARCHAR(36),protocol_version SMALLINT,runtime_dispatch_id VARCHAR(36),evidence_provenance VARCHAR(24))");
            }
            db=new AgentRuntimeV2RegistrationStore(jdbc);resolver=new AgentRuntimeV2RegistrationAuthorityResolver(db,keys,clock);service=new AgentRuntimeV2RegistrationServiceImpl(db,resolver,keys,clock,proofs);identity=resolver.proof("api-key-1","agent-a",SECRET);jdbc.writeCalls=0;
        }
        private static String column(String name,String type,boolean nullable,String extra){return name+" "+type+("auto_increment".equals(extra)?" GENERATED BY DEFAULT AS IDENTITY":nullable?"":" NOT NULL");}
        private void unique(String table,String names){jdbc.execute("CREATE UNIQUE INDEX u_"+UUID.randomUUID().toString().replace("-","")+" ON "+table+"("+names+")");}
        private void source(String table,Map<String,Object> values,String primary){
            if(privateMysql){
                // Explicit TEST setup metadata for native source mandatory
                // columns, never derived native SQL/types from these values.
                if(table.equals("agent_runtime_v2_registration_authority")){values.put("create_time",clock.value);values.put("update_time",clock.value);}
                if(table.equals("core_deployment_registration"))values.put("credential_digest",cn.jia.agent.entity.AgentRuntimeV2CompositeWorkerProof.from(new cn.jia.core.tenant.WorkerScope(cn.jia.core.tenant.TenantId.of("0"),cn.jia.core.tenant.DataDomainId.of("cyf"),"local-experiment-a","local-api-a",4,11,7,"agent-command",3),"b".repeat(64),"c".repeat(64)).credentialSha256());
                insert(table,values);return;
            }
            List<String> columns=new ArrayList<>();values.forEach((name,value)->columns.add(name+" "+(value instanceof byte[]?"BINARY(32)":value instanceof Number||name.endsWith("_at")||name.contains("deadline")||name.equals("expire_time")||name.equals("drain_cutoff")?"BIGINT":"VARCHAR(500)")));jdbc.execute("CREATE TABLE "+table+"("+String.join(",",columns)+",PRIMARY KEY("+primary+"))");insert(table,values);
        }
        void insert(String table,Map<String,Object> values){jdbc.update("INSERT INTO "+table+"("+String.join(",",values.keySet())+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());}
        byte[] createWire(String id){return AgentRuntimeV2RegistrationCodec.encodeCreate(new AgentRuntimeV2RegistrationCodec.Create(id,INSTANCE,publicKey));}
        AgentRuntimeV2RegistrationService.Result create(){return service.create(identity,FENCE,OWNER,0,createWire(uuid()));}
        AgentRuntimeV2RegistrationCodec.Receipt receipt(AgentRuntimeV2RegistrationService.Result result){return AgentRuntimeV2RegistrationCodec.decodeReceipt(AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(result.responseBytes()).receiptBytes());}
        byte[] verifier(AgentRuntimeV2RegistrationCodec.Receipt receipt){return AgentRuntimeV2RegistrationCrypto.openVerifier(receipt.sealedVerifier(),receipt.nonce(),AgentRuntimeV2RegistrationCodec.receiptAad(receipt),privateKey,receipt.serverEphemeralPublicKeySpki());}
        byte[] challengeWire(AgentRuntimeV2RegistrationCodec.Receipt receipt,String id){return AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(new AgentRuntimeV2RegistrationCodec.ChallengeRequest(id,receipt.runtimeBindingId(),receipt.registrationReceiptId(),sha256(AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt)),INSTANCE));}
        byte[] commitWire(AgentRuntimeV2RegistrationCodec.Receipt receipt,AgentRuntimeV2RegistrationCodec.Challenge challenge,String id){byte[] plain=AgentRuntimeV2RegistrationCrypto.openChallenge(challenge.sealedChallenge(),challenge.nonce(),AgentRuntimeV2RegistrationCodec.challengeAad(challenge),privateKey,challenge.serverEphemeralPublicKeySpki());return AgentRuntimeV2RegistrationCodec.encodeCommit(new AgentRuntimeV2RegistrationCodec.Commit(id,challenge.challengeId(),receipt.runtimeBindingId(),receipt.registrationReceiptId(),sha256(AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt)),INSTANCE,verifier(receipt),plain));}
        int count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
        Map<String,List<String>> snapshot(){
            Map<String,List<String>> result=new TreeMap<>();
            for(String table:jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'",String.class)){
                List<String> rows=new ArrayList<>();for(var persisted:jdbc.queryForList("SELECT * FROM "+table)){Map<String,Object> canonical=new TreeMap<>();persisted.forEach((column,value)->canonical.put(column,value instanceof byte[] bytes?HexFormat.of().formatHex(bytes):value));rows.add(canonical.toString());}Collections.sort(rows);result.put(table,List.copyOf(rows));
            }
            return result;
        }
        void drain(AgentRuntimeV2RegistrationCodec.Receipt receipt,String scope,String deploymentStatus) {
            long cutoff=clock.value+1000;clock.value+=2000;boolean tenant=!"MODULE_QUIESCE".equals(scope),module=!"TENANT_DRAIN".equals(scope);
            if(tenant){jdbc.update("UPDATE core_tenant_control SET state='DRAINING',control_revision=13,admission_epoch=8,drain_epoch=3,drain_started_at=?,drain_deadline=?",cutoff,cutoff+60000);jdbc.update("INSERT INTO core_tenant_operation VALUES('1','operator','idempotency','DRAINING',13,'DRAINING|13|6|8')");}
            if(module)jdbc.update("UPDATE core_tenant_module_control SET mode_state='QUIESCING',mode_revision=5,drain_sealed=0,drain_cutoff=?,drain_deadline=?",cutoff,cutoff+60000);
            jdbc.update("UPDATE core_deployment_registration SET status=?",deploymentStatus);
            Map<String,Object> op=row("drain_operation_id",uuid(),"operation_scope",scope,"tenant_id","1","module_id","agent-command","operator_id","operator","audit_reason","test-only drain","source_tenant_operator_id",tenant?"operator":null,"source_tenant_idempotency_key",tenant?"idempotency":null,"tenant_state",tenant?"DRAINING":"ENABLED","tenant_control_revision",tenant?13L:12L,"tenant_session_epoch",6L,"tenant_admission_epoch",tenant?8L:7L,"tenant_drain_epoch",tenant?3L:2L,"module_mode_state",module?"QUIESCING":"DOMAIN","module_mode_revision",module?5L:4L,"module_migration_epoch",3L,"module_schema_version",2L,"module_mapping_digest","a".repeat(64),"tenant_cutoff_at",tenant?cutoff:null,"tenant_deadline_at",tenant?cutoff+60000:null,"module_cutoff_at",module?cutoff:null,"module_deadline_at",module?cutoff+60000:null,"cutoff_at",cutoff,"deadline_at",cutoff+60000,"issued_at",clock.value,"status","OPEN","sealed_at",null,"revoked_at",null,"revoke_reason",null,"version",0L,"update_time",clock.value);op.put("operation_snapshot_sha256",AgentRuntimeV2RegistrationDeltaHashes.operation(op));insert(OPERATION,op);
            var f=jdbc.queryForMap("SELECT * FROM "+FACT+" WHERE binding_generation_fact_id=?",receipt.bindingGenerationFactId());
            Map<String,Object> grant=row("drain_operation_id",op.get("drain_operation_id"),"runtime_binding_id",receipt.runtimeBindingId(),"tenant_id","1","module_id","agent-command","registration_subject_sha256",f.get("registration_subject_sha256"),"registration_authority_id",AUTH,"runtime_instance_id",INSTANCE,"process_public_key_sha256",receipt.processPublicKeySha256(),"claimant_deployment_id","local-api-a","claimant_worker_generation",4L,"frozen_connected_fact_id",receipt.bindingGenerationFactId(),"frozen_connected_fact_sha256",f.get("generation_snapshot_sha256"),"frozen_registration_receipt_id",receipt.registrationReceiptId(),"frozen_registration_receipt_sha256",sha256(AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt)),"operation_snapshot_sha256",op.get("operation_snapshot_sha256"),"issued_at",clock.value,"status","ACTIVE","revoked_at",null,"revoke_reason",null,"version",0L,"update_time",clock.value);grant.put("completion_snapshot_sha256",AgentRuntimeV2RegistrationDeltaHashes.completion(grant));insert(COMPLETION,grant);
        }
    }
    static String uuid(){return UUID.randomUUID().toString();}
    static String rejection(AgentRuntimeV2RegistrationService.Result result){return AgentRuntimeV2RegistrationCodec.decodeRejected(result.responseBytes()).reasonCode();}

    enum LatePoint {PROOF,SEAL,FINAL_WRITE,PRECOMMIT}
    /** Advances only after the real random/seal or successful last SQL; never feeds an early clock sample. */
    static void lateRollback(Fixture f,LatePoint point,String lastSql,long boundary,boolean verifierSeal,boolean cryptoExpected,Runnable invocation) {
        var before=f.snapshot();int proofs=f.proofs.calls,writes=f.jdbc.writeCalls;
        AtomicInteger seals=new AtomicInteger(),finalWrites=new AtomicInteger(),rollbacks=new AtomicInteger(),commits=new AtomicInteger(),crossings=new AtomicInteger();
        Runnable cross=()->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());f.clock.value=boundary;crossings.incrementAndGet();};
        if(point==LatePoint.PROOF)f.proofs.afterProof=cross;
        f.jdbc.afterWritePrefix=lastSql;f.jdbc.afterWrite=()->{
            finalWrites.incrementAndGet();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int status){if(status==STATUS_ROLLED_BACK)rollbacks.incrementAndGet();else commits.incrementAndGet();}});
            if(point==LatePoint.FINAL_WRITE)cross.run();
            if(point==LatePoint.PRECOMMIT)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){cross.run();}});
        };
        try(var crypto=mockStatic(AgentRuntimeV2RegistrationCrypto.class,CALLS_REAL_METHODS)) {
            if(verifierSeal)crypto.when(()->AgentRuntimeV2RegistrationCrypto.sealVerifier(any(),any(),any())).thenAnswer(call->{Object sealed=call.callRealMethod();seals.incrementAndGet();if(point==LatePoint.SEAL)cross.run();return sealed;});
            else crypto.when(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any())).thenAnswer(call->{Object sealed=call.callRealMethod();seals.incrementAndGet();if(point==LatePoint.SEAL)cross.run();return sealed;});
            crypto.clearInvocations();invocation.run();
            if(!cryptoExpected){crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealVerifier(any(),any(),any()),never());crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any()),never());}
        }
        assertEquals(1,crossings.get(),point.name());assertEquals(1,finalWrites.get(),"last DB write really completed");assertTrue(f.jdbc.writeCalls>writes);
        assertEquals(cryptoExpected?1:0,seals.get());assertEquals(proofs+(cryptoExpected?1:0),f.proofs.calls);
        assertEquals(1,rollbacks.get());assertEquals(0,commits.get());AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(f.dataSource));
        assertEquals(before,f.snapshot(),"all tables/rows/bytes/versions must survive rollback unchanged");
    }
    static void validLastMillisecond(Fixture f,String lastSql,long boundary,Runnable invocation) {
        AtomicInteger samples=new AtomicInteger(),commits=new AtomicInteger(),rollbacks=new AtomicInteger();
        f.jdbc.afterWritePrefix=lastSql;f.jdbc.afterWrite=()->TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
            @Override public int getOrder(){return Integer.MAX_VALUE-1;}
            @Override public void beforeCommit(boolean readOnly){f.clock.value=boundary-1;samples.incrementAndGet();}
            @Override public void afterCompletion(int status){if(status==STATUS_COMMITTED)commits.incrementAndGet();else rollbacks.incrementAndGet();}
        });
        invocation.run();assertEquals(1,samples.get());assertEquals(1,commits.get());assertEquals(0,rollbacks.get());AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(f.dataSource));
    }
    @Test void finalPrecommitLastValidMillisecondAllowsAtomicCreateIssueReconnectAndRenew() throws Exception {
        var create=new Fixture();long keyExpiry=create.clock.value+1000;create.jdbc.update("UPDATE oauth_api_key SET expire_time=?",keyExpiry);
        validLastMillisecond(create,"INSERT INTO "+REQUEST,keyExpiry,()->assertNotNull(create.create().currentSocket()));assertEquals(1,create.count(BINDING));assertEquals(1,create.count(FACT));assertEquals(1,create.count(RECEIPT));assertEquals(1,create.count(REQUEST));
        var issue=new Fixture();var firstIssue=issue.receipt(issue.create());long challengeExpiry=issue.clock.value+60000;byte[] challengeRaw=issue.challengeWire(firstIssue,uuid());
        validLastMillisecond(issue,"INSERT INTO "+DECISION,challengeExpiry,()->assertEquals(challengeExpiry,AgentRuntimeV2RegistrationCodec.decodeChallenge(issue.service.challenge(issue.identity,FENCE,OWNER,0,challengeRaw).responseBytes()).expiresAt()));assertEquals(1,issue.count(CHALLENGE));assertEquals(1,issue.count(DECISION));
        for(String boundary:List.of("API_KEY","CHALLENGE","TENANT_DRAIN","MODULE_QUIESCE","COMBINED")) {
            var f=new Fixture();var first=f.receipt(f.create());long expiry;
            if(boundary.equals("API_KEY")){expiry=f.clock.value+2000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);}
            else if(boundary.equals("CHALLENGE")){expiry=f.clock.value+60000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry+120000);}
            else {f.drain(first,boundary,"ACTIVE");expiry=f.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry+120000);}
            var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());f.clock.value=expiry-1000;byte[] raw=f.commitWire(first,c,uuid());
            validLastMillisecond(f,"UPDATE "+BINDING,expiry,()->assertNotNull(f.service.reconnect(f.identity,FENCE,OWNER,0,raw).currentSocket()));assertEquals("CONSUMED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertEquals(3,f.count(FACT));assertEquals(2,f.count(RECEIPT));assertEquals(2,f.count(REQUEST));assertEquals(1,f.jdbc.queryForObject("SELECT version FROM "+BINDING,Long.class));
        }
        for(String boundary:List.of("API_KEY","LEASE","TENANT_DRAIN","MODULE_QUIESCE","COMBINED")) {
            var f=new Fixture();var created=f.create();long expiry;
            if(boundary.equals("API_KEY")){f.clock.value+=9000;expiry=f.clock.value+1000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);}
            else if(boundary.equals("LEASE")){expiry=f.jdbc.queryForObject("SELECT socket_owner_lease_until FROM "+BINDING,Long.class);f.clock.value+=9000;}
            else {var first=f.receipt(created);f.drain(first,boundary,"DRAINING");var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());expiry=f.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);f.clock.value=expiry-1000;created=f.service.reconnect(f.identity,FENCE,OWNER,0,f.commitWire(first,c,uuid()));f.clock.value+=100;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry+120000);}
            var socket=created.currentSocket();var historical=f.snapshot();long version=f.jdbc.queryForObject("SELECT version FROM "+BINDING,Long.class);
            validLastMillisecond(f,"UPDATE "+BINDING,expiry,()->assertTrue(f.service.renew(f.identity,socket)));assertEquals(version+1,f.jdbc.queryForObject("SELECT version FROM "+BINDING,Long.class));var after=f.snapshot();historical.remove(BINDING);after.remove(BINDING);assertEquals(historical,after);
        }
    }

    @Test void createFinalPrecommitRejectsKeyExpiryAfterProofSealAndLastWrite() throws Exception {
        for(LatePoint point:LatePoint.values()) {
            var f=new Fixture();long expiry=f.clock.value+1000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);byte[] raw=f.createWire(uuid());
            lateRollback(f,point,"INSERT INTO "+REQUEST,expiry,true,true,()->assertEquals("NOT_AUTHORIZED",rejection(f.service.create(f.identity,FENCE,OWNER,0,raw))));
            assertEquals(0,f.count(BINDING));assertEquals(0,f.count(FACT));assertEquals(0,f.count(RECEIPT));assertEquals(0,f.count(REQUEST));
        }
    }
    @Test void reconnectFinalPrecommitRejectsOriginalChallengeOrKeyExpiryAndRestoresEveryByte() throws Exception {
        for(String boundary:List.of("CHALLENGE","API_KEY"))for(LatePoint point:LatePoint.values()) {
            var f=new Fixture();var first=f.receipt(f.create());long keyExpiry=f.clock.value+(boundary.equals("API_KEY")?1000:120000);f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",keyExpiry);
            var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());
            long expiry=boundary.equals("CHALLENGE")?c.expiresAt():keyExpiry;f.clock.value=expiry-1;
            assertTrue(f.clock.value+30000>expiry);assertTrue(boundary.equals("API_KEY")?c.expiresAt()>expiry:keyExpiry>expiry);
            byte[] raw=f.commitWire(first,c,uuid());lateRollback(f,point,"UPDATE "+BINDING,expiry,true,true,()->assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,raw))));
            assertEquals("ISSUED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));assertEquals(1,f.count(REQUEST));
        }
    }
    @Test void frozenEveryScopeFinalPrecommitDeadlineRejectsWithIndependentValidKeyChallengeAndLease() throws Exception {
        for(String scope:List.of("TENANT_DRAIN","MODULE_QUIESCE","COMBINED"))for(LatePoint point:LatePoint.values()) {
            var f=new Fixture();var first=f.receipt(f.create());f.drain(first,scope,"DRAINING");long deadline=f.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);
            f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",deadline+120000);var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());
            assertTrue(c.expiresAt()>deadline);f.clock.value=deadline-1;assertTrue(f.clock.value+30000>deadline);byte[] raw=f.commitWire(first,c,uuid());
            lateRollback(f,point,"UPDATE "+BINDING,deadline,true,true,()->assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,raw))));
        }
    }
    @Test void challengeIssuanceFinalPrecommitRejectsKeyTtlAndAllFrozenDeadlines() throws Exception {
        for(String boundary:List.of("API_KEY","CHALLENGE","TENANT_DRAIN","MODULE_QUIESCE","COMBINED"))for(LatePoint point:LatePoint.values()) {
            var f=new Fixture();var first=f.receipt(f.create());long expiry;
            if(boundary.equals("API_KEY")){expiry=f.clock.value+1000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);}
            else if(boundary.equals("CHALLENGE"))expiry=f.clock.value+60000;
            else {f.drain(first,boundary,"ACTIVE");expiry=f.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);assertTrue(f.clock.value+60000>expiry);f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry+120000);}
            byte[] raw=f.challengeWire(first,uuid());lateRollback(f,point,"INSERT INTO "+DECISION,expiry,false,true,()->assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,raw))));
            assertEquals(0,f.count(CHALLENGE));assertEquals(0,f.count(DECISION));
        }
    }
    @Test void renewFinalPrecommitRejectsKeyCurrentLeaseAndEveryFrozenDeadlineWithoutHistoricalMutation() throws Exception {
        for(String boundary:List.of("API_KEY","LEASE","TENANT_DRAIN","MODULE_QUIESCE","COMBINED"))for(LatePoint point:List.of(LatePoint.FINAL_WRITE,LatePoint.PRECOMMIT)) {
            var f=new Fixture();var created=f.create();long expiry;
            if(boundary.equals("API_KEY")){f.clock.value+=9000;expiry=f.clock.value+1000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);}
            else if(boundary.equals("LEASE")){expiry=f.jdbc.queryForObject("SELECT socket_owner_lease_until FROM "+BINDING,Long.class);f.clock.value=expiry-1;}
            else {f.drain(f.receipt(created),boundary,"ACTIVE");var first=f.receipt(created);var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());expiry=f.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);f.clock.value=expiry-1000;created=f.service.reconnect(f.identity,FENCE,OWNER,0,f.commitWire(first,c,uuid()));f.clock.value+=100;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry+120000);}
            var socket=created.currentSocket();lateRollback(f,point,"UPDATE "+BINDING,expiry,false,false,()->assertFalse(f.service.renew(f.identity,socket)));
        }
    }
    @Test void firstQualifiedDeniedFinalIdentityExpiryRollsBackWithoutAnyCrypto() throws Exception {
        for(LatePoint point:List.of(LatePoint.FINAL_WRITE,LatePoint.PRECOMMIT)) {
            var f=new Fixture();var first=f.receipt(f.create());long expiry=f.clock.value+1000;f.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);f.jdbc.update("UPDATE core_tenant_control SET state='DISABLED'");byte[] raw=f.challengeWire(first,uuid());
            lateRollback(f,point,"INSERT INTO "+DECISION,expiry,false,false,()->assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,raw))));assertEquals(0,f.count(DECISION));
        }
    }
    @Test void advancementFinalPrecommitRejectsNewLeaseEqualityAndClockBeforeAdvancement() throws Exception {
        for(String kind:List.of("CREATE","RECONNECT"))for(String boundary:List.of("LEASE","BACKWARD"))for(LatePoint point:LatePoint.values()) {
            var f=new Fixture();byte[] raw;
            if(kind.equals("CREATE"))raw=f.createWire(uuid());
            else {var first=f.receipt(f.create());var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());raw=f.commitWire(first,c,uuid());assertTrue(c.expiresAt()>f.clock.value+30000);}
            long end=f.clock.value+(boundary.equals("LEASE")?30000:-1);
            lateRollback(f,point,kind.equals("CREATE")?"INSERT INTO "+REQUEST:"UPDATE "+BINDING,end,true,true,()->assertEquals("NOT_AUTHORIZED",rejection(kind.equals("CREATE")?f.service.create(f.identity,FENCE,OWNER,0,raw):f.service.reconnect(f.identity,FENCE,OWNER,0,raw))));
        }
    }

    @Test void eligibleUniqueAndLockFailuresRetryOnlyAfterRollbackAndResourceCleanup() throws Exception {
        List<java.util.function.Supplier<RuntimeException>> categories=List.of(()->new DuplicateKeyException("unique"),()->new CannotAcquireLockException("lock"),()->new PessimisticLockingFailureException("deadlock"));
        for(var category:categories)for(int failures:List.of(1,2)) {
            var f=new Fixture();for(int i=0;i<failures;i++)f.jdbc.mappingFailures.add(category.get());f.dataSource.observeRetry=true;
            var result=f.create();f.dataSource.observeRetry=false;assertNotNull(result.currentSocket());
            assertEquals(failures+1,f.dataSource.retryEntries);assertEquals(failures,f.jdbc.rollbacks);assertEquals(1,f.jdbc.commits);assertTrue(f.jdbc.mappingFailures.isEmpty());
            List<String> expected=new ArrayList<>();for(int i=0;i<failures;i++){expected.add("entry");expected.add("rollback");}expected.add("entry");expected.add("commit");assertEquals(expected,f.dataSource.transactionEvents);
            AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(f.dataSource));
            assertEquals(1,f.count(BINDING));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));assertEquals(1,f.count(REQUEST));assertEquals(0,f.count(CHALLENGE));assertEquals(0,f.count(DECISION));
        }
    }
    @Test void eligibleUniqueAndLockFailureExhaustionIsThreeAttemptsAndZeroPersistedEffects() throws Exception {
        List<java.util.function.Supplier<RuntimeException>> categories=List.of(()->new DuplicateKeyException("unique"),()->new CannotAcquireLockException("lock"),()->new PessimisticLockingFailureException("deadlock"));
        for(var category:categories) {
            var f=new Fixture();var before=f.snapshot();RuntimeException last=null;for(int i=0;i<4;i++){var failure=category.get();f.jdbc.mappingFailures.add(failure);if(i==2)last=failure;}
            f.dataSource.observeRetry=true;RuntimeException thrown=assertThrows(last.getClass(),f::create);f.dataSource.observeRetry=false;assertSame(last,thrown);
            assertEquals(3,f.dataSource.retryEntries);assertEquals(3,f.jdbc.rollbacks);assertEquals(0,f.jdbc.commits);assertEquals(1,f.jdbc.mappingFailures.size());assertEquals(List.of("entry","rollback","entry","rollback","entry","rollback"),f.dataSource.transactionEvents);
            AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(f.dataSource));assertEquals(before,f.snapshot());
        }
    }
    @Test void securityAndNonRecoverableFailuresAreNotRetried() throws Exception {
        for(RuntimeException failure:List.of(new SecurityException("security"),new IllegalStateException("nonrecoverable"))) {
            var f=new Fixture();var before=f.snapshot();f.jdbc.mappingFailures.add(failure);f.jdbc.mappingFailures.add(new DuplicateKeyException("must remain unattempted"));f.dataSource.observeRetry=true;
            if(failure instanceof SecurityException)assertEquals("NOT_AUTHORIZED",rejection(f.create()));else assertSame(failure,assertThrows(IllegalStateException.class,f::create));
            f.dataSource.observeRetry=false;assertEquals(1,f.dataSource.retryEntries);assertEquals(1,f.jdbc.rollbacks);assertEquals(0,f.jdbc.commits);assertEquals(1,f.jdbc.mappingFailures.size());assertEquals(List.of("entry","rollback"),f.dataSource.transactionEvents);AgentRuntimeV2RegistrationServiceImpl.outside();assertFalse(TransactionSynchronizationManager.hasResource(f.dataSource));assertEquals(before,f.snapshot());
        }
    }

    @Test void createAtomicLostResponseReplayDifferentIdAndIdentityKindsZeroWrite() throws Exception {
        var f=new Fixture();String id=uuid();byte[] raw=f.createWire(id);var original=f.service.create(f.identity,FENCE,OWNER,0,raw);var r=f.receipt(original);assertEquals(0,r.bindingGeneration());assertArrayEquals(sha256(f.verifier(r)),r.verifierSha256());
        int writes=f.jdbc.writeCalls;var retry=f.service.create(f.identity,FENCE,OWNER,0,raw);assertArrayEquals(AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(original.responseBytes()).receiptBytes(),AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(retry.responseBytes()).receiptBytes());assertEquals(writes,f.jdbc.writeCalls);assertEquals(1,f.count(BINDING));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));assertEquals(1,f.count(REQUEST));
        assertEquals("RECONCILIATION_REQUIRED",rejection(f.create()));
        var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());byte[] commit=f.commitWire(r,challenge,uuid());writes=f.jdbc.writeCalls;
        int connections=f.dataSource.connections;for(String kind:List.of("RUNTIME_V1_INSTALLATION","DEPLOYMENT_CREDENTIAL","JWT","USER","TOKEN","API_TOKEN","ANONYMOUS")){var identity=new AgentRuntimeV2RegistrationContext(kind,"api-key-1","agent-a","test-key",new byte[32]);assertEquals("NOT_AUTHORIZED",rejection(f.service.create(identity,FENCE,OWNER,0,f.createWire(uuid()))));assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(identity,FENCE,OWNER,0,f.challengeWire(r,uuid()))));assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(identity,FENCE,OWNER,0,commit)));assertFalse(f.service.renew(identity,original.currentSocket()));assertEquals(connections,f.dataSource.connections);}
        assertEquals(writes,f.jdbc.writeCalls);
    }
    @Test void originalProofKeyExpiryAccountAgentAndAuthorityChangesDenyBeforeAllWrites() throws Exception {
        for(String mutation:List.of("UPDATE oauth_api_key SET api_key='different-secret'","UPDATE oauth_api_key SET status=0","UPDATE oauth_api_key SET expire_time=1","UPDATE user_info SET account_state='DISABLED'","UPDATE agent_identity_registry SET lifecycle_status='REVOKED'","UPDATE agent_persona_binding SET status=0","UPDATE agent_runtime_v2_registration_authority SET status='REVOKED'")){
            var f=new Fixture();var r=f.receipt(f.create());f.jdbc.update(mutation);int writes=f.jdbc.writeCalls;assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid()))));assertEquals(writes,f.jdbc.writeCalls);assertEquals(0,f.count(DECISION));assertEquals(0,f.count(CHALLENGE));
        }
    }
    @Test void challengeReplayMatrixHasNoReissueExpiryMutationOrNewCryptoRows() throws Exception {
        var f=new Fixture();var r=f.receipt(f.create());byte[] request=f.challengeWire(r,uuid());var issued=f.service.challenge(f.identity,FENCE,OWNER,0,request);var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(issued.responseBytes());int writes=f.jdbc.writeCalls;
        assertArrayEquals(issued.responseBytes(),f.service.challenge(f.identity,FENCE,OWNER,0,request).responseBytes());assertEquals(writes,f.jdbc.writeCalls);
        assertEquals("REQUEST_CONFLICT",rejection(f.service.challenge(f.identity,uuid(),OWNER,0,request)));assertEquals(writes,f.jdbc.writeCalls);
        f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET authority_revision=6");assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,request)));assertEquals(writes,f.jdbc.writeCalls);
        f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET authority_revision=5");f.clock.value=challenge.expiresAt();assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,request)));assertEquals(writes,f.jdbc.writeCalls);assertEquals(1,f.count(CHALLENGE));assertEquals(challenge.expiresAt(),f.jdbc.queryForObject("SELECT expires_at FROM "+CHALLENGE,Long.class));
    }
    @Test void firstQualifiedDeniedIsPersistentNoCryptoAndRemainsDeniedAfterAuthorityRecovery() throws Exception {
        var f=new Fixture();var r=f.receipt(f.create());f.jdbc.update("UPDATE core_deployment_tenant_grant SET status='REVOKED'");byte[] raw=f.challengeWire(r,uuid());var denial=f.service.challenge(f.identity,FENCE,OWNER,0,raw);assertEquals("NOT_AUTHORIZED",rejection(denial));assertEquals(0,f.count(CHALLENGE));assertEquals(1,f.count(DECISION));int writes=f.jdbc.writeCalls;
        int proofs=f.proofs.calls;try(var crypto=mockStatic(AgentRuntimeV2RegistrationCrypto.class,CALLS_REAL_METHODS)){f.jdbc.update("UPDATE core_deployment_tenant_grant SET status='ACTIVE'");assertArrayEquals(denial.responseBytes(),f.service.challenge(f.identity,FENCE,OWNER,0,raw).responseBytes());assertEquals(writes,f.jdbc.writeCalls);assertEquals("REQUEST_CONFLICT",rejection(f.service.challenge(f.identity,uuid(),OWNER,0,raw)));var decoded=AgentRuntimeV2RegistrationCodec.decodeChallengeRequest(raw);var changed=new AgentRuntimeV2RegistrationCodec.ChallengeRequest(decoded.challengeRequestId(),uuid(),decoded.priorRegistrationReceiptId(),decoded.priorRegistrationReceiptSha256(),decoded.runtimeInstanceId());assertEquals("REQUEST_CONFLICT",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(changed))));assertEquals(writes,f.jdbc.writeCalls);assertEquals(proofs,f.proofs.calls);crypto.verifyNoInteractions();}
        var request=new AgentRuntimeV2RegistrationCodec.ChallengeRequest(uuid(),uuid(),uuid(),new byte[32],INSTANCE);assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(request))));assertEquals(2,f.count(DECISION));assertEquals(1,f.count(BINDING));assertEquals(0,f.count(CHALLENGE));
    }
    @Test void everyChallengeReplayCellHasZeroProofNonceEphemeralInsertAndExpiryMutation() throws Exception {
        var f=new Fixture();var receipt=f.receipt(f.create());String id=uuid();byte[] raw=f.challengeWire(receipt,id);
        // Observe the actual production seal boundary: one successful seal produces exactly one
        // nonce; the real X25519 generator is separately spied (no fake key, nonce, or ciphertext).
        var generator=spy(KeyPairGenerator.getInstance("X25519"));var nonceProducts=new AtomicInteger();
        try(var generators=mockStatic(KeyPairGenerator.class,CALLS_REAL_METHODS);var crypto=mockStatic(AgentRuntimeV2RegistrationCrypto.class,CALLS_REAL_METHODS)) {
            generators.when(()->KeyPairGenerator.getInstance("X25519")).thenReturn(generator);
            crypto.when(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any())).thenAnswer(call->{var sealed=(AgentRuntimeV2RegistrationCrypto.Sealed)call.callRealMethod();assertEquals(12,sealed.nonce().length);nonceProducts.incrementAndGet();return sealed;});
            crypto.clearInvocations(); // CALLS_REAL_METHODS records the stubbing probe, not a production seal.
            var issued=f.service.challenge(f.identity,FENCE,OWNER,0,raw);var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(issued.responseBytes());int writes=f.jdbc.writeCalls,proofs=f.proofs.calls;
            assertArrayEquals(issued.responseBytes(),f.service.challenge(f.identity,FENCE,OWNER,0,raw).responseBytes());
            var changed=new AgentRuntimeV2RegistrationCodec.ChallengeRequest(id,receipt.runtimeBindingId(),receipt.registrationReceiptId(),sha256(AgentRuntimeV2RegistrationCodec.encodeReceipt(receipt)),uuid());
            assertEquals("REQUEST_CONFLICT",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,AgentRuntimeV2RegistrationCodec.encodeChallengeRequest(changed))));
            for(var tuple:List.of(new AgentRuntimeV2SocketTuple(receipt.runtimeBindingId(),0,INSTANCE,uuid(),OWNER,0),new AgentRuntimeV2SocketTuple(receipt.runtimeBindingId(),0,INSTANCE,FENCE,uuid(),0),new AgentRuntimeV2SocketTuple(receipt.runtimeBindingId(),0,INSTANCE,FENCE,OWNER,1)))assertEquals("REQUEST_CONFLICT",rejection(f.service.challenge(f.identity,tuple.sessionFence(),tuple.socketOwnerInstanceId(),tuple.socketOwnerEpoch(),raw)));
            var key=new LinkedHashMap<>(f.jdbc.queryForMap("SELECT * FROM oauth_api_key"));key.put("id","api-key-2");f.insert("oauth_api_key",key);var authority=new LinkedHashMap<>(f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_authority"));authority.put("registration_authority_id",uuid());authority.put("credential_reference","api-key-2");authority.put("credential_digest",apiKeyCredential(f.keys.active(),"api-key-2",SECRET));f.insert("agent_runtime_v2_registration_authority",authority);
            assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.resolver.proof("api-key-2","agent-a",SECRET),FENCE,OWNER,0,raw)));
            f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET authority_revision=6 WHERE credential_reference='api-key-1'");assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,raw)));f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET authority_revision=5 WHERE credential_reference='api-key-1'");
            f.jdbc.update("UPDATE "+BINDING+" SET status='REVOKED'");assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,raw)));f.jdbc.update("UPDATE "+BINDING+" SET status='ACTIVE'");
            f.clock.value=challenge.expiresAt();assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,raw)));
            assertEquals(writes,f.jdbc.writeCalls);assertEquals(proofs,f.proofs.calls);assertEquals(1,nonceProducts.get());verify(generator,times(1)).generateKeyPair();crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any()),times(1));assertEquals(1,f.count(CHALLENGE));assertEquals(challenge.expiresAt(),f.jdbc.queryForObject("SELECT expires_at FROM "+CHALLENGE,Long.class));
            var fresh=f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(receipt,uuid()));assertEquals(f.clock.value+60000,AgentRuntimeV2RegistrationCodec.decodeChallenge(fresh.responseBytes()).expiresAt());assertEquals(2,nonceProducts.get());verify(generator,times(2)).generateKeyPair();
        }
        var consumed=new Fixture();var r=consumed.receipt(consumed.create());byte[] challengeRaw=consumed.challengeWire(r,uuid());var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(consumed.service.challenge(consumed.identity,FENCE,OWNER,0,challengeRaw).responseBytes());consumed.service.reconnect(consumed.identity,FENCE,OWNER,0,consumed.commitWire(r,c,uuid()));int writes=consumed.jdbc.writeCalls,proofs=consumed.proofs.calls;
        try(var crypto=mockStatic(AgentRuntimeV2RegistrationCrypto.class,CALLS_REAL_METHODS)){assertEquals("NOT_AUTHORIZED",rejection(consumed.service.challenge(consumed.identity,FENCE,OWNER,0,challengeRaw)));crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any()),never());assertEquals(writes,consumed.jdbc.writeCalls);assertEquals(proofs,consumed.proofs.calls);assertEquals(1,consumed.count(CHALLENGE));assertEquals(c.expiresAt(),consumed.jdbc.queryForObject("SELECT expires_at FROM "+CHALLENGE,Long.class));}
    }
    @Test void reconnectRejectsWrongSecondFactorEveryProvisionalDimensionAndFreshExpiryWithoutConsumption() throws Exception {
        var f=new Fixture();var r=f.receipt(f.create());var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());byte[] raw=f.commitWire(r,c,uuid());var request=AgentRuntimeV2RegistrationCodec.decodeCommit(raw);int writes=f.jdbc.writeCalls,proofs=f.proofs.calls;
        var wrong=new AgentRuntimeV2RegistrationCodec.Commit(uuid(),request.challengeId(),request.runtimeBindingId(),request.priorRegistrationReceiptId(),request.priorRegistrationReceiptSha256(),INSTANCE,request.verifier(),new byte[32]);assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,AgentRuntimeV2RegistrationCodec.encodeCommit(wrong))));
        for(var tuple:List.of(new AgentRuntimeV2SocketTuple(r.runtimeBindingId(),0,INSTANCE,uuid(),OWNER,0),new AgentRuntimeV2SocketTuple(r.runtimeBindingId(),0,INSTANCE,FENCE,uuid(),0),new AgentRuntimeV2SocketTuple(r.runtimeBindingId(),0,INSTANCE,FENCE,OWNER,1)))assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,tuple.sessionFence(),tuple.socketOwnerInstanceId(),tuple.socketOwnerEpoch(),raw)));
        f.clock.samples.add(c.expiresAt()-1);f.clock.samples.add(c.expiresAt());assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,raw)));assertTrue(f.clock.samples.isEmpty());
        f.clock.samples.add(f.clock.value-1);assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,raw)));assertEquals(writes,f.jdbc.writeCalls);assertEquals(proofs,f.proofs.calls);assertEquals("ISSUED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));assertEquals(1,f.count(REQUEST));
    }
    @Test void freshCommitSampleAlsoRejectsDrainDeadlineAndCredentialExpiryCrossing() throws Exception {
        var drain=new Fixture();var r=drain.receipt(drain.create());drain.drain(r,"COMBINED","DRAINING");var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(drain.service.challenge(drain.identity,FENCE,OWNER,0,drain.challengeWire(r,uuid())).responseBytes());byte[] raw=drain.commitWire(r,c,uuid());long deadline=drain.jdbc.queryForObject("SELECT deadline_at FROM "+OPERATION,Long.class);assertTrue(c.expiresAt()>deadline);int writes=drain.jdbc.writeCalls;drain.clock.samples.add(drain.clock.value);drain.clock.samples.add(deadline-1);drain.clock.samples.add(deadline);assertEquals("NOT_AUTHORIZED",rejection(drain.service.reconnect(drain.identity,FENCE,OWNER,0,raw)));assertTrue(drain.clock.samples.isEmpty());assertEquals(writes,drain.jdbc.writeCalls);assertEquals("ISSUED",drain.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));
        var key=new Fixture();var original=key.receipt(key.create());long expiry=key.clock.value+5000;key.jdbc.update("UPDATE oauth_api_key SET expire_time=?",expiry);var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(key.service.challenge(key.identity,FENCE,OWNER,0,key.challengeWire(original,uuid())).responseBytes());byte[] commit=key.commitWire(original,challenge,uuid());writes=key.jdbc.writeCalls;key.clock.samples.add(key.clock.value);key.clock.samples.add(expiry-1);key.clock.samples.add(expiry);assertEquals("NOT_AUTHORIZED",rejection(key.service.reconnect(key.identity,FENCE,OWNER,0,commit)));assertTrue(key.clock.samples.isEmpty());assertEquals(writes,key.jdbc.writeCalls);assertEquals("ISSUED",key.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertEquals(1,key.count(FACT));assertEquals(1,key.count(RECEIPT));
    }
    @Test void reconnectTwoFactorsFreshTimeRollbackAndWinnerAtomicity() throws Exception {
        var f=new Fixture();var first=f.create();var r=f.receipt(first);var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());byte[] raw=f.commitWire(r,c,uuid());
        var commit=AgentRuntimeV2RegistrationCodec.decodeCommit(raw);var wrong=new AgentRuntimeV2RegistrationCodec.Commit(uuid(),commit.challengeId(),commit.runtimeBindingId(),commit.priorRegistrationReceiptId(),commit.priorRegistrationReceiptSha256(),INSTANCE,new byte[32],commit.challenge());int writes=f.jdbc.writeCalls;assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,0,AgentRuntimeV2RegistrationCodec.encodeCommit(wrong))));assertEquals(writes,f.jdbc.writeCalls);
        f.jdbc.failProjection=true;assertThrows(IllegalStateException.class,()->f.service.reconnect(f.identity,FENCE,OWNER,0,raw));f.jdbc.failProjection=false;assertEquals("ISSUED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));assertEquals(1,f.count(REQUEST));
        var result=f.service.reconnect(f.identity,FENCE,OWNER,0,raw);assertEquals(first.currentSocket(),result.replacedSocket());assertEquals(1,f.receipt(result).bindingGeneration());assertEquals(3,f.count(FACT));assertEquals(2,f.count(RECEIPT));assertEquals(2,f.count(REQUEST));assertEquals("CONSUMED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));assertFalse(f.service.disconnect(first.currentSocket(),"SOCKET_CLOSED"));
    }
    @Test void concurrentDifferentReconnectIdsHaveExactlyOneWinner() throws Exception {
        var f=new Fixture();var r=f.receipt(f.create());var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());var barrier=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {List<Future<AgentRuntimeV2RegistrationService.Result>> results=new ArrayList<>();for(int i=0;i<2;i++){byte[] raw=f.commitWire(r,c,uuid());results.add(pool.submit(()->{barrier.await();return f.service.reconnect(f.identity,FENCE,OWNER,0,raw);}));}barrier.countDown();int winners=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS).currentSocket()!=null)winners++;assertEquals(1,winners);assertEquals(3,f.count(FACT));assertEquals(2,f.count(RECEIPT));assertEquals(2,f.count(REQUEST));}finally{pool.shutdownNow();}
    }
    @Test void grantedReplayUsesCommitFingerprintEvenAfterCurrentAdmissionLossAndRev11To12() throws Exception {
        var f=new Fixture();byte[] raw=f.createWire(uuid());var original=f.service.create(f.identity,FENCE,OWNER,0,raw);byte[] receipt=AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(original.responseBytes()).receiptBytes();int writes=f.jdbc.writeCalls;
        f.jdbc.update("UPDATE core_deployment_registration SET registration_revision=12");f.jdbc.update("UPDATE core_deployment_module_admission SET registration_revision=12");assertArrayEquals(receipt,AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(f.service.create(f.identity,FENCE,OWNER,0,raw).responseBytes()).receiptBytes());
        f.jdbc.update("UPDATE core_tenant_control SET state='DISABLED',admission_epoch=9");var replay=f.service.create(f.identity,uuid(),uuid(),1,raw);var delivery=AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(replay.responseBytes());assertArrayEquals(receipt,delivery.receiptBytes());assertEquals("RECONNECT_REQUIRED",delivery.connectionDisposition());assertNull(replay.currentSocket());assertEquals(writes,f.jdbc.writeCalls);
        f.jdbc.update("UPDATE "+BINDING+" SET status='REVOKED'");assertEquals("NOT_AUTHORIZED",rejection(f.service.create(f.identity,FENCE,OWNER,0,raw)));assertEquals(writes,f.jdbc.writeCalls);
    }
    @Test void reconnectGrantedLostResponseReplayAfterConsumedExpiryRev11To12AndAdmissionLossIsZeroMutation() throws Exception {
        var f=new Fixture();var first=f.receipt(f.create());var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(first,uuid())).responseBytes());byte[] raw=f.commitWire(first,challenge,uuid());var committed=f.service.reconnect(f.identity,FENCE,OWNER,0,raw);byte[] original=AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(committed.responseBytes()).receiptBytes();var mapping=f.jdbc.queryForMap("SELECT * FROM "+REQUEST+" WHERE request_kind='RECONNECT'");byte[] fingerprint=(byte[])mapping.get("registration_authority_sha256");var binding=f.jdbc.queryForMap("SELECT * FROM "+BINDING);byte[] verifier=(byte[])binding.get("reconnect_verifier");int writes=f.jdbc.writeCalls,proofs=f.proofs.calls;
        f.clock.value+=1000;f.jdbc.update("UPDATE core_deployment_registration SET registration_revision=12");f.jdbc.update("UPDATE core_deployment_module_admission SET registration_revision=12");
        try(var crypto=mockStatic(AgentRuntimeV2RegistrationCrypto.class,CALLS_REAL_METHODS)) {
            var exact=f.service.reconnect(f.identity,FENCE,OWNER,committed.currentSocket().socketOwnerEpoch(),raw);assertArrayEquals(original,AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(exact.responseBytes()).receiptBytes());assertEquals(committed.currentSocket(),exact.currentSocket());
            f.clock.value=challenge.expiresAt();f.jdbc.update("UPDATE core_tenant_control SET state='DISABLED',admission_epoch=9");f.jdbc.update("UPDATE core_deployment_tenant_grant SET status='REVOKED'");var another=f.service.reconnect(f.identity,uuid(),uuid(),0,raw);var response=AgentRuntimeV2RegistrationCodec.decodeReceiptDelivery(another.responseBytes());assertArrayEquals(original,response.receiptBytes());assertEquals("RECONNECT_REQUIRED",response.connectionDisposition());assertNull(another.currentSocket());assertNull(another.replacedSocket());
            assertEquals(writes,f.jdbc.writeCalls);assertEquals(proofs,f.proofs.calls);assertEquals(3,f.count(FACT));assertEquals(2,f.count(RECEIPT));assertEquals(2,f.count(REQUEST));assertArrayEquals(verifier,f.jdbc.queryForObject("SELECT reconnect_verifier FROM "+BINDING,byte[].class));assertArrayEquals(fingerprint,f.jdbc.queryForObject("SELECT registration_authority_sha256 FROM "+REQUEST+" WHERE request_kind='RECONNECT'",byte[].class));assertEquals("CONSUMED",f.jdbc.queryForObject("SELECT status FROM "+CHALLENGE,String.class));crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealVerifier(any(),any(),any()),never());crypto.verify(()->AgentRuntimeV2RegistrationCrypto.sealChallenge(any(),any(),any()),never());
            f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET status='REVOKED'");assertEquals("NOT_AUTHORIZED",rejection(f.service.reconnect(f.identity,FENCE,OWNER,1,raw)));assertEquals(writes,f.jdbc.writeCalls);
        }
    }
    @Test void allSixSocketDimensionsAreFencedAndProjectionCasChecksVersionAndLease() throws Exception {
        var f=new Fixture();var created=f.create();var s=created.currentSocket();int writes=f.jdbc.writeCalls;
        for(var stale:List.of(new AgentRuntimeV2SocketTuple(uuid(),s.bindingGeneration(),s.runtimeInstanceId(),s.sessionFence(),s.socketOwnerInstanceId(),s.socketOwnerEpoch()),new AgentRuntimeV2SocketTuple(s.runtimeBindingId(),1,s.runtimeInstanceId(),s.sessionFence(),s.socketOwnerInstanceId(),s.socketOwnerEpoch()),new AgentRuntimeV2SocketTuple(s.runtimeBindingId(),s.bindingGeneration(),uuid(),s.sessionFence(),s.socketOwnerInstanceId(),s.socketOwnerEpoch()),new AgentRuntimeV2SocketTuple(s.runtimeBindingId(),s.bindingGeneration(),s.runtimeInstanceId(),uuid(),s.socketOwnerInstanceId(),s.socketOwnerEpoch()),new AgentRuntimeV2SocketTuple(s.runtimeBindingId(),s.bindingGeneration(),s.runtimeInstanceId(),s.sessionFence(),uuid(),s.socketOwnerEpoch()),new AgentRuntimeV2SocketTuple(s.runtimeBindingId(),s.bindingGeneration(),s.runtimeInstanceId(),s.sessionFence(),s.socketOwnerInstanceId(),1))) {assertFalse(f.service.disconnect(stale,"SOCKET_CLOSED"));assertFalse(f.service.renew(f.identity,stale));assertEquals(writes,f.jdbc.writeCalls);}
        var tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource()));for(String field:List.of("version","socket_owner_lease_until"))assertThrows(IllegalStateException.class,()->tx.executeWithoutResult(status->{var before=f.db.lock(BINDING,row("runtime_binding_id",s.runtimeBindingId()));before.put(field,number(before,field)+1);f.db.bindingCas(before,row("status","DISCONNECTED"));}));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM "+BINDING,String.class));assertEquals(0,f.jdbc.queryForObject("SELECT version FROM "+BINDING,Long.class));assertEquals(1,f.count(FACT));
    }
    @Test void corruptedReceiptHolderAndEarlierChainCannotAuthorizeCurrentRegistration() throws Exception {
        for(String mutation:List.of("UPDATE "+RECEIPT+" SET receipt_wire_sha256=CAST(REPEAT('x',32) AS BINARY(32))","UPDATE "+FACT+" SET generation_snapshot_sha256=CAST(REPEAT('x',32) AS BINARY(32))","UPDATE "+FACT+" SET previous_fact_id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa' WHERE fact_sequence=0","UPDATE "+FACT+" SET fact_sequence=7 WHERE fact_sequence=0")) {
            var f=new Fixture();var r=f.receipt(f.create());f.jdbc.update(mutation);int writes=f.jdbc.writeCalls;assertFalse(f.service.renew(f.identity,new AgentRuntimeV2SocketTuple(r.runtimeBindingId(),0,INSTANCE,FENCE,OWNER,0)));assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid()))));assertEquals(writes+1,f.jdbc.writeCalls);assertEquals(0,f.count(CHALLENGE));
        }
    }
    @Test void frozenAllThreeScopesKeepRealOriginalLaneAndReceiptHashForActiveOrDrainingClaimant() throws Exception {
        for(String scope:List.of("TENANT_DRAIN","MODULE_QUIESCE","COMBINED"))for(String deployment:List.of("ACTIVE","DRAINING")){
            var f=new Fixture();var first=f.create();var r=f.receipt(first);byte[] original=AgentRuntimeV2RegistrationCodec.encodeReceipt(r);byte[] originalHash=(byte[])f.jdbc.queryForMap("SELECT * FROM "+FACT).get("generation_snapshot_sha256");f.drain(r,scope,deployment);f.jdbc.update("UPDATE agent_runtime_v2_registration_authority SET authority_revision=6");
            var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());var moved=f.service.reconnect(f.identity,FENCE,OWNER,0,f.commitWire(r,c,uuid()));var next=f.receipt(moved);assertEquals(6,next.registrationAuthorityRevision());assertEquals(7,next.admissionEpoch());assertEquals(3,next.migrationEpoch());assertEquals(11,next.receiverRegistrationRevision());assertArrayEquals(original,f.jdbc.queryForObject("SELECT receipt_wire_bytes FROM "+RECEIPT+" WHERE registration_receipt_id=?",byte[].class,r.registrationReceiptId()));assertArrayEquals(originalHash,f.jdbc.queryForObject("SELECT generation_snapshot_sha256 FROM "+FACT+" WHERE binding_generation_fact_id=?",byte[].class,r.bindingGenerationFactId()));assertEquals("FROZEN_DRAIN",f.jdbc.queryForObject("SELECT connection_admission_kind FROM "+FACT+" WHERE binding_generation_fact_id=?",String.class,next.bindingGenerationFactId()));
            AgentRuntimeV2RegistrationDeltaSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void drainSealRevokeExpiryWorkerControlAndFrozenHashChangesDeny() throws Exception {
        for(String mutation:List.of("UPDATE "+OPERATION+" SET status='SEALED'","UPDATE "+OPERATION+" SET status='REVOKED'","UPDATE "+COMPLETION+" SET status='REVOKED'","UPDATE core_tenant_module_control SET drain_sealed=1","UPDATE core_deployment_registration SET worker_generation=5","UPDATE core_tenant_control SET control_revision=99","UPDATE "+COMPLETION+" SET frozen_connected_fact_sha256=CAST(REPEAT('x',32) AS BINARY(32))")){
            var f=new Fixture();var r=f.receipt(f.create());f.drain(r,"COMBINED","ACTIVE");f.jdbc.update(mutation);assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid()))));assertEquals(0,f.count(CHALLENGE));
        }
        var f=new Fixture();var r=f.receipt(f.create());f.drain(r,"TENANT_DRAIN","ACTIVE");f.clock.value+=60000;assertEquals("NOT_AUTHORIZED",rejection(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid()))));
    }
    @Test void renewalCurrentProofLeaseSnapshotCloseReaperAndLateCallbacks() throws Exception {
        var f=new Fixture();var created=f.create();var r=f.receipt(created);byte[] hash=(byte[])f.jdbc.queryForMap("SELECT * FROM "+FACT).get("generation_snapshot_sha256");f.clock.value+=9000;assertTrue(f.service.renew(f.identity,created.currentSocket()));assertArrayEquals(hash,f.jdbc.queryForObject("SELECT generation_snapshot_sha256 FROM "+FACT,byte[].class));assertTrue(f.service.disconnect(created.currentSocket(),"SOCKET_CLOSED"));assertFalse(f.service.disconnect(created.currentSocket(),"SOCKET_CLOSED"));
        var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());var next=f.service.reconnect(f.identity,FENCE,OWNER,0,f.commitWire(r,c,uuid()));assertFalse(f.service.disconnect(created.currentSocket(),"SOCKET_CLOSED"));f.jdbc.update("UPDATE oauth_api_key SET api_key='changed'");assertFalse(f.service.renew(f.identity,next.currentSocket()));f.clock.value+=31000;assertEquals(1,f.service.reapExpired(100));assertEquals(0,f.service.reapExpired(100));assertEquals("DISCONNECTED",f.jdbc.queryForObject("SELECT status FROM "+BINDING,String.class));
    }
    @Test void lockingSuffixIsOrderedAndDuplicateRetryWaitsForRollbackAmbientTxRefused() throws Exception {
        var f=new Fixture();f.jdbc.failMappingOnce=true;var result=f.create();assertNotNull(result.currentSocket());assertEquals(1,f.jdbc.rollbacks);assertEquals(1,f.count(BINDING));assertEquals(1,f.count(FACT));assertEquals(1,f.count(RECEIPT));
        var r=f.receipt(result);f.jdbc.locks.clear();var c=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,FENCE,OWNER,0,f.challengeWire(r,uuid())).responseBytes());f.jdbc.locks.clear();f.service.reconnect(f.identity,FENCE,OWNER,0,f.commitWire(r,c,uuid()));
        List<String> suffix=f.jdbc.locks.stream().filter(sql->sql.contains("agent_runtime_v2_")).toList();int rank=-1;for(String sql:suffix){int current=sql.contains("registration_authority")?0:sql.contains("binding_generation_fact")?2:sql.contains("binding WHERE")?1:sql.contains("registration_receipt")?3:sql.contains("reconnect_challenge")?4:sql.contains("challenge_request_decision")?5:sql.contains("registration_request")?6:-1;assertTrue(current>=rank,sql);rank=current;}
        var tx=new TransactionTemplate(new DataSourceTransactionManager(f.jdbc.getDataSource()));tx.executeWithoutResult(status->assertThrows(IllegalStateException.class,()->f.create()));TransactionSynchronizationManager.initSynchronization();try{assertThrows(IllegalStateException.class,()->f.create());}finally{TransactionSynchronizationManager.clearSynchronization();}
    }
    // REGISTRATION_STORE_COLUMN_PATTERN_ORACLE_BEGIN: original expressions, not timing evidence.
    private static List<String> registrationColumnCases() {
        return Arrays.asList("a","z","a0","a_","created_at","tenant_id","a".repeat(256),
            "","A","aA","0a","_a","é","中文","aé","a b","a\t","a\n","\na","a\r",
            "\0","a\0","\u2028","a\u2028","\uD800","\uDC00","a\uD800","😀",null);
    }
    private static List<String> registrationColumnOutcome(java.lang.reflect.Method column,String value,boolean original) {
        try {
            if(original){if(!value.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("invalid column");}
            else column.invoke(null,value);
            return List.of("ACCEPTED");
        } catch(Exception failure) {
            Throwable cause=failure instanceof java.lang.reflect.InvocationTargetException wrapped?wrapped.getCause():failure;
            if(value==null) {
                assertEquals(NullPointerException.class,cause.getClass(),"null retains the original exception type");
                String detail=cause.getMessage();
                assertTrue(detail==null||detail.equals("Cannot invoke \"String.matches(String)\" because \"value\" is null"),"only the original helpful receiver text or VM-omitted detail is allowed");
                return List.of(cause.getClass().getName(),"<VM_GENERATED_NULL_NPE_DIAGNOSTIC>");
            }
            return List.of(cause.getClass().getName(),String.valueOf(cause.getMessage()));
        }
    }
    private static java.lang.reflect.Method registrationColumnMethod() throws Exception {
        var column=AgentRuntimeV2RegistrationStore.class.getDeclaredMethod("column",String.class);column.setAccessible(true);return column;
    }
    @Test void registrationStoreColumnPatternPreservesOriginalGrammarAndExactFailures() throws Exception {
        var column=registrationColumnMethod();
        for(int repeat=0;repeat<16;repeat++)for(String value:registrationColumnCases()) {
            var expected=registrationColumnOutcome(column,value,true);
            assertEquals(expected,registrationColumnOutcome(column,value,false),"original whole-string column oracle: "+String.valueOf(value));
        }
        assertEquals(List.of("ACCEPTED"),registrationColumnOutcome(column,"created_at",false));
        assertEquals(List.of(IllegalArgumentException.class.getName(),"invalid column"),registrationColumnOutcome(column,"",false));
        assertEquals(NullPointerException.class.getName(),registrationColumnOutcome(column,null,false).getFirst());
    }
    @Test void registrationStoreColumnPatternKeepsIndependentConcurrentMatchers() throws Exception {
        var column=registrationColumnMethod();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> runs=new ArrayList<>();for(int worker=0;worker<4;worker++)runs.add(pool.submit(()->{
                start.await();for(int repeat=0;repeat<8;repeat++)for(String value:registrationColumnCases())
                    assertEquals(registrationColumnOutcome(column,value,true),registrationColumnOutcome(column,value,false));
                return null;
            }));start.countDown();for(var run:runs)run.get();
        } finally {start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    private static final class RegistrationColumnSqlJdbc extends JdbcTemplate {
        final List<String> sql=new ArrayList<>();final List<List<Object>> bind=new ArrayList<>();List<Map<String,Object>> rows=List.of();
        RegistrationColumnSqlJdbc(DriverManagerDataSource source){super(source);}
        private void record(String query,Object[] args){sql.add(query);bind.add(new ArrayList<>(Arrays.asList(args.clone())));}
        @Override public List<Map<String,Object>> queryForList(String query,Object... args){record(query,args);return rows;}
        @Override public int update(String query,Object... args){record(query,args);return 1;}
    }
    @Test void registrationStoreColumnPatternPreservesPublicSqlNullPredicatesAndBindOrder() throws Exception {
        var source=new DriverManagerDataSource();var jdbc=new RegistrationColumnSqlJdbc(source);var db=new AgentRuntimeV2RegistrationStore(jdbc);
        var key=row("status","ACTIVE","consumed_at",null,"version",7L);jdbc.rows=List.of(new LinkedHashMap<>(key));
        assertEquals(key,db.hint(BINDING,key));
        String selected="SELECT * FROM "+BINDING+" WHERE status=? AND consumed_at IS NULL AND version=?";
        assertEquals(selected,jdbc.sql.getFirst());assertEquals(List.of("ACTIVE",7L),jdbc.bind.getFirst());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.hasResource(source));
        TransactionSynchronizationManager.setActualTransactionActive(true);TransactionSynchronizationManager.bindResource(source,new Object());
        try {
            assertEquals(key,db.lock(BINDING,key));assertEquals(selected+" FOR UPDATE",jdbc.sql.get(1));assertEquals(List.of("ACTIVE",7L),jdbc.bind.get(1));
            var values=row("runtime_binding_id","binding-a","status","ACTIVE","created_at",7L,"update_time",null);db.append(BINDING,values);
            assertEquals("INSERT INTO "+BINDING+"(runtime_binding_id,status,created_at,update_time) VALUES(?,?,?,?)",jdbc.sql.get(2));
            assertEquals(Arrays.asList("binding-a","ACTIVE",7L,null),jdbc.bind.get(2));
            byte[] requestHash=new byte[32],subjectHash=new byte[32];subjectHash[0]=1;
            db.consume(row("issued_at",0L,"expires_at",20L,"challenge_id","challenge-a","request_wire_sha256",requestHash,"registration_subject_sha256",subjectHash),"request-a",10L);
            assertEquals("UPDATE "+CHALLENGE+" SET status=?,consumed_reconnect_request_id=?,consumed_at=?,update_time=? WHERE challenge_id=? AND status=? AND consumed_reconnect_request_id IS NULL AND consumed_at IS NULL AND request_wire_sha256=? AND registration_subject_sha256=?",jdbc.sql.get(3));
            assertEquals(List.of("CONSUMED","request-a",10L,10L,"challenge-a","ISSUED"),jdbc.bind.get(3).subList(0,6));
            assertArrayEquals(requestHash,(byte[])jdbc.bind.get(3).get(6));assertArrayEquals(subjectHash,(byte[])jdbc.bind.get(3).get(7));
            int calls=jdbc.sql.size();
            for(String invalid:registrationColumnCases())if(invalid==null||!invalid.matches("[a-z][a-z0-9_]*")) {
                Class<? extends RuntimeException> type=invalid==null?NullPointerException.class:IllegalArgumentException.class;
                assertThrows(type,()->db.hint(BINDING,row(invalid,"value")));
                assertThrows(type,()->db.lock(BINDING,row(invalid,"value")));
                assertThrows(type,()->db.append(BINDING,row(invalid,"value")));
                assertEquals(calls,jdbc.sql.size(),"invalid column is rejected before JDBC");
            }
            assertThrows(IllegalArgumentException.class,()->db.hint(BINDING,Map.of()));assertEquals(calls,jdbc.sql.size());
        } finally {TransactionSynchronizationManager.unbindResource(source);TransactionSynchronizationManager.setActualTransactionActive(false);}
        assertFalse(TransactionSynchronizationManager.hasResource(source));assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    // REGISTRATION_STORE_COLUMN_PATTERN_ORACLE_END
}
