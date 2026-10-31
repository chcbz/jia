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

    @Test void retiredV3CannotStageOrManufactureResultAfterStart() throws Exception {
        var f=new ResultFixture();
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,f::stage);
        verify(f.base.storage,never()).storeStream(any(),any(),anyLong(),anyString());
        verify(f.base.executions,never()).insertOutput(any());
        verify(f.base.executions,never()).update(any());
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
            when(base.storage.storeStream(any(),any(),anyLong(),anyString())).thenAnswer(call->{assertNoLocks();return stored();});
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


    }
}
