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

    @Test void historicalReceiptReadsConsumedAuthorityWithoutWriting() {
        String consentId="consent_1234567890abcdef1234567890abcdef";
        String locator="mmd-ci-v1:"+consentId;
        var operation=new ControlledImageBridgeOperationEntity().setGrantId("grant").setGrantVersion(1L)
                .setAssignmentRevision(7L).setConsentId(consentId).setExpectedConsentVersion(1L)
                .setAuthorityLocator(locator).setExecutionProtocolVersion(3)
                .setOperationGrantId("opgrant_"+"a".repeat(32));
        var grant=grant().setCostAuthorizationRef(locator);
        var consumed=consent("CONSUMED",4L).setBoundGrantId("grant")
                .setBoundGrantVersion(1L).setBoundAssignmentRevision(7L);
        when(operations.find("0","client","owner","task","assignment-key")).thenReturn(operation);
        when(grantRows.findByGrant("0","client","owner","task","grant")).thenReturn(grant);
        when(consentRows.findByConsent("0","client","owner","task",consentId)).thenReturn(consumed);
        when(grants.view(grant)).thenReturn(grantView());
        when(consents.view(same(consumed),anyLong())).thenReturn(consentView("CONSUMED","4"));
        assertEquals("CONSUMED",service.get(scope,"task","assignment-key").providerConsent().state());
        verify(operations,never()).insert(any());
        verify(operations,never()).lock(anyString(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(transactions);
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


    private static ControlledImagePointAndStartDTO.Request requestWithConsent(String consentId,String version) {
        return requestWithConsentAtTaskVersion(consentId,version,6L);
    }

    private static ControlledImagePointAndStartDTO.Request requestWithConsentAtTaskVersion(
            String consentId,String version,long taskVersion) {
        AgentTaskAssignDTO assignment=new AgentTaskAssignDTO();
        assignment.setWorkflowVersion(2);assignment.setBusinessAction("assign_and_start");
        assignment.setExpectedTaskVersion(taskVersion);assignment.setRequirementRevision(3L);
        assignment.setAgentId("agent");assignment.setRequestedOperations(java.util.List.of("GENERATE_IMAGE"));
        assignment.setInitialOperation("GENERATE_IMAGE");assignment.setInputRefs(java.util.List.of());
        return new ControlledImagePointAndStartDTO.Request(1,assignment,
                new ControlledImagePointAndStartDTO.ProviderConsent(consentId,version));
    }
    private static AgentTaskMetaEntity root() {
        AgentTaskMetaEntity root=new AgentTaskMetaEntity().setTaskId("task").setTaskVersion(0L)
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
