package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskProviderCostConsentDao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.dao.ControlledImageExecutionSourceV3Dao;
import cn.jia.agent.dao.ControlledImageIntentOperationGrantDao;
import cn.jia.agent.dao.PersonalWorkspaceExecutionDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.AgentTaskProviderCostConsentEntity;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.entity.ControlledImageIntentOperationGrantEntity;
import cn.jia.agent.entity.PersonalWorkspaceExecutionEntity;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ControlledImageIntentOperationGrantServiceTest {
    private final AgentTaskMutationTransaction transactions=mock(AgentTaskMutationTransaction.class);
    private final AgentTaskExecutionGrantService grants=mock(AgentTaskExecutionGrantService.class);
    private final AgentTaskProviderCostConsentDao consents=mock(AgentTaskProviderCostConsentDao.class);
    private final ControlledImageIntentOperationGrantDao operationGrants=mock(ControlledImageIntentOperationGrantDao.class);
    private final PersonalWorkspaceExecutionDao executions=mock(PersonalWorkspaceExecutionDao.class);
    private final ControlledImageExecutionSourceV3Dao sources=mock(ControlledImageExecutionSourceV3Dao.class);
    private final ControlledImageBridgeOperationDao initialOperations=mock(ControlledImageBridgeOperationDao.class);
    private final ControlledImageProviderOperatorPolicy policies=mock(ControlledImageProviderOperatorPolicy.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup> declarations=mock(ObjectProvider.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup> sourceAccess=mock(ObjectProvider.class);
    private final ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup declaration=mock(ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.class);
    private final ControlledImageFollowupAuthorityService service=new ControlledImageFollowupAuthorityServiceImpl(
            transactions,grants,consents,operationGrants,executions,sources,policies,declarations,
            sourceAccess,new ObjectMapper());
    private final ControlledImageFollowupAuthorityService.Scope scope=
            new ControlledImageFollowupAuthorityService.Scope("0","client","owner");

    @BeforeEach void rootTransactionAndDeclaration() {
        when(transactions.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),
                eq("task"),any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            AgentTaskMutationTransaction.LockedTaskMutation<Object> mutation=invocation.getArgument(4);
            return mutation.apply(new AgentTaskMetaEntity().setTaskId("task"));
        });
        ((ControlledImageFollowupAuthorityServiceImpl)service).setInitialControlledImageV3(initialOperations);
        when(declarations.getIfUnique()).thenReturn(declaration);
        when(declaration.current(any())).thenReturn(new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.Declaration(
                ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,"runtime",
                List.of("GENERATE_IMAGE","EDIT_IMAGE"),"CONTROLLED_IMAGE_HTTP_V1","binding",7L,
                "model",16,1,1));
    }

    @Test void exactIssueReplayPrecedesBaselinePolicyAndLateChecks() {
        var row=operationGrant("issue-key","a".repeat(64));
        when(operationGrants.lockByIssueKey("0","client","owner","task","issue-key")).thenReturn(row);
        when(consents.findFollowupByConsent("0","client","owner","task",row.getConsentId()))
                .thenReturn(consent(row));
        var late=mock(ControlledImageFollowupAuthorityService.LateCheck.class);

        var replay=service.issue(scope,issue("a".repeat(64)),late);

        assertTrue(replay.replay());
        assertEquals("AUTHORIZED",replay.operationGrantState());
        verifyNoInteractions(grants,policies);
        verify(late,never()).verify();
        verify(operationGrants,never()).insert(any());
        verify(consents,never()).insert(any());
    }

    @Test void sameIssueKeyWithChangedBodyConflictsWithoutCurrentChecksOrWrites() {
        when(operationGrants.lockByIssueKey("0","client","owner","task","issue-key"))
                .thenReturn(operationGrant("issue-key","a".repeat(64)));
        var late=mock(ControlledImageFollowupAuthorityService.LateCheck.class);

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.issue(scope,issue("b".repeat(64)),late));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(grants,policies,consents);
        verify(late,never()).verify();
    }

    @Test void expectedPreviewDriftIsZeroWriteAndDoesNotCreateIndependentAuthority() {
        var command=preview();
        when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),eq(0L),eq(1L),eq("agent")))
                .thenReturn(baseline());
        when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(7L),anyLong()))
                .thenReturn(new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1",
                        "binding",7,"model","OPERATOR_TEMPLATE","operator","policy-r1",
                        "UNPRICED_EXTERNAL_ACCOUNT",1,Long.MAX_VALUE));
        var wrongExpected=new ControlledImageFollowupAuthorityService.ExpectedPreview("f".repeat(64),
                command.instructionSha256(),command.sourceSnapshotSha256(),"model","OPERATOR_TEMPLATE","policy-r1");
        var request=new ControlledImageFollowupAuthorityService.IssueCommand(command,"issue-key",
                "c".repeat(64),new ControlledImageFollowupAuthorityService.ProviderExpectation(
                "binding",7,"model","OPERATOR_TEMPLATE","policy-r1"),wrongExpected,
                "UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT");

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.issue(scope,request,()->fail("late check must not run")));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.CONFLICT,failure.reason());
        verify(operationGrants,never()).insert(any());
        verify(consents,never()).insert(any());
        verifyNoInteractions(executions,sources);
    }

    @Test void reserveReplayRejectsChangedPersistedRuntimeSnapshotBeforeAuthorityOrLateChecks() {
        String expectedRuntimeDigest="cf52041c690cdb7fa6625ba3dbb00adf4d7fbad71882278e0b5968a50cf915e5";
        var prior=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setRunId("run")
                .setRequestHash("6".repeat(64)).setRuntimeInputSnapshotDigest("7".repeat(64))
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef");
        when(executions.findByIdempotency("0","client","owner","conv_"+
                "282bcbc3f0a34a8a4ac6f00c276fcf66cf3757a3332e83d92208e5079af46922"))
                .thenReturn(prior);
        var late=mock(ControlledImageFollowupAuthorityService.LateCheck.class);
        var command=new ControlledImageFollowupAuthorityService.ReserveCommand(preview(),
                new ControlledImageFollowupAuthorityService.Authority(
                        "consent_1234567890abcdef1234567890abcdef",1,
                        "opgrant_1234567890abcdef1234567890abcdef",1),
                "6".repeat(64),expectedRuntimeDigest,"execution","run","image/png");

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.reserve(scope,command,late));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.CONFLICT,failure.reason());
        verifyNoInteractions(operationGrants,consents,grants,sources,policies);
        verify(late,never()).verify();
    }

    @Test void originalKeyReadStorageFailuresAreTypedUnavailable() {
        var row=operationGrant("issue-key","a".repeat(64));
        when(operationGrants.findByIssueKey("0","client","owner","task","issue-key"))
                .thenReturn(row).thenThrow(new DataAccessResourceFailureException("down"));
        when(consents.findFollowupByConsent("0","client","owner","task",row.getConsentId()))
                .thenReturn(consent(row));

        var reconcile=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.reconcileIssue(scope,"task","conversation","issue-key","a".repeat(64)));
        assertEquals(ControlledImageFollowupAuthorityService.Reason.UNAVAILABLE,reconcile.reason());

        reset(operationGrants);
        when(operationGrants.findByInteractionKey("0","client","owner","task","interaction-key"))
                .thenThrow(new DataAccessResourceFailureException("down"));
        var interaction=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.getByInteractionKey(scope,"task","conversation","interaction-key"));
        assertEquals(ControlledImageFollowupAuthorityService.Reason.UNAVAILABLE,interaction.reason());
    }

    @Test void admitRejectsRepointedOperatorPolicyBeforeExecutionOrAuthorityWrites() {
        String runtimeDigest="cf52041c690cdb7fa6625ba3dbb00adf4d7fbad71882278e0b5968a50cf915e5";
        var operation=operationGrant("issue-key","a".repeat(64));
        var consent=followupConsent(operation);
        when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),
                eq(0L),eq(1L),eq("agent"))).thenReturn(baseline());
        when(operationGrants.lockById("0","client","owner","task",operation.getOperationGrantId()))
                .thenReturn(operation);
        when(consents.findFollowupByConsentForUpdate("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(7L),anyLong()))
                .thenReturn(new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1",
                        "binding",7,"changed-model","OPERATOR_TEMPLATE","operator","policy-r1",
                        "UNPRICED_EXTERNAL_ACCOUNT",1,Long.MAX_VALUE));
        var command=new ControlledImageFollowupAuthorityService.ReserveCommand(preview(),
                new ControlledImageFollowupAuthorityService.Authority(consent.getConsentId(),1,
                        operation.getOperationGrantId(),1),"6".repeat(64),runtimeDigest,
                "execution","run","image/png");

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.reserve(scope,command,()->fail("late check must not run")));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.CONFLICT,failure.reason());
        verify(executions,never()).insert(any());
        verify(operationGrants,never()).reserve(any(),anyLong());
        verify(consents,never()).bindFollowup(any(),anyLong());
        verify(consents,never()).reserveFollowup(any(),anyLong());
        verifyNoInteractions(sources);
    }

    @Test void startCompletesAgentChecksBeforeChatLockAndOnlyThenConsumesAuthority() {
        var operation=operationGrant("issue-key","a".repeat(64)).setState("RESERVED").setVersion(2L)
                .setReservedExecutionId("execution").setReservedRunId("run");
        var consent=followupConsent(operation).setState("RESERVED").setVersion(3L)
                .setReservedExecutionId("execution").setReservedRunId("run")
                .setRuntimeInputSnapshotSha256("7".repeat(64));
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setRunId("run")
                .setExecutionProtocolVersion(3).setTargetAgentId("agent").setPermittedOperation("GENERATE_IMAGE")
                .setOperationGrantId(operation.getOperationGrantId()).setControlledConsentId(consent.getConsentId())
                .setConversationId("conversation").setInstruction("draw")
                .setRuntimeInputSnapshotDigest("7".repeat(64));
        var verifier=mock(ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup.class);
        var late=mock(ControlledImageFollowupAuthorityService.LateCheck.class);
        when(executions.lockByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(operationGrants.lockById("0","client","owner","task",operation.getOperationGrantId()))
                .thenReturn(operation);
        when(consents.findFollowupByConsentForUpdate("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),
                eq(0L),eq(1L),eq("agent"))).thenReturn(baseline());
        when(policies.requireCurrent(any(),eq("agent"),eq("binding"),eq(7L),anyLong()))
                .thenReturn(new ControlledImageProviderOperatorPolicy.Policy("CONTROLLED_IMAGE_HTTP_V1",
                        "binding",7,"model","OPERATOR_TEMPLATE","operator","policy-r1",
                        "UNPRICED_EXTERNAL_ACCOUNT",1,Long.MAX_VALUE));
        when(sourceAccess.getIfUnique()).thenReturn(verifier);
        when(operationGrants.consume(eq(operation),eq(2L))).thenReturn(true);
        when(consents.consumeFollowup(eq(consent),eq(3L))).thenReturn(true);
        when(executions.markControlledProviderStartedV3(eq("0"),eq("client"),eq("owner"),eq("task"),
                eq("run"),eq("execution"),eq(consent.getConsentId()),eq(operation.getOperationGrantId()),
                eq(1L),anyLong())).thenReturn(true);
        var provider=new ControlledImageFollowupAuthorityService.ProviderExecution(
                "CONTROLLED_IMAGE_HTTP_V1",consent.getConsentId(),"binding","7","model",16,1,1);
        var command=new ControlledImageFollowupAuthorityService.StartCommand("task","run","execution",
                "command","message","GENERATE_IMAGE","7".repeat(64),provider,1,"lease");

        var receipt=service.consumeForStart(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),command,late);

        assertEquals("execution",receipt.executionId());
        var order=inOrder(late,verifier,operationGrants,consents,executions);
        order.verify(late).verify();
        order.verify(verifier).verify(any(),eq(List.of()),eq(true));
        order.verify(operationGrants).consume(operation,2);
        order.verify(consents).consumeFollowup(consent,3);
        order.verify(executions).markControlledProviderStartedV3(eq("0"),eq("client"),eq("owner"),
                eq("task"),eq("run"),eq("execution"),eq(consent.getConsentId()),
                eq(operation.getOperationGrantId()),eq(1L),anyLong());
    }


    @Test void resultAuthorityUsesExactConsumedStartLeaseWithoutCurrentProviderOrSourceLookup() {
        ResultFixture fixture=resultFixture();
        stubResultFixture(fixture);

        var authority=service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),"task","run","RESULT");

        assertEquals("execution",authority.executionId());
        assertEquals("GENERATE_IMAGE",authority.operation());
        assertEquals("7".repeat(64),authority.inputSnapshotDigest());
        assertEquals("CONTROLLED_IMAGE_HTTP_V1",authority.providerExecution().providerLane());
        assertEquals(fixture.consent().getConsentId(),authority.providerExecution().consentId());
        verifyNoInteractions(grants,policies);
        verify(declarations,never()).getIfUnique();
        verify(sourceAccess,never()).getIfUnique();
    }


    @Test void initialV3ResultUsesConsumedBridgeLeaseWithoutLiveGrantOrDeclarationLookup() {
        String lease="pwe_lease_"+sha("controlled-provider-start-v3\nexecution\nruntime\n1");
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task")
                .setRunId("run").setExecutionProtocolVersion(3).setTargetAgentId("agent")
                .setPermittedOperation("GENERATE_IMAGE").setTaskGrantId("baseline")
                .setTaskGrantVersion(1L).setAssignmentRevision(0L)
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setRuntimeInputSnapshotDigest("7".repeat(64)).setConversationLeaseRuntimeId("runtime")
                .setConversationLeaseVersion(1L).setConversationProviderStartedAt(9L)
                .setConversationProviderLeaseVersion(1L);
        var bridge=new ControlledImageBridgeOperationEntity().setTaskId("task")
                .setExecutionProtocolVersion(3).setOperationGrantId(execution.getOperationGrantId())
                .setConsentId(execution.getControlledConsentId()).setGrantId("baseline")
                .setGrantVersion(1L).setAssignmentRevision(0L);
        var consent=new AgentTaskProviderCostConsentEntity().setConsentId(execution.getControlledConsentId())
                .setConsentPurpose("INITIAL_ASSIGN_AND_START").setOperationGrantId(null)
                .setBoundGrantId("baseline").setBoundGrantVersion(1L).setBoundAssignmentRevision(0L)
                .setTargetAgentId("agent").setState("CONSUMED").setVersion(4L)
                .setReservedExecutionId("execution").setReservedRunId("run")
                .setRuntimeInputSnapshotSha256("7".repeat(64)).setConsumedLeaseId(lease).setConsumedAt(10L)
                .setProviderLane("CONTROLLED_IMAGE_HTTP_V1").setBindingId("binding").setBindingEpoch(7L)
                .setModelId("model").setMaxOutboundRequestAttempts(1);
        consent.setTenantId("0");consent.setClientId("client");consent.setOwnerJiacn("owner");consent.setTaskId("task");
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(operationGrants.findById("0","client","owner","task",execution.getOperationGrantId()))
                .thenReturn(null);
        when(initialOperations.findByOperationGrant("0","client","owner","task",execution.getOperationGrantId()))
                .thenReturn(bridge);
        when(consents.findByConsent("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);

        var result=service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),"task","run","RESULT");

        assertEquals("execution",result.executionId());
        verifyNoInteractions(grants,policies);
        verify(declarations,never()).getIfUnique();
        verify(sourceAccess,never()).getIfUnique();
    }

    @Test void followupPreStartFailureUsesReservedAuthorityWithoutRequiringBrokenSourceAgain() {
        var operation=operationGrant("issue-key","a".repeat(64)).setState("RESERVED").setVersion(2L)
                .setReservedExecutionId("execution").setReservedRunId("run");
        var consent=followupConsent(operation).setState("RESERVED").setVersion(3L)
                .setReservedExecutionId("execution").setReservedRunId("run")
                .setRuntimeInputSnapshotSha256("7".repeat(64));
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task")
                .setRunId("run").setExecutionProtocolVersion(3).setTargetAgentId("agent")
                .setPermittedOperation("GENERATE_IMAGE").setOperationGrantId(operation.getOperationGrantId())
                .setControlledConsentId(consent.getConsentId()).setConversationId("conversation")
                .setInstruction("draw").setRuntimeInputSnapshotDigest("7".repeat(64));
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(operationGrants.findById("0","client","owner","task",operation.getOperationGrantId()))
                .thenReturn(operation);
        when(consents.findFollowupByConsent("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        when(grants.admitFollowupBaseline(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),
                eq(0L),eq(1L),eq("agent"))).thenReturn(baseline());
        when(declaration.currentSession(any())).thenReturn(
                new ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.SessionDeclaration(
                        ControlledImageFollowupAuthorityService.RuntimeDeclarationLookup.State.READY,
                        "runtime",List.of("GENERATE_IMAGE","EDIT_IMAGE")));

        var authority=service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),"task","run","FAILURE");

        assertEquals("execution",authority.executionId());
        assertEquals(consent.getConsentId(),authority.providerExecution().consentId());
        verify(declaration).currentSession(any());
        verify(declaration,never()).current(any());
        verify(sourceAccess,never()).getIfUnique();
        verifyNoInteractions(policies);
    }

    @Test void initialPreStartFailureUsesOrdinaryReservedAdmissionNotConsumedResultLease() {
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task")
                .setRunId("run").setExecutionProtocolVersion(3).setTargetAgentId("agent")
                .setPermittedOperation("GENERATE_IMAGE").setTaskGrantId("baseline")
                .setTaskGrantVersion(1L).setAssignmentRevision(0L)
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setRuntimeInputSnapshotDigest("7".repeat(64));
        var bridge=new ControlledImageBridgeOperationEntity().setTaskId("task")
                .setExecutionProtocolVersion(3).setOperationGrantId(execution.getOperationGrantId())
                .setConsentId(execution.getControlledConsentId()).setGrantId("baseline")
                .setGrantVersion(1L).setAssignmentRevision(0L);
        var consent=new AgentTaskProviderCostConsentEntity().setConsentId(execution.getControlledConsentId())
                .setConsentPurpose("INITIAL_ASSIGN_AND_START").setOperationGrantId(null)
                .setBoundGrantId("baseline").setBoundGrantVersion(1L).setBoundAssignmentRevision(0L)
                .setTargetAgentId("agent").setState("RESERVED").setVersion(3L)
                .setReservedExecutionId("execution").setReservedRunId("run")
                .setProviderLane("CONTROLLED_IMAGE_HTTP_V1").setBindingId("binding").setBindingEpoch(7L)
                .setModelId("model").setMaxOutboundRequestAttempts(1);
        consent.setTenantId("0");consent.setClientId("client");consent.setOwnerJiacn("owner");consent.setTaskId("task");
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(operationGrants.findById("0","client","owner","task",execution.getOperationGrantId()))
                .thenReturn(null);
        when(initialOperations.findByOperationGrant("0","client","owner","task",execution.getOperationGrantId()))
                .thenReturn(bridge);
        when(consents.findByConsent("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        when(grants.admitControlledV3(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("PROVIDER_START"),eq("execution"),eq("run"),eq("runtime")))
                .thenReturn(new AgentTaskExecutionGrantService.Admission("baseline",1,0,"agent",
                        "GENERATE_IMAGE",true,List.of(),"mmd-ci-v1:"+consent.getConsentId(),3L));

        var authority=service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),"task","run","FAILURE");

        assertEquals("execution",authority.executionId());
        verify(grants).admitControlledV3(any(),eq("task"),eq("baseline"),eq(1L),eq(0L),eq("agent"),
                eq("GENERATE_IMAGE"),eq("PROVIDER_START"),eq("execution"),eq("run"),eq("runtime"));
        verify(sourceAccess,never()).getIfUnique();
    }

    @Test void postStartFailureUsesConsumedResultLeaseWithoutLiveRevalidation() {
        ResultFixture fixture=resultFixture();
        stubResultFixture(fixture);

        var authority=service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                "0","client","owner","agent","runtime"),"task","run","FAILURE");

        assertEquals("execution",authority.executionId());
        verifyNoInteractions(grants,policies);
        verify(declarations,never()).getIfUnique();
        verify(sourceAccess,never()).getIfUnique();
    }

    @Test void resultAuthorityRejectsReservedOrMismatchedConsumedStartTupleFailClosed() {
        List<Consumer<ResultFixture>> drifts=List.of(
                value -> value.execution().setConversationLeaseRuntimeId("other-runtime"),
                value -> value.execution().setConversationLeaseVersion(2L),
                value -> value.execution().setConversationProviderLeaseVersion(2L),
                value -> value.execution().setConversationProviderStartedAt(null),
                value -> value.consent().setState("RESERVED"),
                value -> value.operation().setState("RESERVED"),
                value -> value.consent().setConsumedLeaseId("other-lease"),
                value -> value.operation().setConsumedLeaseId("other-lease"),
                value -> value.consent().setReservedExecutionId("other-execution"),
                value -> value.operation().setReservedRunId("other-run"),
                value -> value.consent().setRuntimeInputSnapshotSha256("8".repeat(64)),
                value -> value.consent().setOperationGrantId("opgrant_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        );
        for(Consumer<ResultFixture> drift:drifts) {
            ResultFixture fixture=resultFixture();drift.accept(fixture);stubResultFixture(fixture);
            var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                    () -> service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                            "0","client","owner","agent","runtime"),"task","run","RESULT"));
            assertEquals(ControlledImageFollowupAuthorityService.Reason.CONFLICT,failure.reason());
        }
        verifyNoInteractions(grants,policies);
        verify(declarations,never()).getIfUnique();
        verify(sourceAccess,never()).getIfUnique();
    }

    @Test void runtimeSourceStorageFailureIsUnavailableRatherThanAuthorityConflict() {
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setRunId("run")
                .setExecutionProtocolVersion(3).setTargetAgentId("agent").setPermittedOperation("GENERATE_IMAGE")
                .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                .setControlledConsentId("consent_1234567890abcdef1234567890abcdef")
                .setConversationId("conversation").setInstruction("draw")
                .setRuntimeInputSnapshotDigest("7".repeat(64));
        var operation=operationGrant("issue-key","a".repeat(64)).setState("CONSUMED")
                .setReservedExecutionId("execution").setReservedRunId("run");
        var consent=consent(operation).setState("CONSUMED");
        var verifier=mock(ControlledImageFollowupAuthorityService.RuntimeSourceAccessLookup.class);
        when(executions.findByTaskRun("0","client","owner","task","run")).thenReturn(execution);
        when(operationGrants.findById("0","client","owner","task",operation.getOperationGrantId()))
                .thenReturn(operation);
        when(consents.findFollowupByConsent("0","client","owner","task",consent.getConsentId()))
                .thenReturn(consent);
        when(sourceAccess.getIfUnique()).thenReturn(verifier);
        doThrow(new DataAccessResourceFailureException("archive down")).when(verifier)
                .verify(any(),anyList(),eq(false));

        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.runtimeAuthority(new ControlledImageFollowupAuthorityService.RuntimeScope(
                        "0","client","owner","agent","runtime"),"task","run","EXISTING_RUN"));

        assertEquals(ControlledImageFollowupAuthorityService.Reason.UNAVAILABLE,failure.reason());
        verify(declaration,never()).currentSession(any());
    }

    @Test void ownerScopeAndCanonicalVersionsAreValidatedBeforeAnyStorageLookup() {
        var invalid=new ControlledImageFollowupAuthorityService.Scope("0","client","other\nowner");
        var failure=assertThrows(ControlledImageFollowupAuthorityService.Failure.class,
                () -> service.preview(invalid,preview()));
        assertEquals(ControlledImageFollowupAuthorityService.Reason.BAD_REQUEST,failure.reason());
        verifyNoInteractions(grants,operationGrants,consents,executions,sources,policies);
    }


    private ResultFixture resultFixture() {
        String lease="pwe_lease_"+sha("controlled-provider-start-v3\nexecution\nruntime\n1");
        var operation=operationGrant("issue-key","a".repeat(64)).setState("CONSUMED").setVersion(3L)
                .setReservedExecutionId("execution").setReservedRunId("run").setConsumedLeaseId(lease);
        var consent=followupConsent(operation).setState("CONSUMED").setVersion(4L)
                .setReservedExecutionId("execution").setReservedRunId("run")
                .setRuntimeInputSnapshotSha256("7".repeat(64)).setConsumedLeaseId(lease).setConsumedAt(10L);
        var execution=new PersonalWorkspaceExecutionEntity().setExecutionId("execution").setTaskId("task")
                .setRunId("run").setExecutionProtocolVersion(3).setTargetAgentId("agent")
                .setPermittedOperation("GENERATE_IMAGE").setOperationGrantId(operation.getOperationGrantId())
                .setControlledConsentId(consent.getConsentId()).setConversationId("conversation")
                .setInstruction("draw").setRuntimeInputSnapshotDigest("7".repeat(64))
                .setConversationLeaseRuntimeId("runtime").setConversationLeaseVersion(1L)
                .setConversationProviderStartedAt(9L).setConversationProviderLeaseVersion(1L);
        return new ResultFixture(execution,operation,consent);
    }

    private void stubResultFixture(ResultFixture fixture) {
        when(executions.findByTaskRun("0","client","owner","task","run"))
                .thenReturn(fixture.execution());
        when(operationGrants.findById("0","client","owner","task",fixture.operation().getOperationGrantId()))
                .thenReturn(fixture.operation());
        when(consents.findFollowupByConsent("0","client","owner","task",fixture.consent().getConsentId()))
                .thenReturn(fixture.consent());
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record ResultFixture(PersonalWorkspaceExecutionEntity execution,
            ControlledImageIntentOperationGrantEntity operation,
            AgentTaskProviderCostConsentEntity consent) { }

    private ControlledImageFollowupAuthorityService.IssueCommand issue(String digest) {
        var command=preview();
        var provider=new ControlledImageFollowupAuthorityService.ProviderExpectation(
                "binding",7,"model","OPERATOR_TEMPLATE","policy-r1");
        return new ControlledImageFollowupAuthorityService.IssueCommand(command,"issue-key",digest,
                provider,new ControlledImageFollowupAuthorityService.ExpectedPreview(
                command.ownerPayloadSha256(),command.instructionSha256(),command.sourceSnapshotSha256(),
                "model","OPERATOR_TEMPLATE","policy-r1"),
                "UNPRICED_EXTERNAL_ACCOUNT_ONE_IMAGE_REQUEST_ATTEMPT");
    }

    private ControlledImageFollowupAuthorityService.PreviewCommand preview() {
        return new ControlledImageFollowupAuthorityService.PreviewCommand("task","conversation",1,
                "interaction-key","request","step","intent",
                new ControlledImageFollowupAuthorityService.Baseline("baseline",1,0,0,1,
                        "1".repeat(64),"agent"),"GENERATE_IMAGE","draw",
                "2".repeat(64),"3".repeat(64),"4".repeat(64),List.of());
    }

    private AgentTaskExecutionGrantService.Admission baseline() {
        return new AgentTaskExecutionGrantService.Admission("baseline",1,0,"agent",null,false,
                List.of(),null,null,0L,1L,"1".repeat(64),"assign-key","5".repeat(64));
    }

    private ControlledImageIntentOperationGrantEntity operationGrant(String key,String digest) {
        var row=new ControlledImageIntentOperationGrantEntity().setOperationGrantId(
                "opgrant_1234567890abcdef1234567890abcdef").setOwnerJiacn("owner").setTaskId("task")
                .setTargetAgentId("agent").setConversationId("conversation").setConversationGeneration(1L)
                .setInteractionIdempotencyKey("interaction-key").setRequestId("request").setStepId("step")
                .setExecutionIntentId("intent").setBaselineGrantId("baseline").setBaselineGrantVersion(1L)
                .setTaskVersion(0L).setAssignmentRevision(0L).setRequirementRevision(1L)
                .setRequirementSha256("1".repeat(64)).setOperation("GENERATE_IMAGE")
                .setInstructionSha256("2".repeat(64)).setSourceSnapshotSha256("4".repeat(64))
                .setSourceSnapshotJson("[]").setOwnerPayloadSha256("3".repeat(64))
                .setConsentId("consent_1234567890abcdef1234567890abcdef")
                .setIssueIdempotencyKey(key).setIssueRequestDigest(digest).setState("AUTHORIZED")
                .setVersion(1L).setCreatedAt(1L);
        row.setTenantId("0");row.setClientId("client");return row;
    }

    private AgentTaskProviderCostConsentEntity followupConsent(ControlledImageIntentOperationGrantEntity row) {
        return consent(row).setConsentPurpose("FOLLOWUP_EXECUTE")
                .setOperationGrantId(row.getOperationGrantId()).setExecutionIntentId(row.getExecutionIntentId())
                .setConversationId(row.getConversationId()).setConversationGeneration(row.getConversationGeneration())
                .setOperation(row.getOperation()).setInstructionSha256(row.getInstructionSha256())
                .setSourceSnapshotSha256(row.getSourceSnapshotSha256())
                .setOwnerPayloadSha256(row.getOwnerPayloadSha256()).setTargetAgentId(row.getTargetAgentId())
                .setTaskVersion(row.getTaskVersion()).setRequirementRevision(row.getRequirementRevision())
                .setRequirementSha256(row.getRequirementSha256()).setInputSnapshotDigest(row.getSourceSnapshotSha256())
                .setProviderLane("CONTROLLED_IMAGE_HTTP_V1").setOperatorIssuer("operator");
    }

    private AgentTaskProviderCostConsentEntity consent(ControlledImageIntentOperationGrantEntity row) {
        var value=new AgentTaskProviderCostConsentEntity().setConsentId(row.getConsentId())
                .setState("ISSUED").setVersion(1L).setBindingId("binding").setBindingEpoch(7L)
                .setModelId("model").setCustody("OPERATOR_TEMPLATE").setOperatorPolicyRevision("policy-r1")
                .setPricingMode("UNPRICED_EXTERNAL_ACCOUNT").setMaxOutboundRequestAttempts(1)
                .setExpiresAt(Long.MAX_VALUE);
        value.setTenantId("0");value.setClientId("client");value.setOwnerJiacn("owner");value.setTaskId("task");
        return value;
    }
}
