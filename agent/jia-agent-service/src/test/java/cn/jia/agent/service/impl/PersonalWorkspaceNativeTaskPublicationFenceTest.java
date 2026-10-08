package cn.jia.agent.service.impl;

import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real native + artifact services and transaction-advised persistent auth. Mock DAO/root/storage
 * boundaries are asserted; this is not actual MySQL lock concurrency or cloud execution evidence. */
class PersonalWorkspaceNativeTaskPublicationFenceTest {
    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void storagePreparationPrecedesPersistentFenceAndRowOnlyFormalPublication() throws Exception {
        var f=new TaskFixture();var receipt=f.commit();
        assertEquals("COMMITTED",receipt.state());assertEquals("PUBLISHED",f.output.getPublicationState());
        assertEquals("pwe_art_execution",f.output.getArtifactId());assertEquals(1,f.output.getArtifactVersion());
        assertEquals(f.deliveryId,f.output.getFormalDeliveryId());assertEquals("OUTPUT_COMMITTED",f.base.execution.getExecutionState());
        var order=inOrder(f.base.storage,f.artifactStorage,f.base.installations,f.base.rows,f.artifactRows,f.events,f.formal,f.base.manager);
        order.verify(f.base.storage).read(any(),eq("task-result"),eq(f.hash),eq((long)f.bytes.length),eq("image/png"));
        order.verify(f.artifactStorage,times(2)).store(any(),any(),anyString());
        order.verify(f.base.installations).lock(PersonalWorkspaceControlledImageV3StartTest.StartFixture.ID);
        order.verify(f.base.rows).lockInScope("0","client",f.base.scope.agentId());
        order.verify(f.artifactRows).insert(eq("0"),eq("client"),eq("owner"),any());
        order.verify(f.events).append(any());order.verify(f.artifactRows).insert(eq("0"),eq("client"),eq("owner"),any());
        order.verify(f.events).append(any());order.verify(f.formal).submit(eq("0"),eq("client"),eq("owner"),eq("task"),eq(f.base.scope.agentId()),any());
        order.verify(f.base.manager).commit(f.base.status);
        assertEquals(2,f.persisted.size());
        verify(f.base.authority,never()).consumeForStart(any(),any(),any());verifyNoInteractions(f.base.sources);
    }
    @Test void lostAckReplayRefencesWithoutRequiringClearedLeaseOrStorageOrRepublishing() throws Exception {
        var f=new TaskFixture();var receipt=f.commit();
        f.item.setStatus("submitted").setLeaseToken(null).setLeaseUntil(null);
        assertEquals(receipt,f.commit());
        verify(f.base.storage,times(1)).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(f.artifactStorage,times(2)).store(any(),any(),anyString());
        verify(f.artifactRows,times(2)).insert(anyString(),anyString(),anyString(),any());
        verify(f.events,times(2)).append(any());verify(f.formal,times(1)).submit(any(),any(),any(),any(),any(),any());
        verify(f.base.executions,times(1)).updateOutput(any());verify(f.base.manager,times(2)).commit(f.base.status);
    }
    @Test void rotationDuringArtifactStoreCannotPublishOrArchiveOriginalOutput() throws Exception {
        var f=new TaskFixture();f.afterStore=()->f.base.row.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement");
        assertThrows(RuntimeException.class,f::commit);f.assertNoRows();verify(f.base.manager).rollback(f.base.status);
    }
    @Test void rotationDuringSourceReadCannotPublishUnderOldGeneration() throws Exception {
        var f=new TaskFixture();f.afterRead=()->f.base.row.setRuntimeSessionGeneration(2L);
        assertThrows(RuntimeException.class,f::commit);f.assertNoRows();verify(f.base.manager).rollback(f.base.status);
    }
    @Test void leaseReassignedDuringPreparationDeniesFinalCommit() throws Exception {
        var f=new TaskFixture();f.afterStore=()->f.item.setLeaseToken("new-lease");
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());f.assertNoRows();
    }
    @Test void originalExecutionLeaseCannotBeReboundDuringPreparation() throws Exception {
        var f=new TaskFixture();f.afterStore=()->f.base.execution.setLeaseToken("rewritten-lease");
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());f.assertNoRows();
    }
    @Test void sourceTupleChangedDuringReadDeniesBeforeArchiveOrPublication() throws Exception {
        var f=new TaskFixture();f.afterRead=()->f.output.setStorageUri("replacement-tuple");
        assertEquals(PersonalWorkspaceExecutionService.Reason.OUTPUT_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());f.assertNoRows();
    }
    @Test void corruptReadNeverAcquiresRuntimeFenceOrPreparesArtifact() throws Exception {
        var f=new TaskFixture();
        when(f.base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenReturn(
                new PersonalWorkspaceStorage.StoredContent(new byte[]{1},f.hash,f.bytes.length,"image/png"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());
        verify(f.artifactStorage,never()).store(any(),any(),anyString());verify(f.base.installations,never()).lock(anyString());f.assertNoRows();
    }
    @Test void preparedArtifactsRepeatOriginalAclAndVersionChecks() throws Exception {
        for(boolean acl:new boolean[]{true,false}) {
            var f=new TaskFixture();f.afterStore=()->{
                if(f.storeCount!=2) return;
                if(acl) f.member.setMemberRole("observer");
                else f.persisted.put("pwe_art_execution",new AgentTaskArtifactEntity().setTaskId("task").setArtifactVersion(1));
            };
            assertThrows(AgentTaskCollaborationException.class,f::commit);
            verify(f.artifactRows,never()).insert(anyString(),anyString(),anyString(),any());
            verify(f.events,never()).append(any());verify(f.formal,never()).submit(any(),any(),any(),any(),any(),any());
            verify(f.base.executions,never()).updateOutput(any());verify(f.base.manager).rollback(f.base.status);
        }
    }
    @Test void unrelatedPreparedVersionConflictCannotMasqueradeAsOriginalCommittedReceipt() throws Exception {
        var f=new TaskFixture();f.afterStore=()->f.persisted.put("pwe_manifest_execution",
                new AgentTaskArtifactEntity().setTaskId("task").setArtifactVersion(1));
        assertEquals(AgentTaskCollaborationException.Reason.VERSION_CONFLICT,assertThrows(
                AgentTaskCollaborationException.class,f::commit).getReason());f.assertNoRows();
        verify(f.base.manager).rollback(f.base.status);
    }
    @Test void missingProofAndWrongManifestFailBeforeStoragePreparation() throws Exception {
        var f=new TaskFixture();
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->f.base.service.commitOutputs(
                f.base.scope,"task","run","pwe_m_"+"0".repeat(64),f.declarations()));
        SecurityContextHolder.clearContext();assertThrows(PersonalWorkspaceExecutionService.Failure.class,f::commit);
        verify(f.base.storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(f.artifactStorage,never()).store(any(),any(),anyString());f.assertNoRows();
    }
    @Test void obsoleteGenerationCommittedReplayCannotReturnSuccess() throws Exception {
        var f=new TaskFixture();f.commit();f.base.row.setRuntimeSessionGeneration(2L);
        assertThrows(RuntimeException.class,f::commit);
        verify(f.formal,times(1)).submit(any(),any(),any(),any(),any(),any());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void concurrentOriginalCommitWinsWithoutSecondPublication() throws Exception {
        var f=new TaskFixture();f.afterStore=()->{
            f.output.setOutputState("COMMITTED").setPublicationState("PUBLISHED").setPublicationRevision(1L)
                    .setArtifactId("pwe_art_execution").setArtifactVersion(1).setFormalDeliveryId(f.deliveryId);
            f.base.execution.setExecutionState("OUTPUT_COMMITTED");f.item.setStatus("submitted").setLeaseToken(null);
            for(String id:List.of("pwe_art_execution","pwe_manifest_execution"))
                f.persisted.put(id,new AgentTaskArtifactEntity().setTaskId("task").setArtifactId(id).setArtifactVersion(1));
        };
        assertEquals("COMMITTED",f.commit().state());
        verify(f.artifactRows,never()).insert(anyString(),anyString(),anyString(),any());
        verify(f.events,never()).append(any());verify(f.formal,never()).submit(any(),any(),any(),any(),any(),any());
        verify(f.base.executions,never()).updateOutput(any());verify(f.base.manager).commit(f.base.status);
    }

    static final class TaskFixture {
        final PersonalWorkspaceControlledImageV3StartTest.StartFixture base=new PersonalWorkspaceControlledImageV3StartTest.StartFixture();
        final AgentTaskMutationTransaction roots=mock(AgentTaskMutationTransaction.class);
        final AgentTaskArtifactStorage artifactStorage=mock(AgentTaskArtifactStorage.class);
        final AgentTaskArtifactDao artifactRows=mock(AgentTaskArtifactDao.class);
        final AgentTaskEventWriter events=mock(AgentTaskEventWriter.class);
        final AgentTaskFormalDeliveryService formal=mock(AgentTaskFormalDeliveryService.class);
        final AgentTaskMemberEntity member=new AgentTaskMemberEntity().setMemberRole("worker").setMemberStatus("working");
        final AgentTaskWorkItemEntity item=new AgentTaskWorkItemEntity().setTaskId("task").setWorkItemId("work")
                .setOwnerJiacn("owner").setStatus("running").setVersion(9L).setLeaseToken("lease").setLeaseUntil(Long.MAX_VALUE);
        final AtomicBoolean rootHeld=new AtomicBoolean();
        final Map<String,AgentTaskArtifactEntity> persisted=new LinkedHashMap<>();
        final byte[] bytes="immutable-task-result".getBytes(StandardCharsets.UTF_8);
        final String hash=PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(bytes);
        final String deliveryId="pwe_delivery_"+PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha("execution\nrun".getBytes(StandardCharsets.UTF_8));
        final PersonalWorkspaceExecutionOutputEntity output;
        int storeCount;Runnable afterStore=()->{},afterRead=()->{};
        TaskFixture() throws Exception {
            base.execution.setOwnerJiacn("owner").setExecutionMode("TASK").setWorkItemId("work")
                    .setLeaseToken("lease").setLeaseWorkItemVersion(9L).setLeaseExpiresAt(Long.MAX_VALUE);
            base.execution.setTenantId("0");base.execution.setClientId("client");item.setAssigneeAgentId(base.scope.agentId());
            item.setTenantId("0");item.setClientId("client");
            var root=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(4L).setCurrentEventVersion(0L)
                    .setAssignedAgentId(base.scope.agentId());
            root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
            when(roots.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any()))
                    .thenAnswer(call->{boolean prior=rootHeld.getAndSet(true);try{return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root);}
                        finally{rootHeld.set(prior);}});
            var members=mock(AgentTaskMemberDao.class);
            when(members.findByTaskAndAgent("0","client","owner","task",base.scope.agentId())).thenReturn(member);
            var items=mock(AgentTaskWorkItemDao.class);
            when(items.findByTaskAndWorkItemId("0","client","owner","task","work")).thenReturn(item);
            base.service.setTaskExecutionDependencies(mock(WorkspaceConversationAccessService.class),items,mock(AgentWorkItemLeaseService.class));
            var artifacts=new AgentTaskCollaborationServiceImpl(mock(AgentTaskMetaDao.class),members,items,
                    mock(AgentTaskRequestDao.class),artifactRows,artifactStorage,roots,events,()->1_000L);
            base.service.setTaskPublicationDependencies(artifacts,formal,roots);
            output=new PersonalWorkspaceExecutionOutputEntity().setExecutionId("execution").setOutputId("output_1")
                    .setOwnerJiacn("owner").setOriginalFilename("result.png").setContentMimeType("image/png")
                    .setByteLength((long)bytes.length).setContentHash(hash).setStorageUri("task-result").setOutputPurpose("FILE")
                    .setOutputState("STAGED").setPublicationState("PENDING").setPublicationRevision(0L);
            output.setTenantId("0");output.setClientId("client");
            when(base.executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(base.execution);
            when(base.executions.lockOutputs("0","client","owner","execution")).thenReturn(List.of(output));
            when(base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
                assertNoLocks();afterRead.run();return new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png");});
            when(artifactStorage.store(any(),any(),anyString())).thenAnswer(call->{
                assertNoLocks();byte[] content=call.getArgument(1);String sha=PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(content);
                storeCount++;afterStore.run();return new AgentTaskArtifactStorage.StoredObject("managed:"+sha,sha,content.length,call.getArgument(2),true);});
            when(artifactStorage.owns(anyString())).thenAnswer(call->((String)call.getArgument(0)).startsWith("managed:"));
            when(artifactStorage.matches(any(),anyString(),anyString())).thenAnswer(call->("managed:"+call.getArgument(2)).equals(call.getArgument(1)));
            when(artifactRows.findLatestVersionForUpdate(anyString(),anyString(),anyString(),anyString(),anyString()))
                    .thenAnswer(call->persisted.get(call.getArgument(4)));
            when(artifactRows.findVersion(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt()))
                    .thenAnswer(call->persisted.get(call.getArgument(4)));
            when(artifactRows.insert(eq("0"),eq("client"),eq("owner"),any())).thenAnswer(call->{
                assertTrue(base.transactionActive.get());assertTrue(rootHeld.get());AgentTaskArtifactDTO row=call.getArgument(3);
                var entity=new AgentTaskArtifactEntity();org.springframework.beans.BeanUtils.copyProperties(row,entity);
                entity.setOwnerJiacn("owner");entity.setTenantId("0");entity.setClientId("client");persisted.put(row.getArtifactId(),entity);return 1;});
            when(formal.submit(any(),any(),any(),any(),any(),any())).thenAnswer(call->{
                assertTrue(base.transactionActive.get());assertTrue(rootHeld.get());AgentTaskFormalDeliverySubmitDTO command=call.getArgument(5);
                assertEquals(deliveryId,command.getDeliveryId());assertEquals("lease",command.getLeaseToken());
                assertEquals(9L,command.getExpectedWorkItemVersion());assertEquals(4L,command.getExpectedTaskVersion());
                var result=new AgentTaskFormalDeliveryViewDTO();result.setTaskId("task");result.setWorkItemId("work");
                result.setDeliveryId(deliveryId);result.setState("submitted");return result;});
        }
        void assertNoLocks() { assertFalse(rootHeld.get());assertFalse(base.transactionActive.get()); }
        void assertNoRows() {
            verify(artifactRows,never()).insert(anyString(),anyString(),anyString(),any());verify(events,never()).append(any());
            verify(formal,never()).submit(any(),any(),any(),any(),any(),any());verify(base.executions,never()).updateOutput(any());
            assertEquals("STAGED",output.getOutputState());assertEquals("QUEUED",base.execution.getExecutionState());
        }
        List<PersonalWorkspaceExecutionService.OutputDeclaration> declarations() { return List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,bytes.length)); }
        PersonalWorkspaceExecutionService.CommitView commit() throws Exception {
            String manifest="pwe_m_"+PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(("task\nrun\noutput_1\n"+hash+"\n"+bytes.length+"\n").getBytes(StandardCharsets.UTF_8));
            return base.service.commitOutputs(base.scope,"task","run",manifest,declarations());
        }
    }
}
