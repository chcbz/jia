package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantException;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.ControlledImageExecutionSessionLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlledImageGrantAuthorityTest {
    private final AgentTaskExecutionGrantDao grants=mock(AgentTaskExecutionGrantDao.class);
    private final AgentTaskProviderCostConsentDao consents=mock(AgentTaskProviderCostConsentDao.class);
    private final ControlledImageProviderOperatorPolicy policies=mock(ControlledImageProviderOperatorPolicy.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<ControlledImageExecutionSessionLookup> sessions=mock(ObjectProvider.class);
    private final ControlledImageExecutionSessionLookup lookup=mock(ControlledImageExecutionSessionLookup.class);
    private final ControlledImageGrantAuthority authority=
            new ControlledImageGrantAuthority(grants,consents,policies,sessions);
    private final AgentTaskExecutionGrantService.Scope scope=
            new AgentTaskExecutionGrantService.Scope("0","client","owner");

    @Test void exactReservedTupleAndSameRuntimeSessionAdmitProviderStart() {
        var grant=grant();var consent=consent("RESERVED");
        when(consents.findByConsentForUpdate("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        readyPolicyAndSession();
        var result=authority.verify(scope,root(),grant,"PROVIDER_START","execution","run","runtime");
        assertSame(consent,result.consent());
        assertEquals("runtime",result.session().runtimeInstanceId());
    }

    @Test void arbitraryNonemptyLocatorAndEveryPersistedTupleDriftFailClosed() {
        var grant=grant().setCostAuthorizationRef("customer-supplied-token");
        assertDenied(() -> authority.verify(scope,root(),grant,"NEW_EXECUTION",null,null,null));
        readyPolicyAndSession();
        List<Consumer<AgentTaskProviderCostConsentEntity>> drifts=List.of(
                row -> row.setTenantId("1"),
                row -> row.setClientId("other-client"),
                row -> row.setOwnerJiacn("other"),
                row -> row.setTaskId("other-task"),
                row -> row.setTargetAgentId("other"),
                row -> row.setBoundGrantId("other-grant"),
                row -> row.setBoundGrantVersion(2L),
                row -> row.setBoundAssignmentRevision(8L),
                row -> row.setRequirementRevision(4L),
                row -> row.setAssignmentIdempotencyKey("other-key"),
                row -> row.setAssignmentBaseHash("b".repeat(64)),
                row -> row.setBindingId("other-binding"),
                row -> row.setBindingEpoch(2L),
                row -> row.setModelId("other-model"),
                row -> row.setOperatorPolicyRevision("other-policy"));
        for (Consumer<AgentTaskProviderCostConsentEntity> drift:drifts) {
            AgentTaskProviderCostConsentEntity changed=consent("BOUND");
            drift.accept(changed);
            when(consents.findByConsentForUpdate("0","client","owner","task",changed.getConsentId()))
                    .thenReturn(changed);
            assertDenied(() -> authority.verify(scope,root(),grant(),"NEW_EXECUTION",null,null,null));
        }
    }

    @Test void ownerSummaryRequiresARealBoundTupleNotLocatorShapeAlone() {
        var grant=grant();
        assertFalse(authority.isPersistedAuthorized(grant));
        var bound=consent("BOUND");
        when(consents.findByConsent("0","client","owner","task",bound.getConsentId()))
                .thenReturn(bound);
        assertTrue(authority.isPersistedAuthorized(grant));
        bound.setAssignmentBaseHash("b".repeat(64));
        assertFalse(authority.isPersistedAuthorized(grant));
        bound.setAssignmentBaseHash("a".repeat(64)).setState("REVOKED");
        assertFalse(authority.isPersistedAuthorized(grant));
    }

    @Test void consumedAuthorityIsReadAuditOnlyAndCannotOpenAnotherProviderStart() {
        var consent=consent("CONSUMED");
        when(consents.findByConsentForUpdate("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        var existing=authority.verify(scope,root(),grant(),"EXISTING_RUN","execution","run",null);
        assertNull(existing.session());
        assertDenied(() -> authority.verify(scope,root(),grant(),"PROVIDER_START","execution","run","runtime"));
        verifyNoInteractions(policies,sessions);
    }

    private void readyPolicyAndSession() {
        when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(1L),anyLong()))
                .thenReturn(new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1",
                        "binding",1,"model","OPERATOR_TEMPLATE","operator","policy-r1",
                        "UNPRICED_EXTERNAL_ACCOUNT",1,9_000_000_000_000L));
        when(sessions.getIfUnique()).thenReturn(lookup);
        when(lookup.current(any())).thenReturn(new ControlledImageExecutionSessionLookup.Snapshot(
                ControlledImageExecutionSessionLookup.State.READY,"runtime",1,
                "PERSONAL_WORKSPACE_CONTROLLED_IMAGE_HTTP_V2",List.of("GENERATE_IMAGE"),
                "CONTROLLED_IMAGE_HTTP_V1","binding",1L,"model",16,1,1));
    }
    private static AgentTaskMetaEntity root() {
        var value=new AgentTaskMetaEntity().setTaskId("task").setAssignedAgentId("agent").setTaskVersion(7L);
        value.setTenantId("0");value.setClientId("client");value.setOwnerJiacn("owner");return value;
    }
    private static AgentTaskExecutionGrantEntity grant() {
        var value=new AgentTaskExecutionGrantEntity().setGrantId("grant").setOwnerJiacn("owner")
                .setTaskId("task").setRequirementRevision(3L).setAssignmentRevision(7L)
                .setTargetAgentId("agent").setCostAuthorizationRef(
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef")
                .setSourceBusinessActionId("ASSIGN_AND_START:assignment-key")
                .setIdempotencyKey("assignment-key").setRequestHash("a".repeat(64))
                .setGrantVersion(1L).setState("ACTIVE");
        value.setTenantId("0");value.setClientId("client");return value;
    }
    private static AgentTaskProviderCostConsentEntity consent(String state) {
        var value=new AgentTaskProviderCostConsentEntity()
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setOwnerJiacn("owner").setTaskId("task").setTargetAgentId("agent")
                .setAssignmentIdempotencyKey("assignment-key").setAssignmentBaseHash("a".repeat(64))
                .setRequirementRevision(3L).setProviderLane("CONTROLLED_IMAGE_HTTP_V1")
                .setBindingId("binding").setBindingEpoch(1L).setModelId("model")
                .setCustody("OPERATOR_TEMPLATE").setOperatorIssuer("operator")
                .setOperatorPolicyRevision("policy-r1").setPricingMode("UNPRICED_EXTERNAL_ACCOUNT")
                .setMaxOutboundRequestAttempts(1).setExpiresAt(9_000_000_000_000L)
                .setState(state).setVersion("BOUND".equals(state)?2L:"RESERVED".equals(state)?3L:4L)
                .setBoundGrantId("grant").setBoundGrantVersion(1L).setBoundAssignmentRevision(7L)
                .setReservedExecutionId("execution").setReservedRunId("run");
        value.setTenantId("0");value.setClientId("client");return value;
    }
    private static void assertDenied(org.junit.jupiter.api.function.Executable call) {
        var failure=assertThrows(AgentTaskExecutionGrantException.class,call);
        assertEquals(AgentTaskExecutionGrantException.Reason.PAID_EXECUTION_NOT_AUTHORIZED,
                failure.reason());
    }
}
