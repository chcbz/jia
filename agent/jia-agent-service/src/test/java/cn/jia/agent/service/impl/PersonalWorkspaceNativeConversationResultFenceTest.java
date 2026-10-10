package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real result service + persistent auth transaction proxy. Root/DAO/storage are mocked;
 * this proves call boundaries, not real MySQL lock concurrency or a cloud test result. */
class PersonalWorkspaceNativeConversationResultFenceTest {
    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void rotationDuringStageStoreRejectsBeforeAnyOutputInsertion() throws Exception {
        var f=new ResultFixture();
        when(f.base.storage.store(any(),any(),anyString())).thenAnswer(call->{
            f.assertNoLocks();f.rotate();return f.stored();
        });
        assertThrows(RuntimeException.class,f::stage);
        verify(f.base.executions,never()).insertOutput(any()); verify(f.base.manager).rollback(f.base.status);
    }
    @Test void preparedStageRefencesBeforeOriginalRootLeaseAndOutputMutation() throws Exception {
        var f=new ResultFixture();var result=f.stage();
        assertEquals("STAGED",result.state());assertEquals(f.hash,result.sha256());
        var order=inOrder(f.base.storage,f.base.installations,f.base.rows,f.roots,f.base.executions,f.base.manager);
        order.verify(f.base.storage).store(any(),any(),eq("image/png"));
        order.verify(f.base.installations).lock(PersonalWorkspaceControlledImageV3StartTest.StartFixture.ID);
        order.verify(f.base.rows).lockInScope("0","client",PersonalWorkspaceControlledImageV3StartTest.StartFixture.AGENT);
        order.verify(f.roots).executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any());
        order.verify(f.base.executions).lockByTaskRun("0","client","owner","task","run");
        order.verify(f.base.executions).lockOutput("0","client","owner","execution","output_1");
        order.verify(f.base.executions).insertOutput(any());order.verify(f.base.manager).commit(f.base.status);
        assertEquals("CONVERSATION",f.output.getOutputPurpose());assertNull(f.output.getWorkspaceFileId());
    }
    @Test void leaseChangedDuringPreparationCannotStageDespiteCurrentRuntimeProof() throws Exception {
        var f=new ResultFixture();
        when(f.base.storage.store(any(),any(),anyString())).thenAnswer(call->{
            f.assertNoLocks();f.base.execution.setConversationLeaseRuntimeId("foreign-boot");return f.stored();
        });
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::stage).getReason());
        verify(f.base.executions,never()).insertOutput(any());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void changingConsumedAuthorityDuringStoreCannotRebindOriginalOutput() throws Exception {
        var f=new ResultFixture();
        when(f.base.storage.store(any(),any(),anyString())).thenAnswer(call->{
            f.assertNoLocks();f.base.execution.setControlledConsentId("different-consent");return f.stored();
        });
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::stage).getReason());
        verify(f.base.executions,never()).insertOutput(any());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void commitRotationDuringImmutableStorageReadCannotAdvanceResult() throws Exception {
        var f=new ResultFixture();f.staged();
        when(f.base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
            f.assertNoLocks();f.rotate();return f.content();
        });
        assertThrows(RuntimeException.class,f::commit);
        verify(f.base.executions,never()).updateOutput(any());verify(f.base.executions,never()).update(any());
        assertEquals("STAGED",f.output.getOutputState());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void immutableTupleChangedDuringReadFailsClosedInsideOriginalLocks() throws Exception {
        var f=new ResultFixture();f.staged();
        when(f.base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{
            f.assertNoLocks();f.output.setStorageUri("changed-storage-tuple");return f.content();
        });
        assertEquals(PersonalWorkspaceExecutionService.Reason.OUTPUT_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());
        verify(f.base.executions,never()).updateOutput(any());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void commitVerifiesBytesBeforeFenceAndCommittedReplayDoesNotReadAgain() throws Exception {
        var f=new ResultFixture();f.staged();var receipt=f.commit();
        assertEquals("COMMITTED",receipt.state());assertEquals("OUTPUT_COMMITTED",f.base.execution.getExecutionState());
        assertEquals(receipt,f.commit());
        verify(f.base.storage,times(1)).read(any(),eq("private-result"),eq(f.hash),eq((long)f.bytes.length),eq("image/png"));
        verify(f.base.storage,never()).store(any(),any(),anyString());verify(f.base.executions,times(1)).updateOutput(any());
        verify(f.base.manager,times(2)).commit(f.base.status);
    }
    @Test void corruptedOutputBytesCannotReachPersistentCommit() throws Exception {
        var f=new ResultFixture();f.staged();byte[] corrupt=f.bytes.clone();corrupt[0]^=1;
        when(f.base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenReturn(
                new PersonalWorkspaceStorage.StoredContent(corrupt,f.hash,f.bytes.length,"image/png"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::commit).getReason());
        verify(f.base.installations,never()).lock(anyString());verify(f.base.executions,never()).updateOutput(any());
    }
    @Test void expiredConsumedStartRecoversOriginalOnceWithoutLeaseRewriteOrSourceReplay() throws Exception {
        var f=new ResultFixture();f.expiredRecovery();var receipt=f.recover();
        assertEquals("COMMITTED",receipt.state());assertEquals(receipt,f.recover());
        assertEquals(receipt,f.base.service.recoverStagedConversationOutput(f.base.scope,"task","run",f.manifest(),f.recovery()));
        verify(f.base.storage,times(1)).store(any(),any(),anyString());verify(f.base.storage,times(1)).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(f.base.executions,times(1)).insertOutput(any());verify(f.base.executions,times(1)).update(any());
        assertEquals(1L,f.base.execution.getConversationLeaseExpiresAt());
        assertEquals("original-boot",f.base.execution.getConversationLeaseRuntimeId());
        assertEquals(1L,f.base.execution.getConversationProviderLeaseVersion());assertEquals(10L,f.base.execution.getConversationProviderStartedAt());
        verify(f.base.authority,never()).consumeForStart(any(),any(),any());verifyNoInteractions(f.base.sources);
    }
    @Test void existingStagedRecoveryNeverRestoresBytesAndStillRequiresCurrentFence() throws Exception {
        var f=new ResultFixture();f.expiredRecovery();f.staged();var receipt=f.recover();
        assertEquals("COMMITTED",receipt.state());verify(f.base.storage,never()).store(any(),any(),anyString());
        verify(f.base.executions,never()).insertOutput(any());verify(f.base.manager).commit(f.base.status);
    }
    @Test void rotationDuringSpoolRecoveryCannotPersistStagingOrReportCommit() throws Exception {
        var f=new ResultFixture();f.expiredRecovery();
        when(f.base.storage.store(any(),any(),anyString())).thenAnswer(call->{f.assertNoLocks();f.rotate();return f.stored();});
        assertThrows(RuntimeException.class,f::recover);verify(f.base.executions,never()).insertOutput(any());
        verify(f.base.executions,never()).update(any());verify(f.base.manager).rollback(f.base.status);
    }
    @Test void missingNativeProofIsNotAnAlternativeResultRecoveryLane() throws Exception {
        var f=new ResultFixture();f.expiredRecovery();SecurityContextHolder.clearContext();
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,f::recover);
        verify(f.base.storage,never()).store(any(),any(),anyString());verify(f.base.executions,never()).insertOutput(any());
    }
    @Test void foreignLiveLeaseAndInvalidManifestDenyBeforeStorage() throws Exception {
        var f=new ResultFixture();f.base.execution.setConversationLeaseRuntimeId("foreign-boot");
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,f::recover).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.OUTPUT_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->f.base.service.recoverConversationOutput(
                        f.base.scope,"task","run","pwe_m_"+"0".repeat(64),f.recovery(),"bird.png","image/png",f.bytes)).getReason());
        verify(f.base.storage,never()).store(any(),any(),anyString());verify(f.base.executions,never()).insertOutput(any());
    }

    static final class ResultFixture {
        final PersonalWorkspaceControlledImageV3StartTest.StartFixture base=new PersonalWorkspaceControlledImageV3StartTest.StartFixture();
        final AgentTaskMutationTransaction roots=mock(AgentTaskMutationTransaction.class);
        final AtomicBoolean rootHeld=new AtomicBoolean();
        final byte[] bytes;final String hash;
        PersonalWorkspaceExecutionOutputEntity output;
        ResultFixture() throws Exception {
            var buffer=new ByteArrayOutputStream();assertTrue(ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",buffer));
            bytes=buffer.toByteArray();hash=PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(bytes);
            base.execution.setOwnerJiacn("owner").setTaskGrantId("grant").setTaskGrantVersion(1L).setAssignmentRevision(1L)
                    .setControlledConsentId("consent").setOutputContentMimeType("image/png")
                    .setConversationProviderStartedAt(10L).setConversationProviderLeaseVersion(1L)
                    .setConversationLeaseVersion(1L).setConversationLeaseToken("lease-token")
                    .setConversationLeaseRuntimeId("boot").setConversationLeaseExpiresAt(Long.MAX_VALUE);
            base.execution.setTenantId("0");base.execution.setClientId("client");
            var root=new AgentTaskMetaEntity().setTaskId("task").setAssignedAgentId(base.scope.agentId()).setTaskVersion(1L);
            root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
            when(roots.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any()))
                    .thenAnswer(call->{rootHeld.set(true);try{return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4)).apply(root);}
                        finally{rootHeld.set(false);}});
            base.service.setConversationAdmission(mock(AgentTaskExecutionGrantService.class),roots);
            var access=mock(WorkspaceConversationAccessService.class);
            when(access.requireAccessible(any(),eq("conversation"))).thenReturn(new WorkspaceConversationAccessService.ConversationView(
                    "conversation","bounty","task:task","task",List.of(base.scope.agentId()),1,1));
            base.service.setTaskExecutionDependencies(access,mock(AgentTaskWorkItemDao.class),mock(AgentWorkItemLeaseService.class));
            when(base.authority.runtimeAuthority(any(),eq("task"),eq("run"),anyString())).thenReturn(
                    new ControlledImageFollowupAuthorityService.RuntimeAuthority("execution","GENERATE_IMAGE",
                            base.execution.getRuntimeInputSnapshotDigest(),new ControlledImageFollowupAuthorityService.ProviderExecution(
                                    "CONTROLLED_IMAGE_HTTP_V1","consent","binding","1","model",16,1,1)));
            when(base.executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(base.execution);
            when(base.executions.lockOutput("0","client","owner","execution","output_1")).thenAnswer(call->output);
            when(base.executions.lockOutputs("0","client","owner","execution")).thenAnswer(call->output==null?List.of():List.of(output));
            doAnswer(call->{output=call.getArgument(0);return null;}).when(base.executions).insertOutput(any());
            when(base.storage.store(any(),any(),anyString())).thenAnswer(call->{assertNoLocks();return stored();});
            when(base.storage.read(any(),anyString(),anyString(),anyLong(),anyString())).thenAnswer(call->{assertNoLocks();return content();});
        }
        void assertNoLocks() { assertFalse(rootHeld.get());assertFalse(base.transactionActive.get()); }
        void rotate() { base.row.setRuntimeSessionGeneration(2L).setRuntimeInstanceId("replacement"); }
        void expiredRecovery() { base.execution.setConversationLeaseExpiresAt(1L).setConversationLeaseRuntimeId("original-boot"); }
        void staged() {
            output=new PersonalWorkspaceExecutionOutputEntity().setExecutionId("execution").setOutputId("output_1")
                    .setOwnerJiacn("owner").setOriginalFilename("bird.png").setContentHash(hash).setByteLength((long)bytes.length)
                    .setContentMimeType("image/png").setStorageUri("private-result").setOutputPurpose("CONVERSATION")
                    .setOutputState("STAGED").setPublicationState("PENDING");
            output.setTenantId("0");output.setClientId("client");
        }
        PersonalWorkspaceStorage.StoredObject stored() { return new PersonalWorkspaceStorage.StoredObject("private-result",hash,bytes.length,"image/png"); }
        PersonalWorkspaceStorage.StoredContent content() { return new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"); }
        String manifest() throws Exception { return "pwe_m_"+PersonalWorkspaceControlledImageV3StartTest.StartFixture.sha(
                ("task\nrun\noutput_1\n"+hash+"\n"+bytes.length+"\n").getBytes(StandardCharsets.UTF_8)); }
        List<PersonalWorkspaceExecutionService.OutputDeclaration> declarations() { return List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,bytes.length)); }
        PersonalWorkspaceExecutionService.ConversationResultRecovery recovery() { return new PersonalWorkspaceExecutionService.ConversationResultRecovery(
                "execution",base.command.commandId(),base.command.messageId(),base.execution.getRuntimeInputSnapshotDigest(),declarations()); }
        PersonalWorkspaceExecutionService.StagedOutput stage() { return base.service.stageConversationOutput(base.scope,"task","run",
                new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token"),"output_1","bird.png","image/png",bytes); }
        PersonalWorkspaceExecutionService.CommitView commit() throws Exception { return base.service.commitConversationOutput(base.scope,"task","run",
                new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token"),manifest(),declarations()); }
        PersonalWorkspaceExecutionService.CommitView recover() throws Exception { return base.service.recoverConversationOutput(base.scope,"task","run",
                manifest(),recovery(),"bird.png","image/png",bytes); }
    }
}
