package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    private AgentTaskMetaEntity root;
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
        root=new AgentTaskMetaEntity().setTaskId("task-1").setAssignedAgentId("agent").setTaskVersion(7L);
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),
                eq("owner"),eq("task-1"),any())).thenAnswer(i ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(root));
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1L,7L,
                        "agent","GENERATE_IMAGE",true));
        when(conversation.requireAccessible(any(),eq("42"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("42","bounty","task:task-1",
                        "task-1",List.of("agent"),1,1));
        execution=new PersonalWorkspaceExecutionEntity().setExecutionId("exec-1").setTaskId("task-1")
                .setRunId("run-1").setExecutionMode("CONVERSATION").setConversationId("42")
                .setTargetAgentId("agent").setTaskGrantId("grant-1").setTaskGrantVersion(1L)
                .setAssignmentRevision(7L).setPermittedOperation("GENERATE_IMAGE")
                .setExecutionState("QUEUED").setOutputContentMimeType("image/png");
        execution.setTenantId("0");execution.setClientId("client");execution.setOwnerJiacn("owner");
        when(rows.listInputs(eq("0"),eq("client"),eq("owner"),anyString())).thenReturn(List.of());
        when(rows.findByTaskRun("0","client","owner","task-1","run-1")).thenReturn(execution);
        when(rows.lockByTaskRun("0","client","owner","task-1","run-1")).thenReturn(execution);
    }

    @Test void trustedCreateRequiresActualPaidGrantAndCorrectConversationBeforeInsert() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
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
        when(conversation.requireAccessible(any(),eq("42"))).thenReturn(
                new WorkspaceConversationAccessService.ConversationView("42","bounty","task:other-task",
                        "other-task",List.of("agent"),1,1));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.createConversation(OWNER,command)).getReason());
        verify(rows,never()).insert(any());
    }

    @Test void authoritativeBountyTaskKeyAdmitsExactScopeButNeverStartsProviderOrWritesPersonalFile() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        var created=service.createConversation(OWNER,command);
        assertEquals("CONVERSATION",created.executionMode());
        assertEquals("QUEUED",created.state());
        verify(rows).insert(argThat(row -> "CONVERSATION".equals(row.getExecutionMode())
                && "42".equals(row.getConversationId()) && "grant-1".equals(row.getTaskGrantId())));
        verifyNoInteractions(writes);
        verify(storage,never()).store(any(),any(byte[].class),anyString());
    }

    @Test void publicPrivateTeamAndWrongBountyKeysAreNotExecutionAuthority() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        var rejected=new String[][]{
                {"public","task:task-1","task-1"},
                {"private","task:task-1:agent:agent","task-1"},
                {"team","task:task-1","task-1"},
                {"bounty","task:task-2","task-1"},
                {"bounty","task:task-1","task-2"},
                {"bounty","task:task-1:agent:agent","task-1"}
        };
        for (var shape:rejected) {
            when(conversation.requireAccessible(any(),eq("42"))).thenReturn(
                    new WorkspaceConversationAccessService.ConversationView(
                            "42",shape[0],shape[1],shape[2],List.of("agent"),1,1));
            assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                    PersonalWorkspaceExecutionService.Failure.class,
                    () -> service.createConversation(OWNER,command)).getReason(),shape[0]+"/"+shape[1]);
        }
        verify(rows,never()).insert(any());
        verifyNoInteractions(writes);
    }

    @Test void differentAssignedAgentRejectsCreateBeforeAnyInsert() {
        root.setAssignedAgentId("other-agent").setTaskVersion(8L);
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> service.createConversation(OWNER,command)).getReason());
        verify(rows,never()).insert(any());
    }

    @Test void statusVersionDriftAllowsCreateStartRenewAndReadWithLiveEpoch() throws Exception {
        root.setTaskVersion(9L);
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
                "GENERATE_IMAGE","draw a bird","image/png");
        assertEquals("CONVERSATION",service.createConversation(OWNER,command).executionMode());
        enable();
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        root.setTaskVersion(10L);
        assertEquals(lease.version(),service.renewConversationLease(RUNTIME,"task-1","run-1",lease.fence()).version());
        execution.setExecutionState("OUTPUT_COMMITTED");
        output=new PersonalWorkspaceExecutionOutputEntity().setOutputId("output_1")
                .setExecutionId("exec-1").setOwnerJiacn("owner").setOutputPurpose("CONVERSATION")
                .setOutputState("COMMITTED").setContentMimeType("image/png")
                .setContentHash(sha(png())).setByteLength((long)png().length)
                .setStorageUri("private/object").setOriginalFilename("bird.png");
        when(rows.lockOutput("0","client","owner","exec-1","output_1")).thenReturn(output);
        when(storage.read(any(),eq("private/object"),eq(output.getContentHash()),eq(output.getByteLength()),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(png(),output.getContentHash(),png().length,"image/png"));
        assertArrayEquals(png(),service.readConversationOutput(OWNER,"task-1","run-1","output_1").bytes());
    }

    @Test void targetRepointOrStaleGrantRejectsStartRenewAndRead() {
        enable();
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        root.setTaskVersion(8L);root.setAssignedAgentId("other-agent");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.renewConversationLease(
                        RUNTIME,"task-1","run-1",lease.fence())).getReason());
        root.setAssignedAgentId("agent");
        when(grants.admit(any(),any(),any(),anyLong(),anyLong(),any(),any(),eq(true)))
                .thenThrow(new IllegalStateException("same-agent re-point superseded the grant"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.claimConversationStart(
                        RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        execution.setExecutionState("OUTPUT_COMMITTED");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.readConversationOutput(
                        OWNER,"task-1","run-1","output_1")).getReason());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void replayNeedsLiveGrantNotJustIdempotencyHash() {
        var command=new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,
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
        enable();
        var started=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        assertEquals(1,started.version());
        assertNotNull(started.token());
        String manifest="pwe_m_"+sha(("task-1\nrun-1\noutput_1\n"+hash+"\n"+png.length+"\n")
                .getBytes(StandardCharsets.UTF_8));
        var committed=service.commitConversationOutput(RUNTIME,"task-1","run-1",started.fence(),manifest,
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,png.length)));
        assertEquals("COMMITTED",committed.state());
        assertNull(committed.items().getFirst().fileId());
        assertNull(committed.items().getFirst().fileVersion());
        assertEquals("OUTPUT_COMMITTED",execution.getExecutionState());
        var read=service.readConversationOutput(OWNER,"task-1","run-1","output_1");
        assertArrayEquals(png,read.bytes());assertEquals(hash,read.sha256());
        read.bytes()[0]=0;assertArrayEquals(png,read.bytes());
        verifyNoInteractions(writes);
        // Exercise the callback even for a hostile owner: scoped DAO must not return Alice's row.
        // Without this explicit stub Mockito returns null and never executes the negative path.
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),
                eq("other"),eq("task-1"),any())).thenAnswer(i ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(
                        new AgentTaskMetaEntity().setTaskId("task-1")));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,() -> service.readConversationOutput(
                        new PersonalWorkspaceExecutionService.OwnerScope("0","client","other"),
                        "task-1","run-1","output_1")).getReason());
        verify(storage,times(2)).read(any(),eq("private/object"),eq(hash),eq((long)png.length),eq("image/png"));
    }

    @Test void revokedOrStaleGrantCannotStartOrReadBytes() {
        when(grants.admit(any(),any(),any(),anyLong(),anyLong(),any(),any(),eq(true)))
                .thenThrow(new IllegalStateException("grant revoked"));
        enable();
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.claimConversationStart(RUNTIME,
                        "task-1","run-1",commandId(),messageId())).getReason());
        execution.setExecutionState("OUTPUT_COMMITTED");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class, () -> service.readConversationOutput(
                        OWNER,"task-1","run-1","output_1")).getReason());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(storage,never()).store(any(),any(byte[].class),anyString());
        verifyNoInteractions(writes);
    }


    @Test void catalogOnlyListsVerifiedBytesUnderOwnerGrantAndAssignmentFence() throws Exception {
        var bytes=png();var hash=sha(bytes);execution.setExecutionState("OUTPUT_COMMITTED");
        output=new PersonalWorkspaceExecutionOutputEntity().setOutputId("output_1")
                .setExecutionId("exec-1").setOwnerJiacn("owner").setOutputPurpose("CONVERSATION")
                .setOutputState("COMMITTED").setContentMimeType("image/png")
                .setContentHash(hash).setByteLength((long)bytes.length).setStorageUri("private/object");
        when(rows.lockOutputs("0","client","owner","exec-1")).thenReturn(List.of(output));
        when(storage.read(any(),eq("private/object"),eq(hash),eq((long)bytes.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"));
        var listed=service.listConversationOutputs(OWNER,"task-1","run-1");
        assertEquals(List.of(new PersonalWorkspaceExecutionService.ConversationOutputInfo(
                "exec-1","output_1","image/png",hash,bytes.length)),listed);
        assertFalse(listed.toString().contains("private/object"));
        root.setAssignedAgentId("different-agent");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.listConversationOutputs(
                        OWNER,"task-1","run-1")).getReason());
        verify(storage,times(1)).read(any(),anyString(),anyString(),anyLong(),anyString());
        root.setAssignedAgentId("agent");
        when(storage.read(any(),eq("private/object"),eq(hash),eq((long)bytes.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(new byte[bytes.length],hash,bytes.length,"image/png"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.listConversationOutputs(
                        OWNER,"task-1","run-1")).getReason());
    }

    @Test void nativeConversationInboxSqlHasByteExactOwnerTargetModeAndStableOrder() throws Exception {
        var select=cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper.class.getMethod(
                        "listQueuedConversationsByTarget",String.class,String.class,String.class,String.class,Long.class,String.class,int.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        assertNotNull(select);
        String sql=String.join(" ",select.value());
        assertTrue(sql.contains("execution_mode='CONVERSATION'"));
        assertTrue(sql.contains("execution_state='QUEUED'"));
        assertTrue(sql.contains("ORDER BY created_at ASC, CAST(execution_id AS BINARY) ASC LIMIT #{limit}"));
        assertTrue(sql.contains("created_at &gt; #{afterCreatedAt}"));
        assertTrue(sql.contains("CAST(execution_id AS BINARY) &gt; CAST(#{afterExecutionId} AS BINARY)"));
        assertTrue(sql.contains("<if test='afterCreatedAt != null and afterExecutionId != null'>"));
        for (String key:List.of("tenant_id","client_id","owner_jiacn","target_agent_id","execution_mode")) {
            assertTrue(sql.contains("CAST("+key+" AS BINARY)"),key);
            assertTrue(sql.contains("OCTET_LENGTH("+key+")"),key);
        }
    }

    @Test void nativeConversationInboxIsClosedOffByDefaultAndScopedToLiveGrant() {
        execution.setCreatedAt(100L);
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16))
                .thenReturn(List.of(execution));
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> service.runtimeConversationCommands(RUNTIME,16)).getReason());
        verify(rows,never()).listQueuedConversationsByTarget(any(),any(),any(),any(),any(),any(),anyInt());
        enable();
        var commands=service.runtimeConversationCommands(RUNTIME,16);
        assertEquals(1,commands.size());
        assertEquals("task-1",commands.getFirst().taskId());
        assertEquals(commandId(),commands.getFirst().commandId());
        assertEquals(messageId(),commands.getFirst().messageId());
        assertFalse(commands.toString().contains("grant-1"));
        assertFalse(commands.toString().contains("lease"));
        execution.setTargetAgentId("other-agent");
        assertTrue(service.runtimeConversationCommands(RUNTIME,16).isEmpty());
        execution.setTargetAgentId("agent");
        when(grants.admit(any(),any(),any(),anyLong(),anyLong(),any(),any(),eq(true)))
                .thenThrow(new IllegalStateException("revoked"));
        assertTrue(service.runtimeConversationCommands(RUNTIME,16).isEmpty());
        verify(rows,never()).update(any());

    }

    @Test void sixteenRevokedHeadsDoNotStarveSeventeenthOwnedCommandAtSameTimestamp() {
        enable();
        var revoked=new java.util.ArrayList<PersonalWorkspaceExecutionEntity>();
        for (int i=0;i<16;i++) {
            var row=new PersonalWorkspaceExecutionEntity().setExecutionId("exec-"+String.format("%02d",i))
                    .setTaskId("task-1").setRunId("run-"+i).setCreatedAt(100L)
                    .setExecutionMode("CONVERSATION").setExecutionState("QUEUED")
                    .setTargetAgentId("agent").setTaskGrantId("grant-revoked")
                    .setTaskGrantVersion(1L).setAssignmentRevision(7L)
                    .setPermittedOperation("GENERATE_IMAGE");
            row.setTenantId("0");row.setClientId("client");row.setOwnerJiacn("owner");
            revoked.add(row);
        }
        execution.setExecutionId("exec-16").setRunId("run-16").setCreatedAt(100L);
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16))
                .thenReturn(revoked);
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",100L,"exec-15",16))
                .thenReturn(List.of(execution));
        when(rows.findByTaskRun(eq("0"),eq("client"),eq("owner"),eq("task-1"),anyString()))
                .thenAnswer(i -> "run-16".equals(i.getArgument(4)) ? execution :
                        revoked.stream().filter(row -> row.getRunId().equals(i.getArgument(4))).findFirst().orElse(null));
        when(grants.admit(any(),eq("task-1"),eq("grant-revoked"),anyLong(),anyLong(),any(),any(),eq(true)))
                .thenThrow(new IllegalStateException("revoked"));
        var result=service.runtimeConversationCommands(RUNTIME,16);
        assertEquals(1,result.size());
        assertEquals("pwe_cmd_"+sha("command\nexec-16".getBytes(StandardCharsets.UTF_8)),result.getFirst().commandId());
        verify(rows).listQueuedConversationsByTarget("0","client","owner","agent",100L,"exec-15",16);
        verify(rows,never()).update(any());
        verifyNoInteractions(writes);
    }

    @Test void hostileScopeRowCannotLeakAndRepeatedCursorFailsClosed() {
        enable();
        var foreignRows=new java.util.ArrayList<PersonalWorkspaceExecutionEntity>();
        for (int i=0;i<4;i++) {
            var row=new PersonalWorkspaceExecutionEntity().setExecutionId("exec-foreign-"+i)
                    .setTaskId("foreign-task").setRunId("foreign-run").setCreatedAt(100L+i)
                    .setExecutionMode("CONVERSATION").setExecutionState("QUEUED")
                    .setTargetAgentId(i==3 ? "other-agent" : "agent");
            row.setTenantId(i==0 ? "other-tenant" : "0");
            row.setClientId(i==1 ? "other-client" : "client");
            row.setOwnerJiacn(i==2 ? "other-owner" : "owner");
            foreignRows.add(row);
        }
        execution.setCreatedAt(104L);
        var page=new java.util.ArrayList<>(foreignRows);
        page.add(execution);
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16))
                .thenReturn(page);
        assertEquals(List.of("task-1"),service.runtimeConversationCommands(RUNTIME,16).stream()
                .map(PersonalWorkspaceExecutionService.ConversationRuntimeCommand::taskId).toList());
        verify(rows,never()).findByTaskRun(any(),any(),any(),eq("foreign-task"),any());
        var repeated=new java.util.ArrayList<PersonalWorkspaceExecutionEntity>();
        for(int i=0;i<16;i++) {
            var row=new PersonalWorkspaceExecutionEntity().setExecutionId("page-"+String.format("%02d",i)).setCreatedAt(200L)
                    .setExecutionMode("CONVERSATION").setExecutionState("QUEUED").setTargetAgentId("other");
            repeated.add(row);
        }
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16))
                .thenReturn(repeated);
        when(rows.listQueuedConversationsByTarget("0","client","owner","agent",200L,"page-15",16))
                .thenReturn(List.of(repeated.get(15)));
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,
                () -> service.runtimeConversationCommands(RUNTIME,16)).getReason());
    }

    @Test void defaultOffAndLegacyUnfencedRuntimeLaneNeverStartsConversation() {
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.claimConversationStart(
                        RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.start(
                        RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.stageOutput(
                        RUNTIME,"task-1","run-1","output_1","bird.png","image/png",new byte[]{1})).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.fail(
                        RUNTIME,"task-1","run-1","AGENT_DELIVERY_FAILED")).getReason());
        when(rows.listQueuedByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),anyInt()))
                .thenReturn(List.of(execution));
        assertTrue(service.runtimeQueuedCommands(RUNTIME,16).isEmpty());
        verify(rows,never()).update(any());
        verifyNoInteractions(writes);
    }

    @Test void nativeClaimIsIdempotentWhileLiveAndNewVersionFencesExpiredAttempt() {
        enable();
        var first=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        var replay=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        assertEquals(first,replay);
        verify(rows,times(1)).update(execution);
        var otherRuntime=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime-2");
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.claimConversationStart(
                        otherRuntime,"task-1","run-1",commandId(),messageId())).getReason());
        execution.setConversationLeaseExpiresAt(System.currentTimeMillis()-1L);
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.renewConversationLease(
                        RUNTIME,"task-1","run-1",first.fence())).getReason());
        var recovered=service.claimConversationStart(otherRuntime,"task-1","run-1",commandId(),messageId());
        assertEquals(2,recovered.version());assertNotEquals(first.token(),recovered.token());
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.stageConversationOutput(
                        RUNTIME,"task-1","run-1",first.fence(),"output_1","bird.png","image/png",new byte[]{1}))
                .getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.failConversation(
                        RUNTIME,"task-1","run-1",first.fence(),"AGENT_DELIVERY_FAILED")).getReason());
        var renewed=service.renewConversationLease(otherRuntime,"task-1","run-1",recovered.fence());
        assertEquals(recovered.version(),renewed.version());
        assertEquals(recovered.token(),renewed.token());
        assertTrue(renewed.expiresAt()>System.currentTimeMillis());
        verify(storage,never()).store(any(),any(byte[].class),anyString());
    }

    @Test void leasedStageUsesWorkspacePrivateAndInvalidFenceCannotStoreBytes() throws Exception {
        enable();var bytes=png();var hash=sha(bytes);
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        when(storage.store(any(),any(byte[].class),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredObject("private/object",hash,bytes.length,"image/png"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.stageConversationOutput(
                        RUNTIME,"task-1","run-1",new PersonalWorkspaceExecutionService.ConversationFence(
                                lease.version()+1,lease.token()),"output_1","bird.png","image/png",bytes)).getReason());
        verify(storage,never()).store(any(),any(byte[].class),anyString());
        var staged=service.stageConversationOutput(RUNTIME,"task-1","run-1",lease.fence(),
                "output_1","bird.png","image/png",bytes);
        assertEquals(hash,staged.sha256());
        verify(rows).insertOutput(argThat(out -> "CONVERSATION".equals(out.getOutputPurpose())
                && out.getWorkspaceFileId()==null && out.getArtifactId()==null));
        verifyNoInteractions(writes);
    }

    private void enable() { ReflectionTestUtils.setField(service,"conversationExecutionEnabled",true); }
    private static String commandId() { return "pwe_cmd_"+sha("command\nexec-1".getBytes(StandardCharsets.UTF_8)); }
    private static String messageId() { return "pwe_msg_"+sha("message\nexec-1".getBytes(StandardCharsets.UTF_8)); }

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
