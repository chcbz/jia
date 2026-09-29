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
    private final AgentTaskMetaEntity root = new AgentTaskMetaEntity();
    private AgentTaskExecutionGrantServiceImpl service;

    @BeforeEach
    void setUp() {
        root.setTenantId("0"); root.setClientId("client"); root.setOwnerJiacn("owner");
        root.setTaskId("task-1"); root.setTaskVersion(0L); root.setCurrentEventVersion(0L);
        root.setRewardStatus("open");
        when(legacy.resolveAgentId("0","client","owner","agent-1")).thenReturn("agent-1");
        when(identities.lockActiveCanonicalAgentIdsInScope("0","client","owner",List.of("agent-1")))
                .thenReturn(List.of("agent-1"));
        when(legacy.assignResolvedVersionedWithLockedTask(anyString(),anyString(),anyString(),
                anyString(),anyList(),eq(false),anyLong(),any(),same(root))).thenAnswer(invocation -> {
            long expected=invocation.getArgument(6);
            AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator validator=invocation.getArgument(7);
            validator.validate(root,List.of("agent-1"));
            boolean changed=root.getAssignedAgentId()==null;
            root.setTaskVersion(changed ? expected+1 : expected); root.setAssignedAgentId("agent-1");
            return changed
                    ? new AgentLegacyTaskCompatibilityService.AssignOutcome(List.of("agent-1"),true,"event-1",1L)
                    : new AgentLegacyTaskCompatibilityService.AssignOutcome(List.of("agent-1"),false);
        });
        service=new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,legacy,identities,
                new DirectTransaction(root),new ObjectMapper());
    }

    @Test
    void sameKeySamePayloadReplaysAndDifferentPayloadConflicts() {
        AgentTaskAssignDTO request=request();
        var first=service.assignAndGrant(scope(),"task-1","key-1",request);
        var replay=service.assignAndGrant(scope(),"task-1","key-1",request);
        assertEquals(first.getGrantId(),replay.getGrantId());
        assertEquals(1L,first.getAssignmentRevision());
        assertFalse(first.getPaidExecutionAuthorized());
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
        verify(legacy,never()).resolveAgentId(anyString(),anyString(),anyString(),anyString());
        assertTrue(grants.byAction.isEmpty());
        assertTrue(bootstraps.byAction.isEmpty());
    }

    @Test
    void bootstrapFailureRollsBackAssignmentAndGrantBoundary() {
        bootstraps.failInsert=true;
        service=new AgentTaskExecutionGrantServiceImpl(grants,bootstraps,links,workspace,legacy,identities,
                new RollbackTransaction(root,grants,bootstraps),new ObjectMapper());
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
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(long now){throw new UnsupportedOperationException();}
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
        @Override public AgentTaskExecutionGrantEntity findByActionForUpdate(String t,String c,String o,String a){return byAction.get(a);}
        @Override public AgentTaskExecutionGrantEntity findByGrantForUpdate(String t,String c,String o,String task,String id){return byId.get(id);}
        @Override public AgentTaskExecutionGrantEntity findActiveByTask(String t,String c,String o,String task){return byId.values().stream().filter(g->task.equals(g.getTaskId())&&"ACTIVE".equals(g.getState())).findFirst().orElse(null);}
        @Override public AgentTaskExecutionGrantEntity findByGrant(String t,String c,String o,String task,String id){return byId.get(id);}
        @Override public void insert(AgentTaskExecutionGrantEntity g){byAction.put(g.getSourceBusinessActionId(),g);byId.put(g.getGrantId(),g);}
        @Override public int supersedeActiveForTask(String t,String c,String o,String task,long at){int n=0;for(var g:byId.values())if(task.equals(g.getTaskId())&&"ACTIVE".equals(g.getState())){g.setState("SUPERSEDED").setGrantVersion(g.getGrantVersion()+1).setRevokedAt(at);n++;}return n;}
        @Override public boolean revoke(String t,String c,String o,String task,String id,long v,String key,String hash,long at){var g=byId.get(id);if(g==null||!"ACTIVE".equals(g.getState())||g.getGrantVersion()!=v)return false;g.setState("REVOKED").setGrantVersion(v+1).setRevokedAt(at).setRevokeIdempotencyKey(key).setRevokeRequestHash(hash);return true;}
    }
}
