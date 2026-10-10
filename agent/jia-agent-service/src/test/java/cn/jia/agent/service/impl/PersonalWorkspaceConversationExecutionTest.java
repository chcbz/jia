package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.*;
import cn.jia.agent.entity.*;
import cn.jia.agent.service.*;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import cn.jia.agent.security.*;
import org.springframework.security.core.context.SecurityContextHolder;
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
    private final PersonalWorkspaceDao workspace=mock(PersonalWorkspaceDao.class);
    private final AgentRuntimeDao runtimes=mock(AgentRuntimeDao.class);
    private final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    private final PersonalWorkspaceWriteService writes=mock(PersonalWorkspaceWriteService.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final WorkspaceConversationAccessService conversation=mock(WorkspaceConversationAccessService.class);
    private final ControlledImageFollowupAuthorityService followup=mock(ControlledImageFollowupAuthorityService.class);
    private final ControlledImageExecutionSourceV3Dao followupSources=mock(ControlledImageExecutionSourceV3Dao.class);
    private PersonalWorkspaceExecutionServiceImpl service;
    private AgentTaskMetaEntity root;
    private PersonalWorkspaceExecutionEntity execution;
    private PersonalWorkspaceExecutionOutputEntity output;

    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }
    private void principal(PersonalWorkspaceExecutionService.RuntimeScope scope) throws Exception {
        var principal=org.springframework.beans.BeanUtils.instantiateClass(AgentRuntimeAuthentication.class.getDeclaredConstructor(AgentRuntimeAuthentication.Scope.class),new AgentRuntimeAuthentication.Scope(
                scope.tenantId(),scope.clientId(),scope.ownerJiacn(),scope.agentId(),scope.runtimeInstanceId()));
        principal.setDetails(new AgentRuntimeAuthenticationService.Proof(principal.getPrincipal(),
                "rti_"+"1".repeat(32),"host",1,"b".repeat(64),7,2));
        SecurityContextHolder.getContext().setAuthentication(principal);
    }
    @BeforeEach void setUp() throws Exception {
        when(storage.maxContentBytes()).thenReturn(10_000L);
        var agent=new AgentRuntimeEntity();
        agent.setAgentId("agent");agent.setClientId("client");agent.setOwnerJiacn("owner");
        when(runtimes.findCandidateRosterByOwner("client","owner")).thenReturn(List.of(agent));
        service=new PersonalWorkspaceExecutionServiceImpl(rows,workspace,
                mock(PersonalWorkspaceTaskLinkDao.class),runtimes,storage,writes,
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        service.setConversationAdmission(grants,transactions);
        principal(RUNTIME);
        var authentication=mock(AgentRuntimeAuthenticationService.class);
        when(authentication.withNativeFence(any(),any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(1)).get());
        service.setRuntimeAuthentication(authentication);
        service.setControlledImageFollowupV3(followup,followupSources);
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
                .setExecutionState("QUEUED").setGrantRevision(1L).setOutputContentMimeType("image/png");
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

    @Test void exactGrantedImageVersionIsMaterializedOnlyInTheScopedConversationExecution() {
        var expected=new PersonalWorkspaceExecutionService.ReferenceSelection("file-1",1,"REFERENCE",
                "image/png",100,"a".repeat(64));
        var granted=new AgentTaskExecutionGrantService.AuthorizedInput("file-1",1,"REFERENCE",
                "image/png",100,"a".repeat(64));
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1,7,
                        "agent","GENERATE_IMAGE",true,List.of(granted)));
        var file=new PersonalWorkspaceFileEntity().setFileId("file-1").setState("ACTIVE");
        var version=new PersonalWorkspaceVersionEntity().setFileId("file-1").setVersion(1)
                .setOriginalFilename("bird.png").setContentMimeType("image/png")
                .setByteLength(100L).setContentHash("a".repeat(64)).setStorageUri("owner-private/object");
        when(workspace.lockFile("0","client","owner","file-1")).thenReturn(file);
        when(workspace.findVersion("0","client","owner","file-1",1)).thenReturn(version);
        var created=service.createConversation(OWNER,new PersonalWorkspaceExecutionService.ConversationCreate(
                "42","task-1","agent","intent-1","grant-1",1,7,"GENERATE_IMAGE",
                "draw a bird using the reference","image/png",List.of(expected)));
        assertEquals("CONVERSATION",created.executionMode());
        verify(rows).insertInput(argThat(input -> "file-1".equals(input.getFileId())
                && input.getFileVersion()==1 && "ACTIVE".equals(input.getGrantState())
                && "owner-private/object".equals(input.getStorageUri())
                && "a".repeat(64).equals(input.getContentHash()) && "0".equals(input.getTenantId())
                && "client".equals(input.getClientId()) && "owner".equals(input.getOwnerJiacn())));
        verifyNoInteractions(writes);
    }

    @Test void fencedRuntimeReadsOnlyMatchedVersionedReferenceBytes() {
        byte[] bytes=new byte[100];
        String hash=sha(bytes);
        var granted=new AgentTaskExecutionGrantService.AuthorizedInput("file-1",1,"REFERENCE",
                "image/png",100,hash);
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1,7,
                        "agent","GENERATE_IMAGE",true,List.of(granted)));
        var row=new PersonalWorkspaceExecutionInputEntity().setInputRef("input_1")
                .setExecutionId("exec-1").setOwnerJiacn("owner").setFileId("file-1")
                .setFileVersion(1).setOriginalFilename("bird.png").setContentMimeType("image/png")
                .setByteLength(100L).setContentHash(hash)
                .setStorageUri("owner-private/object").setGrantState("ACTIVE");
        row.setTenantId("0");row.setClientId("client");
        when(rows.listInputs("0","client","owner","exec-1")).thenReturn(List.of(row));
        when(rows.lockInput("0","client","owner","exec-1","input_1")).thenReturn(row);
        when(storage.read(any(),eq("owner-private/object"),eq(hash),eq(100L),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,100,"image/png"));
        enable();
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        var manifest=service.conversationInputs(RUNTIME,"task-1","run-1",lease.fence());
        assertFalse(manifest.noReferencedMaterials());
        assertEquals(List.of("input_1"),manifest.inputs().stream().map(
                PersonalWorkspaceExecutionService.RuntimeInput::inputRef).toList());
        assertArrayEquals(bytes,service.conversationInputContent(RUNTIME,"task-1","run-1",
                lease.fence(),"input_1").bytes());
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputContent(
                        RUNTIME,"task-1","run-1",lease.fence(),"other")).getReason());
        row.setFileVersion(2);
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputContent(
                        RUNTIME,"task-1","run-1",lease.fence(),"input_1")).getReason());
        verify(storage,times(1)).read(any(),eq("owner-private/object"),eq(hash),eq(100L),eq("image/png"));
    }

    @Test void alteredOrOmittedReferenceNeverCreatesAnExecutionWhenGrantContainsOne() {
        var granted=new AgentTaskExecutionGrantService.AuthorizedInput("file-1",1,"REFERENCE",
                "image/png",100,"a".repeat(64));
        when(grants.admit(any(),eq("task-1"),eq("grant-1"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq(true))).thenReturn(
                new AgentTaskExecutionGrantService.Admission("grant-1",1,7,
                        "agent","GENERATE_IMAGE",true,List.of(granted)));
        for (var refs:List.of(
                List.<PersonalWorkspaceExecutionService.ReferenceSelection>of(),
                List.of(new PersonalWorkspaceExecutionService.ReferenceSelection("file-1",2,"REFERENCE",
                        "image/png",100,"a".repeat(64))),
                List.of(new PersonalWorkspaceExecutionService.ReferenceSelection("file-1",1,"REFERENCE",
                        "image/png",100,"b".repeat(64))))) {
            var command=new PersonalWorkspaceExecutionService.ConversationCreate("42","task-1","agent",
                    "intent-1","grant-1",1,7,"GENERATE_IMAGE","draw a bird","image/png",refs);
            assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                    PersonalWorkspaceExecutionService.Failure.class,()->service.createConversation(
                            OWNER,command)).getReason());
        }
        verify(rows,never()).insert(any());
        verify(rows,never()).insertInput(any());
        verifyNoInteractions(workspace);
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

    @Test void providerStartIsDurableOneShotEvenWhenLeaseOrClientRetries() {
        enable();
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        service.beginConversationProviderStart(RUNTIME,"task-1","run-1",lease.fence());
        assertNotNull(execution.getConversationProviderStartedAt());
        assertEquals(lease.version(),execution.getConversationProviderLeaseVersion());
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,
                assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->
                        service.beginConversationProviderStart(RUNTIME,"task-1","run-1",lease.fence())).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,
                assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->
                        service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        verify(rows, times(2)).update(execution); // lease claim then the single durable admission
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

    @Test void exactNativeNoReferenceManifestRequiresActiveFenceAndLiveGrant() {
        enable();
        var lease=service.claimConversationStart(RUNTIME,"task-1","run-1",commandId(),messageId());
        var input=service.conversationInputs(RUNTIME,"task-1","run-1",lease.fence());
        assertEquals("exec-1",input.executionId());
        assertEquals(lease.version(),input.leaseVersion());
        assertTrue(input.noReferencedMaterials());
        assertTrue(input.inputs().isEmpty());
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputs(
                        RUNTIME,"task-1","run-1",new PersonalWorkspaceExecutionService.ConversationFence(
                                lease.version(),"stale"))).getReason());
        var other=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","other-runtime");
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputs(
                        other,"task-1","run-1",lease.fence())).getReason());
        when(rows.listInputs("0","client","owner","exec-1")).thenReturn(List.of(
                new PersonalWorkspaceExecutionInputEntity()));
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputs(
                        RUNTIME,"task-1","run-1",lease.fence())).getReason());
        when(rows.listInputs("0","client","owner","exec-1")).thenReturn(List.of());
        root.setAssignedAgentId("other-agent");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.conversationInputs(
                        RUNTIME,"task-1","run-1",lease.fence())).getReason());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
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

    private ControlledImageExecutionSourceV3Entity committedEditFixture(boolean workspaceSource) throws Exception {
        var bytes=png();var hash=sha(bytes);
        execution.setExecutionProtocolVersion(3).setPermittedOperation("EDIT_IMAGE").setExecutionState("OUTPUT_COMMITTED");
        output=new PersonalWorkspaceExecutionOutputEntity().setOutputId("output_1").setExecutionId("exec-1")
                .setOwnerJiacn("owner").setOutputPurpose("CONVERSATION").setOutputState("COMMITTED")
                .setContentMimeType("image/png").setContentHash(hash).setByteLength((long)bytes.length).setStorageUri("private/object");
        when(rows.lockOutputs("0","client","owner","exec-1")).thenReturn(List.of(output));
        when(storage.read(any(),eq("private/object"),eq(hash),eq((long)bytes.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"));
        var source=new ControlledImageExecutionSourceV3Entity().setExecutionId("exec-1").setOwnerJiacn("owner")
                .setInputRef("input_1").setInputOrdinal(1).setContentMimeType("image/png")
                .setByteLength(100L).setContentSha256("a".repeat(64)).setCreatedAt(1L);
        source.setTenantId("0");source.setClientId("client");
        java.util.Map<String,Object> descriptor;
        if(workspaceSource) {
            source.setSourceKind("TASK_LINKED_WORKSPACE_VERSION").setFileId("file-1").setFileVersion(1).setPurpose("INPUT");
            descriptor=java.util.Map.of("kind",source.getSourceKind(),"fileId","file-1","version","1","purpose","INPUT");
        } else {
            source.setSourceKind("CURRENT_CONVERSATION_ASSET").setConversationId("42").setConversationGeneration(1L)
                    .setAssetId("asset-1").setAssetRevision(1L).setProducerRequestId("prior-request").setProducerRequestRevision(1L)
                    .setProducerStepId("prior-step").setProducerExecutionId("prior-exec").setProducerRunId("prior-run").setProducerOutputId("prior-output");
            descriptor=java.util.Map.of("kind",source.getSourceKind(),"conversationId","42","conversationGeneration","1",
                    "assetId","asset-1","assetRevision","1","producerRequestId","prior-request","producerStepId","prior-step",
                    "producerExecutionId","prior-exec","producerRunId","prior-run","producerOutputId","prior-output");
        }
        var json=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        source.setSourceJson(json.writeValueAsString(descriptor));
        var inputs=List.of(java.util.Map.of("byteLength","100","contentMimeType","image/png","inputRef","input_1",
                "sha256",source.getContentSha256(),"source",descriptor));
        var domain=java.util.Map.of("conversationId","42","executionId","exec-1","inputs",inputs,"noReferencedMaterials",false,
                "operation","EDIT_IMAGE","runId","run-1","schemaVersion",1,"taskId","task-1");
        execution.setRuntimeInputSnapshotDigest(sha(json.writeValueAsString(domain).getBytes(StandardCharsets.UTF_8)));
        when(followupSources.list("0","client","owner","exec-1")).thenReturn(List.of(source));
        when(followup.committedResultAuthority(any(),eq("task-1"),eq("run-1")))
                .thenReturn(new ControlledImageFollowupAuthorityService.CommittedResultAuthority("exec-1","agent","EDIT_IMAGE",execution.getRuntimeInputSnapshotDigest()));
        return source;
    }

    @Test void committedEditCatalogCarriesExactParentWithoutProviderOrPersonalSpace() throws Exception {
        committedEditFixture(false);
        var result=service.listConversationOutputs(OWNER,"task-1","run-1").getFirst();
        assertEquals(new PersonalWorkspaceExecutionService.OutputReplacement("prior-request","prior-step","prior-output","a".repeat(64)),result.replaces());
        verifyNoInteractions(writes,workspace,runtimes);
        verify(followup,never()).runtimeAuthority(any(),anyString(),anyString(),anyString());
        verify(storage,never()).store(any(),any(byte[].class),anyString());
        assertFalse(result.toString().contains("prior-run"));
    }
    @Test void editFromWorkspaceDoesNotReplaceUnrelatedConversationResult() throws Exception {
        committedEditFixture(true);
        assertNull(service.listConversationOutputs(OWNER,"task-1","run-1").getFirst().replaces());
        verifyNoInteractions(workspace,writes);
    }
    @Test void alteredOrForeignEditSourcesAndAmbiguousOutputCountFailBeforeReadingBytes() throws Exception {
        var source=committedEditFixture(false);
        source.setProducerOutputId("changed");
        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()->service.listConversationOutputs(OWNER,"task-1","run-1")).getReason());
        source.setProducerOutputId("prior-output").setOwnerJiacn("foreign");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.listConversationOutputs(OWNER,"task-1","run-1"));
        source.setOwnerJiacn("owner");
        when(rows.lockOutputs("0","client","owner","exec-1")).thenReturn(List.of(output,output));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.listConversationOutputs(OWNER,"task-1","run-1"));
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
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


    @Test void v3ConsumedResultStagesAndCommitsWithResultAuthorityOnly() throws Exception {
        enable();startedV3();byte[] bytes=png();String hash=sha(bytes);
        when(storage.store(any(),any(byte[].class),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredObject("private/result",hash,bytes.length,"image/png"));
        when(rows.lockOutput("0","client","owner","exec-1","output_1"))
                .thenAnswer(ignored -> output);
        doAnswer(invocation -> { output=invocation.getArgument(0);return null; })
                .when(rows).insertOutput(any());
        when(rows.lockOutputs("0","client","owner","exec-1"))
                .thenAnswer(ignored -> List.of(output));
        when(storage.read(any(),eq("private/result"),eq(hash),eq((long)bytes.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"));
        var fence=new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token");

        var staged=service.stageConversationOutput(RUNTIME,"task-1","run-1",fence,
                "output_1","bird.png","image/png",bytes);
        String manifest="pwe_m_"+sha(("task-1\nrun-1\noutput_1\n"+hash+"\n"+bytes.length+"\n")
                .getBytes(StandardCharsets.UTF_8));
        var committed=service.commitConversationOutput(RUNTIME,"task-1","run-1",fence,manifest,
                List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",hash,bytes.length)));

        assertEquals(hash,staged.sha256());
        assertEquals("COMMITTED",committed.state());
        assertEquals("OUTPUT_COMMITTED",execution.getExecutionState());
        verify(followup,times(4)).runtimeAuthority(argThat(scope -> "runtime".equals(scope.runtimeInstanceId())),
                eq("task-1"),eq("run-1"),eq("RESULT"));
        verifyNoInteractions(followupSources,writes);
    }

    @Test void v3ConsumedResultFailureUsesResultAuthorityAndKeepsProviderStartFact() {
        enable();startedV3();long startedAt=execution.getConversationProviderStartedAt();
        var result=service.failConversation(RUNTIME,"task-1","run-1",
                new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token"),
                "AGENT_DELIVERY_FAILED");

        assertEquals("FAILED",result.state());
        assertEquals(startedAt,execution.getConversationProviderStartedAt());
        assertEquals(1L,execution.getConversationProviderLeaseVersion());
        verify(followup).runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("FAILURE"));
        verify(rows).update(execution);
        verify(storage,never()).store(any(),any(byte[].class),anyString());
    }


    @Test void v3PreStartFailureUsesReservedFailureAuthorityWithoutConsumedResultLease() {
        enable();reservedV3();

        var result=service.failConversation(RUNTIME,"task-1","run-1",
                new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token"),
                "AGENT_DELIVERY_FAILED");

        assertEquals("FAILED",result.state());
        assertNull(execution.getConversationProviderStartedAt());
        assertNull(execution.getConversationProviderLeaseVersion());
        verify(followup).runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("FAILURE"));
        verify(followup,never()).runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("RESULT"));
        verify(rows).update(execution);
        verify(storage,never()).store(any(),any(byte[].class),anyString());
    }

    @Test void v3ResultStillRejectsStaleFenceAndCurrentConversationAclRevocation() throws Exception {
        enable();startedV3();byte[] bytes=png();
        var stale=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.stageConversationOutput(RUNTIME,"task-1","run-1",
                        new PersonalWorkspaceExecutionService.ConversationFence(2,"lease-token"),
                        "output_1","bird.png","image/png",bytes));
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,stale.getReason());
        verify(storage,never()).store(any(),any(byte[].class),anyString());

        reset(conversation);
        when(conversation.requireAccessible(any(),eq("42")))
                .thenThrow(new IllegalStateException("conversation ACL revoked"));
        var denied=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.failConversation(RUNTIME,"task-1","run-1",
                        new PersonalWorkspaceExecutionService.ConversationFence(1,"lease-token"),
                        "AGENT_DELIVERY_FAILED"));
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,denied.getReason());
        verify(rows,never()).update(execution);
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


    @Test void spoolOnlyResultUploadsAndCommitsAfterExpiryWithoutRenewingOrReexecuting() throws Exception {
        byte[] bytes=recoverableV3();
        var replacement=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","replacement");
        execution.setConversationLeaseExpiresAt(1L);
        principal(replacement);
        var proof=recoveryProof(bytes);
        var first=service.recoverConversationOutput(replacement,"task-1","run-1",recoveryManifest(bytes),
                proof,"bird.png","image/png",bytes);
        assertEquals("COMMITTED",first.state());
        assertEquals("OUTPUT_COMMITTED",execution.getExecutionState());
        assertEquals(first,service.recoverConversationOutput(replacement,"task-1","run-1",recoveryManifest(bytes),
                proof,"bird.png","image/png",bytes));
        assertEquals(first,service.recoverStagedConversationOutput(replacement,"task-1","run-1",recoveryManifest(bytes),proof));
        verify(storage,times(1)).store(any(),any(byte[].class),eq("image/png"));
        verify(rows,times(1)).insertOutput(any());verify(rows,times(1)).update(execution);
        assertEquals("runtime",execution.getConversationLeaseRuntimeId());
        assertEquals(1L,execution.getConversationLeaseVersion());assertEquals(1L,execution.getConversationLeaseExpiresAt());
        assertEquals(10L,execution.getConversationProviderStartedAt());
        verify(followup,times(6)).runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("RESULT_RECOVERY"));
        verifyNoMoreInteractions(followup);verifyNoInteractions(followupSources,writes);
    }

    @Test void spoolRecoveryRejectsForeignLiveLeaseAndMissingStartWithoutWritingBytes() throws Exception {
        byte[] bytes=recoverableV3();var proof=recoveryProof(bytes);String manifest=recoveryManifest(bytes);
        var replacement=new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","replacement");
        principal(replacement);
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                replacement,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        execution.setConversationProviderStartedAt(null);
        principal(RUNTIME);
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        verify(storage,never()).store(any(),any(byte[].class),anyString());verify(rows,never()).insertOutput(any());
    }

    @Test void spoolRecoveryRejectsChangedBytesManifestCommandAndSnapshotBeforeStorage() throws Exception {
        byte[] bytes=recoverableV3();var proof=recoveryProof(bytes);String manifest=recoveryManifest(bytes);
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",changed));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1","pwe_m_"+"0".repeat(64),proof,"bird.png","image/png",bytes));
        for (var wrong:List.of(
                new PersonalWorkspaceExecutionService.ConversationResultRecovery("other",commandId(),messageId(),"7".repeat(64),proof.outputs()),
                new PersonalWorkspaceExecutionService.ConversationResultRecovery("exec-1","wrong",messageId(),"7".repeat(64),proof.outputs()),
                new PersonalWorkspaceExecutionService.ConversationResultRecovery("exec-1",commandId(),"wrong","7".repeat(64),proof.outputs()),
                new PersonalWorkspaceExecutionService.ConversationResultRecovery("exec-1",commandId(),messageId(),"8".repeat(64),proof.outputs())))
            assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                    RUNTIME,"task-1","run-1",manifest,wrong,"bird.png","image/png",bytes));
        verify(storage,never()).store(any(),any(byte[].class),anyString());verify(rows,never()).insertOutput(any());
    }

    @Test void spoolRecoveryStillRequiresCurrentAclAssignmentAndOwnerAndConsumedAuthority() throws Exception {
        byte[] bytes=recoverableV3();var proof=recoveryProof(bytes);String manifest=recoveryManifest(bytes);
        root.setAssignedAgentId("other");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        root.setAssignedAgentId("agent");
        for(var scope:List.of(
                new PersonalWorkspaceExecutionService.RuntimeScope("foreign","client","owner","agent","runtime"),
                new PersonalWorkspaceExecutionService.RuntimeScope("0","foreign","owner","agent","runtime"),
                new PersonalWorkspaceExecutionService.RuntimeScope("0","client","foreign","agent","runtime"),
                new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","foreign","runtime")))
            assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                    scope,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        when(conversation.requireAccessible(any(),eq("42"))).thenThrow(new IllegalStateException("revoked"));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        verify(storage,never()).store(any(),any(byte[].class),anyString());verify(rows,never()).insertOutput(any());
    }

    @Test void spoolRecoveryNeverOverwritesAConflictingPersistedOutputOrResurrectsFailure() throws Exception {
        byte[] bytes=recoverableV3();var proof=recoveryProof(bytes);String manifest=recoveryManifest(bytes);
        service.recoverConversationOutput(RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes);
        output.setContentHash("0".repeat(64));
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        output.setContentHash(sha(bytes));execution.setExecutionState("FAILED");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,()->service.recoverConversationOutput(
                RUNTIME,"task-1","run-1",manifest,proof,"bird.png","image/png",bytes));
        verify(storage,times(1)).store(any(),any(byte[].class),anyString());verify(rows,times(1)).insertOutput(any());
    }

    private byte[] recoverableV3() throws Exception {
        enable();startedV3();byte[] bytes=png();String hash=sha(bytes);
        var authority=followup.runtimeAuthority(null,"task-1","run-1","RESULT");
        clearInvocations(followup);
        when(followup.runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("RESULT_RECOVERY"))).thenReturn(authority);
        when(storage.store(any(),any(byte[].class),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredObject("private/result",hash,bytes.length,"image/png"));
        when(rows.lockOutput("0","client","owner","exec-1","output_1")).thenAnswer(ignored -> output);
        doAnswer(invocation -> { output=invocation.getArgument(0);return null; }).when(rows).insertOutput(any());
        when(rows.lockOutputs("0","client","owner","exec-1"))
                .thenAnswer(ignored -> output==null?List.of():List.of(output));
        when(storage.read(any(),eq("private/result"),eq(hash),eq((long)bytes.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"));
        return bytes;
    }
    private PersonalWorkspaceExecutionService.ConversationResultRecovery recoveryProof(byte[] bytes) {
        return new PersonalWorkspaceExecutionService.ConversationResultRecovery("exec-1",commandId(),messageId(),
                "7".repeat(64),List.of(new PersonalWorkspaceExecutionService.OutputDeclaration("output_1",sha(bytes),bytes.length)));
    }
    private String recoveryManifest(byte[] bytes) {
        return "pwe_m_"+sha(("task-1\nrun-1\noutput_1\n"+sha(bytes)+"\n"+bytes.length+"\n").getBytes(StandardCharsets.UTF_8));
    }

    private void startedV3() {
        execution.setExecutionProtocolVersion(3)
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                .setRuntimeInputSnapshotDigest("7".repeat(64))
                .setConversationLeaseToken("lease-token").setConversationLeaseRuntimeId("runtime")
                .setConversationLeaseVersion(1L).setConversationLeaseExpiresAt(Long.MAX_VALUE)
                .setConversationProviderStartedAt(10L).setConversationProviderLeaseVersion(1L);
        var authority=new ControlledImageFollowupAuthorityService.RuntimeAuthority(
                "exec-1","GENERATE_IMAGE","7".repeat(64),
                new ControlledImageFollowupAuthorityService.ProviderExecution(
                        "CONTROLLED_IMAGE_HTTP_V1",execution.getControlledConsentId(),
                        "binding","1","model",16,1,1));
        when(followup.runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("RESULT")))
                .thenReturn(authority);
        when(followup.runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("FAILURE")))
                .thenReturn(authority);
    }

    private void reservedV3() {
        execution.setExecutionProtocolVersion(3)
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                .setRuntimeInputSnapshotDigest("7".repeat(64))
                .setConversationLeaseToken("lease-token").setConversationLeaseRuntimeId("runtime")
                .setConversationLeaseVersion(1L).setConversationLeaseExpiresAt(Long.MAX_VALUE);
        when(followup.runtimeAuthority(any(),eq("task-1"),eq("run-1"),eq("FAILURE")))
                .thenReturn(new ControlledImageFollowupAuthorityService.RuntimeAuthority(
                        "exec-1","GENERATE_IMAGE","7".repeat(64),
                        new ControlledImageFollowupAuthorityService.ProviderExecution(
                                "CONTROLLED_IMAGE_HTTP_V1",execution.getControlledConsentId(),
                                "binding","1","model",16,1,1)));
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
