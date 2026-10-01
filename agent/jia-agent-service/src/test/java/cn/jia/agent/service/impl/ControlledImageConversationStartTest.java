package cn.jia.agent.service.impl;

import cn.jia.agent.config.PersonalWorkspaceExecutionProperties;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionInputEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentWorkItemLeaseService;
import cn.jia.agent.service.PersonalWorkspaceExecutionService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.chat.service.WorkspaceConversationAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlledImageConversationStartTest {
    private final PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
    private final PersonalWorkspaceDao workspace=mock(PersonalWorkspaceDao.class);
    private final PersonalWorkspaceTaskLinkDao taskLinks=mock(PersonalWorkspaceTaskLinkDao.class);
    private final AgentRuntimeDao runtimes=mock(AgentRuntimeDao.class);
    private final PersonalWorkspaceStorage storage=mock(PersonalWorkspaceStorage.class);
    private final PersonalWorkspaceWriteService writes=mock(PersonalWorkspaceWriteService.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final AgentTaskProviderCostConsentServiceImpl consents=mock(AgentTaskProviderCostConsentServiceImpl.class);
    private final WorkspaceConversationAccessService conversationAccess=
            mock(WorkspaceConversationAccessService.class);
    private final PersonalWorkspaceExecutionService.RuntimeScope runtime=
            new PersonalWorkspaceExecutionService.RuntimeScope("0","client","owner","agent","runtime");
    private PersonalWorkspaceExecutionServiceImpl service;
    private PersonalWorkspaceExecutionEntity execution;

    @BeforeEach void setUp() throws Exception {
        when(storage.maxContentBytes()).thenReturn(10_000_000L);
        service=new PersonalWorkspaceExecutionServiceImpl(executions,workspace,taskLinks,runtimes,storage,writes,
                new PersonalWorkspaceExecutionProperties(List.of("image/png")));
        service.setConversationAdmission(grants,transactions);
        service.setControlledConsentLifecycle(consents);
        service.setTaskExecutionDependencies(conversationAccess,mock(AgentTaskWorkItemDao.class),
                mock(AgentWorkItemLeaseService.class));
        when(conversationAccess.requireAccessible(
                new WorkspaceConversationAccessService.Scope("0","client","owner"),"conversation"))
                .thenReturn(conversationView());
        Field enabled=PersonalWorkspaceExecutionServiceImpl.class.getDeclaredField("conversationExecutionEnabled");
        enabled.setAccessible(true);enabled.setBoolean(service,true);
        doAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> callback=invocation.getArgument(4);
            return callback.apply(root());
        }).when(transactions).executeWithLockedTaskRootInOwnerScope(
                anyString(),anyString(),anyString(),anyString(),any());
        execution=execution();
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(consents.get(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(consentView("RESERVED","3"));
        when(consents.lockForBridge(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(consentEntity());
        when(executions.markControlledProviderStarted(eq("0"),eq("client"),eq("owner"),eq("task"),
                eq("run"),eq(execution.getExecutionId()),eq(execution.getControlledConsentId()),eq(1L),anyLong()))
                .thenReturn(true);
    }

    @Test void sharedInboxProjectsNativeV1NineFieldsAndControlledV2TenFieldsWithoutFallback() {
        PersonalWorkspaceExecutionEntity nativeRow=execution().setExecutionId("pwe_native")
                .setRunId("run-native").setControlledConsentId(null).setCreatedAt(1L);
        execution.setCreatedAt(2L);
        when(executions.listQueuedConversationsByTarget("0","client","owner","agent",null,null,16))
                .thenReturn(List.of(nativeRow,execution));
        when(executions.findByTaskRun(eq("0"),eq("client"),eq("owner"),eq("task"),anyString()))
                .thenAnswer(invocation -> "run-native".equals(invocation.getArgument(4))?nativeRow:execution);
        when(grants.admit(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq(true))).thenReturn(new AgentTaskExecutionGrantService.Admission(
                        "grant",1,7,"agent","GENERATE_IMAGE",true,List.of()));
        admit(List.of());

        List<? extends PersonalWorkspaceExecutionService.ConversationCommandView> views=
                service.runtimeConversationCommandViews(runtime,16);

        assertEquals(2,views.size());
        var nativeCommand=assertInstanceOf(
                PersonalWorkspaceExecutionService.ConversationRuntimeCommand.class,views.get(0));
        var controlled=assertInstanceOf(
                PersonalWorkspaceExecutionService.ControlledConversationRuntimeCommand.class,views.get(1));
        assertEquals(1,nativeCommand.schemaVersion());assertEquals("run-native",nativeCommand.runId());
        assertEquals(2,controlled.schemaVersion());assertEquals("run",controlled.runId());
        assertEquals(descriptor(),controlled.providerExecution());
        assertEquals(List.of(nativeCommand),service.runtimeConversationCommands(runtime,16));
        verify(grants,atLeastOnce()).admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),
                eq("agent"),eq("GENERATE_IMAGE"),eq("PROVIDER_START"),eq("pwe_execution"),
                eq("run"),eq("runtime"));
    }

    @Test void descriptorCommandExecutionRuntimeAndFenceDriftRejectBeforeConsumeOrStartWrite() {
        admit(List.of());
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(List.of());
        var exact=command();
        List<PersonalWorkspaceExecutionService.ControlledProviderStart> changed=List.of(
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId()+"x",exact.messageId(),exact.executionId(),exact.providerExecution(),exact.fence()),
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId(),exact.messageId()+"x",exact.executionId(),exact.providerExecution(),exact.fence()),
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId(),exact.messageId(),"other-execution",exact.providerExecution(),exact.fence()),
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId(),exact.messageId(),exact.executionId(),
                        new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
                                exact.providerExecution().consentId(),"other-binding","1","model",16,1,1),exact.fence()),
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId(),exact.messageId(),exact.executionId(),exact.providerExecution(),
                        new PersonalWorkspaceExecutionService.ConversationFence(2,exact.fence().token())),
                new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                        exact.commandId(),exact.messageId(),exact.executionId(),exact.providerExecution(),
                        new PersonalWorkspaceExecutionService.ConversationFence(1,
                                "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")));
        for (var command:changed) assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command));
        var otherRuntime=new PersonalWorkspaceExecutionService.RuntimeScope(
                "0","client","owner","agent","other-runtime");
        assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(otherRuntime,"task","run",exact));
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,never()).update(any(PersonalWorkspaceExecutionEntity.class));
        verify(executions,never()).markControlledProviderStarted(anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void zeroInputStartConsumesOnceAndNeverReturnsAnotherCallableReceipt() {
        admit(List.of());
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(List.of());
        var command=command();

        var receipt=service.beginControlledConversationProviderStart(runtime,"task","run",command);

        assertTrue(receipt.started());assertEquals(2,receipt.schemaVersion());
        assertEquals(command.providerExecution(),receipt.providerExecution());
        assertEquals(1L,receipt.leaseVersion());
        assertNotNull(execution.getConversationProviderStartedAt());
        assertEquals(1L,execution.getConversationProviderLeaseVersion());
        verify(consents,times(1)).consumeWithinLockedRoot(any(),eq("task"),any(),eq(3L),
                eq(execution.getExecutionId()),eq("run"),startsWith("pwe_lease_"));
        verify(executions).markControlledProviderStarted(eq("0"),eq("client"),eq("owner"),eq("task"),
                eq("run"),eq(execution.getExecutionId()),eq(execution.getControlledConsentId()),eq(1L),anyLong());
        verify(executions,never()).update(execution);

        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("EXISTING_RUN"),eq(execution.getExecutionId()),eq("run"),isNull()))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent",
                        "GENERATE_IMAGE",true,List.of(),
                        "mmd-ci-v1:"+execution.getControlledConsentId(),4L));
        var snapshot=service.conversationInputs(runtime,"task","run",command.fence());
        assertTrue(snapshot.noReferencedMaterials());
        assertEquals(execution.getExecutionId(),snapshot.executionId());

        var replay=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command));
        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,replay.getReason());
        verify(consents,times(1)).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,times(1)).markControlledProviderStarted(anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        verify(executions,never()).update(execution);
    }

    @Test void legacyProviderStartRejectsControlledLaneBeforeInputValidation() {
        admit(List.of());

        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginConversationProviderStart(runtime,"task","run",command().fence()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,failure.getReason());
        verify(executions,never()).listInputs(anyString(),anyString(),anyString(),anyString());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(executions,never()).markControlledProviderStarted(anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
    }

    @Test void markerCasLossRejectsReceiptAndLeavesInMemoryMarkerUnset() {
        admit(List.of());
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(List.of());
        when(executions.markControlledProviderStarted(anyString(),anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(false);

        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.TASK_CONFLICT,failure.getReason());
        assertNull(execution.getConversationProviderStartedAt());
        assertNull(execution.getConversationProviderLeaseVersion());
        verify(consents).consumeWithinLockedRoot(any(),eq("task"),any(),eq(3L),
                eq(execution.getExecutionId()),eq("run"),startsWith("pwe_lease_"));
        verify(executions).markControlledProviderStarted(anyString(),anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),eq(1L),anyLong());
    }

    @Test void sixteenCanonicalImageInputsAreFullyByteVerifiedWithoutTruncation() {
        List<AgentTaskExecutionGrantService.AuthorizedInput> authorized=new ArrayList<>();
        List<PersonalWorkspaceExecutionInputEntity> rows=new ArrayList<>();
        for (int i=1;i<=16;i++) {
            byte[] bytes=("image-"+i).getBytes(StandardCharsets.UTF_8);
            String hash=sha(bytes);String file="file-"+i;
            authorized.add(new AgentTaskExecutionGrantService.AuthorizedInput(
                    file,1,"REFERENCE","image/png",bytes.length,hash));
            PersonalWorkspaceExecutionInputEntity input=input(i,file,bytes.length,hash);
            rows.add(input);
            when(workspace.findFile("0","client","owner",file)).thenReturn(file(file));
            when(workspace.findVersion("0","client","owner",file,1)).thenReturn(version(file,bytes.length,hash));
            when(storage.read(any(),eq("memory://"+file),eq(hash),eq((long)bytes.length),eq("image/png")))
                    .thenReturn(new PersonalWorkspaceStorage.StoredContent(bytes,hash,bytes.length,"image/png"));
        }
        admit(authorized);
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(rows);

        var receipt=service.beginControlledConversationProviderStart(runtime,"task","run",command());

        assertTrue(receipt.started());
        verify(storage,times(16)).read(any(),anyString(),anyString(),anyLong(),eq("image/png"));
        verify(consents).consumeWithinLockedRoot(any(),eq("task"),any(),eq(3L),anyString(),eq("run"),anyString());
    }

    @Test void seventeenInputsRejectBeforeAnyByteReadConsumeOrStartWrite() {
        List<AgentTaskExecutionGrantService.AuthorizedInput> authorized=new ArrayList<>();
        List<PersonalWorkspaceExecutionInputEntity> rows=new ArrayList<>();
        for (int i=1;i<=17;i++) {
            authorized.add(new AgentTaskExecutionGrantService.AuthorizedInput(
                    "file-"+i,1,"REFERENCE","image/png",1,"a".repeat(64)));
            rows.add(input(i,"file-"+i,1,"a".repeat(64)));
        }
        admit(authorized);
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(rows);

        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,failure.getReason());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,never()).update(any(PersonalWorkspaceExecutionEntity.class));
    }

    @Test void changedCurrentBytesFailBeforeConsumeEvenWhenPersistedMetadataClaimsOldHash() {
        byte[] expected="expected".getBytes(StandardCharsets.UTF_8);
        String hash=sha(expected);
        var authorized=List.of(new AgentTaskExecutionGrantService.AuthorizedInput(
                "file-1",1,"REFERENCE","image/png",expected.length,hash));
        admit(authorized);
        when(executions.listInputs("0","client","owner",execution.getExecutionId()))
                .thenReturn(List.of(input(1,"file-1",expected.length,hash)));
        when(workspace.findFile("0","client","owner","file-1")).thenReturn(file("file-1"));
        when(workspace.findVersion("0","client","owner","file-1",1))
                .thenReturn(version("file-1",expected.length,hash));
        byte[] changed="modified".getBytes(StandardCharsets.UTF_8);
        when(storage.read(any(),eq("memory://file-1"),eq(hash),eq((long)expected.length),eq("image/png")))
                .thenReturn(new PersonalWorkspaceStorage.StoredContent(changed,hash,expected.length,"image/png"));

        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.STORAGE_UNAVAILABLE,failure.getReason());
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,never()).update(any(PersonalWorkspaceExecutionEntity.class));
    }

    @Test void currentConversationAclRevocationBlocksStartBeforeConsumeOrMarker() {
        admit(List.of());
        when(executions.listInputs("0","client","owner",execution.getExecutionId())).thenReturn(List.of());
        when(conversationAccess.requireAccessible(any(),eq("conversation")))
                .thenThrow(new IllegalStateException("conversation revoked before provider start"));

        var denied=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,denied.getReason());
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,never()).markControlledProviderStarted(anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void consentProjectionVersionMustEqualAdmittedReservedVersion() {
        admit(List.of());
        when(consents.get(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(consentView("RESERVED","4"));

        var denied=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.beginControlledConversationProviderStart(runtime,"task","run",command()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.GRANT_REVOKED,denied.getReason());
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(executions,never()).markControlledProviderStarted(anyString(),anyString(),anyString(),
                anyString(),anyString(),anyString(),anyString(),anyLong(),anyLong());
    }

    @Test void consumedRunConversationAclRevocationBlocksInputReadWithoutSecondStart() {
        execution.setConversationProviderStartedAt(123L).setConversationProviderLeaseVersion(1L);
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("EXISTING_RUN"),eq(execution.getExecutionId()),eq("run"),isNull()))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent",
                        "GENERATE_IMAGE",true,List.of(),
                        "mmd-ci-v1:"+execution.getControlledConsentId(),4L));
        when(conversationAccess.requireAccessible(any(),eq("conversation")))
                .thenThrow(new IllegalStateException("conversation revoked"));

        var denied=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.conversationInputs(runtime,"task","run",command().fence()));

        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,denied.getReason());
        verify(consents,never()).consumeWithinLockedRoot(any(),anyString(),any(),anyLong(),
                anyString(),anyString(),anyString());
        verify(storage,never()).read(any(),anyString(),anyString(),anyLong(),anyString());
    }

    @Test void controlledCreateUsesAdmissionLockedFilesWithoutTakingPostConsentFileLocks() {
        byte[] bytes="reference".getBytes(StandardCharsets.UTF_8);String hash=sha(bytes);
        var ref=new PersonalWorkspaceExecutionService.ReferenceSelection(
                "file-1",1,"REFERENCE","image/png",bytes.length,hash);
        AgentRuntimeEntity target=new AgentRuntimeEntity().setAgentId("agent").setOwnerJiacn("owner");
        target.setClientId("client");target.setTenantId("0");
        when(runtimes.findCandidateRosterByOwner("client","owner")).thenReturn(List.of(target));
        when(executions.findByIdempotency(eq("0"),eq("client"),eq("owner"),anyString()))
                .thenReturn(null);
        when(workspace.findFile("0","client","owner","file-1")).thenReturn(file("file-1"));
        when(workspace.findVersion("0","client","owner","file-1",1))
                .thenReturn(version("file-1",bytes.length,hash));
        var admission=new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent",
                "GENERATE_IMAGE",true,List.of(new AgentTaskExecutionGrantService.AuthorizedInput(
                        "file-1",1,"REFERENCE","image/png",bytes.length,hash)),
                "mmd-ci-v1:"+execution.getControlledConsentId(),2L);
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("NEW_EXECUTION"),isNull(),isNull(),isNull()))
                .thenReturn(admission);
        when(consents.lockForBridge(any(),eq("task"),eq(execution.getControlledConsentId())))
                .thenReturn(consentEntity().setState("BOUND").setVersion(2L)
                        .setReservedExecutionId(null).setReservedRunId(null));

        var created=service.createConversation(new PersonalWorkspaceExecutionService.OwnerScope(
                "0","client","owner"),new PersonalWorkspaceExecutionService.ConversationCreate(
                "conversation","task","agent","intent","grant",1,7,"GENERATE_IMAGE",
                "draw","image/png",List.of(ref),true));

        assertEquals("CONVERSATION",created.executionMode());
        verify(workspace).findFile("0","client","owner","file-1");
        verify(workspace,never()).lockFile(anyString(),anyString(),anyString(),anyString());
        verify(consents).reserveWithinLockedRoot(any(),eq("task"),any(),eq(2L),
                eq(created.executionId()),eq(created.runId()));
    }

    private void admit(List<AgentTaskExecutionGrantService.AuthorizedInput> inputs) {
        when(grants.admitControlled(any(),eq("task"),eq("grant"),eq(1L),eq(7L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("PROVIDER_START"),eq(execution.getExecutionId()),eq("run"),eq("runtime")))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("grant",1,7,"agent",
                        "GENERATE_IMAGE",true,inputs,"mmd-ci-v1:"+execution.getControlledConsentId(),3L));
    }
    private PersonalWorkspaceExecutionService.ControlledProviderStart command() {
        return new PersonalWorkspaceExecutionService.ControlledProviderStart(2,
                "pwe_cmd_"+sha("command\n"+execution.getExecutionId()),
                "pwe_msg_"+sha("message\n"+execution.getExecutionId()),execution.getExecutionId(),
                descriptor(),new PersonalWorkspaceExecutionService.ConversationFence(1,
                        "12345678-1234-4234-8234-123456789abc"));
    }
    private static PersonalWorkspaceExecutionService.ProviderExecution descriptor() {
        return new PersonalWorkspaceExecutionService.ProviderExecution("CONTROLLED_IMAGE_HTTP_V1",
                "consent_1234567890abcdef1234567890abcdef","binding","1","model",16,1,1);
    }
    private static PersonalWorkspaceExecutionEntity execution() {
        PersonalWorkspaceExecutionEntity row=new PersonalWorkspaceExecutionEntity()
                .setExecutionId("pwe_execution").setOwnerJiacn("owner").setTaskId("task").setRunId("run")
                .setConversationId("conversation").setTargetAgentId("agent").setInstruction("draw")
                .setOutputContentMimeType("image/png").setExecutionState("QUEUED").setGrantRevision(1L)
                .setExecutionMode("CONVERSATION").setTaskGrantId("grant").setTaskGrantVersion(1L)
                .setAssignmentRevision(7L).setPermittedOperation("GENERATE_IMAGE")
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setConversationLeaseVersion(1L)
                .setConversationLeaseToken("12345678-1234-4234-8234-123456789abc")
                .setConversationLeaseRuntimeId("runtime").setConversationLeaseExpiresAt(9_000_000_000_000L)
                .setCreatedAt(2L);
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static WorkspaceConversationAccessService.ConversationView conversationView() {
        return new WorkspaceConversationAccessService.ConversationView(
                "conversation","bounty","task:task","task",List.of("agent"),1,1);
    }
    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity row=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(7L)
                .setAssignedAgentId("agent");
        row.setTenantId("0");row.setClientId("client");row.setOwnerJiacn("owner");return row;
    }
    private static AgentTaskProviderCostConsentEntity consentEntity() {
        AgentTaskProviderCostConsentEntity row=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setState("RESERVED").setVersion(3L).setReservedExecutionId("pwe_execution")
                .setReservedRunId("run");
        row.setTenantId("0");row.setClientId("client");row.setOwnerJiacn("owner");row.setTaskId("task");return row;
    }
    private static AgentTaskProviderCostConsentDTO consentView(String state,String version) {
        return new AgentTaskProviderCostConsentDTO(1,
                "consent_1234567890abcdef1234567890abcdef","task","agent",state,version,
                "assignment-key","a".repeat(64),"b".repeat(64),
                new AgentTaskProviderCostConsentDTO.ProviderBinding("binding","1"),"model",
                "OPERATOR_TEMPLATE","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,"9000000000000");
    }
    private static PersonalWorkspaceExecutionInputEntity input(int index,String file,long length,String hash) {
        PersonalWorkspaceExecutionInputEntity row=new PersonalWorkspaceExecutionInputEntity()
                .setInputRef("input_"+index).setExecutionId("pwe_execution").setOwnerJiacn("owner")
                .setFileId(file).setFileVersion(1).setOriginalFilename(file+".png")
                .setContentMimeType("image/png").setByteLength(length).setContentHash(hash)
                .setStorageUri("memory://"+file).setGrantState("ACTIVE");
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static PersonalWorkspaceFileEntity file(String id) {
        PersonalWorkspaceFileEntity row=new PersonalWorkspaceFileEntity().setFileId(id)
                .setOwnerJiacn("owner").setState("ACTIVE");
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static PersonalWorkspaceVersionEntity version(String file,long length,String hash) {
        PersonalWorkspaceVersionEntity row=new PersonalWorkspaceVersionEntity().setFileId(file).setVersion(1)
                .setOriginalFilename(file+".png").setContentMimeType("image/png")
                .setByteLength(length).setContentHash(hash).setStorageUri("memory://"+file);
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
