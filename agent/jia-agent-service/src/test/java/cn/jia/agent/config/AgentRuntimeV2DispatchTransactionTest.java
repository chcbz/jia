package cn.jia.agent.config;

import cn.jia.agent.dao.*;
import cn.jia.agent.dao.impl.AgentRuntimeV2InternalOutboxDaoImpl;
import cn.jia.agent.entity.*;
import cn.jia.agent.mapper.AgentRuntimeV2InternalOutboxMapper;
import cn.jia.agent.mapper.AgentCommandInboxV2Mapper;
import cn.jia.agent.dao.impl.AgentCommandInboxV2DaoImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import cn.jia.agent.security.AgentRuntimeV2CompositeAuthority;
import cn.jia.agent.service.*;
import cn.jia.agent.service.impl.*;
import cn.jia.core.tenant.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Local H2 transaction evidence only; not MySQL catalog, deployed topology or Node acceptance. */
public class AgentRuntimeV2DispatchTransactionTest {
    public static void verifyPostTransactionTransport() throws Exception {new AgentRuntimeV2DispatchTransactionTest().sendDoesNotHoldControlLocksOrTransactionResources();}
    public static void verifyCommitFailureZeroSend() throws Exception {new AgentRuntimeV2DispatchTransactionTest().physicalCommitFailureNeverCreatesOutboxOrSends();}
    public static void verifyControlCloseFirstZeroDispatch() throws Exception {var f=new Fixture();f.f.jdbc.update("UPDATE core_tenant_control SET state='DISABLED'");assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(f.command).kind());assertNull(f.internal());assertEquals(0,f.sends.get());}
    /** Real combined R2c handoff + retained legacy ACK SQL formula; NOT R2d authority acceptance. */
    public static void verifyCombinedHandoffAckFormula() throws Exception {
        var f=new Fixture();f.prepared();var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.addMapper(AgentCommandInboxV2Mapper.class);var global=new GlobalConfig();global.setBanner(false);var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(f.f.dataSource);factory.setConfiguration(configuration);factory.setGlobalConfig(global);var dao=new AgentCommandInboxV2DaoImpl(new SqlSessionTemplate(factory.getObject()).getMapper(AgentCommandInboxV2Mapper.class));var manager=new DataSourceTransactionManager(f.f.dataSource);var guard=mock(TenantModuleAdmissionGuard.class);
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());java.util.function.Function<TenantModuleAdmissionGuard.Mode,Object> work=call.getArgument(2);return work.apply(TenantModuleAdmissionGuard.Mode.DOMAIN);}).when(guard).withWorkerAdmission(eq(f.worker),any(),any());var ackService=new AgentCommandAckV2ServiceImpl(dao,f.worker,guard,manager);var inboxService=new AgentCommandInboxV2ServiceImpl(dao,f.worker,guard,manager);var t=f.token;var message=new AgentInboxMessage(t.consumerName(),t.tenantId(),t.dataDomainId(),t.executionPoolId(),t.admissionEpoch(),t.producerDeploymentId(),t.moduleId(),t.migrationEpoch(),2,t.claimantDeploymentId(),t.claimantWorkerGeneration(),t.clientId(),t.messageId(),t.eventId(),t.commandId(),t.taskId(),t.targetAgentId(),t.commandType(),t.deliveryId(),f.command.wirePayload());
        for(int k=1;k<=3;k++){String status=List.of("RECEIVED","STARTED","SUCCEEDED").get(k-1);long now=f.f.clock.value+k*100;var ack=new AgentCommandAckV2(t,"ack-"+k,t.messageId(),t.targetAgentId(),"installation-a",t.targetAgentId()+"-instance",status,now);assertEquals(AgentCommandAckResult.Kind.ADVANCED,ackService.acknowledge(ack,now).kind());long inboxAdvances=f.value("agent_consumer_inbox","version")-t.inboxVersion(),deliveryAdvances=f.value("agent_command_delivery","version")-t.deliveryVersion();assertEquals(1+k,inboxAdvances);assertEquals(1+k,deliveryAdvances);assertEquals(k,deliveryAdvances-1);assertEquals(AgentInboxClaim.Kind.PRIOR_RESULT,inboxService.claim(message,"new-broker-redelivery",now,1000).kind());assertEquals(t.inboxVersion(),f.value("agent_consumer_inbox","claim_inbox_version"));assertEquals(t.deliveryVersion(),f.value("agent_consumer_inbox","claim_delivery_version"));}
        // No independent RECEIVED/STARTED receipts or lease authority are fabricated by this legacy proof.
        assertThrows(SecurityException.class,()->f.prepare.verifyPrior(61));assertEquals(1,f.f.count("agent_runtime_v2_internal_outbox"));assertEquals(0,f.sends.get());
    }
    public static void verifyPrepareSerialization() throws Exception {
        var f=new Fixture();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{f.f.jdbc.afterWritePrefix="INSERT INTO agent_runtime_v2_internal_outbox";f.f.jdbc.afterWrite=()->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}};
            var prepared=pool.submit(()->f.prepare.prepare(f.command));assertTrue(entered.await(5,TimeUnit.SECONDS));var close=pool.submit(()->f.f.jdbc.update("UPDATE core_tenant_control SET state='DISABLED'"));assertThrows(TimeoutException.class,()->close.get(150,TimeUnit.MILLISECONDS));release.countDown();assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,prepared.get(5,TimeUnit.SECONDS).kind());assertEquals(1,close.get(5,TimeUnit.SECONDS));assertNotNull(f.internal());assertEquals(0,f.sends.get());
        }finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    static class Fixture {
        final AgentRuntimeV2RegistrationTransactionTest.Fixture f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();
        final WorkerScope worker=new WorkerScope(TenantId.of("1"),DataDomainId.of("cyf"),"local-experiment-a","local-api-a",4,11,7,"agent-command",3);
        final AgentRuntimeV2CompositeWorkerProof proof=AgentRuntimeV2CompositeWorkerProof.from(worker,"b".repeat(64),"c".repeat(64));
        final AgentRuntimeV2CompositeAuthority authority;
        final AgentRuntimeV2InternalOutboxDao dao;
        final AgentRuntimeV2DispatchServiceImpl prepare;
        final AgentInboxClaimToken token;
        final AgentCommandV2ExecutionPort.Command command;
        final AgentCommandDraft draft;
        final AgentRuntimeV2SocketTuple tuple;
        final AtomicInteger sends=new AtomicInteger();
        boolean route=true,sendResult=true;
        Runnable onSend;
        final AgentRuntimeV2SocketGateway gateway=new AgentRuntimeV2SocketGateway(){
            public String ownerInstanceId(){return AgentRuntimeV2RegistrationTransactionTest.OWNER;}
            public boolean current(AgentRuntimeV2SocketTuple t){return route&&tuple.equals(t);}
            public boolean send(AgentRuntimeV2SocketTuple t,byte[] raw,long deadline){
                AgentRuntimeV2DispatchServiceImpl.outside();assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());assertFalse(TransactionSynchronizationManager.isSynchronizationActive());assertEquals(tuple,t);assertTrue(f.clock.value<deadline);assertArrayEquals(command.wirePayload(),AgentRuntimeV2DispatchCodec.decode(raw).commandBytes());sends.incrementAndGet();if(onSend!=null)onSend.run();return sendResult;
            }
        };
        Fixture() throws Exception {
            f.jdbc.execute("ALTER TABLE core_deployment_registration ADD credential_digest VARCHAR(64)");f.jdbc.update("UPDATE core_deployment_registration SET credential_digest=?",proof.credentialSha256());
            f.insert("oauth_client",row("client_id","caller-client"));f.insert("oauth_client_tenant_grant",row("tenant_id","1","client_id","caller-client","status","ACTIVE","grant_revision",8L));f.insert("oauth_client_tenant_domain_grant",row("tenant_id","1","client_id","caller-client","data_domain_id","cyf","status","ACTIVE","domain_revision",3L));
            f.jdbc.update("INSERT INTO core_deployment_registration SELECT 'producer-a',status,stage,artifact_digest,worker_generation,registration_revision,credential_digest FROM core_deployment_registration WHERE deployment_id='local-api-a'");
            f.jdbc.update("INSERT INTO core_deployment_tenant_grant SELECT 'producer-a',tenant_id,data_domain_id,status,execution_pool_id,grant_revision,allow_read,allow_write FROM core_deployment_tenant_grant WHERE deployment_id='local-api-a'");
            f.jdbc.update("INSERT INTO core_deployment_module_admission SELECT 'producer-a',tenant_id,data_domain_id,module_id,mode_state,mode_revision,migration_epoch,admission_epoch,schema_version,registration_revision,grant_revision,allow_read,allow_write,status FROM core_deployment_module_admission WHERE deployment_id='local-api-a'");
            tuple=f.create().currentSocket();assertNotNull(tuple);
            long now=f.clock.value,expiry=now+60000;
            draft=new AgentCommandDraft(1,AgentCommandCanonicalCodec.taskInviteCommandId("1","caller-client","owner-a","task-1","agent-a"),"task-1","cause-1","1","caller-client","owner-a","task-1",null,"agent-a","TASK_INVITE",expiry-AgentCommandCanonicalCodec.TASK_INVITE_TTL_MILLIS,expiry,new AgentTaskInvitePayload("task_briefing","宋江首领已完成悬赏分派，请按职责协作推进。","阅读悬赏任务，确认自己的职责；如需协助，优先参考协作名册中的好汉能力并回报下一步计划。","任务",List.of("analysis"),"agent-a",List.of("agent-a"),"coordinator","回报执行计划、风险和协助诉求；Protocol v1 使用 work.progress，完成后使用 work.result，旧客户端由兼容层处理。","juyiting"));
            byte[] business=AgentCommandCanonicalCodec.businessBytes(draft),wire=AgentCommandCanonicalCodec.wireBytesV2(draft,"msg-1",41L,1,new ProducerScope(TenantId.of("1"),DataDomainId.of("cyf"),"local-experiment-a","producer-a",7,"agent-command",3));
            token=new AgentInboxClaimToken(61,"agent-command-dispatch-v2","1","cyf","local-experiment-a",7,"producer-a","agent-command",3,2,"local-api-a",4,"caller-client","msg-1","evt-1",draft.commandId(),"task-1","agent-a","TASK_INVITE",41,"replica-x-broker-claim",now+20000,1,0,1,6,expiry);
            command=new AgentCommandV2ExecutionPort.Command(token,"owner-a",wire);
            var common=row("protocol_version",2,"tenant_id","1","data_domain_id","cyf","execution_pool_id","local-experiment-a","admission_epoch",7L,"producer_deployment_id","producer-a","module_id","agent-command","migration_epoch",3L,"client_id","caller-client","command_id",draft.commandId(),"expires_at",expiry,"attempt_count",1,"active_attempt",1,"version",6L,"next_retry_at",null,"lease_owner",null,"lease_until",null,"last_error",null,"claimed_deployment_id",null,"claimed_worker_generation",null,"replay_parent_message_id",null,"replay_requester_id",null,"replay_approver_id",null,"replay_reason",null,"create_time",now,"update_time",now);
            var d=new LinkedHashMap<>(common);d.putAll(row("id",41L,"owner_jiacn","owner-a","task_id","task-1","work_item_id",null,"target_agent_id","agent-a","command_type","TASK_INVITE","command_payload",business,"command_payload_hash",sha256(business),"status","CONSUMED","active_message_id","msg-1"));table("agent_command_delivery",d);f.insert("agent_command_delivery",d);
            var o=new LinkedHashMap<>(common);o.putAll(row("id",51L,"event_id","evt-1","message_id","msg-1","delivery_id",41L,"aggregate_type","TASK","aggregate_id","task-1","destination","dispatch-exchange","routing_key","dispatch-key","wire_payload",wire,"wire_payload_hash",sha256(wire),"status","PUBLISHED","publisher_confirm_status","ACK","confirmed_at",now,"confirm_error",null,"mandatory_return_status","NOT_RETURNED","returned_at",null,"return_reply_code",null,"return_reply_text",null,"published_at",now));table("agent_outbox_event",o);f.insert("agent_outbox_event",o);
            // Only this isolated fixture's five-column placeholder is replaced, never a shared database.
            f.jdbc.execute("DROP TABLE agent_consumer_inbox");var i=new LinkedHashMap<>(common);i.remove("claimed_deployment_id");i.remove("claimed_worker_generation");
            i.putAll(row("id",61L,"claimant_deployment_id","local-api-a","claimant_worker_generation",4L,"consumer_name",token.consumerName(),"message_id","msg-1","event_id","evt-1","delivery_id",41L,"wire_payload",wire,"wire_payload_hash",sha256(wire),"status","PROCESSING","result_status",null,"processed_at",null,"lease_owner",token.leaseOwner(),"lease_until",token.leaseUntil(),"version",0L,"claim_delivery_version",6L,"claim_inbox_version",0L,"claim_started_at",now,"evidence_provenance","R2B1_ENFORCED"));
            for(String name:List.of("runtime_dispatch_id","runtime_binding_id","runtime_binding_generation","dispatch_runtime_instance_id","dispatch_runtime_credential_client_id","dispatch_receiver_registration_revision","dispatch_generation_fact_id","dispatch_generation_snapshot_sha256","execution_origin_snapshot_sha256","dispatch_prepared_at","ack_delivery_version","ack_message_id","ack_status","ack_at","ack_agent_id","ack_runtime_installation_id","ack_runtime_instance_id","ack_runtime_binding_id","ack_runtime_binding_generation","ack_committed_at","execution_started_at","execution_lease_until","execution_lease_revision","execution_holder_binding_id","execution_holder_binding_generation","execution_holder_runtime_instance_id","execution_current_lease_fact_id"))i.put(name,null);
            table("agent_consumer_inbox",i);f.insert("agent_consumer_inbox",i);
            String sql;try(var stream=getClass().getResourceAsStream("/db/agent-runtime-v2-dispatch-manual-20261001.sql")){sql=new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8);}
            // MySQL ICU POSIX cntrl is Java regex Cntrl in H2; preserve every constraint,
            // translating only this equivalent regex dialect spelling in the isolated fixture.
            // H2 2.4 constant-IN CHECK retains the DDL connection's closed comparator;
            // equivalent CASE avoids that fixture-only engine bug without dropping allowlists.
            String local=sql.substring(0,sql.lastIndexOf(" ENGINE=")).replace("[[:cntrl:]]","\\p{Cntrl}")
                .replace("last_error_code IN ('SEND_FAILED','LOCAL_ROUTE_UNAVAILABLE','CLAIM_EXPIRED')","CASE last_error_code WHEN 'SEND_FAILED' THEN 1 WHEN 'LOCAL_ROUTE_UNAVAILABLE' THEN 1 WHEN 'CLAIM_EXPIRED' THEN 1 ELSE 0 END=1")
                .replace("last_error_code IN ('TARGET_FENCED','AUTHORITY_REVOKED','CONTROL_NOT_ADMITTED')","CASE last_error_code WHEN 'TARGET_FENCED' THEN 1 WHEN 'AUTHORITY_REVOKED' THEN 1 WHEN 'CONTROL_NOT_ADMITTED' THEN 1 ELSE 0 END=1");
            f.jdbc.execute(local+";");
            dao=new AgentRuntimeV2InternalOutboxDaoImpl(new AgentRuntimeV2InternalOutboxMapper(f.jdbc));authority=new AgentRuntimeV2CompositeAuthority(f.db,f.keys,f.clock);
            prepare=new AgentRuntimeV2DispatchServiceImpl(dao,authority,proof,new DataSourceTransactionManager(f.dataSource),f.clock,failure->{throw new AssertionError("isolated fixture handoff failed",failure);});
        }
        void table(String name,Map<String,Object> values){var columns=new ArrayList<String>();values.forEach((k,v)->{String type=v instanceof byte[]||k.endsWith("sha256")||k.endsWith("_hash")||k.equals("wire_payload")||k.equals("command_payload")?"VARBINARY(175018)":v instanceof Number||k.endsWith("_at")||k.endsWith("_until")||k.endsWith("_version")||k.endsWith("_generation")||k.endsWith("_revision")||k.equals("work_item_id")?"BIGINT":"VARCHAR(1000)";columns.add(k+" "+type);});f.jdbc.execute("CREATE TABLE "+name+"("+String.join(",",columns)+",PRIMARY KEY(id))");}
        AgentRuntimeV2InternalOutboxRow internal(){return dao.forInboxHint(61);}
        AgentRuntimeV2InternalOutboxRelay relay(){return new AgentRuntimeV2InternalOutboxRelay(dao,authority,gateway,new DataSourceTransactionManager(f.dataSource),f.clock);}
        void prepared(){assertEquals(AgentCommandV2ExecutionPort.Kind.DISPATCHED,prepare.prepare(command).kind());AgentRuntimeV2DispatchServiceImpl.outside();assertNotNull(internal());assertEquals(0,sends.get());}
        long value(String table,String column){return f.jdbc.queryForObject("SELECT "+column+" FROM "+table+" WHERE id=?",Long.class,table.equals("agent_consumer_inbox")?61:41);}
    }
    @Test void differentCallerCredentialProducerClaimantAndReplicaOwnerHandOffOriginalBytes() throws Exception {
        var f=new Fixture();f.prepared();var r=f.internal();assertNotEquals(r.text("command_caller_client_id"),r.text("runtime_credential_client_id"));assertNotEquals(r.text("producer_deployment_id"),r.text("claimant_deployment_id"));assertNotEquals(f.token.leaseOwner(),r.text("socket_owner_instance_id"));assertArrayEquals(f.command.wirePayload(),r.binary("command_wire_bytes"));assertArrayEquals(r.origin().sha256(),r.binary("execution_origin_snapshot_sha256"));assertEquals(1,f.value("agent_consumer_inbox","version"));assertEquals(7,f.value("agent_command_delivery","version"));assertEquals(0,f.value("agent_consumer_inbox","claim_inbox_version"));assertEquals(6,f.value("agent_consumer_inbox","claim_delivery_version"));f.prepare.verifyPrior(61);f.relay().poll(100);assertEquals("SENT",f.internal().text("status"));assertEquals(1,f.sends.get());f.relay().poll(100);assertEquals(1,f.sends.get());
    }
    @Test void eachIndependentGrantRevokeProducesZeroWritesOrSocket() throws Exception {
        for(String sql:List.of("UPDATE oauth_client_tenant_grant SET status='REVOKED' WHERE client_id='caller-client'","UPDATE oauth_client_tenant_domain_grant SET status='REVOKED' WHERE client_id='runtime-client'","UPDATE core_deployment_tenant_grant SET status='REVOKED' WHERE deployment_id='producer-a'","UPDATE core_deployment_module_admission SET allow_write=0 WHERE deployment_id='local-api-a'","UPDATE core_tenant_control SET state='WRITE_CLOSED_CLAIM_CLOSED'","UPDATE core_tenant_module_control SET mode_state='QUIESCING'")){var f=new Fixture();f.f.jdbc.update(sql);var before=f.f.snapshot();assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(f.command).kind(),sql);assertEquals(before,f.f.snapshot());assertEquals(0,f.sends.get());}
    }
    @Test void wrongWorkerArtifactAndCredentialDoNotGrantPrepare() throws Exception {
        for(boolean artifact:List.of(true,false)){var f=new Fixture();var proof=new AgentRuntimeV2CompositeWorkerProof(f.worker,artifact?"0".repeat(64):f.proof.artifactSha256(),artifact?f.proof.credentialSha256():"0".repeat(64));var service=new AgentRuntimeV2DispatchServiceImpl(f.dao,f.authority,proof,new DataSourceTransactionManager(f.f.dataSource),f.f.clock);assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,service.prepare(f.command).kind());assertNull(f.internal());}
    }
    @Test void fullBusinessWrongPayloadUnknownNestedAndByteOrderRejectEvenRehashedSources() throws Exception {
        for(String replacement:List.of("\"unexpected\":{},","\"schemaVersion\":2, ","\"schemaVersion\":2,\"extra\":{\"nested\":true},")){var f=new Fixture();String raw=new String(f.command.wirePayload(),StandardCharsets.UTF_8);raw=replacement.equals("\"unexpected\":{},")?raw.replace("\"taskTitle\":\"任务\"","\"taskTitle\":\"另一个 payload\""):raw.replace("\"schemaVersion\":2,",replacement);byte[] changed=raw.getBytes(StandardCharsets.UTF_8);assertFalse(Arrays.equals(changed,f.command.wirePayload()));for(String table:List.of("agent_outbox_event","agent_consumer_inbox"))f.f.jdbc.update("UPDATE "+table+" SET wire_payload=?,wire_payload_hash=?",changed,sha256(changed));var before=f.f.snapshot();assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(new AgentCommandV2ExecutionPort.Command(f.token,"owner-a",changed)).kind());assertEquals(before,f.f.snapshot());}
    }
    @Test void lastWriteExpiryAndBeforeCommitClockBoundaryRollbackAllFourProjections() throws Exception {
        for(boolean precommit:List.of(false,true)){var f=new Fixture();var before=f.f.snapshot();f.f.jdbc.afterWritePrefix="UPDATE agent_command_delivery";f.f.jdbc.afterWrite=()->{if(precommit)TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){f.f.clock.value=f.token.leaseUntil();}});else f.f.clock.value=f.token.expiresAt();};assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(f.command).kind());assertEquals(before,f.f.snapshot());assertNull(f.internal());assertEquals(0,f.sends.get());AgentRuntimeV2DispatchServiceImpl.outside();}
    }
    @Test void physicalCommitFailureNeverCreatesOutboxOrSends() throws Exception {
        var f=new Fixture();var manager=new DataSourceTransactionManager(f.f.dataSource){@Override protected void doCommit(DefaultTransactionStatus status){throw new TransactionSystemException("injected isolated commit failure");}};manager.setRollbackOnCommitFailure(true);var service=new AgentRuntimeV2DispatchServiceImpl(f.dao,f.authority,f.proof,manager,f.f.clock);var before=f.f.snapshot();assertEquals(AgentCommandV2ExecutionPort.Kind.RETRYABLE,service.prepare(f.command).kind());assertEquals(before,f.f.snapshot());assertNull(f.internal());assertEquals(0,f.sends.get());AgentRuntimeV2DispatchServiceImpl.outside();
    }
    @Test void sendDoesNotHoldControlLocksOrTransactionResources() throws Exception {
        var f=new Fixture();f.prepared();var pool=Executors.newSingleThreadExecutor();try{f.onSend=()->{try{assertEquals(1,pool.submit(()->f.f.jdbc.update("UPDATE core_tenant_control SET state='AUTH_CLOSED' WHERE tenant_id='1'")).get(2,TimeUnit.SECONDS));}catch(Exception error){throw new AssertionError(error);}};f.relay().poll(1);assertEquals(1,f.sends.get());}finally{pool.shutdownNow();}
    }
    @Test void clippedRetryExpiryWakeIsCleanupOnlyUsesRealClock() throws Exception {
        var f=new Fixture();f.prepared();f.sendResult=false;f.f.clock.value=f.token.expiresAt()-1000;f.f.jdbc.update("UPDATE agent_runtime_v2_binding SET socket_owner_lease_until=?",f.token.expiresAt()+10000);f.relay().poll(1);var retry=f.internal();assertEquals("RETRY",retry.text("status"));assertEquals(f.token.expiresAt(),retry.number("next_attempt_at"));assertNull(retry.get("finished_at"));assertEquals(1,f.sends.get());f.f.clock.value=f.token.expiresAt();f.relay().poll(1);assertEquals("EXPIRED",f.internal().text("status"));assertEquals(f.f.clock.value,f.internal().number("finished_at"));assertEquals(1,f.sends.get());
    }
    @Test void localRouteUnavailableCreatesNoFakeClaimOrSend() throws Exception {var f=new Fixture();f.prepared();f.route=false;f.relay().poll(1);assertEquals("PENDING",f.internal().text("status"));assertEquals(0,f.internal().number("attempt_count"));assertEquals(0,f.sends.get());}
    @Test void expiredOwnerLeaseCannotRetargetOriginalOutbox() throws Exception {var f=new Fixture();f.prepared();var original=f.internal();f.f.jdbc.update("UPDATE agent_runtime_v2_binding SET socket_owner_lease_until=?",f.f.clock.value);f.relay().poll(1);assertEquals("FENCED",f.internal().text("status"));assertEquals(original.tuple(),f.internal().tuple());assertEquals(0,f.sends.get());}
    @Test void realReconnectNPlusOneNewOwnerEpochFencesOldDispatchWithoutRetargeting() throws Exception {
        var f=new Fixture();f.prepared();var original=f.internal();var receipt=AgentRuntimeV2RegistrationCodec.decodeReceipt((byte[])f.f.jdbc.queryForMap("SELECT * FROM "+RECEIPT).get("receipt_wire_bytes"));String fence=UUID.randomUUID().toString(),owner=UUID.randomUUID().toString();
        var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.f.service.challenge(f.f.identity,fence,owner,1,f.f.challengeWire(receipt,UUID.randomUUID().toString())).responseBytes());var next=f.f.service.reconnect(f.f.identity,fence,owner,1,f.f.commitWire(receipt,challenge,UUID.randomUUID().toString()));assertNotNull(next.currentSocket());assertEquals(original.number("dispatch_binding_generation")+1,next.currentSocket().bindingGeneration());assertEquals(owner,next.currentSocket().socketOwnerInstanceId());assertEquals(2,next.currentSocket().socketOwnerEpoch());assertEquals(original.tuple(),next.replacedSocket());assertEquals(3,f.f.count(FACT));
        // A new replica discovers no send candidate; bounded maintenance can only fence the old target.
        assertTrue(f.dao.due(owner,f.f.clock.value,100).isEmpty());f.relay().maintain(original.text("internal_outbox_id"));assertEquals("FENCED",f.internal().text("status"));assertEquals(original.tuple(),f.internal().tuple());assertArrayEquals(original.binary("dispatch_wire_bytes"),f.internal().binary("dispatch_wire_bytes"));f.prepare.verifyPrior(61);assertEquals(0,f.sends.get());
    }
    @Test void exactClaimCasAndLateOutcomeCannotOverwriteReclaimedWinner() throws Exception {
        var f=new Fixture();f.prepared();f.onSend=()->{var old=f.internal();assertEquals("CLAIMED",old.text("status"));var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.dataSource));tx.executeWithoutResult(status->{var current=f.dao.lock(old.text("internal_outbox_id"));assertTrue(f.dao.transition(current,row("claim_id",UUID.randomUUID().toString(),"claim_until",old.number("claim_until"),"update_time",f.f.clock.value)));assertFalse(f.dao.transition(old,row("status","SENT","claim_id",null,"claim_until",null,"sent_at",f.f.clock.value,"finished_at",f.f.clock.value,"update_time",f.f.clock.value)));});};f.relay().poll(1);assertEquals("CLAIMED",f.internal().text("status"));assertEquals(2,f.internal().number("version"));
    }
    @Test void realExpiredClaimReclaimWinsAndLateSuccessfulOutcomeCannotOverwriteIt() throws Exception {
        var f=new Fixture();f.prepared();var claims=new ArrayList<String>();f.onSend=()->{var old=f.internal();claims.add(old.text("claim_id"));f.f.clock.value=old.number("claim_until");f.onSend=()->claims.add(f.internal().text("claim_id"));f.relay().deliver(old.text("internal_outbox_id"));new TransactionTemplate(new DataSourceTransactionManager(f.f.dataSource)).executeWithoutResult(status->assertFalse(f.dao.transition(old,row("status","SENT","claim_id",null,"claim_until",null,"sent_at",f.f.clock.value,"finished_at",f.f.clock.value,"update_time",f.f.clock.value))));};
        f.relay().poll(1);assertEquals("SENT",f.internal().text("status"));assertEquals(2,f.internal().number("attempt_count"));assertEquals(3,f.internal().number("version"));assertEquals(2,f.sends.get());assertEquals(2,claims.size());assertNotEquals(claims.get(0),claims.get(1));
    }
    @Test void simultaneousPrepareCreatesOneUniqueHandoffAndDuplicatePriorReusesIt() throws Exception {
        var f=new Fixture();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var a=pool.submit(()->{start.await();return f.prepare.prepare(f.command).kind();});var b=pool.submit(()->{start.await();return f.prepare.prepare(f.command).kind();});start.countDown();assertEquals(Set.of(AgentCommandV2ExecutionPort.Kind.DISPATCHED,AgentCommandV2ExecutionPort.Kind.REJECTED),Set.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS)));assertEquals(1,f.f.count("agent_runtime_v2_internal_outbox"));String id=f.internal().text("runtime_dispatch_id");f.prepare.verifyPrior(61);assertEquals(id,f.internal().text("runtime_dispatch_id"));assertEquals(1,f.value("agent_consumer_inbox","version"));assertEquals(7,f.value("agent_command_delivery","version"));assertEquals(0,f.sends.get());}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void uniqueConflictRetriesOnlyAfterFullRollbackAndStopsAfterTwoAttempts() throws Exception {
        for(boolean twice:List.of(false,true)){var f=new Fixture();var dao=spy(f.dao);var calls=new AtomicInteger();doAnswer(call->{int attempt=calls.incrementAndGet();assertNull(f.internal());assertEquals(0,f.value("agent_consumer_inbox","version"));assertEquals(6,f.value("agent_command_delivery","version"));call.callRealMethod();if(attempt==1||twice)throw new org.springframework.dao.DuplicateKeyException("isolated collision after insert");return null;}).when(dao).insert(any());var service=new AgentRuntimeV2DispatchServiceImpl(dao,f.authority,f.proof,new DataSourceTransactionManager(f.f.dataSource),f.f.clock);assertEquals(twice?AgentCommandV2ExecutionPort.Kind.RETRYABLE:AgentCommandV2ExecutionPort.Kind.DISPATCHED,service.prepare(f.command).kind());assertEquals(2,calls.get());assertEquals(twice?0:1,f.f.count("agent_runtime_v2_internal_outbox"));AgentRuntimeV2DispatchServiceImpl.outside();assertEquals(0,f.sends.get());}
    }
    @Test void freshFinalClaimClockEqualityRollsBackClaimAndPerformsZeroSend() throws Exception {
        var f=new Fixture();f.prepared();var before=f.f.snapshot();f.f.jdbc.afterWritePrefix="UPDATE agent_runtime_v2_internal_outbox";f.f.jdbc.afterWrite=()->f.f.clock.value=f.internal().number("claim_until");assertThrows(SecurityException.class,()->f.relay().poll(1));assertEquals(before,f.f.snapshot());assertEquals(0,f.sends.get());AgentRuntimeV2DispatchServiceImpl.outside();
    }
    @Test void attemptAndVersionOverflowRefuseAnyClaimWriteOrSend() throws Exception {
        for(boolean attempt:List.of(true,false)){var f=new Fixture();f.prepared();f.f.clock.value++;if(attempt)f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET status='RETRY',attempt_count=2147483647,last_attempt_at=prepared_at,next_attempt_at=?,last_error_code='SEND_FAILED'",f.f.clock.value);else f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET version=9223372036854775807");var before=f.f.snapshot();assertThrows(ArithmeticException.class,()->f.relay().poll(1));assertEquals(before,f.f.snapshot());assertEquals(0,f.sends.get());}
    }
    @Test void outcomeCannotBlessSelfConsistentlyRehashedImmutableBytesChangedAfterClaim() throws Exception {
        var f=new Fixture();f.prepared();f.onSend=()->{var old=f.internal();byte[] changed="not-the-original-producer-bytes".getBytes(StandardCharsets.UTF_8);byte[] outer=AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(old.text("runtime_dispatch_id"),changed));f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET command_wire_bytes=?,command_wire_sha256=?,dispatch_wire_bytes=?,dispatch_wire_sha256=?",changed,sha256(changed),outer,sha256(outer));};f.relay().deliver(f.internal().text("internal_outbox_id"));assertEquals("CLAIMED",f.internal().text("status"));assertEquals(1,f.internal().number("version"));assertEquals(1,f.sends.get());assertThrows(SecurityException.class,()->f.prepare.verifyPrior(61));
    }
    @Test void jumpedVerifierWithRehashedReceiptHolderAndCurrentProjectionStillRejectsZeroWrites() throws Exception {
        var f=new Fixture();var receipt=f.f.jdbc.queryForMap("SELECT * FROM "+RECEIPT);byte[] wire=new String((byte[])receipt.get("receipt_wire_bytes"),StandardCharsets.UTF_8).replace("\"verifierVersion\":\"1\"","\"verifierVersion\":\"2\"").getBytes(StandardCharsets.UTF_8);assertFalse(Arrays.equals(wire,(byte[])receipt.get("receipt_wire_bytes")));byte[] hash=sha256(wire);f.f.jdbc.update("UPDATE "+RECEIPT+" SET verifier_version=2,receipt_wire_bytes=?,receipt_wire_sha256=?",wire,hash);f.f.jdbc.update("UPDATE "+BINDING+" SET verifier_version=2,current_registration_receipt_sha256=?",hash);f.f.jdbc.update("UPDATE "+FACT+" SET verifier_version=2,registration_receipt_sha256=?",hash);var fact=f.f.jdbc.queryForMap("SELECT * FROM "+FACT);byte[] holder=AgentRuntimeV2RegistrationServiceImpl.holder(fact).sha256();f.f.jdbc.update("UPDATE "+FACT+" SET generation_snapshot_sha256=?",holder);assertArrayEquals(holder,AgentRuntimeV2RegistrationServiceImpl.holder(f.f.jdbc.queryForMap("SELECT * FROM "+FACT)).sha256());assertArrayEquals(hash,sha256((byte[])f.f.jdbc.queryForMap("SELECT * FROM "+RECEIPT).get("receipt_wire_bytes")));var before=f.f.snapshot();assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(f.command).kind());assertEquals(before,f.f.snapshot());assertEquals(0,f.sends.get());
    }
    @Test void wrongStableSourceLinkAndHolderHashRejectWithoutRepairWrites() throws Exception {
        for(String sql:List.of("UPDATE agent_outbox_event SET event_id='wrong-event'","UPDATE agent_outbox_event SET delivery_id=42","UPDATE agent_runtime_v2_binding_generation_fact SET generation_snapshot_sha256=X'0000000000000000000000000000000000000000000000000000000000000000'","UPDATE agent_consumer_inbox SET claim_delivery_version=7")){var f=new Fixture();f.f.jdbc.update(sql);var before=f.f.snapshot();assertEquals(AgentCommandV2ExecutionPort.Kind.REJECTED,f.prepare.prepare(f.command).kind(),sql);assertEquals(before,f.f.snapshot());assertNull(f.internal());assertEquals(0,f.sends.get());}
    }
    @Test void ambientTransactionsSynchronizationAndResourcesAreRejected() throws Exception {
        var f=new Fixture();var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.dataSource));tx.executeWithoutResult(status->assertThrows(IllegalStateException.class,()->f.prepare.prepare(f.command)));TransactionSynchronizationManager.initSynchronization();try{assertThrows(IllegalStateException.class,()->f.prepare.prepare(f.command));}finally{TransactionSynchronizationManager.clearSynchronization();}Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());try{assertThrows(IllegalStateException.class,()->f.prepare.prepare(f.command));}finally{TransactionSynchronizationManager.unbindResource(key);}assertNull(f.internal());
    }
    @Test void graphRejectsPartialOrigin() throws Exception {
        var f=new Fixture();f.prepared();AgentRuntimeV2DispatchSchemaGate.validateGraph(f.f.jdbc);f.f.jdbc.update("UPDATE agent_consumer_inbox SET execution_origin_snapshot_sha256=NULL");assertThrows(SecurityException.class,()->AgentRuntimeV2DispatchSchemaGate.validateGraph(f.f.jdbc));
    }
    @Test void graphRejectsRealOrphanAndStrictOuterDriftEvenAfterRehash() throws Exception {
        for(boolean orphan:List.of(true,false)){var f=new Fixture();f.prepared();if(orphan)f.f.jdbc.update("DELETE FROM agent_runtime_v2_internal_outbox");else{byte[] raw=(" "+new String(f.internal().binary("dispatch_wire_bytes"),StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET dispatch_wire_bytes=?,dispatch_wire_sha256=?",raw,sha256(raw));}var before=f.f.snapshot();assertThrows(RuntimeException.class,()->AgentRuntimeV2DispatchSchemaGate.validateGraph(f.f.jdbc));assertEquals(before,f.f.snapshot());assertEquals(0,f.sends.get());}
    }
    @Test void internalChecksAndUniqueIndexesRejectNullHalfShapesAndDuplicates() throws Exception {
        var f=new Fixture();f.prepared();assertThrows(RuntimeException.class,()->f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET status='CLAIMED',attempt_count=1"));assertThrows(RuntimeException.class,()->f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET status='EXPIRED',finished_at=?,last_error_code='MESSAGE_EXPIRED',next_attempt_at=NULL",f.f.clock.value));assertThrows(RuntimeException.class,()->f.f.jdbc.update("UPDATE agent_runtime_v2_internal_outbox SET command_wire_sha256=NULL"));var tx=new TransactionTemplate(new DataSourceTransactionManager(f.f.dataSource));tx.executeWithoutResult(status->{var values=f.internal().values();values.put("internal_outbox_id",UUID.randomUUID().toString());assertThrows(RuntimeException.class,()->f.dao.insert(new AgentRuntimeV2InternalOutboxRow(values)));status.setRollbackOnly();});assertEquals(1,f.f.count("agent_runtime_v2_internal_outbox"));
    }
    @Test void compositePrepareSerializesWithCurrentControl() throws Exception {verifyPrepareSerialization();}
    @Test void combinedHandoffRetainsOriginalClaimAndAckAdvancesOnePlusK() throws Exception {verifyCombinedHandoffAckFormula();}
}
