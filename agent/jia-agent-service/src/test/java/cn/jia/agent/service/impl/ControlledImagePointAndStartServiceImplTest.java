package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.entity.AgentTaskAssignDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentDTO;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.entity.ControlledImagePointAndStartDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskProviderCostConsentService;
import cn.jia.agent.service.ControlledImagePointAndStartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlledImagePointAndStartServiceImplTest {
    private final ControlledImageBridgeOperationDao operations=mock(ControlledImageBridgeOperationDao.class);
    private final AgentTaskExecutionGrantDao grantRows=mock(AgentTaskExecutionGrantDao.class);
    private final AgentTaskProviderCostConsentDao consentRows=mock(AgentTaskProviderCostConsentDao.class);
    private final AgentTaskExecutionGrantServiceImpl grants=mock(AgentTaskExecutionGrantServiceImpl.class);
    private final AgentTaskProviderCostConsentServiceImpl consents=mock(AgentTaskProviderCostConsentServiceImpl.class);
    private final ControlledImageGrantAuthority authority=mock(ControlledImageGrantAuthority.class);
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final AgentTaskExecutionGrantService.Scope scope=
            new AgentTaskExecutionGrantService.Scope("0","client","owner");
    private ControlledImagePointAndStartServiceImpl service;

    @BeforeEach void setUp() {
        service=new ControlledImagePointAndStartServiceImpl(operations,grantRows,consentRows,grants,
                consents,authority,transactions,new ObjectMapper());
        doAnswer(invocation -> {
            AgentTaskMutationTransaction.LockedTaskMutation<?> callback=invocation.getArgument(4);
            return callback.apply(root());
        }).when(transactions).executeWithLockedTaskRootInOwnerScope(
                anyString(),anyString(),anyString(),anyString(),any());
    }

    @Test void firstSubmitPersistsExactWrapperThenReplayAndGetRemainReadOnly() {
        AgentTaskExecutionGrantEntity grant=grant();
        AgentTaskProviderCostConsentEntity issued=consent("ISSUED",1L);
        AgentTaskProviderCostConsentDTO bound=consentView("BOUND","2");
        AgentTaskExecutionGrantDTO grantView=grantView();
        when(grants.assignControlledWithinLockedTask(eq(scope),eq("task"),eq("assignment-key"),any(),any()))
                .thenReturn(grantView);
        when(authority.lockGrant(scope,"task","grant")).thenReturn(grant);
        when(consents.lockForBridge(any(),eq("task"),eq(issued.getConsentId()))).thenReturn(issued);
        when(consents.bindWithinLockedRoot(any(),eq("task"),same(issued),eq(1L),same(grant),same(authority)))
                .thenReturn(bound);
        when(grants.view(grant)).thenReturn(grantView);
        ArgumentCaptor<ControlledImageBridgeOperationEntity> persisted=
                ArgumentCaptor.forClass(ControlledImageBridgeOperationEntity.class);
        doNothing().when(operations).insert(any());

        var first=service.submit(scope,"task","assignment-key",request("1"));

        assertFalse(first.replay());
        assertEquals("BOUND",first.receipt().providerConsent().state());
        verify(operations).insert(persisted.capture());
        ControlledImageBridgeOperationEntity operation=persisted.getValue();
        assertEquals(64,operation.getWrapperDigest().length());
        assertEquals("mmd-ci-v1:"+issued.getConsentId(),operation.getAuthorityLocator());
        assertEquals(1L,operation.getExpectedConsentVersion());

        AgentTaskProviderCostConsentEntity consumed=consent("CONSUMED",4L)
                .setBoundGrantId("grant").setBoundGrantVersion(1L).setBoundAssignmentRevision(7L);
        grant.setCostAuthorizationRef(operation.getAuthorityLocator());
        when(operations.lock("0","client","owner","task","assignment-key")).thenReturn(operation);
        when(operations.find("0","client","owner","task","assignment-key")).thenReturn(operation);
        when(grantRows.findByGrant("0","client","owner","task","grant")).thenReturn(grant);
        when(consentRows.findByConsent("0","client","owner","task",issued.getConsentId()))
                .thenReturn(consumed);
        when(consents.view(same(consumed),anyLong())).thenReturn(consentView("CONSUMED","4"));

        var replay=service.submit(scope,"task","assignment-key",request("1"));
        var recovered=service.get(scope,"task","assignment-key");

        assertTrue(replay.replay());
        assertEquals("CONSUMED",replay.receipt().providerConsent().state());
        assertEquals("CONSUMED",recovered.providerConsent().state());
        verify(grants,times(1)).assignControlledWithinLockedTask(any(),anyString(),anyString(),any(),any());
        verify(operations,times(1)).insert(any());
        verify(operations,times(1)).find(anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void sameKeyChangedConsentOrVersionConflictsBeforeAnyNewAssignment() {
        ControlledImageBridgeOperationEntity persisted=new ControlledImageBridgeOperationEntity()
                .setWrapperDigest("a".repeat(64));
        when(operations.lock("0","client","owner","task","assignment-key")).thenReturn(persisted);

        for (var changed:new ControlledImagePointAndStartDTO.Request[]{request("2"),requestWithConsent(
                "consent_abcdefabcdefabcdefabcdefabcdefab","1")}) {
            var failure=assertThrows(ControlledImagePointAndStartService.Failure.class,
                    () -> service.submit(scope,"task","assignment-key",changed));
            assertEquals(ControlledImagePointAndStartService.Reason.CONFLICT,failure.reason());
        }
        verifyNoInteractions(grants,consents,authority,grantRows,consentRows);
        verify(operations,never()).insert(any());
    }

    @Test void laneShapeRejectsWrongOperationAndSeventeenReferencesBeforeAssignmentOrBind() {
        var wrongOperation=request("1");
        wrongOperation.assignment().setRequestedOperations(java.util.List.of("EDIT_IMAGE"));
        wrongOperation.assignment().setInitialOperation("EDIT_IMAGE");
        var tooMany=request("1");
        java.util.List<cn.jia.agent.entity.AgentTaskGrantInputDTO> refs=new java.util.ArrayList<>();
        for(int i=0;i<17;i++)refs.add(new cn.jia.agent.entity.AgentTaskGrantInputDTO());
        tooMany.assignment().setInputRefs(refs);

        for(var invalid:java.util.List.of(wrongOperation,tooMany)) {
            var failure=assertThrows(ControlledImagePointAndStartService.Failure.class,
                    () -> service.submit(scope,"task","assignment-key",invalid));
            assertEquals(ControlledImagePointAndStartService.Reason.BAD_REQUEST,failure.reason());
        }
        verifyNoInteractions(transactions,grants,consents,authority,operations,grantRows,consentRows);
    }

    @Test void lateMappingOrConsentUniquenessCollisionIsConflictAndRollsBack() {
        AgentTaskExecutionGrantEntity grant=grant();
        AgentTaskProviderCostConsentEntity issued=consent("ISSUED",1L);
        when(grants.assignControlledWithinLockedTask(eq(scope),eq("task"),eq("assignment-key"),any(),any()))
                .thenReturn(grantView());
        when(authority.lockGrant(scope,"task","grant")).thenReturn(grant);
        when(consents.lockForBridge(any(),eq("task"),eq(issued.getConsentId()))).thenReturn(issued);
        when(consents.bindWithinLockedRoot(any(),eq("task"),same(issued),eq(1L),same(grant),same(authority)))
                .thenReturn(consentView("BOUND","2"));
        doThrow(new org.springframework.dao.DuplicateKeyException("same consent already mapped"))
                .when(operations).insert(any());

        var failure=assertThrows(ControlledImagePointAndStartService.Failure.class,
                () -> service.submit(scope,"task","assignment-key",request("1")));

        assertEquals(ControlledImagePointAndStartService.Reason.CONFLICT,failure.reason());
        verify(operations).insert(any());
        verify(consents).bindWithinLockedRoot(any(),eq("task"),same(issued),eq(1L),same(grant),same(authority));
    }

    @Test void readbackRejectsRegressedOrUnrelatedAuthorityWithoutWriting() {
        ControlledImageBridgeOperationEntity operation=new ControlledImageBridgeOperationEntity()
                .setGrantId("grant").setGrantVersion(1L).setAssignmentRevision(7L)
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setExpectedConsentVersion(1L)
                .setAuthorityLocator("mmd-ci-v1:consent_1234567890abcdef1234567890abcdef");
        when(operations.find("0","client","owner","task","assignment-key")).thenReturn(operation);
        AgentTaskExecutionGrantEntity grant=grant().setCostAuthorizationRef(operation.getAuthorityLocator());
        when(grantRows.findByGrant("0","client","owner","task","grant")).thenReturn(grant);
        when(consentRows.findByConsent("0","client","owner","task",operation.getConsentId()))
                .thenReturn(consent("BOUND",1L).setBoundGrantId("grant")
                        .setBoundGrantVersion(1L).setBoundAssignmentRevision(7L));

        var failure=assertThrows(ControlledImagePointAndStartService.Failure.class,
                () -> service.get(scope,"task","assignment-key"));
        assertEquals(ControlledImagePointAndStartService.Reason.SOURCE_UNAVAILABLE,failure.reason());
        verify(operations,never()).lock(anyString(),anyString(),anyString(),anyString(),anyString());
        verify(operations,never()).insert(any());
        verifyNoInteractions(transactions);
    }

    private static ControlledImagePointAndStartDTO.Request request(String version) {
        return requestWithConsent("consent_1234567890abcdef1234567890abcdef",version);
    }
    private static ControlledImagePointAndStartDTO.Request requestWithConsent(String consentId,String version) {
        AgentTaskAssignDTO assignment=new AgentTaskAssignDTO();
        assignment.setWorkflowVersion(2);assignment.setBusinessAction("assign_and_start");
        assignment.setExpectedTaskVersion(6L);assignment.setRequirementRevision(3L);
        assignment.setAgentId("agent");assignment.setRequestedOperations(java.util.List.of("GENERATE_IMAGE"));
        assignment.setInitialOperation("GENERATE_IMAGE");assignment.setInputRefs(java.util.List.of());
        return new ControlledImagePointAndStartDTO.Request(1,assignment,
                new ControlledImagePointAndStartDTO.ProviderConsent(consentId,version));
    }
    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(7L)
                .setAssignedAgentId("agent");
        root.setTenantId("0");root.setClientId("client");root.setOwnerJiacn("owner");return root;
    }
    private static AgentTaskExecutionGrantEntity grant() {
        AgentTaskExecutionGrantEntity grant=new AgentTaskExecutionGrantEntity().setGrantId("grant")
                .setOwnerJiacn("owner").setTaskId("task").setRequirementRevision(3L)
                .setAssignmentRevision(7L).setTargetAgentId("agent")
                .setIdempotencyKey("assignment-key").setRequestHash("a".repeat(64))
                .setGrantVersion(1L).setState("ACTIVE");
        grant.setTenantId("0");grant.setClientId("client");return grant;
    }
    private static AgentTaskProviderCostConsentEntity consent(String state,long version) {
        AgentTaskProviderCostConsentEntity row=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setOwnerJiacn("owner").setTaskId("task").setTargetAgentId("agent")
                .setAssignmentIdempotencyKey("assignment-key").setAssignmentBaseHash("a".repeat(64))
                .setState(state).setVersion(version);
        row.setTenantId("0");row.setClientId("client");return row;
    }
    private static AgentTaskExecutionGrantDTO grantView() {
        return new AgentTaskExecutionGrantDTO().setGrantId("grant").setTaskId("task")
                .setRequirementRevision(3L).setAssignmentRevision(7L).setTargetAgentId("agent")
                .setPermittedOperations(java.util.List.of("GENERATE_IMAGE")).setInputs(java.util.List.of())
                .setState("ACTIVE").setGrantVersion(1L).setPaidExecutionAuthorized(true)
                .setCreatedAt(1L);
    }
    private static AgentTaskProviderCostConsentDTO consentView(String state,String version) {
        return new AgentTaskProviderCostConsentDTO(1,
                "consent_1234567890abcdef1234567890abcdef","task","agent",state,version,
                "assignment-key","a".repeat(64),"b".repeat(64),
                new AgentTaskProviderCostConsentDTO.ProviderBinding("binding","1"),"model",
                "OPERATOR_TEMPLATE","policy-r1","UNPRICED_EXTERNAL_ACCOUNT",1,"9000000000000");
    }
}
