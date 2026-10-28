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
                new PersonalWorkspaceExecutionProperties(List.of("image/png","text/plain")));
        wireAssigneeFixture(service,rows);
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

    private PersonalWorkspaceExecutionService.ConversationCreate textCommand() {
        return new PersonalWorkspaceExecutionService.ConversationCreate("42","task-1","agent","text-intent","grant-1",1,7,
                "GENERATE_TEXT_FILE","生成三行会议清单","text/plain");
    }

    @Test void retiredConversationCreateRejectsWithoutAuthorityOrWrites() {
        var failure=assertThrows(PersonalWorkspaceExecutionService.Failure.class,
                () -> service.createConversation(OWNER,textCommand()));
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,failure.getReason());
        verify(rows,never()).insert(any());verifyNoInteractions(writes);
    }


    @Test void utf8FormatIsStrictAndNeverAcceptedAsImage() {
        byte[] text="确认议题\n通知参会人\n检查设备\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(PersonalWorkspaceOutputFormatValidator.isValid("text/plain",text));
        assertFalse(PersonalWorkspaceOutputFormatValidator.isValid("image/png",text));
        for(byte[] bad:List.of(new byte[0],new byte[]{0},new byte[]{(byte)0xc3,0x28}))
            assertFalse(PersonalWorkspaceOutputFormatValidator.isValid("text/plain",bad));
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
        assertTrue(sql.contains("g.owner_jiacn=agent_personal_workspace_execution.owner_jiacn"));
        assertTrue(sql.contains("g.grant_id=agent_personal_workspace_execution.task_grant_id"));
        assertTrue(sql.contains("g.grant_version=agent_personal_workspace_execution.task_grant_version"));
        assertTrue(sql.contains("BINARY g.executor_owner_jiacn=BINARY #{ownerJiacn}"));
        for (String key:List.of("tenant_id","client_id","target_agent_id","execution_mode")) {
            assertTrue(sql.contains("CAST("+key+" AS BINARY)"),key);
            assertTrue(sql.contains("OCTET_LENGTH("+key+")"),key);
        }
    }







    @Test void defaultOffAndLegacyUnfencedRuntimeLaneNeverStartsConversation() {
        assertEquals(PersonalWorkspaceExecutionService.Reason.CAPABILITY_UNAVAILABLE,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.claimConversationStart(
                        RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.start(
                        RUNTIME,"task-1","run-1",commandId(),messageId())).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.stageOutput(
                        RUNTIME,"task-1","run-1","output_1","bird.png","image/png",new byte[]{1})).getReason());
        assertEquals(PersonalWorkspaceExecutionService.Reason.NOT_FOUND,assertThrows(
                PersonalWorkspaceExecutionService.Failure.class,()-> service.fail(
                        RUNTIME,"task-1","run-1","AGENT_DELIVERY_FAILED")).getReason());
        when(rows.listQueuedByTarget(eq("0"),eq("client"),eq("owner"),eq("agent"),anyInt()))
                .thenReturn(List.of(execution));
        assertTrue(service.runtimeQueuedCommands(RUNTIME,16).isEmpty());
        verify(rows,never()).update(any());
        verifyNoInteractions(writes);
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

    /** Existing fixture has one authenticated owner. Cross-owner fixtures supply explicit grant authority separately. */
    private static void wireAssigneeFixture(cn.jia.agent.service.PersonalWorkspaceExecutionService instance,
            cn.jia.agent.dao.PersonalWorkspaceExecutionDao rows) {
        var service=(cn.jia.agent.service.impl.PersonalWorkspaceExecutionServiceImpl)instance;
        var authority=org.mockito.Mockito.mock(cn.jia.agent.service.TaskAssigneeAuthorityService.class);
        org.mockito.Mockito.when(authority.resolveForAssignment(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),1L,null));
        org.mockito.Mockito.when(authority.requireGrantTarget(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target(i.getArgument(0),i.getArgument(1),i.getArgument(2),"agent",1L,null));
        service.setAssigneeAuthority(authority);
        org.mockito.Mockito.when(rows.findForRuntime(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(i->rows.findByTaskRun(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(4),i.getArgument(5)));
    }
}
