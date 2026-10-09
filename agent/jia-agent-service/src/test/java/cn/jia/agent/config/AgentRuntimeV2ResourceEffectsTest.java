package cn.jia.agent.config;

import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.AgentRuntimeV2EffectCodec;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationCodec;
import cn.jia.agent.service.impl.AgentRuntimeV2RegistrationServiceImpl;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import cn.jia.agent.skill.SkillInstallResultService;
import cn.jia.agent.skill.SkillMarketplaceException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exact association seams and actual same-transaction Skill resource reducers.
 * These are NOT complete R4 WebSocket/runner/MySQL resource acceptance. */
class AgentRuntimeV2ResourceEffectsTest {
    private static final String EFFECT="eff_"+"a".repeat(64);
    private static final byte[] UNKNOWN="retained-unknown-observation".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KNOWN="verifier-selected-known-resource-proof".getBytes(StandardCharsets.UTF_8);

    // Independent historical reflection oracle is TEST ONLY. Production has
    // no component metadata, regex/reflection accessor or authority cache.
    private static Map<String,Object> originalRegistrationProjection(Record value) throws ReflectiveOperationException {
        var result=new LinkedHashMap<String,Object>();
        for(var field:value.getClass().getRecordComponents())result.put(field.getName().replaceAll("([a-z0-9])([A-Z])","$1_$2").toLowerCase(Locale.ROOT),field.getAccessor().invoke(value));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> fixedRegistrationProjection(Record value) throws ReflectiveOperationException {
        var method=AgentRuntimeV2RegistrationServiceImpl.class.getDeclaredMethod("record",Record.class);method.setAccessible(true);
        return (Map<String,Object>)method.invoke(null,value);
    }
    private static void assertProjectionValues(Map<String,Object> expected,Map<String,Object> actual){
        assertEquals(new ArrayList<>(expected.keySet()),new ArrayList<>(actual.keySet()),"complete ordered schema");
        expected.forEach((name,value)->{if(value instanceof byte[] bytes)assertArrayEquals(bytes,(byte[])actual.get(name),name);else{assertEquals(value,actual.get(name),name);if(value!=null)assertEquals(value.getClass(),actual.get(name).getClass(),name+" numeric boxing/type");}});
    }
    private static void assertProjectionEquivalentAndArraysCloned(Record value) throws ReflectiveOperationException {
        var original=originalRegistrationProjection(value);var projected=fixedRegistrationProjection(value);assertProjectionValues(original,projected);
        for(var entry:original.entrySet())if(entry.getValue() instanceof byte[] bytes){assertNotSame(bytes,projected.get(entry.getKey()),entry.getKey());((byte[])projected.get(entry.getKey()))[0]^=1;}
        assertProjectionValues(original,fixedRegistrationProjection(value));assertProjectionValues(original,originalRegistrationProjection(value));
    }
    private static Object changedProjectionColumn(String column,Object original){
        if(original instanceof byte[] bytes){byte[] changed=bytes.clone();changed[0]^=1;return changed;}
        // CREATE generation 0 and the real RECONNECT generation 1 coexist.
        // Use an unused value so the production validator, not a unique index,
        // is what rejects the persisted mutation.
        if(original instanceof Number number)return Math.addExact(number.longValue(),2L);
        if(original instanceof String value)return value.substring(0,value.length()-1)+(value.endsWith("a")?"b":"a");
        assertNull(original);return column.endsWith("sha256")?new byte[32]:UUID.randomUUID().toString();
    }
    private record UnsupportedRegistrationProjection(String value) { }

    @Test void fixedRegistrationProjectionMatchesEveryOriginalComponentOrderTypeNullAndDefensiveArray() throws Exception {
        var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();
        try{
            var first=f.receipt(f.create());assertNull(first.previousRegistrationReceiptId());assertNull(first.previousRegistrationReceiptSha256());
            assertProjectionEquivalentAndArraysCloned(first);assertEquals(30,fixedRegistrationProjection(first).size());
            var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.challengeWire(first,UUID.randomUUID().toString())).responseBytes());
            assertProjectionEquivalentAndArraysCloned(challenge);assertEquals(12,fixedRegistrationProjection(challenge).size());
            var next=f.receipt(f.service.reconnect(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.commitWire(first,challenge,UUID.randomUUID().toString())));
            assertNotNull(next.previousRegistrationReceiptId());assertNotNull(next.previousRegistrationReceiptSha256());assertProjectionEquivalentAndArraysCloned(next);
            var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->fixedRegistrationProjection(new UnsupportedRegistrationProjection("not an allowed record")));
            assertInstanceOf(IllegalArgumentException.class,failure.getCause());
        }finally{f.jdbc.execute("SHUTDOWN");}
    }

    @Test void freshActualReceiptValidationRejectsEveryProjectedDatabaseColumnIndependentlyAndRestores() throws Exception {
        var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();
        try{
            var first=f.receipt(f.create());
            var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.challengeWire(first,UUID.randomUUID().toString())).responseBytes());
            var next=f.receipt(f.service.reconnect(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.commitWire(first,challenge,UUID.randomUUID().toString())));
            for(var receipt:List.of(first,next)){
                var original=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_receipt WHERE registration_receipt_id=?",receipt.registrationReceiptId());
                AgentRuntimeV2RegistrationServiceImpl.validateReceipt(original);int checked=0;
                for(String column:originalRegistrationProjection(receipt).keySet()){
                    assertEquals(1,f.jdbc.update("UPDATE agent_runtime_v2_registration_receipt SET "+column+"=? WHERE id=?",changedProjectionColumn(column,original.get(column)),original.get("id")));
                    try{assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationServiceImpl.validateReceipt(f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_receipt WHERE id=?",original.get("id"))),"fresh receipt column "+receipt.receiptKind()+"/"+column);checked++;}
                    finally{assertEquals(1,f.jdbc.update("UPDATE agent_runtime_v2_registration_receipt SET "+column+"=? WHERE id=?",original.get(column),original.get("id")));}
                    assertDoesNotThrow(()->AgentRuntimeV2RegistrationServiceImpl.validateReceipt(f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_receipt WHERE id=?",original.get("id"))));
                }
                assertEquals(30,checked);assertProjectionValues(original,f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_registration_receipt WHERE id=?",original.get("id")));
            }
        }finally{f.jdbc.execute("SHUTDOWN");}
    }

    @Test void freshActualChallengeDecisionRejectsEveryProjectedDatabaseColumnThenRealReconnectStillSucceeds() throws Exception {
        var f=new AgentRuntimeV2RegistrationTransactionTest.Fixture();
        try{
            var first=f.receipt(f.create());
            var challenge=AgentRuntimeV2RegistrationCodec.decodeChallenge(f.service.challenge(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.challengeWire(first,UUID.randomUUID().toString())).responseBytes());
            var original=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE challenge_id=?",challenge.challengeId());
            var decision=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_challenge_request_decision WHERE challenge_request_id=?",challenge.challengeRequestId());
            AgentRuntimeV2RegistrationServiceImpl.verifyDecision(decision,original);int checked=0;
            for(String column:originalRegistrationProjection(challenge).keySet()){
                assertEquals(1,f.jdbc.update("UPDATE agent_runtime_v2_reconnect_challenge SET "+column+"=? WHERE id=?",changedProjectionColumn(column,original.get(column)),original.get("id")));
                try{assertThrows(SecurityException.class,()->AgentRuntimeV2RegistrationServiceImpl.verifyDecision(f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_challenge_request_decision WHERE challenge_request_id=?",challenge.challengeRequestId()),f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE id=?",original.get("id"))),"fresh challenge column "+column);checked++;}
                finally{assertEquals(1,f.jdbc.update("UPDATE agent_runtime_v2_reconnect_challenge SET "+column+"=? WHERE id=?",original.get(column),original.get("id")));}
                assertDoesNotThrow(()->AgentRuntimeV2RegistrationServiceImpl.verifyDecision(decision,f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE id=?",original.get("id"))));
            }
            assertEquals(12,checked);assertProjectionValues(original,f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE id=?",original.get("id")));
            var result=f.service.reconnect(f.identity,AgentRuntimeV2RegistrationTransactionTest.FENCE,AgentRuntimeV2RegistrationTransactionTest.OWNER,0,f.commitWire(first,challenge,UUID.randomUUID().toString()));
            assertNotNull(result.currentSocket());assertEquals(first.registrationReceiptId(),f.receipt(result).previousRegistrationReceiptId());
        }finally{f.jdbc.execute("SHUTDOWN");}
    }

    @Test void realClockIpcValidatorsAreDirectProductionAndStillRejectFreshReceiptCorruption() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile(false,true,false)){
            assertFalse(mockingDetails(f.executionEvidence).isMock());assertFalse(mockingDetails(f.effectEvidence).isMock());
            var graph=f.file.tx.execute(status->f.executionEvidence.inspect(f.rows(),f.origin(),true));assertEquals(2,graph.receipts().size());
            byte[] invalid="{}".getBytes(StandardCharsets.UTF_8);
            f.jdbc.update("UPDATE agent_runtime_v2_registration_receipt SET receipt_wire_bytes=?,receipt_wire_sha256=?",invalid,cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(invalid));
            assertThrows(RuntimeException.class,()->f.file.tx.execute(status->f.executionEvidence.inspect(f.rows(),f.origin(),true)),"direct production validator cannot retain a prior fresh receipt");
        }
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile(false,true)){
            assertTrue(mockingDetails(f.executionEvidence).isSpy());assertTrue(mockingDetails(f.effectEvidence).isSpy());
        }
    }

    @Test void graphLocalMembershipIndicesRejectDuplicateIdsAndKeepOriginalCopiedRows() {
        byte[] source={1,2,3};var request=new java.util.LinkedHashMap<String,Object>();request.put("request_id","request-a");request.put("effect_id",EFFECT);request.put("request_family","EFFECT");request.put("request_wire_bytes",source);
        var effect=new java.util.LinkedHashMap<String,Object>();effect.put("effect_id",EFFECT);
        var graph=new cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph(null,List.of(effect),List.of(request),List.of(),java.util.Map.of());
        source[0]=9;request.put("request_id","changed");effect.put("effect_id","changed");
        assertSame(graph.requests().getFirst(),graph.request("request-a"));assertSame(graph.effects().getFirst(),graph.effect(EFFECT));assertNull(graph.request("changed"));assertNull(graph.effect("changed"));
        assertArrayEquals(new byte[]{1,2,3},(byte[])graph.request("request-a").get("request_wire_bytes"));assertThrows(UnsupportedOperationException.class,()->graph.request("request-a").put("request_id","changed"));
        assertThrows(SecurityException.class,()->new cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph(null,graph.effects(),List.of(graph.request("request-a"),graph.request("request-a")),List.of(),java.util.Map.of()));
        assertThrows(SecurityException.class,()->new cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph(null,List.of(graph.effect(EFFECT),graph.effect(EFFECT)),graph.requests(),List.of(),java.util.Map.of()));
    }

    @Test void actualGraphLocalEventMembershipReparsesOriginalReceiptAndCannotBlessLaterCorruption() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            f.completeFile();var graph=f.historicalGraph();var effect=graph.effects().getFirst();
            byte[] first=cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(graph,effect);assertNotNull(first);byte[] expected=first.clone();first[0]^=1;
            assertArrayEquals(expected,cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(graph,effect));
            var events=new java.util.ArrayList<java.util.Map<String,Object>>();boolean changed=false;
            for(var event:graph.events()){var row=new java.util.LinkedHashMap<>(event);if(!changed&&effect.get("effect_id").equals(row.get("effect_id"))&&java.util.Set.of("OUTCOME_OBSERVED","SERVER_COMMIT").contains(row.get("event_kind"))&&java.util.Arrays.equals(expected,(byte[])row.get("evidence_wire_bytes"))){byte[] invalid=expected.clone();invalid[0]^=1;row.put("evidence_wire_bytes",invalid);changed=true;}events.add(row);}
            assertTrue(changed);var corrupted=new cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.Graph(graph.run(),graph.effects(),graph.requests(),events,graph.blobs());
            assertThrows(RuntimeException.class,()->cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(corrupted,corrupted.effect(effect.get("effect_id"))));
            assertThrows(RuntimeException.class,()->f.file.tx.executeWithoutResult(status->{var rows=f.rows();var execution=f.executionEvidence.inspect(rows,f.origin(),false);f.effectEvidence.validate(f.origin(),rows,execution,corrupted);}));
        }
    }

    @Test void actualHolderIndexChecksEveryReferencedHashAndRereadsCanonicalReceiptOnNextValidation() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var graph=f.file.tx.execute(status->f.executionEvidence.inspect(f.rows(),f.origin(),true));
            var receipts=new java.util.ArrayList<java.util.Map<String,Object>>();
            for(var receipt:graph.receipts())receipts.add(new java.util.LinkedHashMap<>(receipt));
            receipts.getLast().put("ack_holder_generation_snapshot_sha256",new byte[32]);
            var changed=new cn.jia.agent.service.impl.AgentRuntimeV2ExecutionEvidenceValidator.Graph(receipts,graph.facts(),graph.anchors(),graph.requests(),graph.grants());
            assertThrows(SecurityException.class,()->f.file.tx.executeWithoutResult(status->f.executionEvidence.validate(f.rows(),f.origin(),changed)),"origin's indexed holder cannot bless a different reference hash");
            byte[] invalid="{}".getBytes(StandardCharsets.UTF_8);
            f.jdbc.update("UPDATE agent_runtime_v2_registration_receipt SET receipt_wire_bytes=?,receipt_wire_sha256=?",invalid,cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(invalid));
            assertThrows(RuntimeException.class,()->f.file.tx.execute(status->f.executionEvidence.inspect(f.rows(),f.origin(),true)),"a previous validation cannot retain receipt authority");
        }
    }

    @Test void actualMissingRenewalIsUnknownUntilCommittedHolderRetirementAndBarrierReplaysHistoricallyWithoutLateGrant() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            byte[] raw=f.renewal();var renewal=cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.decodeRenew(raw);var oldSocket=f.socket;
            var before=f.reconcile(f.renewalQuery(raw));assertFalse(before.close());assertEquals("UNKNOWN",cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(before.responses().getFirst()).decision());
            f.reconnectForAuditRead();var query=f.renewalQuery(raw);var result=f.reconcile(query);assertFalse(result.close());var receipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst());assertEquals("NOT_COMMITTED",receipt.decision());
            var proof=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.RetirementBarrier)((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)receipt.result()).proof();
            var prior=f.jdbc.queryForMap("SELECT * FROM agent_command_v2_execution_grant WHERE execution_grant_id=?",renewal.previousExecutionGrantId());var barrier=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_binding_generation_fact WHERE binding_generation_fact_id=?",proof.barrierEventId());
            assertEquals(prior.get("holder_generation_fact_id"),proof.retiredHolderGenerationFactId());assertEquals("DISCONNECTED",barrier.get("event_type"));assertEquals(proof.retiredHolderGenerationFactId(),barrier.get("previous_fact_id"));assertEquals(cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.durableRowHash(barrier),proof.barrierEventSha256());
            var late=f.execution.renewExecution(f.identity,oldSocket,raw);assertFalse(late.close());assertArrayEquals(cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",renewal.renewalRequestId(),renewal.runtimeDispatchId(),renewal.startedReceiptId(),"RENEWAL_NOT_AUTHORIZED"),late.responses().getFirst());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));
            assertArrayEquals(result.responses().getFirst(),f.reconcile(query).responses().getFirst());f.reconnectForAuditRead();assertArrayEquals(result.responses().getFirst(),f.reconcile(query).responses().getFirst());assertDoesNotThrow(f::historicalGraph);
        }
    }
    @Test void actualInFlightRenewalAndAuthenticatedReconnectSerializeBothOrdersOnHeldBindingThenReturnOnlyRealMappingOrBarrier() throws Exception {
        for(boolean retirementFirst:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            byte[] raw=f.renewal();var oldSocket=f.socket;f.registration.clock.value+=1000;
            var atCommit=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var committed=new java.util.concurrent.atomic.AtomicLong();var acquired=new java.util.concurrent.atomic.AtomicLong();var releasedAt=new java.util.concurrent.atomic.AtomicLong();
            f.registration.jdbc.afterWritePrefix=retirementFirst?"UPDATE agent_runtime_v2_binding":"INSERT INTO agent_command_v2_execution_request";
            f.registration.jdbc.afterWrite=()->TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void beforeCommit(boolean readOnly){atCommit.countDown();try{assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS),"real first transaction commit gate released within original timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}}
                @Override public void afterCompletion(int status){assertEquals(STATUS_COMMITTED,status);committed.set(System.nanoTime());}
            });
            var executor=java.util.concurrent.Executors.newFixedThreadPool(3);
            try{
                java.util.concurrent.Callable<Object> reconnect=()->{f.reconnectForAuditRead();return Boolean.TRUE;};java.util.concurrent.Callable<Object> renew=()->f.execution.renewExecution(f.identity,oldSocket,raw);
                var first=executor.submit(retirementFirst?reconnect:renew);assertTrue(atCommit.await(3,java.util.concurrent.TimeUnit.SECONDS));var second=executor.submit(retirementFirst?renew:reconnect);
                // Independent real SQL transaction proves the exact binding
                // root is held, in addition to the real competing reducer.
                var probeStarted=new java.util.concurrent.CountDownLatch(1);var probe=executor.submit(()->{var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.registration.dataSource));return tx.execute(status->{probeStarted.countDown();var row=f.registration.db.lock(cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.BINDING,cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.row("runtime_binding_id",oldSocket.runtimeBindingId()));acquired.set(System.nanoTime());return row;});});
                assertTrue(probeStarted.await(1,java.util.concurrent.TimeUnit.SECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->probe.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));assertThrows(java.util.concurrent.TimeoutException.class,()->second.get(100,java.util.concurrent.TimeUnit.MILLISECONDS));releasedAt.set(System.nanoTime());release.countDown();
                Object firstResult=first.get(5,java.util.concurrent.TimeUnit.SECONDS),secondResult=second.get(5,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(probe.get(5,java.util.concurrent.TimeUnit.SECONDS));assertTrue(committed.get()>0&&acquired.get()>=releasedAt.get(),"binding lock cannot be acquired before first transaction commit gate is released");
                var renewResult=(cn.jia.agent.service.AgentRuntimeV2ExecutionService.Result)(retirementFirst?secondResult:firstResult);assertFalse(renewResult.close());var renewal=cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.decodeRenew(raw);
                byte[] expected=retirementFirst?cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",renewal.renewalRequestId(),renewal.runtimeDispatchId(),renewal.startedReceiptId(),"RENEWAL_NOT_AUTHORIZED"):cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.grantDelivery(f.jdbc.queryForObject("SELECT grant_wire_bytes FROM agent_command_v2_execution_grant ORDER BY id DESC LIMIT 1",byte[].class),"RENEWAL",renewal.renewalRequestId(),false);assertArrayEquals(expected,renewResult.responses().getFirst());
                var query=f.renewalQuery(raw);var status=f.reconcile(query);assertFalse(status.close());assertEquals(retirementFirst?"NOT_COMMITTED":"GRANTED_HISTORICAL",cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(status.responses().getFirst()).decision());
                assertEquals(retirementFirst?0:1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(retirementFirst?1:2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));assertDoesNotThrow(f::historicalGraph);
            }finally{release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));}
        }
    }
    @Test void actualExpiredRenewalAndDisconnectedHolderWithoutTransferHaveNoDenialMappingAndStayUnknownUntilRealReconnect() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            byte[] raw=f.renewal();var renewal=cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.decodeRenew(raw);f.registration.clock.value=f.jdbc.queryForObject("SELECT lease_until FROM agent_command_v2_execution_lease_fact",Long.class);
            var rejected=f.execution.renewExecution(f.identity,f.socket,raw);assertFalse(rejected.close());assertArrayEquals(cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.rejected("RENEWAL",renewal.renewalRequestId(),renewal.runtimeDispatchId(),renewal.startedReceiptId(),"RENEWAL_NOT_AUTHORIZED"),rejected.responses().getFirst());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));
            var active=f.reconcile(f.renewalQuery(raw));assertFalse(active.close());assertEquals("UNKNOWN",cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(active.responses().getFirst()).decision());
            assertTrue(f.registration.service.disconnect(f.socket,"SOCKET_CLOSED"));var disconnected=f.reconcile(f.renewalQuery(raw));assertFalse(disconnected.close());assertEquals("UNKNOWN",cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(disconnected.responses().getFirst()).decision());
            f.reconnectForAuditRead();var transferred=f.reconcile(f.renewalQuery(raw));assertFalse(transferred.close());assertEquals("NOT_COMMITTED",cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(transferred.responses().getFirst()).decision());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));assertDoesNotThrow(f::historicalGraph);
        }
    }
    @Test void actualRenewalStatusRejectsChangedStartedAndPredecessorBytesWithoutSavingAnObservationOrGrant() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var original=cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.decodeRenew(f.renewal());f.reconnectForAuditRead();
            for(var changed:List.of(
                    new cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.Renew(original.renewalRequestId(),original.runtimeDispatchId(),original.startedReceiptId(),"0".repeat(64),original.previousExecutionGrantId(),original.previousExecutionGrantSha256()),
                    new cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.Renew(original.renewalRequestId(),original.runtimeDispatchId(),original.startedReceiptId(),original.startedReceiptSha256(),original.previousExecutionGrantId(),"0".repeat(64)),
                    new cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.Renew(original.renewalRequestId(),original.runtimeDispatchId(),java.util.UUID.randomUUID().toString(),original.startedReceiptSha256(),original.previousExecutionGrantId(),original.previousExecutionGrantSha256()),
                    new cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.Renew(original.renewalRequestId(),original.runtimeDispatchId(),original.startedReceiptId(),original.startedReceiptSha256(),java.util.UUID.randomUUID().toString(),original.previousExecutionGrantSha256())))assertTrue(f.reconcile(f.renewalQuery(cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.encodeRenew(changed))).close());
            assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_request",Integer.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));assertDoesNotThrow(f::historicalGraph);
        }
    }
    @Test void actualSavedRetirementBarrierRejectsCompleteRowAndCanonicalProofHashTamperingOnHistoricalReplay() throws Exception {
        for(boolean changeRow:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            byte[] raw=f.renewal();f.reconnectForAuditRead();var query=f.renewalQuery(raw);var saved=f.reconcile(query);assertFalse(saved.close());var receipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(saved.responses().getFirst());var result=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)receipt.result();var proof=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.RetirementBarrier)result.proof();
            if(changeRow)f.jdbc.update("UPDATE agent_runtime_v2_binding_generation_fact SET create_time=create_time+1 WHERE binding_generation_fact_id=?",proof.barrierEventId());
            else{
                var forged=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.RetirementBarrier(proof.requestId(),proof.requestSha256(),proof.runtimeDispatchId(),proof.retiredHolderGenerationFactId(),proof.retiredHolderGenerationSnapshotSha256(),proof.currentHolderGenerationFactId(),proof.currentHolderGenerationSnapshotSha256(),proof.barrierEventId(),"0".repeat(64),proof.checkedAt());
                var changed=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Receipt(receipt.messageType(),receipt.requestId(),receipt.runtimeDispatchId(),receipt.receiptId(),receipt.requestSha256(),receipt.decision(),receipt.serverAt(),new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation(result.subjectKind(),result.subjectId(),result.disposition(),result.originalRequestSha256(),forged));byte[] wire=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encodeReceipt(changed);
                f.jdbc.update("UPDATE agent_runtime_v2_effect_request SET response_wire_bytes=?,response_sha256=? WHERE request_id=?",wire,cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(wire),query.requestId());
            }
            assertThrows(SecurityException.class,f::historicalGraph);assertTrue(f.reconcile(query).close());assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_request",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));
        }
    }

    @Test void ordinaryRawTerminalCannotBypassDescribedOrStagedR4Membership() throws Exception {
        for(boolean stage:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            assertFalse(f.reduce(f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null)).close());
            if(stage){String hash=cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(f.file.output));assertFalse(f.reduce(f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_STAGE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.STAGE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileStage("exec-a","task-a","item-a","run-a","output_1",hash,f.file.output.length,0,f.file.output.length),f.file.output)).close());}
            for(String status:List.of("SUCCEEDED","FAILED"))assertTrue(f.execution.acknowledge(f.identity,f.socket,f.terminal(status).originalRequest()).close());
            assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_lease_fact",Integer.class));assertEquals("STARTED",f.jdbc.queryForObject("SELECT status FROM agent_command_delivery",String.class));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT disposition FROM agent_runtime_v2_effect_run",String.class));AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void actualCompleteFileSettlementUsesOriginalSuccessesAtOrdinaryEqualityAndExpiredTime() throws Exception {
        for(int timeCase=0;timeCase<3;timeCase++)try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var commit=f.completeFile();byte[] first=(byte[])f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_id=?",commit.effectId()).get("result_wire_bytes");String holder=f.jdbc.queryForObject("SELECT holder_generation_fact_id FROM agent_command_v2_execution_lease_fact",String.class);long until=f.jdbc.queryForObject("SELECT lease_until FROM agent_command_v2_execution_lease_fact",Long.class);
            if(timeCase>0)f.registration.clock.value=until+(timeCase==2?1:0);long terminalAt=f.registration.clock.value;var terminal=f.terminal("SUCCEEDED");clearInvocations(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);var result=f.reconcile(terminal);assertFalse(result.close(),"actual complete file terminal case "+timeCase);
            var receipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst());var reconciliation=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)receipt.result();assertEquals("SETTLED_KNOWN",reconciliation.disposition());var proof=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof)reconciliation.proof();assertEquals(3,proof.effectCount());var graph=f.historicalGraph();var references=graph.effects().stream().map(effect->cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccessReference(graph,effect)).toList();assertEquals(java.util.Set.of("DESCRIBED","STAGED","COMMITTED"),references.stream().map(value->value.decision()).collect(java.util.stream.Collectors.toSet()));assertEquals(proof.effectGraphSha256(),cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.completeGraphSha256(graph,references));
            assertEquals(terminalAt,receipt.serverAt());assertEquals(terminalAt,cn.jia.agent.entity.AgentRuntimeV2WireCodec.decodeReceipt(proof.settlementReceipt()).committedAt());assertArrayEquals(first,(byte[])f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_id=?",commit.effectId()).get("result_wire_bytes"));assertEquals(holder,f.jdbc.queryForObject("SELECT holder_generation_fact_id FROM agent_command_v2_execution_lease_fact WHERE fact_type='SETTLED'",String.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SERVER_COMMIT'",Integer.class));assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Integer.class));assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_grant",Integer.class));
            AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertEquals("SETTLED_KNOWN",f.historicalGraph().run().get("disposition"));assertArrayEquals(result.responses().getFirst(),f.reconcile(terminal).responses().getFirst());
            f.registration.clock.value+=10;var replay=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconcile(java.util.UUID.randomUUID().toString(),terminal.runtimeDispatchId(),terminal.subjectKind(),terminal.subjectId(),terminal.originalRequest(),null);var replayResult=f.reconcile(replay);assertFalse(replayResult.close());var replayReceipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(replayResult.responses().getFirst());assertEquals(terminalAt+10,replayReceipt.serverAt());assertArrayEquals(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encodeProof(proof),cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encodeProof(((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)replayReceipt.result()).proof()));
            f.reconnectForAuditRead();
            for(var phase:List.of(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.COMMIT,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.OBSERVE)){var q=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT,phase,f.file.commitRequest(),null);var observed=f.reduce(q);assertFalse(observed.close());assertEquals(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(first).result(),cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(observed.responses().getFirst()).result());var actor=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect_request WHERE request_id=?",q.grant().requestId());assertEquals(f.socket.bindingGeneration(),((Number)actor.get("actor_binding_generation")).longValue());assertEquals(f.socket.sessionFence(),actor.get("actor_session_fence"));}
            verifyNoInteractions(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);assertEquals(1,f.file.count("agent_task_formal_delivery"));assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect",Integer.class));AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }
    @Test void actualServerSelectedFileDescriptorIsCompleteMemberAtLiveEqualityAndExpiredSettlement() throws Exception {
        for(int timing=0;timing<3;timing++)try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var commit=f.completeFileViaServerSelectedDescriptor();var original=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_kind='WORKSPACE_PREPARE'");
            assertEquals("DESCRIBED",original.get("disposition"));String effectId=(String)original.get("effect_id");
            var graph=f.historicalGraph();byte[] description=cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(graph,graph.effect(effectId));
            assertEquals("WORKSPACE_FILE",((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.DescribedResult)cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(description).result()).branch());
            long until=f.jdbc.queryForObject("SELECT lease_until FROM agent_command_v2_execution_lease_fact",Long.class);if(timing>0)f.registration.clock.value=until+(timing==2?1:0);
            var terminal=f.terminal("SUCCEEDED");var response=f.reconcile(terminal);assertFalse(response.close());
            var result=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(response.responses().getFirst()).result();assertEquals("SETTLED_KNOWN",result.disposition());assertEquals(3,((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof)result.proof()).effectCount());
            assertEquals("SETTLED_KNOWN",f.historicalGraph().effect(effectId).get("disposition"));assertArrayEquals(description,cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccess(f.historicalGraph(),f.historicalGraph().effect(effectId)));
            AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertArrayEquals(response.responses().getFirst(),f.reconcile(terminal).responses().getFirst());
            assertEquals(1,f.file.count("agent_task_formal_delivery"));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SERVER_COMMIT'",Integer.class));assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Integer.class));
            assertNotNull(f.historicalGraph().effect(commit.effectId()));assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }
    // Synthetic typed kernel projection ONLY for server graph negatives and
    // resource reducers. Actual native/process acceptance is in paired Runtime.
    private static AgentRuntimeV2EffectCodec.ProcessDrainObservation serverOnlyDrain(AgentRuntimeV2R4ProductionFixture.CompositeFile f){
        long now=f.registration.clock.value;var closed=new AgentRuntimeV2EffectCodec.CloseProjection(0,null,now);
        var proof=new AgentRuntimeV2EffectCodec.DrainProjection(f.runToken,"DRAINED_KNOWN",true,closed,closed,List.of(new AgentRuntimeV2EffectCodec.NamespaceProjection("pid:[200]",2,true,102L,302L,true,true)),List.of(new AgentRuntimeV2EffectCodec.ProcessProjection(101,301,"pid:[100]",true,true),new AgentRuntimeV2EffectCodec.ProcessProjection(102,302,"pid:[200]",true,true)),List.of(),closed);
        return new AgentRuntimeV2EffectCodec.ProcessDrainObservation(f.runToken,proof,now);
    }
    private static AgentRuntimeV2EffectCodec.ProcessStart fileProcess(AgentRuntimeV2R4ProductionFixture.CompositeFile f){return new AgentRuntimeV2EffectCodec.ProcessStart("task-a","item-a","legacy-codex",cn.jia.agent.service.impl.AgentRuntimeV2EffectServiceImpl.workspaceKey(f.origin()));}
    private static void describeFile(AgentRuntimeV2R4ProductionFixture.CompositeFile f){assertFalse(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE,AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new AgentRuntimeV2EffectCodec.WorkspacePrepare("task-a","item-a",fileProcess(f).workspaceKey(),null),null)).close());}
    @Test void strictFileOnlyAdmitsTypedLifecycleAfterImmutableSelectionAndNeverOrdinaryWorkspace() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            assertTrue(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null)).close());describeFile(f);int count=f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_request",Integer.class);
            for(var resource:List.<AgentRuntimeV2EffectCodec.Resource>of(new AgentRuntimeV2EffectCodec.WorkspacePrepare("task-a","item-a",fileProcess(f).workspaceKey(),"0".repeat(64)),new AgentRuntimeV2EffectCodec.WorkspaceMutate("task-a","item-a",fileProcess(f).workspaceKey(),"0".repeat(64),"1".repeat(64)))){var kind=resource instanceof AgentRuntimeV2EffectCodec.WorkspacePrepare?AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE:AgentRuntimeV2EffectCodec.Kind.WORKSPACE_MUTATE;assertTrue(f.reduce(f.request(kind,AgentRuntimeV2EffectCodec.Phase.ADMIT,resource,null)).close());}
            assertEquals(count,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_request",Integer.class));var start=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null);var admitted=f.reduce(start);assertFalse(admitted.close());assertEquals("ADMITTED",AgentRuntimeV2EffectCodec.decodeReceipt(admitted.responses().getFirst()).decision());
            assertNull(cn.jia.agent.service.impl.AgentRuntimeV2HistoricalSettlementEvidenceValidator.originalSuccessReference(f.historicalGraph(),f.historicalGraph().effect(start.effectId())));assertTrue(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.OBSERVE,fileProcess(f),null)).close());
            assertEquals(0,f.file.count("agent_task_formal_delivery"));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));
        }
    }
    @Test void completeFileLifecycleRetainsAdmissionUntilExactMatchingExitAndObservedStart() throws Exception {
        for(boolean observeStart:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            describeFile(f);var start=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null);var admitted=f.reduce(start);assertFalse(admitted.close());byte[] original=admitted.responses().getFirst();
            // A later expired ticket does not erase a valid actual start. This
            // server-only typed vector is not a parent startup-gate proof.
            f.registration.clock.value+=1001;var observation=serverOnlyDrain(f);var exitResource=new AgentRuntimeV2EffectCodec.ProcessExit("task-a","item-a","legacy-codex",fileProcess(f).workspaceKey(),101,301,0,null);var exit=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE,exitResource,AgentRuntimeV2EffectCodec.encodeEvidence(observation));var observed=f.reduce(exit);assertFalse(observed.close());assertEquals("OBSERVED",AgentRuntimeV2EffectCodec.decodeReceipt(observed.responses().getFirst()).decision());
            if(observeStart){var result=f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.OBSERVE,fileProcess(f),null));assertFalse(result.close());assertEquals("OBSERVED",AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst()).decision());}
            f.completeFileViaServerSelectedDescriptor();var query=f.terminal("SUCCEEDED");var result=f.reconcile(query);assertFalse(result.close());var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst());assertEquals(observeStart?"SETTLED_KNOWN":"COMMITTED_PROVEN",receipt.decision());
            assertArrayEquals(original,(byte[])f.jdbc.queryForMap("SELECT response_wire_bytes FROM agent_runtime_v2_effect_request WHERE request_id=?",start.grant().requestId()).get("response_wire_bytes"));assertEquals(observeStart?3:2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));
            if(observeStart){assertEquals(5,((AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof)((AgentRuntimeV2EffectCodec.Reconciliation)receipt.result()).proof()).effectCount());assertEquals(5,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Integer.class));AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertArrayEquals(result.responses().getFirst(),f.reconcile(query).responses().getFirst());}
        }
    }
    @Test void vanishedFileSelectionCannotFallbackToOrdinaryUnknownOrNewAdmission() throws Exception {
        for(boolean admitted:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            describeFile(f);if(admitted)assertFalse(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null)).close());
            assertEquals(1,f.jdbc.update("DELETE FROM agent_personal_workspace_execution WHERE execution_id='exec-a'"));var before=f.registration.snapshot();
            var phase=admitted?AgentRuntimeV2EffectCodec.Phase.OBSERVE:AgentRuntimeV2EffectCodec.Phase.ADMIT;
            assertTrue(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,phase,fileProcess(f),null)).close());assertEquals(before,f.registration.snapshot(),"original FILE selection cannot be silently downgraded");
            assertEquals(0,f.file.count("agent_task_formal_delivery"));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));
        }
    }
    @Test void ordinaryWorkItemWithoutFileAssociationKeepsUnknownAndExactReadOnlyReplay() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            assertEquals(1,f.jdbc.update("DELETE FROM agent_personal_workspace_execution WHERE execution_id='exec-a'"));
            var described=f.request(AgentRuntimeV2EffectCodec.Kind.WORKSPACE_PREPARE,AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new AgentRuntimeV2EffectCodec.WorkspacePrepare("task-a","item-a",fileProcess(f).workspaceKey(),null),null);var description=f.reduce(described);assertFalse(description.close());assertEquals("CODEX",((AgentRuntimeV2EffectCodec.DescribedResult)AgentRuntimeV2EffectCodec.decodeReceipt(description.responses().getFirst()).result()).branch());
            var start=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null);assertFalse(f.reduce(start).close());
            var observe=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.OBSERVE,fileProcess(f),null);var result=f.reduce(observe);assertFalse(result.close());byte[] saved=result.responses().getFirst();var receipt=AgentRuntimeV2EffectCodec.decodeReceipt(saved);assertEquals("UNKNOWN",receipt.decision());assertEquals("EXTERNAL_UNKNOWN",((AgentRuntimeV2EffectCodec.ObservedResult)receipt.result()).category());
            var before=f.registration.snapshot();assertArrayEquals(saved,f.reduce(observe).responses().getFirst());assertEquals(before,f.registration.snapshot());assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));assertEquals(0,f.file.count("agent_task_formal_delivery"));
        }
    }
    @Test void typedProcessExitRejectsMissingAdmissionCopiedScopeTokenAndExitTuple() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            describeFile(f);var observation=serverOnlyDrain(f);var resource=new AgentRuntimeV2EffectCodec.ProcessExit("task-a","item-a","legacy-codex",fileProcess(f).workspaceKey(),101,301,0,null);var exit=f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE,resource,AgentRuntimeV2EffectCodec.encodeEvidence(observation));assertTrue(f.reduce(exit).close());
            assertFalse(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_START,AgentRuntimeV2EffectCodec.Phase.ADMIT,fileProcess(f),null)).close());
            var copied=new AgentRuntimeV2EffectCodec.ProcessExit("task-a","another-item","legacy-codex",fileProcess(f).workspaceKey(),101,301,0,null);assertTrue(f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE,copied,AgentRuntimeV2EffectCodec.encodeEvidence(observation))).close());
            var wrongPid=new AgentRuntimeV2EffectCodec.ProcessExit("task-a","item-a","legacy-codex",fileProcess(f).workspaceKey(),101,999,0,null);assertThrows(IllegalArgumentException.class,()->f.reduce(f.request(AgentRuntimeV2EffectCodec.Kind.PROCESS_EXIT,AgentRuntimeV2EffectCodec.Phase.OBSERVE,wrongPid,AgentRuntimeV2EffectCodec.encodeEvidence(observation))));
            var copiedToken=new AgentRuntimeV2EffectCodec.EffectRequest(exit.grant(),java.util.UUID.randomUUID().toString(),exit.effectId(),exit.effectKind(),exit.phase(),exit.resource(),exit.content());assertThrows(IllegalArgumentException.class,()->f.reduce(copiedToken));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));assertEquals(0,f.file.count("agent_task_formal_delivery"));
        }
    }
    @Test void laterRealFileTrashKeepsImmutableTerminalStartupValidButCurrentAclIsSeparate() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            f.completeFile();var terminal=f.terminal("SUCCEEDED");var settled=f.reconcile(terminal);assertFalse(settled.close());var file=f.jdbc.queryForMap("SELECT * FROM agent_personal_workspace_file");String id=(String)file.get("file_id");long revision=((Number)file.get("metadata_revision")).longValue();var scope=new cn.jia.agent.service.impl.PersonalWorkspaceWriteService.Scope("0","caller-C","owner-a");
            var claim=f.file.tx.execute(status->f.file.writes.claim(scope,"TRASH","r4-real-trash","b".repeat(64)));assertNotNull(claim);var trashed=f.file.tx.execute(status->f.file.writes.trash(scope,claim.operation(),id,revision,true,"\""+id+":"+revision+"\""));assertEquals("TRASHED",trashed.getState());assertEquals(revision+1,trashed.getMetadataRevision());
            AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertEquals("SETTLED_KNOWN",f.historicalGraph().run().get("disposition"));assertArrayEquals(settled.responses().getFirst(),f.reconcile(terminal).responses().getFirst());
            assertThrows(SecurityException.class,()->f.file.tx.execute(status->{f.effectEvidence.verifyProspectiveCompleteFile(f.origin(),f.rows(),f.historicalGraph());return null;}));
            f.jdbc.update("UPDATE oauth_client_tenant_grant SET status='REVOKED' WHERE client_id='caller-C'");assertTrue(f.reconcile(terminal).close());AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertEquals(3,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));
        }
    }
    @Test void advancingEveryMillisClockKeepsTerminalOuterReceiptAndClosuresAtSingleTruthfulSample() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile(true)){
            f.completeFile();long before=f.effectClockCalls.get();var result=f.reconcile(f.terminal("SUCCEEDED"));assertFalse(result.close());assertTrue(f.effectClockCalls.get()>before+1,"actual same reduce called the advancing Clock repeatedly");
            var receipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst());var proof=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.SettlementProof)((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)receipt.result()).proof();long committed=cn.jia.agent.entity.AgentRuntimeV2WireCodec.decodeReceipt(proof.settlementReceipt()).committedAt();assertEquals(committed,receipt.serverAt());assertTrue(f.registration.clock.value>committed);assertEquals(java.util.Set.of(committed),new java.util.HashSet<>(f.jdbc.queryForList("SELECT created_at FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Long.class)));AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void actual129UniqueMembersMaxReadAndFullTwoChunkOutputCloseCompactAndReplayWithoutStorage() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            byte[] input=f.file.pinnedInput();assertFalse(f.reduce(f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null)).close());
            cn.jia.agent.entity.AgentRuntimeV2EffectCodec.EffectRequest maxRead=null;byte[] maxResponse=null;
            for(int i=0;i<125;i++){
                var read=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_INPUT_READ,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.READ,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileRead("exec-a","task-a","item-a","run-a","input_1",i==0?0:i-1,i==0?65536:1),null);var response=f.reduce(read);assertFalse(response.close(),"real read "+i);
                if(i==0){maxRead=read;maxResponse=response.responses().getFirst();assertTrue(maxResponse.length>98304);assertArrayEquals(input,((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.ReadResult)cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(maxResponse).result()).content());}
                // Do not retain every complete graph argument in Mockito;
                // SQL, full durable history and all real validators stay intact.
                clearInvocations(f.executionEvidence,f.effectEvidence);
            }
            String hash=cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(f.file.output));int split=f.file.output.length/2;
            for(int offset:new int[]{0,split}){int length=offset==0?split:f.file.output.length-split;var stage=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_STAGE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.STAGE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileStage("exec-a","task-a","item-a","run-a","output_1",hash,f.file.output.length,offset,length),java.util.Arrays.copyOfRange(f.file.output,offset,offset+length));assertFalse(f.reduce(stage).close());}
            var commit=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.COMMIT,f.file.commitRequest(),null);assertFalse(f.reduce(commit).close());assertEquals(129,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect",Integer.class));f.registration.clock.value=Math.max(f.registration.clock.value+1,System.currentTimeMillis()+1);
            clearInvocations(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);var terminal=f.terminal("SUCCEEDED");long terminalStarted=System.nanoTime();var result=f.reconcile(terminal);long terminalElapsed=System.nanoTime()-terminalStarted;System.out.println("R4_ACTUAL_129_TERMINAL elapsedNs="+terminalElapsed+" close="+result.close()+" responses="+result.responses().size());assertFalse(result.close(),"Actual 129-member terminal reconciliation closed");assertTrue(terminalElapsed<7000000000L,"actual terminal retains the original strict 7s bound");var proof=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.ExecutionSettlementGraphProof)((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst()).result()).proof();assertEquals(129,proof.effectCount());assertTrue(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encodeProof(proof).length<65536);assertEquals(129,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Integer.class));assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_blob",Integer.class));
            var replay=f.reducer.effect(f.identity,null,new cn.jia.agent.service.AgentRuntimeV2EffectService.TrustedRoute("r4-audit-fence","r4-audit-owner",0),cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encode(maxRead));assertFalse(replay.close());assertArrayEquals(maxResponse,replay.responses().getFirst());assertArrayEquals(result.responses().getFirst(),f.reconcile(terminal).responses().getFirst());verifyNoInteractions(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void serverOriginalSuccessReferenceUsesEventSequenceForBothQ1Q2Counterexamples() throws Exception {
        for(boolean firstArrives:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var q1=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null);var q2=f.request(q1.effectKind(),q1.phase(),q1.resource(),null);assertEquals(q1.effectId(),q2.effectId());var selected=firstArrives?q1:q2;
            var success=f.reduce(selected);assertFalse(success.close());assertFalse(f.reduce(firstArrives?q2:q1).close());
            var lookup=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconcile(java.util.UUID.randomUUID().toString(),q1.grant().runtimeDispatchId(),cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Subject.EFFECT_SUCCESS,q1.effectId(),cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encode(q2),null);var result=f.reconcile(lookup);assertFalse(result.close());var reference=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.EffectSuccessReference)((cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Reconciliation)cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst()).result()).proof();assertEquals(selected.grant().requestId(),reference.originalRequestId());assertEquals(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(success.responses().getFirst())),reference.originalResponseSha256());assertEquals(selected.grant().executionGrantSha256(),reference.executionGrantSha256());assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect",Integer.class));assertEquals(4,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event",Integer.class));AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void unregisteredExactOriginalMaxReadRecoveryHasZeroWritesAndNeverFallsThroughForChangedOrUnseenProducer() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            f.file.pinnedInput();assertFalse(f.reduce(f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.DESCRIBE,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null)).close());
            var q=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_INPUT_READ,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.READ,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileRead("exec-a","task-a","item-a","run-a","input_1",0,65536),null);byte[] raw=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encode(q),response=f.reduce(q).responses().getFirst();assertTrue(response.length>98304);assertTrue(response.length<=131072);
            f.registration.clock.value+=31000;var route=new cn.jia.agent.service.AgentRuntimeV2EffectService.TrustedRoute("r4-audit-fence","r4-audit-owner",0);clearInvocations(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);var before=f.registration.snapshot();int writes=f.registration.jdbc.writeCalls;long sqlWrites=f.sqlMutationCalls.get();assertTrue(sqlWrites>0,"SQL mutation instrumentation observed actual fixture and producer writes");
            var recovered=f.reducer.effect(f.identity,null,route,raw);assertFalse(recovered.close());assertArrayEquals(response,recovered.responses().getFirst());assertTrue(f.reducer.current(f.identity,null,route,response));assertEquals(before,f.registration.snapshot());assertEquals(writes,f.registration.jdbc.writeCalls);assertEquals(sqlWrites,f.sqlMutationCalls.get());
            var g=q.grant();
            for(String fault:List.of("request","dispatch","grant","run","resource","unseen")){
                var grant=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.GrantReference(fault.equals("request")||fault.equals("unseen")?java.util.UUID.randomUUID().toString():g.requestId(),fault.equals("dispatch")?java.util.UUID.randomUUID().toString():g.runtimeDispatchId(),g.startedReceiptId(),g.startedReceiptSha256(),g.executionGrantId(),fault.equals("grant")?"b".repeat(64):g.executionGrantSha256());
                var resource=fault.equals("resource")?new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileRead("exec-a","task-a","item-a","run-a","input_1",1,65535):q.resource();
                var changed=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.EffectRequest(grant,fault.equals("run")?java.util.UUID.randomUUID().toString():q.runToken(),fault.equals("unseen")?"eff_"+"c".repeat(64):q.effectId(),q.effectKind(),q.phase(),resource,null);
                assertTrue(f.reducer.effect(f.identity,null,route,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encode(changed)).close(),fault);assertEquals(before,f.registration.snapshot());assertEquals(writes,f.registration.jdbc.writeCalls);assertEquals(sqlWrites,f.sqlMutationCalls.get());
            }
            assertTrue(f.reducer.inspect(f.identity,null,route,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encode(new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Inspect(g))).close());assertEquals(before,f.registration.snapshot());assertEquals(writes,f.registration.jdbc.writeCalls);assertEquals(sqlWrites,f.sqlMutationCalls.get());verifyNoInteractions(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);
            f.jdbc.update("UPDATE core_deployment_tenant_grant SET allow_read=0 WHERE deployment_id='local-api-a'");before=f.registration.snapshot();writes=f.registration.jdbc.writeCalls;sqlWrites=f.sqlMutationCalls.get();assertTrue(f.reducer.effect(f.identity,null,route,raw).close());assertFalse(f.reducer.current(f.identity,null,route,response));assertEquals(before,f.registration.snapshot());assertEquals(writes,f.registration.jdbc.writeCalls);assertEquals(sqlWrites,f.sqlMutationCalls.get());
            f.jdbc.update("UPDATE core_deployment_tenant_grant SET allow_read=1 WHERE deployment_id='local-api-a'");f.jdbc.update("UPDATE oauth_api_key SET status=0");before=f.registration.snapshot();writes=f.registration.jdbc.writeCalls;sqlWrites=f.sqlMutationCalls.get();assertTrue(f.reducer.effect(f.identity,null,route,raw).close());assertFalse(f.reducer.current(f.identity,null,route,response));assertEquals(before,f.registration.snapshot());assertEquals(writes,f.registration.jdbc.writeCalls);assertEquals(sqlWrites,f.sqlMutationCalls.get());AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void completeFileSettlementRollbackAndBeforeCommitResourceRevocationLeaveNoTerminal() throws Exception {
        for(boolean revoke:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var commit=f.completeFile();byte[] first=(byte[])f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_id=?",commit.effectId()).get("result_wire_bytes");
            var reached=new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(invocation->{var result=invocation.callRealMethod();TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){reached.set(true);if(revoke)f.jdbc.update("UPDATE agent_task_member SET member_status='left'");else throw new IllegalStateException("actual terminal original transaction rollback");}});return result;}).when(f.file.workspace).v2LockKnownOutcome(any());
            assertTrue(f.reconcile(f.terminal("SUCCEEDED")).close());assertTrue(reached.get(),"injected real original beforeCommit was reached");assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_ack_receipt",Integer.class));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_v2_execution_lease_fact",Integer.class));assertEquals(0,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SETTLED_KNOWN'",Integer.class));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT disposition FROM agent_runtime_v2_effect_run",String.class));assertEquals("working",f.jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));assertArrayEquals(first,(byte[])f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_id=?",commit.effectId()).get("result_wire_bytes"));assertEquals(1,f.file.count("agent_task_formal_delivery"));assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            doCallRealMethod().when(f.file.workspace).v2LockKnownOutcome(any());assertFalse(f.reconcile(f.terminal("SUCCEEDED")).close());AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
        }
    }
    @Test void originalFormalDeliveryDigestArtifactScopeAndStageBytesAreRequiredBySharedStartup() throws Exception {
        for(String corruption:List.of("digest","artifact-uri","stage","missing-effect"))try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            f.completeFile();assertFalse(f.reconcile(f.terminal("SUCCEEDED")).close());AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);
            switch(corruption){case "digest"->f.jdbc.update("UPDATE agent_task_formal_delivery SET submission_digest=?","d".repeat(64));case "artifact-uri"->f.jdbc.update("UPDATE agent_task_artifact SET storage_uri=CONCAT('cyf-artifact://',?,'/',content_hash)","e".repeat(64));case "stage"->{byte[] changed=f.jdbc.queryForObject("SELECT chunk_bytes FROM agent_runtime_v2_effect_blob",byte[].class).clone();changed[0]^=1;f.jdbc.update("UPDATE agent_runtime_v2_effect_blob SET chunk_bytes=?",changed);}case "missing-effect"->f.jdbc.update("DELETE FROM agent_runtime_v2_effect WHERE effect_kind='FILE_DESCRIBE'");}
            assertThrows(RuntimeException.class,()->AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc),corruption);assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }

    @Test void actualCompositeFileCommitDroppedExactDistinctAndObserveReplayPreserveFirstReceipt() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.CompositeFile()){
            var kind=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_DESCRIBE;var phase=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.DESCRIBE;
            var describe=f.request(kind,phase,new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileDescribe("task-a","item-a"),null);assertFalse(f.reduce(describe).close());
            String hash=cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(f.file.output));
            var stageResource=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.FileStage("exec-a","task-a","item-a","run-a","output_1",hash,f.file.output.length,0,f.file.output.length);var stage=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_STAGE,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.STAGE,stageResource,f.file.output);assertFalse(f.reduce(stage).close());
            var commit=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.COMMIT,f.file.commitRequest(),null);var actual=f.reduce(commit);assertFalse(actual.close());var receipt=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(actual.responses().getFirst());assertEquals("COMMITTED",receipt.decision());var committed=(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.CommittedResult)receipt.result();
            byte[] first=actual.responses().getFirst();int businessEvents=f.file.count("agent_task_event");clearInvocations(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);
            var exact=f.reduce(commit);assertFalse(exact.close());assertArrayEquals(first,exact.responses().getFirst());
            for(var replayPhase:List.of(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.COMMIT,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.OBSERVE)){
                var replay=f.request(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.FILE_COMMIT,replayPhase,f.file.commitRequest(),null);assertEquals(commit.effectId(),replay.effectId());var result=f.reduce(replay);assertFalse(result.close());assertEquals(committed,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeReceipt(result.responses().getFirst()).result());
            }
            verifyNoInteractions(f.file.storage,f.file.artifactStorage,f.file.writes,f.file.artifacts,f.file.deliveries);assertEquals(1,f.file.count("agent_personal_workspace_file"));assertEquals(2,f.file.count("agent_task_artifact"));assertEquals(1,f.file.count("agent_task_formal_delivery"));assertEquals(businessEvents,f.file.count("agent_task_event"));
            var effect=f.jdbc.queryForMap("SELECT * FROM agent_runtime_v2_effect WHERE effect_id=?",commit.effectId());assertArrayEquals(first,(byte[])effect.get("result_wire_bytes"));assertEquals(committed.commitReceiptId(),effect.get("commit_receipt_id"));assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM agent_runtime_v2_effect_event WHERE event_kind='SERVER_COMMIT'",Integer.class));
            AgentRuntimeV2DispatchSchemaGate.validateGraph(f.jdbc);AgentRuntimeV2ExecutionSchemaGate.validateGraph(f.jdbc);assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }

    @Test void actualFileDescriptorStageArchiveArtifactFormalDeliveryAndDroppedResponseReplay() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var described=f.tx.execute(status->f.workspace.v2Describe(f.scope));assertNotNull(described);assertEquals("WORKSPACE_FILE",described.branch());assertEquals("exec-a",described.executionId());assertEquals("item-a",described.workItemId());
            var prepared=f.workspace.v2PrepareOutput(f.scope,f.output);assertEquals(f.output.length,prepared.length());
            var staged=f.tx.execute(status->f.workspace.v2Stage(f.scope,"output_1",prepared));assertEquals("STAGED",staged.state());assertEquals(0,f.count("agent_personal_workspace_file"));assertEquals(0,f.count("agent_task_artifact"));
            var request=f.commitRequest();var commitBytes=f.workspace.v2PrepareCommit(f.scope,"output_1");
            var committed=f.tx.execute(status->f.workspace.v2Commit(f.scope,request,commitBytes));assertNotNull(committed);assertEquals("exec-a",committed.executionId());assertEquals(request.manifestId(),committed.manifestId());assertEquals(request.manifestSha256(),committed.manifestSha256());assertEquals(1,committed.items().size());
            assertEquals(1,f.count("agent_personal_workspace_file"));assertEquals(1,f.count("agent_personal_workspace_file_version"));assertEquals(2,f.count("agent_task_artifact"));assertEquals(1,f.count("agent_task_formal_delivery"));assertEquals(2,f.count("agent_task_formal_delivery_item"));
            assertEquals("OUTPUT_COMMITTED",f.jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution",String.class));assertEquals("PUBLISHED",f.jdbc.queryForObject("SELECT publication_state FROM agent_personal_workspace_execution_output",String.class));assertEquals("submitted",f.jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));assertEquals("reviewing",f.jdbc.queryForObject("SELECT reward_status FROM agent_task_meta",String.class));
            var file=f.jdbc.queryForMap("SELECT * FROM agent_personal_workspace_file_version");assertEquals(committed.items().getFirst().fileId(),file.get("file_id"));assertEquals(committed.items().getFirst().sha256(),file.get("content_hash"));assertArrayEquals(f.output,f.storage.read(new cn.jia.agent.service.PersonalWorkspaceStorage.Scope("0","caller-C","owner-a"),(String)file.get("storage_uri"),(String)file.get("content_hash"),((Number)file.get("byte_length")).longValue(),(String)file.get("content_mime_type")).content());
            int events=f.count("agent_task_event");clearInvocations(f.storage,f.artifactStorage,f.writes,f.artifacts,f.deliveries);
            // Treat the committed response as dropped. Reuse the original
            // pinned resource input: retry cannot re-read bytes or republish.
            assertEquals(committed,f.tx.execute(status->f.workspace.v2Commit(f.scope,request,commitBytes)));assertEquals(events,f.count("agent_task_event"));verifyNoInteractions(f.storage,f.artifactStorage,f.writes,f.artifacts,f.deliveries);
            assertEquals(1,f.count("agent_personal_workspace_file"));assertEquals(2,f.count("agent_task_artifact"));assertEquals(1,f.count("agent_task_formal_delivery"));assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }

    @Test void artifactPreparationChecksExactOwnerMemberAndWorkItemBeforeAnyStore() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var command=f.artifactCommand();
            for(String[] scope:List.of(new String[]{"0","caller-C","wrong-owner","task-a",AgentRuntimeV2R4ProductionFixture.FileResource.AGENT},new String[]{"0","caller-C","owner-a","missing-task",AgentRuntimeV2R4ProductionFixture.FileResource.AGENT},new String[]{"0","caller-C","owner-a","task-a","agt_00000000000000000000000000000002"}))assertThrows(RuntimeException.class,()->f.artifacts.prepareManaged(scope[0],scope[1],scope[2],scope[3],scope[4],command));
            command.setWorkItemId("missing-item");assertThrows(RuntimeException.class,()->f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",AgentRuntimeV2R4ProductionFixture.FileResource.AGENT,command));
            verifyNoInteractions(f.artifactStorage);assertEquals(0,f.count("agent_task_artifact"));
        }
    }
    @Test void artifactPreparationCannotBorrowActiveSynchronizationOrRetainedResources() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var command=f.artifactCommand();var actor=AgentRuntimeV2R4ProductionFixture.FileResource.AGENT;
            assertThrows(IllegalStateException.class,()->f.tx.execute(status->f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,command)));
            TransactionSynchronizationManager.initSynchronization();try{assertThrows(IllegalStateException.class,()->f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,command));}finally{TransactionSynchronizationManager.clearSynchronization();}
            Object key=new Object();TransactionSynchronizationManager.bindResource(key,new Object());try{assertThrows(IllegalStateException.class,()->f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,command));}finally{TransactionSynchronizationManager.unbindResource(key);}
            verifyNoInteractions(f.artifactStorage);assertEquals(0,f.count("agent_task_artifact"));
        }
    }
    @Test void preparedArtifactRejectsForgedForeignCrossScopeAndChangedCommandsWithoutStorage() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource();var foreign=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var actor=AgentRuntimeV2R4ProductionFixture.FileResource.AGENT;var original=f.artifactCommand();var prepared=f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,original);var another=foreign.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,foreign.artifactCommand());clearInvocations(f.artifactStorage);
            for(var handle:List.of(new cn.jia.agent.service.AgentTaskArtifactService.PreparedArtifact(){},another))assertThrows(RuntimeException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,original,handle)));
            assertThrows(RuntimeException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","other-owner","task-a",actor,original,prepared)));
            for(int mutation=0;mutation<7;mutation++){var command=f.artifactCommand();switch(mutation){case 0->command.setTitle("changed");case 1->command.setWorkItemId("changed");case 2->command.setContentBytes(new byte[]{1,2});case 3->command.setMetadata(java.util.Map.of("unexpected","value"));case 4->command.setArtifactId("pwe_art_changed");case 5->command.setVisibility("private");case 6->command.setContentMimeType("application/json");}assertThrows(RuntimeException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared)));}
            verify(f.artifactStorage,never()).store(any(),any(),any());verify(f.artifactStorage,never()).read(any(),any(),any(),anyLong(),any());assertEquals(0,f.count("agent_task_artifact"));assertEquals(0,f.count("agent_task_event"));
        }
    }
    @Test void preparedArtifactCopiesNestedMetadataAndRechecksActualBeforeCommitMembership() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var actor=AgentRuntimeV2R4ProductionFixture.FileResource.AGENT;var command=f.artifactCommand();var nested=new java.util.LinkedHashMap<String,Object>();nested.put("value","original");command.setMetadata(java.util.Map.of("nested",nested));var prepared=f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,command);nested.put("value","mutated");
            assertThrows(RuntimeException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared)));
            command.setMetadata(java.util.Map.of("nested",java.util.Map.of("value","original")));clearInvocations(f.artifactStorage);
            assertThrows(RuntimeException.class,()->f.tx.execute(status->{f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){f.jdbc.update("UPDATE agent_task_member SET member_status='revoked'");}});return null;}));
            assertEquals(0,f.count("agent_task_artifact"));assertEquals(0,f.count("agent_task_event"));assertEquals("working",f.jdbc.queryForObject("SELECT member_status FROM agent_task_member",String.class));verify(f.artifactStorage,never()).store(any(),any(),any());
            assertNotNull(f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared)));assertEquals(1,f.count("agent_task_artifact"));
            assertThrows(RuntimeException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared)));assertEquals(1,f.count("agent_task_artifact"));
        }
    }
    @Test void preparedArtifactRequiresOriginalWritableJdbcHolderAtEntryAndBeforeCommit() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var actor=AgentRuntimeV2R4ProductionFixture.FileResource.AGENT;var command=f.artifactCommand();var prepared=f.artifacts.prepareManaged("0","caller-C","owner-a","task-a",actor,command);clearInvocations(f.artifactStorage);
            assertThrows(IllegalStateException.class,()->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared));
            f.tx.setReadOnly(true);try{assertThrows(IllegalStateException.class,()->f.tx.execute(status->f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared)));}finally{f.tx.setReadOnly(false);}
            for(boolean replacement:List.of(false,true))assertThrows(IllegalStateException.class,()->f.tx.execute(status->{var binding=TransactionSynchronizationManager.getResourceMap().entrySet().stream().filter(entry->entry.getValue() instanceof org.springframework.jdbc.datasource.ConnectionHolder).findFirst().orElseThrow();var holder=(org.springframework.jdbc.datasource.ConnectionHolder)binding.getValue();Object extra=new Object();if(replacement)TransactionSynchronizationManager.unbindResource(binding.getKey());TransactionSynchronizationManager.bindResource(replacement?binding.getKey():extra,new org.springframework.jdbc.datasource.ConnectionHolder(holder.getConnection()));try{return f.artifacts.publishPrepared("0","caller-C","owner-a","task-a",actor,command,prepared);}finally{TransactionSynchronizationManager.unbindResource(replacement?binding.getKey():extra);if(replacement)TransactionSynchronizationManager.bindResource(binding.getKey(),holder);}}));
            assertEquals(0,f.count("agent_task_artifact"));verify(f.artifactStorage,never()).store(any(),any(),any());
        }
    }
    @Test void actualFilePublicationRollsBackArchiveArtifactsDeliveryEventsAndMappingTogether() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var prepared=f.workspace.v2PrepareOutput(f.scope,f.output);f.tx.executeWithoutResult(status->f.workspace.v2Stage(f.scope,"output_1",prepared));var commit=f.workspace.v2PrepareCommit(f.scope,"output_1");
            assertThrows(IllegalStateException.class,()->f.tx.execute(status->{var result=f.workspace.v2Commit(f.scope,f.commitRequest(),commit);TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("actual-file-original-tx-rollback");}});return result;}));
            for(String table:List.of("agent_personal_workspace_file","agent_personal_workspace_file_version","agent_task_artifact","agent_task_formal_delivery","agent_task_formal_delivery_item","agent_task_event"))assertEquals(0,f.count(table),table);
            assertEquals("QUEUED",f.jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution",String.class));assertEquals("STAGED",f.jdbc.queryForObject("SELECT output_state FROM agent_personal_workspace_execution_output",String.class));assertEquals("PENDING",f.jdbc.queryForObject("SELECT publication_state FROM agent_personal_workspace_execution_output",String.class));assertEquals("running",f.jdbc.queryForObject("SELECT status FROM agent_task_work_item",String.class));assertEquals("running",f.jdbc.queryForObject("SELECT reward_status FROM agent_task_meta",String.class));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());assertNotNull(f.tx.execute(status->f.workspace.v2Commit(f.scope,f.commitRequest(),commit)));assertEquals(1,f.count("agent_task_formal_delivery"));
        }
    }
    @Test void actualFileOriginalBeforeCommitRechecksRevokedResourceNotOnlyClock() throws Exception {
        for(boolean publication:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.FileResource()){
            var prepared=f.workspace.v2PrepareOutput(f.scope,f.output);
            if(publication)f.tx.executeWithoutResult(status->f.workspace.v2Stage(f.scope,"output_1",prepared));
            var commit=publication?f.workspace.v2PrepareCommit(f.scope,"output_1"):null;
            assertThrows(cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure.class,()->f.tx.execute(status->{
                if(publication)f.workspace.v2Commit(f.scope,f.commitRequest(),commit);else f.workspace.v2Stage(f.scope,"output_1",prepared);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public int getOrder(){return Integer.MAX_VALUE-1;}@Override public void beforeCommit(boolean readOnly){f.jdbc.update("UPDATE agent_personal_workspace_execution SET execution_state='INPUTS_REVOKED',grant_revision=grant_revision+1");}});return null;
            }));
            assertEquals("QUEUED",f.jdbc.queryForObject("SELECT execution_state FROM agent_personal_workspace_execution",String.class));assertEquals(0,f.count("agent_personal_workspace_file"));assertEquals(0,f.count("agent_task_artifact"));assertEquals(0,f.count("agent_task_formal_delivery"));assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
        }
    }

    @Test void realPurchaseFrozenPayloadPassesR4CodecAndSameTransactionPackageReadWithoutDigestNormalization() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            assertTrue(f.payload.packageDigest().matches("sha256:[0-9a-f]{64}"));assertEquals(f.payload.packageDigest(),f.identity.packageDigest());
            var read=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.SkillRead(f.identity,0,5);
            byte[] resource=cn.jia.agent.entity.AgentRuntimeV2EffectCodec.encodeResource(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.SKILL_PACKAGE_READ,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.READ,read);
            assertEquals(read,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.decodeResource(cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Kind.SKILL_PACKAGE_READ,cn.jia.agent.entity.AgentRuntimeV2EffectCodec.Phase.READ,resource));
            var actual=f.tx.execute(status->f.results.v2Package(f.origin,f.payload,read));assertNotNull(actual);assertEquals(5,actual.content().length);assertEquals(Long.parseLong(f.payload.packageSize()),actual.totalLength());
            assertEquals(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.hex(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(actual.content())),actual.sha256());
            assertEquals(0,f.count("economy_skill_result_receipt"));assertEquals(1,f.count("economy_transaction"));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));verifyNoInteractions(f.legacyCommands);
        }
    }

    @Test void skillUnknownNeverConsumesTerminalResultIdentityOrRefundsEscrow() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            for(int attempt=0;attempt<3;attempt++)assertEquals("UNKNOWN",f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.UNKNOWN,null,f.heldAt+attempt+1));
            assertEquals(0,f.count("economy_skill_result_receipt"));assertEquals(1,f.count("economy_transaction"));assertEquals(70000000L,f.wallet());
            assertEquals("INSTALLING",f.status("economy_skill_order"));assertEquals("INSTALLING",f.status("economy_skill_installation"));assertEquals("PENDING_INSTALLATION",f.status("economy_skill_entitlement"));
            assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));
            verify(f.legacyVersions,never()).requireOwned(any(),anyString(),any(),anyBoolean());verifyNoInteractions(f.legacyCommands);
        }
    }
    @Test void skillUnknownThenRealCaptureIsExactlyOnceAndUnknownCannotDowngradeIt() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            assertEquals("UNKNOWN",f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.UNKNOWN,null,f.heldAt+1));
            long installedAt=f.heldAt+2,now=installedAt+1;
            assertEquals("ACTIVE",f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,installedAt,now));
            assertEquals("ACTIVE",f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,installedAt,now+10));
            assertEquals("ACTIVE",f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.UNKNOWN,null,now+20));
            assertEquals(1,f.count("economy_skill_result_receipt"));assertEquals(2,f.count("economy_transaction"));assertEquals(70000000L,f.wallet());
            assertEquals("ACTIVE",f.status("economy_skill_order"));assertEquals("SUCCEEDED",f.status("economy_skill_installation"));assertEquals("ACTIVE",f.status("economy_skill_entitlement"));
            assertEquals("CAPTURED",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));assertEquals(0L,f.jdbc.queryForObject("SELECT SUM(signed_amount_micro) FROM economy_entry",Long.class));
            assertArrayEquals(cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.sha256(KNOWN),f.jdbc.queryForObject("SELECT request_hash FROM economy_skill_result_receipt",byte[].class));
            verify(f.legacyVersions,never()).requireOwned(any(),anyString(),any(),anyBoolean());verifyNoInteractions(f.legacyCommands);
        }
    }
    @Test void skillUnknownThenProvenNotRunRefundIsExactlyOnceAndRetainsKnownOutcome() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            assertEquals("UNKNOWN",f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.UNKNOWN,null,f.heldAt+1));
            assertEquals("REFUNDED",f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.NOT_RUN_PROVEN,null,f.heldAt+2));
            assertEquals("REFUNDED",f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.NOT_RUN_PROVEN,null,f.heldAt+3));
            assertEquals("REFUNDED",f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.UNKNOWN,null,f.heldAt+4));
            assertEquals(1,f.count("economy_skill_result_receipt"));assertEquals(2,f.count("economy_transaction"));assertEquals(100000000L,f.wallet());
            assertEquals("REFUNDED",f.status("economy_skill_order"));assertEquals("FAILED",f.status("economy_skill_installation"));assertEquals("FAILED",f.status("economy_skill_entitlement"));assertEquals("REFUNDED",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));
            assertEquals("V2_NOT_RUN_PROVEN",f.jdbc.queryForObject("SELECT failure_code FROM economy_skill_installation",String.class));
        }
    }
    @Test void skillKnownProofConflictCannotCaptureAgainOrChangeRefundDisposition() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+2);
            assertThrows(SkillMarketplaceException.class,()->f.result(EFFECT,UNKNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+3));
            assertThrows(SkillMarketplaceException.class,()->f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+2,f.heldAt+3));
            assertThrows(SkillMarketplaceException.class,()->f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.NOT_RUN_PROVEN,null,f.heldAt+3));
            assertEquals(1,f.count("economy_skill_result_receipt"));assertEquals(2,f.count("economy_transaction"));assertEquals("CAPTURED",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));
        }
    }
    @Test void skillResourceAndEscrowRollbackTogetherAtOriginalBeforeCommit() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            assertThrows(IllegalStateException.class,()->f.tx.execute(status->{
                assertEquals("ACTIVE",f.results.v2Result(f.origin,f.payload,f.identity,EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+2));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void beforeCommit(boolean readOnly){throw new IllegalStateException("r4-original-beforeCommit-revocation-probe");}});return null;
            }));
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
            assertEquals(0,f.count("economy_skill_result_receipt"));assertEquals(1,f.count("economy_transaction"));assertEquals("INSTALLING",f.status("economy_skill_order"));assertEquals("INSTALLING",f.status("economy_skill_installation"));assertEquals("PENDING_INSTALLATION",f.status("economy_skill_entitlement"));assertEquals("ACTIVE",f.jdbc.queryForObject("SELECT status FROM economy_escrow",String.class));
            assertEquals("ACTIVE",f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+3));assertEquals(1,f.count("economy_skill_result_receipt"));assertEquals(2,f.count("economy_transaction"));
        }
    }
    @Test void skillV2RejectsNoOriginalOrReadOnlyTransactionBeforeResourceMutation() throws Exception {
        try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            assertThrows(SkillMarketplaceException.class,()->f.results.v2Result(f.origin,f.payload,f.identity,EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+2));
            f.tx.setReadOnly(true);assertThrows(SkillMarketplaceException.class,()->f.result(EFFECT,KNOWN,SkillInstallResultService.V2Disposition.ACTIVE,f.heldAt+1,f.heldAt+2));
            assertEquals(0,f.count("economy_skill_result_receipt"));assertEquals(1,f.count("economy_transaction"));
        }
    }
    @Test void realPackageReadRejectsAdditionalOrReplacedJdbcBindingButAcceptsLazyMyBatisSession() throws Exception {
        for(boolean replacement:List.of(false,true))try(var f=new AgentRuntimeV2R4ProductionFixture.SkillResource()){
            var packages=f.resultPackages.getObject();var read=new cn.jia.agent.entity.AgentRuntimeV2EffectCodec.SkillRead(f.identity,0,5);
            // The positive package test above starts with only the original
            // JDBC binding and performs actual lazy MyBatis session creation.
            assertThrows(SkillMarketplaceException.class,()->f.tx.execute(status->{
                var binding=TransactionSynchronizationManager.getResourceMap().entrySet().stream().filter(entry->entry.getValue() instanceof org.springframework.jdbc.datasource.ConnectionHolder).findFirst().orElseThrow();
                Object extra=new Object();var holder=(org.springframework.jdbc.datasource.ConnectionHolder)binding.getValue();
                when(f.resultPackages.getObject()).thenAnswer(invocation->{
                    if(replacement)TransactionSynchronizationManager.unbindResource(binding.getKey());
                    TransactionSynchronizationManager.bindResource(replacement?binding.getKey():extra,new org.springframework.jdbc.datasource.ConnectionHolder(holder.getConnection()));return packages;
                });
                try{return f.results.v2Package(f.origin,f.payload,read);}
                finally{TransactionSynchronizationManager.unbindResource(replacement?binding.getKey():extra);if(replacement)TransactionSynchronizationManager.bindResource(binding.getKey(),holder);}
            }));
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());assertEquals(0,f.count("economy_skill_result_receipt"));assertEquals(1,f.count("economy_transaction"));assertEquals("INSTALLING",f.status("economy_skill_order"));
        }
    }
    private static PersonalWorkspaceExecutionEntity association(String id, String state) {
        var value = new PersonalWorkspaceExecutionEntity();
        value.setTenantId("0"); value.setClientId("caller-C"); value.setOwnerJiacn("owner-a");
        return value.setExecutionId(id).setTaskId("task-a").setWorkItemId("item-a")
                .setTargetAgentId("agent-a").setExecutionMode("TASK").setExecutionState(state);
    }
    private static PersonalWorkspaceExecutionDaoImpl dao(PersonalWorkspaceExecutionMapper mapper) {
        return new PersonalWorkspaceExecutionDaoImpl(mapper, mock(PersonalWorkspaceExecutionInputMapper.class), mock(PersonalWorkspaceExecutionOutputMapper.class));
    }
    @Test void exactAssociationDoesNotHideRevokedCommittedOrAmbiguousRows() {
        var mapper = mock(PersonalWorkspaceExecutionMapper.class);
        var a = association("exec-a", "REVOKED"); var b = association("exec-b", "COMMITTED");
        when(mapper.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a")).thenReturn(List.of(a, b));
        var found = dao(mapper).findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a");
        assertEquals(List.of(a, b), found); assertThrows(UnsupportedOperationException.class, () -> found.clear());
        verify(mapper).findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a");
        verifyNoMoreInteractions(mapper);
    }
    @Test void absentRowsAreAnExplicitExactReadNotATruncatedTargetQueue() {
        var mapper = mock(PersonalWorkspaceExecutionMapper.class);
        when(mapper.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a")).thenReturn(List.of());
        assertTrue(dao(mapper).findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a").isEmpty());
        verify(mapper).findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a"); verifyNoMoreInteractions(mapper);
    }
    @Test void associationRemainsAreaZeroAndRejectsMissingScopeBeforeQuery() {
        var mapper = mock(PersonalWorkspaceExecutionMapper.class); var dao = dao(mapper);
        assertThrows(IllegalArgumentException.class, () -> dao.findTaskAssociations("1", "caller-C", "owner-a", "task-a", "item-a", "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> dao.findTaskAssociations("0", "caller-C", "owner-a", "task-a", null, "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> dao.findTaskAssociations("0", "caller-C", "0", "task-a", "item-a", "agent-a"));
        verifyNoInteractions(mapper);
    }
    @Test void associationProjectionCannotLaunderScopeModeOrExcessRows() {
        var mapper = mock(PersonalWorkspaceExecutionMapper.class); var dao = dao(mapper);
        var a = association("exec-a", "QUEUED"); var b = association("exec-b", "COMMITTED");
        when(mapper.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a")).thenReturn(List.of(a, b, association("exec-c", "REVOKED")));
        assertThrows(IllegalStateException.class, () -> dao.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a"));
        for (var wrong : List.of(association("exec-a", "QUEUED").setExecutionMode("PRIVATE"), association("exec-a", "QUEUED").setTargetAgentId("agent-b"), association("exec-a", "QUEUED").setWorkItemId("item-b"))) {
            when(mapper.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a")).thenReturn(List.of(wrong));
            assertThrows(IllegalStateException.class, () -> dao.findTaskAssociations("0", "caller-C", "owner-a", "task-a", "item-a", "agent-a"));
        }
    }
    @Test void taskAssociationSqlIsByteLengthExactFixedLimitAndStateIndependent() throws Exception {
        var method = PersonalWorkspaceExecutionMapper.class.getMethod("findTaskAssociations", String.class, String.class, String.class, String.class, String.class, String.class);
        var sql = String.join("\n", method.getAnnotation(Select.class).value());
        for (var entry : List.of(new String[]{"tenant_id", "tenantId"}, new String[]{"client_id", "clientId"}, new String[]{"owner_jiacn", "ownerJiacn"}, new String[]{"task_id", "taskId"}, new String[]{"work_item_id", "workItemId"}, new String[]{"target_agent_id", "targetAgentId"})) {
            assertTrue(sql.contains("CAST(" + entry[0] + " AS BINARY)=CAST(#{" + entry[1] + "} AS BINARY)"));
            assertTrue(sql.contains("OCTET_LENGTH(" + entry[0] + ")=OCTET_LENGTH(#{" + entry[1] + "})"));
        }
        assertTrue(sql.contains("OCTET_LENGTH(execution_mode)=4")); assertTrue(sql.contains("LIMIT 2"));
        assertFalse(sql.contains("execution_state")); assertFalse(sql.contains("FOR UPDATE"));
    }
}
