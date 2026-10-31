package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.PersonalWorkspaceTaskLinkDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskGrantInputDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.PersonalWorkspaceFileEntity;
import cn.jia.agent.entity.PersonalWorkspaceTaskFileLinkEntity;
import cn.jia.agent.entity.PersonalWorkspaceVersionEntity;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentTaskExecutionGrantServiceImplTest {
    private final MemoryGrantDao grants = new MemoryGrantDao();
    private final MemoryBootstrapDao bootstraps = new MemoryBootstrapDao();
    private final PersonalWorkspaceTaskLinkDao links = mock(PersonalWorkspaceTaskLinkDao.class);
    private final PersonalWorkspaceDao workspace = mock(PersonalWorkspaceDao.class);
    private final AgentLegacyTaskCompatibilityService legacy = mock(AgentLegacyTaskCompatibilityService.class);
    private final AgentIdentityService identities = mock(AgentIdentityService.class);
    private final AgentTaskRequirementSnapshotService requirementSnapshots = mock(AgentTaskRequirementSnapshotService.class);
    private final AgentTaskMetaEntity root = new AgentTaskMetaEntity();
    private AgentTaskExecutionGrantServiceImpl service;
    private final cn.jia.agent.service.TaskAssigneeAuthorityService authority=mock(cn.jia.agent.service.TaskAssigneeAuthorityService.class);


    @BeforeEach
    void setUp() {
        when(authority.lockForAssignment(eq("0"),eq("client"),eq("owner"),anyString()))
                .thenAnswer(i->new cn.jia.agent.service.TaskAssigneeAuthorityService.Target("0","client","owner",i.getArgument(3),1L,null));
        when(authority.requireGrantTarget(eq("0"),eq("client"),eq("owner"),anyString(),anyString(),anyLong()))
                .thenReturn(new cn.jia.agent.service.TaskAssigneeAuthorityService.Target("0","client","owner","agent-1",1L,null));
        root.setTenantId("0"); root.setClientId("client"); root.setOwnerJiacn("owner");
        root.setTaskId("task-1"); root.setTaskVersion(0L); root.setCurrentEventVersion(0L);
        root.setRewardStatus("open");
        when(requirementSnapshots.requireCurrent(any(),eq("task-1"),anyLong()))
                .thenAnswer(i -> new AgentTaskRequirementSnapshotService.Snapshot(
                        "0","client","owner","task-1",i.getArgument(2),
                        "Original title","Full original description","a".repeat(64),"CREATE"));
        when(legacy.resolveAgentId("0","client","owner","agent-1")).thenReturn("agent-1");
        when(identities.lockActiveCanonicalAgentIdsInScope("0","client","owner",List.of("agent-1")))
                .thenReturn(List.of("agent-1"));
        when(legacy.assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root))).thenAnswer(invocation -> {
            long expected=invocation.getArgument(6);
            AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator validator=invocation.getArgument(7);
            validator.beforeIdentityLock(root,List.of("agent-1"));
            validator.validate(root,List.of("agent-1"));
            boolean changed=root.getAssignedAgentId()==null;
            root.setTaskVersion(changed ? expected+1 : expected); root.setAssignedAgentId("agent-1");
            return changed
                    ? new AgentLegacyTaskCompatibilityService.AssignOutcome(List.of("agent-1"),true,"event-1",1L)
                    : new AgentLegacyTaskCompatibilityService.AssignOutcome(List.of("agent-1"),false);
        });
        service=new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,legacy,identities,
                new DirectTransaction(root),new ObjectMapper(),requirementSnapshots);
        service.setAssigneeAuthority(authority);
    }

    @Test void grayScopeReachesTenantBoundRootLockWithoutProductionFallback() {
        var transactions = mock(AgentTaskMutationTransaction.class);
        var scoped = new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,
                legacy,identities,transactions,new ObjectMapper(),requirementSnapshots);
        var gray = new AgentTaskExecutionGrantService.Scope("1","client","owner");
        scoped.admit(gray,"task-1","grant-1",1,0,"agent-1","READ_TASK",false);
        verify(transactions).executeWithLockedTaskRootInOwnerScope(eq("1"),eq("client"),
                eq("owner"),eq("task-1"),any());
        verifyNoMoreInteractions(transactions);
        for (String tenant : List.of("", "1 ", " 1", "x".repeat(51))) {
            assertThrows(AgentTaskExecutionGrantException.class, () -> scoped.admit(
                    new AgentTaskExecutionGrantService.Scope(tenant,"client","owner"),
                    "task-1","grant-1",1,0,"agent-1","READ_TASK",false));
        }
        assertThrows(AgentTaskExecutionGrantException.class, () -> scoped.admit(
                new AgentTaskExecutionGrantService.Scope("1","client","0"),
                "task-1","grant-1",1,0,"agent-1","READ_TASK",false));
        verifyNoMoreInteractions(transactions);
    }

    @Test void externalGrantFreezesExecutorAndStillCreatesOriginalAutomaticBootstrap() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        when(authority.lockForAssignment("0","client","owner","agent-1"))
                .thenReturn(new cn.jia.agent.service.TaskAssigneeAuthorityService.Target("0","client","provider","agent-1",7L,2L));
        service.assignForDeliberation(scope(),"task-1","external","agent-1",0,1);
        var row=grants.byAction.values().iterator().next();
        assertEquals("owner",row.getOwnerJiacn());assertEquals("provider",row.getExecutorOwnerJiacn());
        assertEquals(7L,row.getExecutorBindingId());assertEquals(2L,row.getExternalPolicyRevision());
        assertEquals("DELIBERATE",bootstraps.byAction.values().iterator().next().getPermittedOperation());
        service.assignForDeliberation(scope(),"task-1","external","agent-1",0,1);
        verify(authority,times(1)).lockForAssignment("0","client","owner","agent-1");
    }

    @Test void pendingReviewAllowsReadOnlyDiscussionContextWithoutGrantingImageExecution() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var grant=service.assignForDeliberation(scope(),"task-1","review-context","agent-1",0,1);
        root.setRewardStatus("reviewing");
        var context=service.currentFollowupContext(scope(),"task-1","agent-1");
        assertEquals(grant.getAssignmentRevision(),context.assignmentRevision());
        assertEquals(root.getTaskVersion(),context.taskVersion());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",true));
        root.setRewardStatus("completed");
        assertThrows(AgentTaskExecutionGrantException.class,()->service.currentFollowupContext(scope(),"task-1","agent-1"));
    }

    @Test
    void pointWithoutMaterialsCreatesDeliberationNotImageOrPaidAuthority() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var receipt=service.assignForDeliberation(scope(),"task-1","point-empty","agent-1",0,1);
        assertEquals(List.of("DELIBERATE","EXECUTE_SKILL"),receipt.getPermittedOperations());
        assertFalse(receipt.getPaidExecutionAuthorized());
        assertTrue(receipt.getInputs().isEmpty());
        assertEquals("DELIBERATE",bootstraps.byAction.values().iterator().next().getPermittedOperation());
        assertNull(grants.byAction.values().iterator().next().getCostAuthorizationRef());
    }

    @Test void newOrdinaryGrantAdmitsSkillButAnOldGrantDoesNotGainTheOperation() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var grant=service.assignForDeliberation(scope(),"task-1","text-grant","agent-1",0,1);
        assertEquals("EXECUTE_SKILL",service.admit(scope(),"task-1",grant.getGrantId(),grant.getGrantVersion(),
                grant.getAssignmentRevision(),"agent-1","EXECUTE_SKILL",false).operation());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",true));
        grants.byAction.values().iterator().next().setPermittedOperationsJson("[\"DELIBERATE\",\"INSPECT_INPUTS\"]");
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1","EXECUTE_SKILL",false));
    }

    @Test void unknownToolPolicyDoesNotBecomeNativeExecutionAuthority() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var grant=service.assignForDeliberation(scope(),"task-1","unknown-tools","agent-1",0,1);
        var row=grants.byAction.values().iterator().next();
        for(String policy:List.of("ALL_TOOLS","NATIVE_LOCAL_FILES_V1","NO_TOOLS_V1")) {
            row.setPermittedToolPolicyRef(policy);
            assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",grant.getGrantId(),
                    grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1","EXECUTE_SKILL",false));
        }
        row.setPermittedToolPolicyRef("SKILLS_V1");
        assertEquals("EXECUTE_SKILL",service.admit(scope(),"task-1",grant.getGrantId(),grant.getGrantVersion(),
                grant.getAssignmentRevision(),"agent-1","EXECUTE_SKILL",false).operation());
    }

    @Test
    void pointFreezesAllMixedMediaAndReplayDoesNotReadChangedCatalogue() {
        List<PersonalWorkspaceTaskFileLinkEntity> materials=new java.util.ArrayList<>();
        int i=0;
        for(String mime:List.of("image/png","application/pdf","audio/ogg","text/plain")) {
            var link=pointMaterial("file-"+(++i),1,"INPUT",mime);
            materials.add(link);
        }
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(materials);
        var first=service.assignForDeliberation(scope(),"task-1","point-mixed","agent-1",0,1);
        assertEquals(4,first.getInputs().size());
        assertEquals(List.of("image/png","application/pdf","audio/ogg","text/plain"),
                first.getInputs().stream().map(v->v.contentMimeType()).toList());
        when(links.list("0","client","owner","task-1",null,null,33))
                .thenThrow(new AssertionError("Replay must use the original fixed catalogue"));
        var again=service.assignForDeliberation(scope(),"task-1","point-mixed","agent-1",0,1);
        assertEquals(first.getGrantId(),again.getGrantId());
        assertEquals(first.getInputs(),again.getInputs());
        assertEquals(1,bootstraps.byAction.size());
        verify(links,times(1)).list("0","client","owner","task-1",null,null,33);
        verify(legacy,times(1)).assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root));
    }

    @Test
    void pointSameKeyDifferentTargetOrVersionConflictsWithoutSecondAssignment() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        service.assignForDeliberation(scope(),"task-1","point-conflict","agent-1",0,1);
        when(legacy.resolveAgentId("0","client","owner","agent-2")).thenReturn("agent-2");
        for(Runnable changed:List.<Runnable>of(
                ()->service.assignForDeliberation(scope(),"task-1","point-conflict","agent-2",0,1),
                ()->service.assignForDeliberation(scope(),"task-1","point-conflict","agent-1",1,1),
                ()->service.assignForDeliberation(scope(),"task-1","point-conflict","agent-1",0,2))) {
            var failure=assertThrows(AgentTaskExecutionGrantException.class,changed::run);
            assertEquals(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,failure.reason());
        }
        assertEquals(1,grants.byAction.size()); assertEquals(1,bootstraps.byAction.size());
    }

    @Test
    void pointDeduplicatesRolesButKeepsDistinctVersionsAndIgnoresOutputs() {
        var reference=pointMaterial("file-1",1,"REFERENCE","image/png");
        var input=pointMaterial("file-1",1,"INPUT","image/png");
        var second=pointMaterial("file-1",2,"INPUT","image/png");
        var output=pointMaterial("output",1,"OUTPUT","image/png");
        var removed=pointMaterial("removed",1,"INPUT","image/png").setLinkState("REMOVED");
        when(links.list("0","client","owner","task-1",null,null,33))
                .thenReturn(List.of(reference,input,second,output,removed));
        var receipt=service.assignForDeliberation(scope(),"task-1","point-versions","agent-1",0,1);
        assertEquals(2,receipt.getInputs().size());
        assertEquals(List.of(1,2),receipt.getInputs().stream().map(v->v.version()).toList());
        assertTrue(receipt.getInputs().stream().allMatch(v->"INPUT".equals(v.purpose())));
    }

    @Test
    void pointPaginatesPastNonInputRowsInsteadOfSilentlyDroppingMaterials() {
        var first=new java.util.ArrayList<PersonalWorkspaceTaskFileLinkEntity>();
        for(int i=0;i<33;i++)first.add(pointMaterial("output-"+i,1,"OUTPUT","text/plain")
                .setCreatedAt(100L).setRelationId(String.format("r%02d",i)));
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(first);
        var late=pointMaterial("late-input",1,"INPUT","audio/ogg");
        when(links.list("0","client","owner","task-1",100L,"r32",33)).thenReturn(List.of(late));
        var receipt=service.assignForDeliberation(scope(),"task-1","point-page","agent-1",0,1);
        assertEquals("late-input",receipt.getInputs().getFirst().fileId());
    }

    @Test
    void pointRejectsMoreThan32DistinctVersionsBeforeAssignment() {
        var rows=new java.util.ArrayList<PersonalWorkspaceTaskFileLinkEntity>();
        for(int i=0;i<33;i++)rows.add(pointMaterial("file-"+i,1,"INPUT","text/plain"));
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(rows);
        var failure=assertThrows(AgentTaskExecutionGrantException.class,
                ()->service.assignForDeliberation(scope(),"task-1","point-limit","agent-1",0,1));
        assertEquals(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,failure.reason());
        assertNull(root.getAssignedAgentId()); assertTrue(grants.byAction.isEmpty());
    }

    @Test
    void pointRejectsCrossOwnerCatalogueBeforeReadingBytesOrAssigning() {
        var foreign=pointMaterial("foreign",1,"INPUT","text/plain").setOwnerJiacn("other");
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of(foreign));
        var failure=assertThrows(AgentTaskExecutionGrantException.class,
                ()->service.assignForDeliberation(scope(),"task-1","point-acl","agent-1",0,1));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        assertNull(root.getAssignedAgentId()); assertTrue(grants.byAction.isEmpty());
        verify(workspace,never()).lockFile(anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void pointUnavailableVersionPreventsAssignmentAndGrant() {
        var row=pointMaterial("gone",1,"INPUT","application/pdf");
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of(row));
        when(workspace.findVersion("0","client","owner","gone",1)).thenReturn(null);
        var failure=assertThrows(AgentTaskExecutionGrantException.class,
                ()->service.assignForDeliberation(scope(),"task-1","point-gone","agent-1",0,1));
        assertEquals(AgentTaskExecutionGrantException.Reason.NOT_FOUND,failure.reason());
        assertNull(root.getAssignedAgentId()); assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void pointBootstrapFailureRollsBackAssignmentGrantAndIntentInTransactionHarness() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        service=new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,legacy,identities,
                new RollbackTransaction(root,grants,bootstraps),new ObjectMapper(),requirementSnapshots);
        service.setAssigneeAuthority(authority);
        bootstraps.failInsert=true;
        assertThrows(IllegalStateException.class,
                ()->service.assignForDeliberation(scope(),"task-1","point-rollback","agent-1",0,1));
        assertNull(root.getAssignedAgentId()); assertEquals(0L,root.getTaskVersion());
        assertTrue(grants.byAction.isEmpty()); assertTrue(bootstraps.byAction.isEmpty());
    }

    private PersonalWorkspaceTaskFileLinkEntity pointMaterial(String id,int version,String role,String mime) {
        var row=new PersonalWorkspaceTaskFileLinkEntity().setOwnerJiacn("owner").setTaskId("task-1")
                .setFileId(id).setFileVersion(version).setLinkRole(role).setLinkState("ACTIVE")
                .setRelationId("relation-"+id).setCreatedAt(100L);
        row.setTenantId("0"); row.setClientId("client");
        when(links.lockBySelection("0","client","owner","task-1",id,version,role)).thenReturn(row);
        when(workspace.lockFile("0","client","owner",id))
                .thenReturn(new PersonalWorkspaceFileEntity().setState("ACTIVE"));
        when(workspace.findVersion("0","client","owner",id,version))
                .thenReturn(new PersonalWorkspaceVersionEntity().setFileId(id).setVersion(version)
                        .setContentMimeType(mime).setByteLength(12L).setContentHash("b".repeat(64)));
        return row;
    }

    @Test
    void missingOrStaleServerRequirementPreventsAssignmentAndOutbox() {
        when(requirementSnapshots.requireCurrent(any(),eq("task-1"),anyLong()))
                .thenThrow(new IllegalStateException("No persisted owner confirmation"));
        var failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-unconfirmed",request()));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        assertNull(root.getAssignedAgentId());
        assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void sameKeySamePayloadReplaysAndDifferentPayloadConflicts() {
        AgentTaskAssignDTO request=request();
        var first=service.assignAndGrant(scope(),"task-1","key-1",request);
        AgentTaskAssignDTO reordered=request();
        reordered.setRequestedOperations(List.of("INSPECT_INPUTS","GENERATE_IMAGE"));
        var replay=service.assignAndGrant(scope(),"task-1","key-1",reordered);
        assertEquals(first.getGrantId(),replay.getGrantId());
        assertEquals(1L,first.getAssignmentRevision());
        assertFalse(first.getPaidExecutionAuthorized());
        assertEquals("db82df399bed89b72c4bacb5d3e0616d4745fcf1e314bf55e4713f4c9f76e963",
                grants.byAction.values().iterator().next().getRequestHash());
        assertEquals(1,bootstraps.byAction.size());
        AgentTaskBountyBootstrapOutboxEntity intent=bootstraps.byAction.values().iterator().next();
        assertEquals(first.getGrantId(),intent.getGrantId());
        assertEquals(first.getGrantVersion(),intent.getGrantVersion());
        assertEquals("GENERATE_IMAGE",intent.getPermittedOperation());
        assertEquals("TASK_REQUIREMENT_REVISION_V1",intent.getRequirementAnchor());
        assertEquals("PENDING",intent.getStatus());
        verify(legacy,times(1)).assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root));

        AgentTaskAssignDTO conflict=request(); conflict.setRequirementRevision(2L);
        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-1",conflict));
        assertEquals(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,failure.reason());
    }

    @Test
    void explicitInitialOperationKeepsFullGrantAndCreatesOneBootstrapIntent() {
        AgentTaskAssignDTO request=explicitRequest("GENERATE_IMAGE");
        var first=service.assignAndGrant(scope(),"task-1","key-explicit",request);

        assertEquals(List.of("EDIT_IMAGE","GENERATE_IMAGE","INSPECT_INPUTS"),
                first.getPermittedOperations());
        AgentTaskExecutionGrantEntity stored=grants.byAction.values().iterator().next();
        assertEquals("[\"EDIT_IMAGE\",\"GENERATE_IMAGE\",\"INSPECT_INPUTS\"]",
                stored.getPermittedOperationsJson());
        assertFalse(stored.getAllowOwnTaskDerivedAssets());
        assertNull(stored.getCostAuthorizationRef());
        assertEquals("7725b9e4962fab28e928a45052eeade3ee9e89a3d10f63bdb5af6077f56f221d",
                stored.getRequestHash());
        assertEquals(1,bootstraps.byAction.size());
        assertEquals("GENERATE_IMAGE",
                bootstraps.byAction.values().iterator().next().getPermittedOperation());

        AgentTaskAssignDTO reordered=explicitRequest("GENERATE_IMAGE");
        reordered.setRequestedOperations(List.of("INSPECT_INPUTS","GENERATE_IMAGE","EDIT_IMAGE"));
        var replay=service.assignAndGrant(scope(),"task-1","key-explicit",reordered);
        assertEquals(first.getGrantId(),replay.getGrantId());
        assertEquals(1,bootstraps.byAction.size());
        verify(legacy,times(1)).assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root));
    }

    @Test
    void explicitInspectIsAValidSingleInitialActionWithinTheFullGrant() {
        AgentTaskAssignDTO request=explicitRequest("INSPECT_INPUTS");
        var grant=service.assignAndGrant(scope(),"task-1","key-inspect",request);

        assertEquals(List.of("EDIT_IMAGE","GENERATE_IMAGE","INSPECT_INPUTS"),
                grant.getPermittedOperations());
        assertEquals("INSPECT_INPUTS",
                bootstraps.byAction.values().iterator().next().getPermittedOperation());
        assertEquals("0a999a29f38b5682654947762bc7a2d620de192e2a75591350142c07af3a3599",
                grants.byAction.values().iterator().next().getRequestHash());
    }

    @Test
    void selectorValueOrPresenceChangeConflictsForTheSameKey() {
        service.assignAndGrant(scope(),"task-1","key-selector",explicitRequest("GENERATE_IMAGE"));

        AgentTaskExecutionGrantException changed=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-selector",
                        explicitRequest("EDIT_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,changed.reason());

        AgentTaskAssignDTO removed=explicitRequest("GENERATE_IMAGE");
        removed.setInitialOperation(null);
        AgentTaskExecutionGrantException missing=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-selector",removed));
        assertEquals(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,missing.reason());
        assertEquals(1,grants.byAction.size());
        assertEquals(1,bootstraps.byAction.size());
    }

    @Test
    void addingSelectorToLegacySameKeyConflictsEvenWhenActionIsTheSame() {
        service.assignAndGrant(scope(),"task-1","key-domain",request());
        AgentTaskAssignDTO explicit=request(); explicit.setInitialOperation("GENERATE_IMAGE");

        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-domain",explicit));
        assertEquals(AgentTaskExecutionGrantException.Reason.IDEMPOTENCY_CONFLICT,failure.reason());
    }

    @Test
    void missingSelectorStillSupportsTheLegacyInspectOnlyRequestAndHashDomain() {
        AgentTaskAssignDTO request=request();
        request.setRequestedOperations(List.of("INSPECT_INPUTS"));
        var grant=service.assignAndGrant(scope(),"task-1","key-legacy-inspect",request);

        assertEquals(List.of("INSPECT_INPUTS"),grant.getPermittedOperations());
        assertEquals("INSPECT_INPUTS",
                bootstraps.byAction.values().iterator().next().getPermittedOperation());
        assertEquals("37b778f9ffac39db3881ddd585c10dc6f63ba44cf8dc080c744b55c6e555a43f",
                grants.byAction.values().iterator().next().getRequestHash());
    }

    @Test
    void explicitSelectorIsStrictAndMustBeAnExactSetMember() {
        for (String selected:List.of(""," ","generate_image","GENERATE_IMAGE ",
                "EDIT_AUDIO","UNKNOWN","\t")) {
            AgentTaskAssignDTO request=request(); request.setInitialOperation(selected);
            AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                    () -> service.assignAndGrant(scope(),"task-1","key-invalid",request),selected);
            assertEquals(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,failure.reason(),selected);
        }
        verify(legacy,never()).resolveAgentId(anyString(),anyString(),anyString(),anyString());
        assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void exactTaskLinkedInputSnapshotIsPersistedWithoutStorageUri() {
        AgentTaskGrantInputDTO input=new AgentTaskGrantInputDTO();
        input.setFileId("file-1"); input.setVersion(3); input.setPurpose("REFERENCE");
        AgentTaskAssignDTO request=request(); request.setInputRefs(List.of(input));
        when(links.lockFile("0","client","owner","file-1",true)).thenReturn(true);
        when(links.lockBySelection("0","client","owner","task-1","file-1",3,"REFERENCE"))
                .thenReturn(new PersonalWorkspaceTaskFileLinkEntity().setLinkState("ACTIVE"));
        when(workspace.lockFile("0","client","owner","file-1"))
                .thenReturn(new PersonalWorkspaceFileEntity().setState("ACTIVE"));
        when(workspace.findVersion("0","client","owner","file-1",3))
                .thenReturn(new PersonalWorkspaceVersionEntity().setFileId("file-1").setVersion(3)
                        .setContentMimeType("image/png").setByteLength(123L)
                        .setContentHash("a".repeat(64)).setStorageUri("secret/path"));

        var result=service.assignAndGrant(scope(),"task-1","key-input",request);

        assertEquals(1,result.getInputs().size());
        assertEquals("REFERENCE",result.getInputs().getFirst().purpose());
        String persisted=grants.byAction.values().iterator().next().getInputScopeJson();
        String bootstrap=bootstraps.byAction.values().iterator().next().getReferenceSummaryJson();
        assertTrue(persisted.contains("a".repeat(64)));
        assertTrue(bootstrap.contains("a".repeat(64)));
        assertTrue(bootstrap.contains("file-1"));
        assertFalse(persisted.contains("secret/path"));
        assertFalse(bootstrap.contains("secret/path"));
        assertFalse(bootstrap.toLowerCase().contains("token"));
        assertFalse(bootstrap.toLowerCase().contains("uri"));
    }

    @Test
    void multipleInitialExecutableOperationsAreRejectedBeforeMutation() {
        AgentTaskAssignDTO request=request();
        request.setRequestedOperations(List.of("GENERATE_IMAGE","EDIT_IMAGE"));
        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-ambiguous",request));
        assertEquals(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,failure.reason());
        verify(legacy,times(1)).resolveAgentId("0","client","owner","agent-1");
        verify(legacy,never()).assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root));
        assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void bootstrapFailureRollsBackAssignmentAndGrantBoundary() {
        bootstraps.failInsert=true;
        service=new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,legacy,identities,
                new RollbackTransaction(root,grants,bootstraps),new ObjectMapper(),requirementSnapshots);
        service.setAssigneeAuthority(authority);
        assertThrows(IllegalStateException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-rollback",request()));
        assertNull(root.getAssignedAgentId());
        assertEquals(0L,root.getTaskVersion());
        assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void replayFailsClosedWhenSameActionBootstrapPayloadDrifts() {
        service.assignAndGrant(scope(),"task-1","key-drift",request());
        AgentTaskBountyBootstrapOutboxEntity intent=bootstraps.byAction.values().iterator().next();
        intent.setPermittedOperation("EDIT_IMAGE");
        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-drift",request()));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        verify(legacy,times(1)).assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root));
    }

    @Test
    void replayUsesPersistedInitialActionAndRejectsARehashedActionDrift() {
        service.assignAndGrant(scope(),"task-1","key-action-drift",
                explicitRequest("GENERATE_IMAGE"));
        AgentTaskBountyBootstrapOutboxEntity intent=bootstraps.byAction.values().iterator().next();
        intent.setPermittedOperation("EDIT_IMAGE");
        intent.setPayloadHash(AgentTaskBountyBootstrapPayload.payloadHash(intent.getTenantId(),
                intent.getClientId(),intent.getOwnerJiacn(),intent.getTaskId(),
                intent.getSourceBusinessActionId(),intent.getRequirementRevision(),
                intent.getAssignmentRevision(),intent.getTargetAgentId(),intent.getGrantId(),
                intent.getGrantVersion(),intent.getPermittedOperation(),
                intent.getReferenceSummarySha256()));

        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-action-drift",
                        explicitRequest("GENERATE_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        assertEquals("EDIT_IMAGE",intent.getPermittedOperation());
        assertEquals(1,bootstraps.byAction.size());
    }

    @Test
    void replayRejectsMissingBootstrapInsteadOfRecreatingIt() {
        service.assignAndGrant(scope(),"task-1","key-missing-bootstrap",
                explicitRequest("GENERATE_IMAGE"));
        bootstraps.byAction.clear(); bootstraps.byId.clear();

        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-missing-bootstrap",
                        explicitRequest("GENERATE_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void replayRejectsMissingPersistedInitialAction() {
        service.assignAndGrant(scope(),"task-1","key-missing-action",
                explicitRequest("GENERATE_IMAGE"));
        AgentTaskBountyBootstrapOutboxEntity intent=bootstraps.byAction.values().iterator().next();
        intent.setPermittedOperation(null);

        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-missing-action",
                        explicitRequest("GENERATE_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,failure.reason());
        assertNull(intent.getPermittedOperation());
        assertEquals(1,bootstraps.byAction.size());
    }

    @Test
    void bootstrapWithoutGrantFailsClosed() {
        service.assignAndGrant(scope(),"task-1","key-half-outbox",
                explicitRequest("GENERATE_IMAGE"));
        AgentTaskExecutionGrantEntity grant=grants.byAction.remove("ASSIGN_AND_START:key-half-outbox");
        grants.byId.remove(grant.getGrantId());
        AgentTaskExecutionGrantException half=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-half-outbox",
                        explicitRequest("GENERATE_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,half.reason());
    }

    @Test
    void persistedGrantSetCannotDropTheSelectedInitialOperation() {
        service.assignAndGrant(scope(),"task-1","key-set-drift",
                explicitRequest("GENERATE_IMAGE"));
        AgentTaskExecutionGrantEntity stored=grants.byAction.values().iterator().next();
        stored.setPermittedOperationsJson("[\"EDIT_IMAGE\",\"INSPECT_INPUTS\"]");
        AgentTaskExecutionGrantException drift=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-set-drift",
                        explicitRequest("GENERATE_IMAGE")));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,drift.reason());
    }

    @Test
    void clientToolOrCostAuthorityIsRejectedBeforeAnyMutation() {
        AgentTaskAssignDTO request=request(); request.setCostAuthorizationRef("client-money");
        AgentTaskExecutionGrantException failure=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.assignAndGrant(scope(),"task-1","key-money",request));
        assertEquals(AgentTaskExecutionGrantException.Reason.BAD_REQUEST,failure.reason());
        verify(legacy,never()).resolveAgentId(anyString(),anyString(),anyString(),anyString());
        assertTrue(grants.byAction.isEmpty());
    }

    @Test
    void serverSideResolveDoesNotNeedBrowserGrantIdAndPaidAdmissionFailsClosed() {
        var grant=service.assignAndGrant(scope(),"task-1","key-admit",request());
        root.setTaskVersion(grant.getAssignmentRevision()); root.setAssignedAgentId("agent-1");

        var free=service.resolveAndAdmit(scope(),"task-1",grant.getAssignmentRevision(),
                "agent-1","GENERATE_IMAGE",false);
        assertEquals(grant.getGrantId(),free.grantId());
        assertFalse(free.paidExecutionAuthorized());

        AgentTaskExecutionGrantException paid=assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.resolveAndAdmit(scope(),"task-1",grant.getAssignmentRevision(),
                        "agent-1","GENERATE_IMAGE",true));
        assertEquals(AgentTaskExecutionGrantException.Reason.PAID_EXECUTION_NOT_AUTHORIZED,paid.reason());
    }


    @Test
    void selectedOutputPromotionUsesCurrentUnpaidGrantAndFailsAfterRevocation() {
        var grant=service.assignAndGrant(scope(),"task-1","key-finalize",request());
        root.setTaskVersion(grant.getAssignmentRevision());root.setAssignedAgentId("agent-1");
        var admitted=service.admitSelectedOutputPromotion(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1");
        assertEquals("FINALIZE_SELECTED_OUTPUTS",admitted.operation());assertFalse(admitted.paidExecutionAuthorized());
        service.revoke(scope(),"task-1",grant.getGrantId(),"key-finalize-revoke",grant.getGrantVersion());
        var denied=assertThrows(AgentTaskExecutionGrantException.class,()->service.admitSelectedOutputPromotion(
                scope(),"task-1",grant.getGrantId(),grant.getGrantVersion(),grant.getAssignmentRevision(),"agent-1"));
        assertEquals(AgentTaskExecutionGrantException.Reason.CONFLICT,denied.reason());
    }

    @Test void serverResolvedPromotionGrantIsCurrentUnpaidAndStillChecksAssignmentAndRevocation(){
        var grant=service.assignAndGrant(scope(),"task-1","key-resolve-final",request());
        root.setTaskVersion(grant.getAssignmentRevision());root.setAssignedAgentId("agent-1");
        var admitted=service.resolveSelectedOutputPromotion(scope(),"task-1",grant.getAssignmentRevision(),"agent-1");
        assertEquals(grant.getGrantId(),admitted.grantId());assertEquals("FINALIZE_SELECTED_OUTPUTS",admitted.operation());
        assertFalse(admitted.paidExecutionAuthorized());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveSelectedOutputPromotion(scope(),"task-1",grant.getAssignmentRevision()+1,"agent-1"));
        service.revoke(scope(),"task-1",grant.getGrantId(),"key-resolved-revoke",grant.getGrantVersion());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveSelectedOutputPromotion(scope(),"task-1",grant.getAssignmentRevision(),"agent-1"));
    }

    @Test
    void ownerReconfirmationInvalidatesPreviouslyIssuedGrantForBothAdmissionPaths() {
        var grant=service.assignAndGrant(scope(),"task-1","key-reconfirmed",request());
        root.setTaskVersion(grant.getAssignmentRevision()); root.setAssignedAgentId("agent-1");
        when(requirementSnapshots.requireCurrent(any(),eq("task-1"),eq(1L)))
                .thenThrow(new IllegalStateException("Owner has confirmed revision 2"));
        var explicit=assertThrows(AgentTaskExecutionGrantException.class, () ->
                service.admit(scope(),"task-1",grant.getGrantId(),grant.getGrantVersion(),
                        grant.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,explicit.reason());
        var resolved=assertThrows(AgentTaskExecutionGrantException.class, () ->
                service.resolveAndAdmit(scope(),"task-1",grant.getAssignmentRevision(),
                        "agent-1","GENERATE_IMAGE",false));
        assertEquals(AgentTaskExecutionGrantException.Reason.INVALID_PERSISTED_STATE,resolved.reason());
    }

    @Test
    void newBusinessActionSupersedesPriorGrantAndOldGrantCannotAdmit() {
        var first=service.assignAndGrant(scope(),"task-1","key-old",request());
        AgentTaskAssignDTO next=request(); next.setExpectedTaskVersion(first.getAssignmentRevision());
        var second=service.assignAndGrant(scope(),"task-1","key-new",next);
        assertEquals(first.getAssignmentRevision(),second.getAssignmentRevision());
        assertEquals("SUPERSEDED",grants.byId.get(first.getGrantId()).getState());
        assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.admit(scope(),"task-1",first.getGrantId(),1L,
                        first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false));
    }

    @Test
    void statusVersionDriftKeepsAssignmentButSameAgentRepointAndMissingEpochRevoke() {
        var first=service.assignAndGrant(scope(),"task-1","epoch-first",request());
        root.setTaskVersion(first.getAssignmentRevision()+3);
        // Ordinary task status/event changes are not assignment changes.
        assertEquals(first.getGrantId(),service.admit(scope(),"task-1",first.getGrantId(),
                first.getGrantVersion(),first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false).grantId());
        assertEquals(first.getGrantId(),service.resolveAndAdmit(scope(),"task-1",
                first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false).grantId());
        // The durable TASK_ASSIGNED event changed while the target stayed identical.
        grants.latestAssignedVersion=first.getAssignmentRevision()+3;
        assertEquals(AgentTaskExecutionGrantException.Reason.CONFLICT,assertThrows(
                AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",first.getGrantId(),
                        first.getGrantVersion(),first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false)).reason());
        grants.latestAssignedVersion=null;
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveAndAdmit(scope(),"task-1",
                first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false));
        grants.latestAssignedVersion=1L;
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",first.getGrantId(),
                first.getGrantVersion()+1,first.getAssignmentRevision(),"agent-1","GENERATE_IMAGE",false));
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",first.getGrantId(),
                first.getGrantVersion(),first.getAssignmentRevision(),"another-agent","GENERATE_IMAGE",false));
    }

    @Test
    void revokeIsIdempotentOnlyForSameKeyAndPayloadAndBlocksAdmission() {
        var grant=service.assignAndGrant(scope(),"task-1","key-revoke-create",request());
        root.setTaskVersion(grant.getAssignmentRevision()); root.setAssignedAgentId("agent-1");
        var revoked=service.revoke(scope(),"task-1",grant.getGrantId(),"key-revoke",1L);
        var replay=service.revoke(scope(),"task-1",grant.getGrantId(),"key-revoke",1L);
        assertEquals("REVOKED",revoked.getState()); assertEquals(revoked.getGrantVersion(),replay.getGrantVersion());
        assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.revoke(scope(),"task-1",grant.getGrantId(),"other-key",1L));
        assertThrows(AgentTaskExecutionGrantException.class,
                () -> service.resolveAndAdmit(scope(),"task-1",grant.getAssignmentRevision(),
                        "agent-1","GENERATE_IMAGE",false));
    }

    private static AgentTaskAssignDTO request() {
        AgentTaskAssignDTO request=new AgentTaskAssignDTO(); request.setAgentId("agent-1");
        request.setWorkflowVersion(2); request.setBusinessAction("assign_and_start");
        request.setExpectedTaskVersion(0L); request.setRequirementRevision(1L);
        request.setRequestedOperations(List.of("GENERATE_IMAGE","INSPECT_INPUTS"));
        request.setInputRefs(List.of()); return request;
    }
    private static AgentTaskAssignDTO explicitRequest(String initialOperation) {
        AgentTaskAssignDTO request=request();
        request.setRequestedOperations(List.of("GENERATE_IMAGE","INSPECT_INPUTS","EDIT_IMAGE"));
        request.setInitialOperation(initialOperation);
        return request;
    }

    @Test void oldBaselineIsReplacedByDistinctLatestGrantWithoutFeesOrBootstrapReplay() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var original=service.assignForDeliberation(scope(),"task-1","old","agent-1",0,1);
        var old=grants.byId.get(original.getGrantId());old.setPermittedOperationsJson("[\"DELIBERATE\"]").setPermittedToolPolicyRef("NO_TOOLS_V1");
        var next=service.resolveSkillBaseline(scope(),"task-1",original.getAssignmentRevision(),"agent-1");
        assertNotEquals(original.getGrantId(),next.grantId());assertEquals("SUPERSEDED",old.getState());assertEquals("EXECUTE_SKILL",next.operation());assertFalse(next.paidExecutionAuthorized());
        var current=grants.byId.get(next.grantId());assertEquals("SKILLS_V1",current.getPermittedToolPolicyRef());assertNull(current.getCostAuthorizationRef());assertEquals(1,bootstraps.byAction.size());
        assertEquals(next,service.resolveSkillBaseline(scope(),"task-1",original.getAssignmentRevision(),"agent-1"));assertEquals(2,grants.byId.size());
    }
    @Test void nativeBaselineDoesNotReauthorizeDetachedBootstrapInputs() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var original=service.assignForDeliberation(scope(),"task-1","native-input-change","agent-1",0,1);
        var grant=grants.byId.get(original.getGrantId());
        grant.setInputScopeJson("[{\"fileId\":\"original-file\",\"version\":1,\"purpose\":\"INPUT\","
                +"\"contentMimeType\":\"image/png\",\"byteLength\":1499,\"contentHash\":\""+"a".repeat(64)+"\"}]");
        clearInvocations(workspace,links,identities,authority);

        var baseline=service.resolveSkillBaseline(scope(),"task-1",original.getAssignmentRevision(),"agent-1");
        assertEquals(original.getGrantId(),baseline.grantId());
        assertEquals("EXECUTE_SKILL",baseline.operation());
        assertFalse(baseline.paidExecutionAuthorized());
        assertEquals(1,grants.byId.size());
        verify(authority).requireGrantTarget("0","client","owner","task-1",grant.getGrantId(),grant.getGrantVersion());
        verify(identities).lockActiveCanonicalAgentIdsInScope("0","client","owner",List.of("agent-1"));
        verifyNoInteractions(workspace,links);

        // Actual native execution admission also uses the execution's current source snapshot.
        var execution=service.admit(scope(),"task-1",grant.getGrantId(),grant.getGrantVersion(),
                original.getAssignmentRevision(),"agent-1","EXECUTE_SKILL",false);
        assertEquals("EXECUTE_SKILL",execution.operation());
        assertEquals(List.of(),execution.inputs());
        assertEquals(execution,service.resolveAndAdmit(scope(),"task-1",original.getAssignmentRevision(),
                "agent-1","EXECUTE_SKILL",false));
        var promotion=service.resolveSelectedOutputPromotion(scope(),"task-1",original.getAssignmentRevision(),"agent-1");
        assertEquals("FINALIZE_SELECTED_OUTPUTS",promotion.operation());
        assertEquals(List.of(),promotion.inputs());
        assertEquals(promotion,service.admitSelectedOutputPromotion(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),original.getAssignmentRevision(),"agent-1"));
        verifyNoInteractions(workspace,links);
        // Non-native/frozen-input admission still rejects the unavailable original input.
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admit(scope(),"task-1",grant.getGrantId(),
                grant.getGrantVersion(),original.getAssignmentRevision(),"agent-1","INSPECT_INPUTS",false));
        when(identities.lockActiveCanonicalAgentIdsInScope("0","client","owner",List.of("agent-1")))
                .thenReturn(List.of());
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveSkillBaseline(
                scope(),"task-1",original.getAssignmentRevision(),"agent-1"));
    }

    @Test void discussionRoutingAndReceiptsDoNotAuthorizeDetachedBootstrapInputs() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var original=service.assignForDeliberation(scope(),"task-1","discussion-input-change","agent-1",0,1);
        var grant=grants.byId.get(original.getGrantId());
        grant.setInputScopeJson("[{\"fileId\":\"original-file\",\"version\":1,\"purpose\":\"INPUT\","
                +"\"contentMimeType\":\"image/png\",\"byteLength\":1499,\"contentHash\":\""+"a".repeat(64)+"\"}]");
        root.setRewardStatus("reviewing");
        clearInvocations(workspace,links);
        var current=service.currentFollowupContext(scope(),"task-1","agent-1");
        var admitted=service.resolveDiscussionBaseline(scope(),"task-1",current.baselineGrantVersion(),
                current.taskVersion(),current.assignmentRevision(),current.requirementRevision(),"agent-1");
        assertEquals(original.getGrantId(),admitted.grantId());
        assertEquals(List.of(),admitted.inputs());
        assertNull(admitted.operation());
        assertFalse(admitted.paidExecutionAuthorized());
        assertEquals(admitted,service.admitDiscussionBaseline(scope(),"task-1",admitted.grantId(),
                admitted.grantVersion(),current.taskVersion(),admitted.assignmentRevision(),current.requirementRevision(),"agent-1"));
        verifyNoInteractions(workspace,links);
        // Paid/frozen-input consumers keep their original admission semantics.
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveFollowupBaseline(scope(),"task-1",
                current.baselineGrantVersion(),current.taskVersion(),current.assignmentRevision(),current.requirementRevision(),"agent-1"));
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admitDiscussionBaseline(scope(),"task-1",
                admitted.grantId(),admitted.grantVersion(),current.taskVersion()+1,admitted.assignmentRevision(),current.requirementRevision(),"agent-1"));
        grant.setState("REVOKED");
        assertThrows(AgentTaskExecutionGrantException.class,()->service.admitDiscussionBaseline(scope(),"task-1",
                admitted.grantId(),admitted.grantVersion(),current.taskVersion(),admitted.assignmentRevision(),current.requirementRevision(),"agent-1"));
    }

    @Test void latestBaselineDoesNotReviveRevokedGrantOrDifferentAssignment() {
        when(links.list("0","client","owner","task-1",null,null,33)).thenReturn(List.of());
        var original=service.assignForDeliberation(scope(),"task-1","old-revoked","agent-1",0,1);
        assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveSkillBaseline(scope(),"task-1",99,"agent-1"));
        grants.byId.get(original.getGrantId()).setState("REVOKED");assertThrows(AgentTaskExecutionGrantException.class,()->service.resolveSkillBaseline(scope(),"task-1",original.getAssignmentRevision(),"agent-1"));assertEquals(1,grants.byId.size());
    }
    private static AgentTaskExecutionGrantService.Scope scope() {
        return new AgentTaskExecutionGrantService.Scope("0","client","owner");
    }

    private static final class DirectTransaction implements AgentTaskMutationTransaction {
        private final AgentTaskMetaEntity root; DirectTransaction(AgentTaskMetaEntity root){this.root=root;}
        @Override public <T>T executeWithLockedTaskRootInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){return m.apply(root);}
        @Override public <T>T executeWithLockedTaskRoot(String t,String c,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeWithLockedTaskRootForWorkItem(String t,String c,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeWithLockedTaskRootForWorkItemInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeAfterTaskRootReservation(String t,String c,String id,TaskRootReservation r,ReservedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeAfterTaskRootReservationInOwnerScope(String t,String c,String o,String id,TaskRootReservation r,ReservedTaskMutation<T> m){throw new UnsupportedOperationException();}
    }


    private static final class RollbackTransaction implements AgentTaskMutationTransaction {
        private final AgentTaskMetaEntity root;
        private final MemoryGrantDao grants;
        private final MemoryBootstrapDao bootstraps;
        RollbackTransaction(AgentTaskMetaEntity root,MemoryGrantDao grants,MemoryBootstrapDao bootstraps){
            this.root=root;this.grants=grants;this.bootstraps=bootstraps;
        }
        @Override public <T>T executeWithLockedTaskRootInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){
            String assigned=root.getAssignedAgentId();Long version=root.getTaskVersion();
            try{return m.apply(root);}catch(RuntimeException failure){
                root.setAssignedAgentId(assigned);root.setTaskVersion(version);
                grants.byAction.clear();grants.byId.clear();bootstraps.byAction.clear();bootstraps.byId.clear();
                throw failure;
            }
        }
        @Override public <T>T executeWithLockedTaskRoot(String t,String c,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeWithLockedTaskRootForWorkItem(String t,String c,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeWithLockedTaskRootForWorkItemInOwnerScope(String t,String c,String o,String id,LockedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeAfterTaskRootReservation(String t,String c,String id,TaskRootReservation r,ReservedTaskMutation<T> m){throw new UnsupportedOperationException();}
        @Override public <T>T executeAfterTaskRootReservationInOwnerScope(String t,String c,String o,String id,TaskRootReservation r,ReservedTaskMutation<T> m){throw new UnsupportedOperationException();}
    }

    private static final class MemoryBootstrapDao implements AgentTaskBountyBootstrapOutboxDao {
        private final Map<String,AgentTaskBountyBootstrapOutboxEntity> byAction=new LinkedHashMap<>();
        private final Map<String,AgentTaskBountyBootstrapOutboxEntity> byId=new LinkedHashMap<>();
        private boolean failInsert;
        @Override public AgentTaskBountyBootstrapOutboxEntity findByActionForUpdate(String t,String c,String o,String a){return byAction.get(a);}
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableForUpdate(String t,String c,String o,long now){throw new UnsupportedOperationException();}
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(String tenant, String client, long now){throw new UnsupportedOperationException();}
        @Override public AgentTaskBountyBootstrapOutboxEntity findByBootstrapForUpdate(String t,String c,String o,String id){return byId.get(id);}
        @Override public void insert(AgentTaskBountyBootstrapOutboxEntity row){
            if(failInsert)throw new IllegalStateException("bootstrap insert failed");
            byAction.put(row.getSourceBusinessActionId(),row);byId.put(row.getBootstrapId(),row);
        }
        @Override public boolean claim(AgentTaskBountyBootstrapOutboxEntity row,String owner,long until,long now){throw new UnsupportedOperationException();}
        @Override public boolean reconcile(AgentTaskBountyBootstrapOutboxEntity row,String status,Long next,String conversation,String request,String error,Long reconciled,long now){throw new UnsupportedOperationException();}
    }

    private static final class MemoryGrantDao implements AgentTaskExecutionGrantDao {
        private final Map<String,AgentTaskExecutionGrantEntity> byAction=new LinkedHashMap<>();
        private final Map<String,AgentTaskExecutionGrantEntity> byId=new LinkedHashMap<>();
        private Long latestAssignedVersion=1L;
        @Override public AgentTaskExecutionGrantEntity findLatestByTaskAgent(String t,String c,String o,String task,String agent){return byId.values().stream().filter(g->t.equals(g.getTenantId())&&c.equals(g.getClientId())&&o.equals(g.getOwnerJiacn())&&task.equals(g.getTaskId())&&agent.equals(g.getTargetAgentId())).max(java.util.Comparator.comparing(AgentTaskExecutionGrantEntity::getAssignmentRevision)).orElse(null);}
        @Override public AgentTaskExecutionGrantEntity findByActionForUpdate(String t,String c,String o,String a){return byAction.get(a);}
        @Override public AgentTaskExecutionGrantEntity findByGrantForUpdate(String t,String c,String o,String task,String id){return byId.get(id);}
        @Override public AgentTaskExecutionGrantEntity findActiveByTask(String t,String c,String o,String task){return byId.values().stream().filter(g->task.equals(g.getTaskId())&&"ACTIVE".equals(g.getState())).findFirst().orElse(null);}
        @Override public AgentTaskExecutionGrantEntity findByGrant(String t,String c,String o,String task,String id){return byId.get(id);}
        @Override public String latestAssignmentEventJson(String t,String c,String o,String task){
            return latestAssignedVersion==null?null:"{\"resultVersion\":"+latestAssignedVersion+"}";
        }
        @Override public void insert(AgentTaskExecutionGrantEntity g){byAction.put(g.getSourceBusinessActionId(),g);byId.put(g.getGrantId(),g);}
        @Override public int supersedeActiveForTask(String t,String c,String o,String task,long at){int n=0;for(var g:byId.values())if(task.equals(g.getTaskId())&&"ACTIVE".equals(g.getState())){g.setState("SUPERSEDED").setGrantVersion(g.getGrantVersion()+1).setRevokedAt(at);n++;}return n;}
        @Override public boolean setCostAuthorizationRef(String t,String c,String o,String task,String id,long v,String locator){var g=byId.get(id);if(g==null||!t.equals(g.getTenantId())||!c.equals(g.getClientId())||!o.equals(g.getOwnerJiacn())||!task.equals(g.getTaskId())||g.getGrantVersion()!=v||g.getCostAuthorizationRef()!=null)return false;g.setCostAuthorizationRef(locator);return true;}
        @Override public boolean revoke(String t,String c,String o,String task,String id,long v,String key,String hash,long at){var g=byId.get(id);if(g==null||!"ACTIVE".equals(g.getState())||g.getGrantVersion()!=v)return false;g.setState("REVOKED").setGrantVersion(v+1).setRevokedAt(at).setRevokeIdempotencyKey(key).setRevokeRequestHash(hash);return true;}
    }
}
