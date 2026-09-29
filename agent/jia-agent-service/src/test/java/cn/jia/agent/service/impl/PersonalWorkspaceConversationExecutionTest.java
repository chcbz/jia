package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Owner/grant/no-file vertical slice; mocks do not claim to exercise MySQL row locks. */
class PersonalWorkspaceConversationExecutionTest {
    private static final PersonalWorkspaceExecutionService.OwnerScope OWNER =
            new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    private static final PersonalWorkspaceExecutionService.RuntimeScope RUNTIME =
            new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");
    private final PersonalWorkspaceExecutionDao rows=mock(PersonalWorkspaceExecutionDao.class);
    private final AgentRuntimeDao runtimes=mock(AgentRuntimeDao.class);
    private final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    private final PersonalWorkspaceWriteService writes=mock(PersonalWorkspaceWriteService.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final WorkspaceConversationAccessService conversation=mock(WorkspaceConversationAccessService.class);
    private PersonalWorkspaceExecutionServiceImpl service;
    private PersonalWorkspaceExecutionEntity execution;
    private PersonalWorkspaceExecutionOutputEntity output;

    @BeforeEach void setUp() {
        when(storage.maxContentBytes()).thenReturn(10_000L);
        var agent=new AgentRuntimeEntity();
        agent.setAgentId("agent");agent.setClientId("client");agent.setOwnerJiacn("owner");
        when(runtimes.findCandidateRosterByOwner("client","owner")).thenReturn(List.of(agent));
        service=new PersonalWorkspaceExecutionServiceImpl(rows,mock(PersonalWorkspaceDao.class),
                mock(PersonalWorkspaceTaskLinkDao.class),runtimes,storage,writes,
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        service.setConversationAdmission(grants,transactions);
        service.setTaskExecutionDependencies(conversation,mock(AgentTaskWorkItemDao.class),
                mock(AgentWorkItemLeaseService.class));
        var root=new AgentTaskMetaEntity().setTaskId("task-1").setAssignedAgentId("agent").setTaskVersion(7L);
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),
                eq("owner"),eq("task-1"),any())).thenAnswer(i ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(root));
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1L,7L,
                        "agent","GENERATE_IMAGE",true));
        when(conversation.requireAccessible(any(),eq("conv-1"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("conv-1","TASK","task-1",
                        "task-1",List.of("agent"),1,1));
        execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec-1").setTaskId("task-1")
                .setRunId("run-1").setExecutionMode("CONVERSATION").setConversationId("conv-1")
                .setTargetAgentId("agent").setTaskGrantId("grant-1").setTaskGrantVersion(1L)
                .setAssignmentRevision(7L).setPermittedOperation("GENERATE_IMAGE")
                .setExecutionState("QUEUED").setOutputContentMimeType("image/png");
        execution.setTenantId("0");execution.setClientId("client");execution.setOwnerJiacn("owner");
        when(rows.findByTaskRun("0","client","owner","task-1","run-1")).thenReturn(execution);
        when(rows.lockByTaskRun("0","client","owner","task-1","run-1")).thenReturn(execution);
    }

    @Test void trustedCreateRequiresActualPaidGrantAndCorrectConversationBeforeInsert() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "conv-1","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1,7,"agent","GENERATE_IMAGE",false));
        var failed=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.createConversation(OWNER,command));
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,failed.getReason());
        verify(rows,never()).insert(any());
        verifyNoInteractions(writes);
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1,7,"agent","GENERATE_IMAGE",true));
        // An unrelated conversation must not create an execution even with a real grant.
        when(conversation.requireAccessible(any(),eq("conv-1"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("conv-1","TASK","other-task",
                        "other-task",List.of("agent"),1,1));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.createConversation(OWNER,command)).getReason());
        verify(rows,never()).insert(any());
    }

    @Test void changedAssignmentRejectsCreateBeforeAnyInsert() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "conv-1","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        var drift=new AgentTaskMetaEntity().setTaskId("task-1").setAssignedAgentId("agent")
                .setTaskVersion(8L);
        drift.setTenantId("0");drift.setClientId("client");drift.setOwnerJiacn("owner");
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),
                eq("owner"),eq("task-1"),any())).thenAnswer(i ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(drift));
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> service.createConversation(OWNER,command)).getReason());
        verify(rows,never()).insert(any());
    }

    @Test void replayNeedsLiveGrantNotJustIdempotencyHash() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "conv-1","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        // Real DAO hash is intentionally not fabricated: conflicting key must fail closed.
        when(rows.findByIdempotency(eq("0"),eq("client"),eq("owner"),anyString()))
                .thenReturn(execution);
        assertEquals(PersonalWorkspaceExecutionService.Reason.IDEMPOTENCY_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> service.createConversation(OWNER,command)).getReason());
        verify(rows,never()).insert(any());
    }

    @Test void nativeStartCommitAndTrustedReadUsePrivateBytesWithoutPersonalFileOrFormalDelivery() throws Exception {
        byte[] png=png();String hash=sha(png);
        output=new PersonalWorkspaceExecutionOutputEntity().setOutputId("output_1")
                .setExecutionId("exec-1").setOwnerJiacn("owner").setOutputPurpose("CONVERSATION")
                .setOutputState("STAGED").setPublicationState("PENDING")
                .setContentMimeType("image/png").setContentHash(hash).setByteLength((long)png.length)
                .setStorageUri("private/object").setOriginalFilename("bird.png");
        when(rows.lockOutputs("0","client","owner","exec-1")).thenReturn(List.of(output));
        when(rows.lockOutput("0","client","owner","exec-1","output_1")).thenReturn(output);
        when(storage.read(any(),eq("private/object"),eq(hash),eq((long)png.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(png,hash,png.length,"image/png"));
        var started=service.start(RUNTIME,"task-1","run-1","pwe_cmd_"+sha("command\nexec-1".getBytes(StandardCharsets.UTF_8)),
                "pwe_msg_"+sha("message\nexec-1".getBytes(StandardCharsets.UTF_8)));
        assertEquals("STARTED",started.state());
        String manifest="pwe_m_"+sha(("task-1\nrun-1\noutput_1\n"+hash+"\n"+png.length+"\n")
                .getBytes(StandardCharsets.UTF_8));
        var committed=service.commitOutputs(RUNTIME,"task-1","run-1",manifest,
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,png.length)));
        assertEquals("COMMITTED",committed.state());
        assertNull(committed.items().getFirst().fileId());
        assertNull(committed.items().getFirst().fileVersion());
        assertEquals("OUTPUT_COMMITTED",execution.getExecutionState());
        var read=service.readConversationOutput(OWNER,"task-1","run-1","output_1");
        assertArrayEquals(png,read.bytes());assertEquals(hash,read.sha256());
        read.bytes()[0]=0;assertArrayEquals(png,read.bytes());
        verifyNoInteractions(writes);
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,() -> service.readConversationOutput(
                        new PersonalWorkspaceExecutionService.OwnerScope("0","client","other"),
                        "task-1","run-1","output_1")).getReason());
        verify(storage,times(2)).read(any(),eq("private/object"),eq(hash),eq((long)png.length),eq("image/png"));
    }

    @Test void revokedOrStaleGrantCannotStartOrReadBytes() {
        when(grants.admit(any(),any(),any(),anyLong(),anyLong(),any(),any(),eq(true)))
                .thenThrow(new IllegalStateException("grant revoked"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.start(RUNTIME,
                        "task-1","run-1","pwe_cmd_"+sha("command\nexec-1".getBytes(StandardCharsets.UTF_8)),
                        "pwe_msg_"+sha("message\nexec-1".getBytes(StandardCharsets.UTF_8)))).getReason());
        execution.setExecutionState("OUTPUT_COMMITTED");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.readConversationOutput(
                        OWNER,"task-1","run-1","output_1")).getReason());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(storage,never()).store(any(),any(byte[].class),anyString());
        verifyNoInteractions(writes);
    }

    private static byte[] png() throws Exception {
        var stream=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB),"png",stream);
        return stream.toByteArray();
    }
    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
