package cn.jia.agent.service.impl;

import cn.jia.agent.api.AgentTaskDeliberationOperationController;
import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.dao.AgentTaskExecutionGrantDao;
import cn.jia.agent.dao.ControlledImageBridgeOperationDao;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.entity.AgentTaskExecutionGrantEntity;
import cn.jia.agent.entity.ControlledImageBridgeOperationEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.exception.AgentTaskCollaborationException;
import cn.jia.agent.service.AgentTaskDeliberationOperationReadService;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.AgentTaskRequirementSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentTaskBountyBootstrapOutboxServiceImplTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryDao dao = new MemoryDao();
    private final AgentTaskRequirementSnapshotService requirementSnapshots =
            org.mockito.Mockito.mock(AgentTaskRequirementSnapshotService.class);
    private AgentTaskBountyBootstrapOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskBountyBootstrapOutboxServiceImpl(dao, json,
                new DirectPlatformTransactionManager(), requirementSnapshots);
        dao.insert(valid("owner", "action-1"));
        org.mockito.Mockito.when(requirementSnapshots.read(
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(call -> {
            AgentTaskExecutionGrantService.Scope scope = call.getArgument(0);
            String taskId = call.getArgument(1);
            long revision = call.getArgument(2);
            return new AgentTaskRequirementSnapshotService.Snapshot(scope.tenantId(),scope.clientId(),
                    scope.ownerJiacn(),taskId,revision,"Title","Original description","a".repeat(64),"CREATE");
        });
    }

    @Test
    void historicalIntentWithoutConfirmedRevisionNeverReachesChat() {
        org.mockito.Mockito.doThrow(new IllegalStateException("No confirmed revision"))
                .when(requirementSnapshots).read(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());
        assertNull(service.claimNextAvailable("chat-worker",1000));
        assertEquals("DEAD",dao.rows.get("bootstrap-1").getStatus());
        assertEquals("CORRUPT_BOOTSTRAP_INTENT",dao.rows.get("bootstrap-1").getLastErrorCode());
    }

    @Test
    void claimIsOwnerScopedAndCarriesOnlyAdmissionFacts() {
        assertNull(service.claimNext(new AgentTaskExecutionGrantService.Scope(
                "0", "client", "other-owner"), "chat-consumer", 1000));

        var claim = service.claimNext(scope(), "chat-consumer", 1000);
        assertNotNull(claim);
        assertEquals("owner", claim.ownerJiacn());
        assertEquals("task-1", claim.taskId());
        assertEquals(1L, claim.requirementRevision());
        assertEquals("TASK_REQUIREMENT_REVISION_V1", claim.requirementAnchor());
        assertEquals(3L, claim.assignmentRevision());
        assertEquals("grant-1", claim.grantId());
        assertEquals(1L, claim.grantVersion());
        assertEquals("GENERATE_IMAGE", claim.permittedOperation());
        assertEquals("file-1", claim.references().getFirst().fileId());
        String projection = claim.toString().toLowerCase();
        assertFalse(projection.contains("storage_uri"));
        assertFalse(projection.contains("localhost"));
        assertFalse(projection.contains("access_token"));
    }

    @Test
    void admittedReconcileIsFencedIdempotentAndDoesNotSayDelivered() {
        var claim = service.claimNext(scope(), "chat-consumer", 1000);
        var command = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(),
                claim.outboxVersion(), claim.leaseOwner(), claim.claimAttempt(),
                AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                "conversation-1", "request-1", null);
        var first = service.reconcile(scope(), command, 1100);
        var replay = service.reconcile(scope(), command, 1200);
        assertEquals("ADMITTED", first.status());
        assertEquals(first, replay);
        assertFalse(first.status().contains("DELIVER"));
        assertEquals("conversation-1", dao.rows.get(claim.bootstrapId())
                .getAdmittedConversationId());
    }

    @Test
    void staleOrCrossOwnerFenceCannotReconcile() {
        var claim = service.claimNext(scope(), "chat-consumer", 1000);
        var stale = new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(),
                claim.outboxVersion() + 1, claim.leaseOwner(), claim.claimAttempt(),
                AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                "conversation-1", "request-1", null);
        assertThrows(IllegalStateException.class, () -> service.reconcile(scope(), stale, 1100));
        assertThrows(IllegalStateException.class, () -> service.reconcile(
                new AgentTaskExecutionGrantService.Scope("0", "client", "other-owner"),
                new AgentTaskBountyBootstrapReconcileDTO(claim.bootstrapId(),
                        claim.outboxVersion(), claim.leaseOwner(), claim.claimAttempt(),
                        AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                        "conversation-1", "request-1", null), 1100));
        assertEquals("CLAIMED", dao.rows.get(claim.bootstrapId()).getStatus());
    }

    @Test
    void retryReleasesLeaseAndReclaimsSameBusinessIntent() {
        var first = service.claimNext(scope(), "chat-a", 1000);
        var retry = service.reconcile(scope(), new AgentTaskBountyBootstrapReconcileDTO(
                first.bootstrapId(), first.outboxVersion(), first.leaseOwner(),
                first.claimAttempt(), AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                null, null, "CHAT_TRANSACTION_RETRY"), 1100);
        assertEquals("RETRY", retry.status());
        assertNull(service.claimNext(scope(), "chat-b", 2099));
        var second = service.claimNext(scope(), "chat-b", 2100);
        assertEquals(first.bootstrapId(), second.bootstrapId());
        assertEquals(first.claimAttempt() + 1, second.claimAttempt());
        assertTrue(second.outboxVersion() > first.outboxVersion());
    }

    @Test
    void repeatedRetryUsesBoundedExponentialDelayWithoutTightPollOrTerminalReclaim() {
        long now = 1_000;
        for (int i = 1; i <= 11; i++) {
            var claim = service.claimNextAvailable("worker", now);
            assertNotNull(claim);
            long reconciledAt = now + 1;
            service.reconcile(scope(), new AgentTaskBountyBootstrapReconcileDTO(
                    claim.bootstrapId(), claim.outboxVersion(), claim.leaseOwner(),
                    claim.claimAttempt(), AgentTaskBountyBootstrapReconcileDTO.Outcome.RETRYABLE_FAILURE,
                    null, null, "CHAT_TRANSACTION_RETRY"), reconciledAt);
            long delay = Math.min(300_000L, 1_000L << Math.min(i - 1, 9));
            assertEquals(reconciledAt + delay, dao.rows.get(claim.bootstrapId()).getNextRetryAt());
            assertNull(service.claimNextAvailable("worker", reconciledAt + delay - 1));
            now = reconciledAt + delay;
        }
        var claim = service.claimNextAvailable("worker", now);
        service.reconcile(scope(), new AgentTaskBountyBootstrapReconcileDTO(
                claim.bootstrapId(), claim.outboxVersion(), claim.leaseOwner(),
                claim.claimAttempt(), AgentTaskBountyBootstrapReconcileDTO.Outcome.TERMINAL_FAILURE,
                null, null, "CHAT_TERMINAL"), now + 1);
        assertNull(service.claimNextAvailable("worker", now + 1));
    }

    @Test
    void corruptPayloadIsQuarantinedInsteadOfLeakingToChat() {
        AgentTaskBountyBootstrapOutboxEntity row = dao.rows.values().iterator().next();
        row.setPayloadHash("0".repeat(64));
        assertNull(service.claimNext(scope(), "chat-consumer", 1000));
        assertEquals("DEAD", row.getStatus());
        assertEquals("CORRUPT_BOOTSTRAP_INTENT", row.getLastErrorCode());
    }

    @Test
    void unattendedDiscoveryDerivesScopeAndKeepsReconcileOwnerScoped() {
        dao.insert(valid("another-owner", "action-2").setBootstrapId("bootstrap-2"));
        var first = service.claimNextAvailable("chat-worker", 1000);
        var second = service.claimNextAvailable("chat-worker", 1001);
        assertEquals("owner", first.ownerJiacn());
        assertEquals("another-owner", second.ownerJiacn());
        assertEquals("0", second.tenantId());
        assertEquals("client", second.clientId());
        var command = new AgentTaskBountyBootstrapReconcileDTO(second.bootstrapId(),
                second.outboxVersion(), second.leaseOwner(), second.claimAttempt(),
                AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                "conversation-2", "request-2", null);
        assertThrows(IllegalStateException.class, () -> service.reconcile(scope(), command, 1100));
        assertEquals("ADMITTED", service.reconcile(
                new AgentTaskExecutionGrantService.Scope("0", "client", "another-owner"),
                command, 1100).status());
    }

    @Test
    void expiredClaimIsReclaimedWithNewVersionAndOldFenceCannotSettle() {
        var original = service.claimNextAvailable("chat-old", 1000);
        assertNull(service.claimNextAvailable("chat-new", original.leaseUntil() - 1));
        var reclaimed = service.claimNextAvailable("chat-new", original.leaseUntil());
        assertEquals(original.bootstrapId(), reclaimed.bootstrapId());
        assertEquals(original.claimAttempt() + 1, reclaimed.claimAttempt());
        assertTrue(reclaimed.outboxVersion() > original.outboxVersion());
        assertThrows(IllegalStateException.class, () -> service.reconcile(scope(),
                new AgentTaskBountyBootstrapReconcileDTO(original.bootstrapId(),
                        original.outboxVersion(), original.leaseOwner(), original.claimAttempt(),
                        AgentTaskBountyBootstrapReconcileDTO.Outcome.ADMITTED,
                        "old-conversation", "old-request", null), reclaimed.leaseUntil()));
    }

    @Test
    void corruptPayloadIsQuarantinedAndNextOwnerRemainsDiscoverable() {
        dao.rows.get("bootstrap-1").setPayloadHash("0".repeat(64));
        dao.insert(valid("another-owner", "action-2").setBootstrapId("bootstrap-2"));
        assertNull(service.claimNextAvailable("chat-worker", 1000));
        assertEquals("DEAD", dao.rows.get("bootstrap-1").getStatus());
        assertEquals("another-owner", service.claimNextAvailable("chat-worker", 1001).ownerJiacn());
    }

    @Test
    void invalidPersistedScopeCannotBecomeClaimOrStarveAnotherOwner() {
        dao.rows.get("bootstrap-1").setOwnerJiacn(" ");
        dao.insert(valid("another-owner", "action-2").setBootstrapId("bootstrap-2"));
        assertEquals("another-owner", service.claimNextAvailable("chat-worker", 1000).ownerJiacn());
        assertEquals("PENDING", dao.rows.get("bootstrap-1").getStatus());
        assertThrows(IllegalArgumentException.class,
                () -> service.claimNextAvailable("chat-worker", Long.MAX_VALUE));
    }

    @Test
    void competingWorkersSkipLockedRowWithoutLeakingOwnerScope() throws Exception {
        class BlockingDao extends MemoryDao {
            private final CountDownLatch locked = new CountDownLatch(1);
            private final CountDownLatch continueClaim = new CountDownLatch(1);
            @Override public boolean claim(AgentTaskBountyBootstrapOutboxEntity row,
                    String owner, long until, long now) {
                if ("bootstrap-1".equals(row.getBootstrapId())) {
                    locked.countDown();
                    try {
                        if (!continueClaim.await(5, TimeUnit.SECONDS))
                            throw new IllegalStateException("simulated worker did not resume");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                }
                return super.claim(row, owner, until, now);
            }
        }
        BlockingDao concurrent = new BlockingDao();
        concurrent.insert(valid("owner", "action-1"));
        concurrent.insert(valid("another-owner", "action-2").setBootstrapId("bootstrap-2"));
        var worker = new AgentTaskBountyBootstrapOutboxServiceImpl(concurrent, json,
                new DirectPlatformTransactionManager(), requirementSnapshots);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> worker.claimNextAvailable("worker-1", 1000));
            assertTrue(concurrent.locked.await(5, TimeUnit.SECONDS));
            var second = worker.claimNextAvailable("worker-2", 1000);
            assertEquals("another-owner", second.ownerJiacn());
            concurrent.continueClaim.countDown();
            assertEquals("owner", first.get(5, TimeUnit.SECONDS).ownerJiacn());
        } finally {
            concurrent.continueClaim.countDown();
            executor.shutdownNow();
        }
    }


    /**
     * Selector compromise: HTTP/read-projection coverage stays in this already isolated
     * mmdU1BootstrapOutbox class so this source slice does not modify shared build files.
     * It is source-only and does not replace security-chain or MySQL lock verification.
     */
    @Nested
    class AssignmentOperationReadContract {
        @Test
        void readsOnlyPersistedFactsInRootGrantBootstrapOrderWithoutSideEffects() {
            ReadFixture fixture = new ReadFixture();
            fixture.root.setTaskVersion(Long.MAX_VALUE);

            AgentTaskDeliberationOperationReadService.Operation result = fixture.service.read(
                    scope(), "task-1", "original-key");

            assertEquals(List.of("task-root", "grant-action", "bootstrap-action"),
                    fixture.order.subList(0, 3));
            assertEquals(Long.MAX_VALUE, result.taskVersion());
            assertEquals(3L, result.assignmentRevision());
            assertEquals("ACTIVE", result.grantState());
            assertEquals("PENDING", result.bootstrapState());
            assertNull(result.conversationId());
            assertNull(result.initialRequestId());
            assertTrue(result.currentAssignment(),
                    "ordinary taskVersion advancement must not manufacture a new assignment epoch");
            assertEquals(0, fixture.grants.writeCalls);
            assertEquals(0, fixture.bootstraps.writeCalls);
            verify(fixture.requirements).read(scope(), "task-1", 1L);
            verifyNoMoreInteractions(fixture.requirements);
        }

        @Test
        void explicitHashDomainRestoresCompleteAuthorizationSetAndPersistedInitialAction() {
            List<String> operations = List.of(
                    "EDIT_IMAGE", "GENERATE_IMAGE", "INSPECT_INPUTS");
            ReadFixture noChangeSource = new ReadFixture(
                    operations, "GENERATE_IMAGE", true, 3L);

            var generated = noChangeSource.service.read(scope(), "task-1", "original-key");

            assertEquals(operations, generated.permittedOperations());
            assertEquals("GENERATE_IMAGE", generated.initialOperation());
            assertEquals(0, noChangeSource.grants.writeCalls);
            assertEquals(0, noChangeSource.bootstraps.writeCalls);

            ReadFixture versionMinusOneSource = new ReadFixture(
                    operations, "INSPECT_INPUTS", true, 2L);
            var inspected = versionMinusOneSource.service.read(
                    scope(), "task-1", "original-key");
            assertEquals(operations, inspected.permittedOperations());
            assertEquals("INSPECT_INPUTS", inspected.initialOperation());
        }

        @Test
        void oldHashRejectsAmbiguousSetsAndExplicitHashRejectsPersistedActionDrift() {
            List<String> operations = List.of(
                    "EDIT_IMAGE", "GENERATE_IMAGE", "INSPECT_INPUTS");
            ReadFixture ambiguousOldDomain = new ReadFixture(
                    operations, "GENERATE_IMAGE", false, 2L);
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> ambiguousOldDomain.service.read(scope(), "task-1", "original-key"));

            ReadFixture driftedSelector = new ReadFixture(
                    operations, "GENERATE_IMAGE", true, 2L);
            rewriteBootstrapInitialOperation(driftedSelector.bootstraps.row, "EDIT_IMAGE");
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> driftedSelector.service.read(scope(), "task-1", "original-key"));

            ReadFixture oldKeyAuthority = new ReadFixture();
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.NOT_FOUND,
                    () -> oldKeyAuthority.service.read(scope(), "task-1", "different-key"));
        }

        @Test
        void currentAssignmentUsesDurableAssignmentEpochAndKeepsHistoryReadable() {
            ReadFixture fixture = new ReadFixture();
            fixture.grants.eventJson = "{\"resultVersion\":4}";
            assertFalse(fixture.service.read(scope(), "task-1", "original-key")
                    .currentAssignment(), "later same-Agent assignment event is not the original assignment");

            fixture.grants.eventJson = "{\"resultVersion\":2}";
            fixture.grants.grant.setState("SUPERSEDED");
            fixture.grants.active = null;
            var historical = fixture.service.read(scope(), "task-1", "original-key");
            assertEquals("SUPERSEDED", historical.grantState());
            assertFalse(historical.currentAssignment());

            fixture.grants.grant.setState("ACTIVE");
            fixture.grants.active = fixture.grants.grant;
            fixture.grants.eventJson = null;
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> fixture.service.read(scope(), "task-1", "original-key"));
            fixture.grants.eventJson = "{\"resultVersion\":9223372036854775807}";
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> fixture.service.read(scope(), "task-1", "original-key"));
            fixture.grants.eventJson = "{\"resultVersion\":\"3\"}";
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> fixture.service.read(scope(), "task-1", "original-key"));
        }

        @Test
        void missingHalfAndCorruptFactsHaveNonLeakingFailureBoundaries() {
            ReadFixture exactScope = new ReadFixture();
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.NOT_FOUND,
                    () -> exactScope.service.read(
                            new AgentTaskExecutionGrantService.Scope("0", "Client", "owner"),
                            "task-1", "original-key"));
            assertThrows(IllegalArgumentException.class, () -> exactScope.service.read(
                    new AgentTaskExecutionGrantService.Scope("0", "client ", "owner"),
                    "task-1", "original-key"));

            ReadFixture absent = new ReadFixture();
            absent.grants.grant = null;
            absent.grants.active = null;
            absent.bootstraps.row = null;
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.NOT_FOUND,
                    () -> absent.service.read(scope(), "task-1", "original-key"));

            ReadFixture sameKeyOtherTask = new ReadFixture();
            sameKeyOtherTask.grants.grant.setTaskId("task-other");
            sameKeyOtherTask.bootstraps.row.setTaskId("task-other");
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.NOT_FOUND,
                    () -> sameKeyOtherTask.service.read(scope(), "task-1", "original-key"));

            ReadFixture grantOnly = new ReadFixture();
            grantOnly.bootstraps.row = null;
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> grantOnly.service.read(scope(), "task-1", "original-key"));

            ReadFixture badRequestHash = new ReadFixture();
            badRequestHash.grants.grant.setRequestHash("0".repeat(64));
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> badRequestHash.service.read(scope(), "task-1", "original-key"));

            ReadFixture badReferenceHash = new ReadFixture();
            badReferenceHash.bootstraps.row.setReferenceSummarySha256("0".repeat(64));
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> badReferenceHash.service.read(scope(), "task-1", "original-key"));

            ReadFixture unavailable = new ReadFixture();
            unavailable.grants.failRead = true;
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.SOURCE_UNAVAILABLE,
                    () -> unavailable.service.read(scope(), "task-1", "original-key"));
        }

        @Test
        void protocol3PostBindAuthorityRequiresTheExactPersistedBridge() {
            ReadFixture accepted = new ReadFixture();
            accepted.grants.grant.setCostAuthorizationRef(
                    "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef");
            when(accepted.controlledImageOperations.find(
                    "0", "client", "owner", "task-1", "original-key"))
                    .thenReturn(accepted.protocol3Bridge());

            assertEquals("grant-1", accepted.service.read(
                    scope(), "task-1", "original-key").grantId());
            verify(accepted.controlledImageOperations).find(
                    "0", "client", "owner", "task-1", "original-key");

            ReadFixture malformedLocator = new ReadFixture();
            malformedLocator.grants.grant.setCostAuthorizationRef("mmd-ci-v1:consent-not-exact");
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> malformedLocator.service.read(scope(), "task-1", "original-key"));
            verifyNoInteractions(malformedLocator.controlledImageOperations);

            List<java.util.function.Consumer<ControlledImageBridgeOperationEntity>> corruptions = List.of(
                    row -> row.setConsentId("consent_abcdefabcdefabcdefabcdefabcdefab"),
                    row -> row.setGrantId("grant-other"),
                    row -> row.setGrantVersion(2L),
                    row -> row.setAssignmentRevision(4L),
                    row -> row.setExecutionProtocolVersion(2),
                    row -> row.setOperationGrantId("opgrant-not-exact"));
            for (var corrupt : corruptions) {
                ReadFixture rejected = new ReadFixture();
                rejected.grants.grant.setCostAuthorizationRef(
                        "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef");
                ControlledImageBridgeOperationEntity bridge = rejected.protocol3Bridge();
                corrupt.accept(bridge);
                when(rejected.controlledImageOperations.find(
                        "0", "client", "owner", "task-1", "original-key"))
                        .thenReturn(bridge);
                assertReadReason(
                        AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                        () -> rejected.service.read(scope(), "task-1", "original-key"));
            }
        }

        @Test
        void admittedAndNonAdmittedIdentifierRulesAreExact() {
            ReadFixture fixture = new ReadFixture();
            fixture.bootstraps.row.setStatus("ADMITTED")
                    .setAdmittedConversationId("9223372036854775807")
                    .setAdmittedRequestId("initial-request-1").setVersion(Long.MAX_VALUE);
            var admitted = fixture.service.read(scope(), "task-1", "original-key");
            assertEquals("9223372036854775807", admitted.conversationId());
            assertEquals(Long.MAX_VALUE, admitted.stateVersion());

            fixture.bootstraps.row.setStatus("RETRY");
            ReadFixture staleReceipt = fixture;
            assertReadReason(AgentTaskDeliberationOperationReadService.ReadException.Reason.INTEGRITY_ERROR,
                    () -> staleReceipt.service.read(scope(), "task-1", "original-key"));
        }

        @Test
        void controllerUsesJwtOnlyReturnsStringFencesNoStoreAndDefaultOff() throws Exception {
            AgentTaskDeliberationOperationReadService reads =
                    mock(AgentTaskDeliberationOperationReadService.class);
            var operation = new AgentTaskDeliberationOperationReadService.Operation(
                    "task-1", "agent-1", Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE,
                    "grant-1", Long.MAX_VALUE, "ACTIVE", List.of("GENERATE_IMAGE"),
                    List.of(new AgentTaskDeliberationOperationReadService.InputSummary(
                            "file-1", 2, "REFERENCE", "image/png", Long.MAX_VALUE,
                            "a".repeat(64))), "bootstrap-1", "ADMITTED", Long.MAX_VALUE,
                    "GENERATE_IMAGE", "77", "initial-request-1", true);
            when(reads.read(scope(), "task-1", "original-key")).thenReturn(operation);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AgentTaskDeliberationOperationController(reads)).build();

            mvc.perform(get("/agent/tasks/task-1/assignment-operation")
                            .principal(jwtToken("owner", "client"))
                            .header("Idempotency-Key", "original-key")
                            .header("X-Owner-Jiacn", "foreign")
                            .header("X-Client-Id", "foreign")
                            .header("X-Tenant-Id", "foreign"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.data.schemaVersion").value(1))
                    .andExpect(jsonPath("$.data.requirementRevision")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.assignmentRevision")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.taskVersion")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.grantVersion")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.stateVersion")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.inputs[0].byteLength")
                            .value("9223372036854775807"))
                    .andExpect(jsonPath("$.data.currentAssignment").value(true));
            verify(reads).read(scope(), "task-1", "original-key");
            verifyNoMoreInteractions(reads);

            ConditionalOnProperty gate = AgentTaskDeliberationOperationController.class
                    .getAnnotation(ConditionalOnProperty.class);
            assertNotNull(gate);
            assertEquals("agent.task-deliberation-operation", gate.prefix());
            assertTrue(Arrays.asList(gate.name()).contains("read-enabled"));
            assertEquals("true", gate.havingValue());
            assertFalse(gate.matchIfMissing());
        }

        @Test
        void controllerRejectsAuthQueryAndExactIdentityViolationsAndSanitizesFailures()
                throws Exception {
            AgentTaskDeliberationOperationReadService reads =
                    mock(AgentTaskDeliberationOperationReadService.class);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new AgentTaskDeliberationOperationController(reads)).build();
            mvc.perform(get("/agent/tasks/task-1/assignment-operation")
                            .header("Idempotency-Key", "original-key"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code")
                            .value("ASSIGNMENT_OPERATION_UNAUTHENTICATED"));
            mvc.perform(get("/agent/tasks/task-1/assignment-operation?owner=foreign")
                            .principal(jwtToken("owner", "client"))
                            .header("Idempotency-Key", "original-key"))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code").value("ASSIGNMENT_OPERATION_BAD_REQUEST"));
            mvc.perform(get("/agent/tasks/task-1/assignment-operation")
                            .principal(jwtToken("owner", "client")))
                    .andExpect(status().isBadRequest());

            AgentTaskDeliberationOperationController direct =
                    new AgentTaskDeliberationOperationController(reads);
            assertThrows(RuntimeException.class, () -> direct.read("task-1 ", "original-key",
                    new MockHttpServletRequest(), jwtToken("owner", "client")));
            assertThrows(RuntimeException.class, () -> direct.read("task-1", " key",
                    new MockHttpServletRequest(), jwtToken("owner", "client")));
            assertThrows(RuntimeException.class, () -> direct.read("task-1", "original-key",
                    new MockHttpServletRequest(), jwtToken("Owner ", "client")));
            verifyNoInteractions(reads);

            when(reads.read(scope(), "task-1", "original-key")).thenThrow(
                    new AgentTaskDeliberationOperationReadService.ReadException(
                            AgentTaskDeliberationOperationReadService.ReadException.Reason.SOURCE_UNAVAILABLE,
                            new DataAccessResourceFailureException(
                                    "secret SQL /private/path owner task-1")));
            String body = mvc.perform(get("/agent/tasks/task-1/assignment-operation")
                            .principal(jwtToken("owner", "client"))
                            .header("Idempotency-Key", "original-key"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                    .andExpect(jsonPath("$.code")
                            .value("ASSIGNMENT_OPERATION_SOURCE_UNAVAILABLE"))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(body.contains("secret SQL"));
            assertFalse(body.contains("/private/path"));
            assertFalse(body.contains("task-1"));
        }

        private final class ReadFixture {
            private final List<String> order;
            private final AgentTaskMetaEntity root;
            private final ReadGrantDao grants;
            private final ReadBootstrapDao bootstraps;
            private final ControlledImageBridgeOperationDao controlledImageOperations;
            private final AgentTaskRequirementSnapshotService requirements;
            private final AgentTaskMutationTransaction transactions;
            private final AgentTaskDeliberationOperationReadServiceImpl service;

            private ReadFixture() {
                this(List.of("GENERATE_IMAGE"), "GENERATE_IMAGE", false, 2L);
            }

            private ReadFixture(List<String> operations, String initialOperation,
                    boolean explicitSelector, long hashExpectedTaskVersion) {
                order = new ArrayList<>();
                root = root();
                AgentTaskExecutionGrantEntity grant = grant(operations, initialOperation,
                        explicitSelector, hashExpectedTaskVersion);
                grants = new ReadGrantDao(order, grant);
                bootstraps = new ReadBootstrapDao(order, bootstrap(grant, initialOperation));
                controlledImageOperations = mock(ControlledImageBridgeOperationDao.class);
                requirements = mock(AgentTaskRequirementSnapshotService.class);
                transactions = mock(AgentTaskMutationTransaction.class);
                when(transactions.executeWithLockedTaskRootInOwnerScope(anyString(), anyString(),
                        anyString(), anyString(), any())).thenAnswer(call -> {
                    order.add("task-root");
                    if (!Objects.equals("0", call.getArgument(0))
                            || !Objects.equals("client", call.getArgument(1))
                            || !Objects.equals("owner", call.getArgument(2))
                            || !Objects.equals("task-1", call.getArgument(3))) {
                        throw new AgentTaskCollaborationException(
                                AgentTaskCollaborationException.Reason.NOT_FOUND, "not found");
                    }
                    return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)call.getArgument(4))
                            .apply(root);
                });
                when(requirements.read(scope(), "task-1", 1L)).thenReturn(
                        new AgentTaskRequirementSnapshotService.Snapshot("0", "client", "owner",
                                "task-1", 1L, "Title", "Full immutable requirement",
                                "b".repeat(64), "CREATE"));
                service = new AgentTaskDeliberationOperationReadServiceImpl(grants, bootstraps,
                        controlledImageOperations, requirements, transactions, json);
            }

            private ControlledImageBridgeOperationEntity protocol3Bridge() {
                ControlledImageBridgeOperationEntity row = new ControlledImageBridgeOperationEntity()
                        .setOwnerJiacn("owner").setTaskId("task-1")
                        .setAssignmentIdempotencyKey("original-key")
                        .setWrapperDigest("c".repeat(64))
                        .setConsentId("consent_1234567890abcdef1234567890abcdef")
                        .setExpectedConsentVersion(1L).setGrantId("grant-1")
                        .setGrantVersion(1L).setAssignmentRevision(3L)
                        .setAuthorityLocator(
                                "mmd-ci-v1:consent_1234567890abcdef1234567890abcdef")
                        .setExecutionProtocolVersion(3)
                        .setOperationGrantId("opgrant_1234567890abcdef1234567890abcdef")
                        .setCreatedAt(1L);
                row.setTenantId("0");
                row.setClientId("client");
                return row;
            }
        }

        private AgentTaskMetaEntity root() {
            AgentTaskMetaEntity root = new AgentTaskMetaEntity();
            root.setTenantId("0");
            root.setClientId("client");
            root.setOwnerJiacn("owner").setTaskId("task-1").setTaskVersion(8L)
                    .setCurrentEventVersion(8L).setAssignedAgentId("agent-1");
            return root;
        }

        private AgentTaskExecutionGrantEntity grant(List<String> operations,
                String initialOperation, boolean explicitSelector,
                long hashExpectedTaskVersion) {
            try {
                String inputJson = "[{\"fileId\":\"file-1\",\"version\":2,"
                        + "\"purpose\":\"REFERENCE\","
                        + "\"contentMimeType\":\"image/png\",\"byteLength\":123,"
                        + "\"contentHash\":\"" + "a".repeat(64) + "\"}]";
                String requestInputs = "[{\"fileId\":\"file-1\",\"version\":2,"
                        + "\"purpose\":\"REFERENCE\"}]";
                String domain = explicitSelector
                        ? "ASSIGN_AND_START_INITIAL_OPERATION_V1" : "ASSIGN_AND_START";
                String hashMaterial = domain + "\ntask-1\nagent-1\n"
                        + hashExpectedTaskVersion + "\n1\n"
                        + json.writeValueAsString(operations) + "\n" + requestInputs;
                if (explicitSelector) hashMaterial += "\n" + initialOperation;
                String requestHash = AgentTaskBountyBootstrapPayload.sha256(hashMaterial);
                AgentTaskExecutionGrantEntity grant = new AgentTaskExecutionGrantEntity()
                        .setGrantId("grant-1");
                grant.setTenantId("0");
                grant.setClientId("client");
                grant.setOwnerJiacn("owner").setTaskId("task-1")
                        .setRequirementRevision(1L).setAssignmentRevision(3L)
                        .setTargetAgentId("agent-1")
                        .setPermittedOperationsJson(json.writeValueAsString(operations))
                        .setPermittedToolPolicyRef("NO_TOOLS_V1")
                        .setInputScopeJson(inputJson)
                        .setAllowOwnTaskDerivedAssets(false).setCostAuthorizationRef(null)
                        .setSourceBusinessActionId("ASSIGN_AND_START:original-key")
                        .setIdempotencyKey("original-key").setRequestHash(requestHash)
                        .setPolicyRevision("MMD_U1_V1").setGrantVersion(1L)
                        .setState("ACTIVE").setIssuedBy("owner").setCreatedAt(1L);
                return grant;
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        private AgentTaskBountyBootstrapOutboxEntity bootstrap(
                AgentTaskExecutionGrantEntity grant, String initialOperation) {
            List<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary> references = List.of(
                    new AgentTaskBountyBootstrapClaimDTO.ReferenceSummary(
                            "file-1", 2, "REFERENCE", "image/png", 123, "a".repeat(64)));
            String referencesJson = AgentTaskBountyBootstrapPayload.referencesJson(json, references);
            String referencesHash = AgentTaskBountyBootstrapPayload.referenceHash(json, references);
            String payloadHash = AgentTaskBountyBootstrapPayload.payloadHash("0", "client", "owner",
                    "task-1", "ASSIGN_AND_START:original-key", 1, 3, "agent-1",
                    "grant-1", 1, initialOperation, referencesHash);
            AgentTaskBountyBootstrapOutboxEntity row = new AgentTaskBountyBootstrapOutboxEntity()
                    .setBootstrapId("bootstrap-1");
            row.setTenantId("0");
            row.setClientId("client");
            row.setOwnerJiacn("owner").setTaskId("task-1")
                    .setSourceBusinessActionId("ASSIGN_AND_START:original-key")
                    .setPayloadHash(payloadHash).setRequirementRevision(1L)
                    .setRequirementAnchor(AgentTaskBountyBootstrapPayload.REQUIREMENT_ANCHOR)
                    .setAssignmentRevision(3L).setTargetAgentId("agent-1")
                    .setGrantId(grant.getGrantId()).setGrantVersion(1L)
                    .setPermittedOperation(initialOperation)
                    .setReferenceSummaryJson(referencesJson)
                    .setReferenceSummarySha256(referencesHash).setStatus("PENDING")
                    .setAttemptCount(0).setVersion(0L).setCreatedAt(1L);
            return row;
        }

        private void rewriteBootstrapInitialOperation(
                AgentTaskBountyBootstrapOutboxEntity row, String initialOperation) {
            row.setPermittedOperation(initialOperation);
            row.setPayloadHash(AgentTaskBountyBootstrapPayload.payloadHash(
                    row.getTenantId(), row.getClientId(), row.getOwnerJiacn(), row.getTaskId(),
                    row.getSourceBusinessActionId(), row.getRequirementRevision(),
                    row.getAssignmentRevision(), row.getTargetAgentId(), row.getGrantId(),
                    row.getGrantVersion(), initialOperation, row.getReferenceSummarySha256()));
        }

        private static JwtAuthenticationToken jwtToken(String owner, String client) {
            Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                    .claim("jiacn", owner).claim("client_id", client).build();
            JwtAuthenticationToken authentication = new JwtAuthenticationToken(token);
            authentication.setAuthenticated(true);
            return authentication;
        }

        private static void assertReadReason(
                AgentTaskDeliberationOperationReadService.ReadException.Reason reason,
                org.junit.jupiter.api.function.Executable action) {
            assertEquals(reason, assertThrows(
                    AgentTaskDeliberationOperationReadService.ReadException.class, action).reason());
        }
    }

    private static final class ReadGrantDao implements AgentTaskExecutionGrantDao {
        private final List<String> order;
        private AgentTaskExecutionGrantEntity grant;
        private AgentTaskExecutionGrantEntity active;
        private String eventJson = "{\"resultVersion\":3}";
        private boolean failRead;
        private int writeCalls;

        private ReadGrantDao(List<String> order, AgentTaskExecutionGrantEntity grant) {
            this.order = order;
            this.grant = grant;
            this.active = grant;
        }
        @Override public AgentTaskExecutionGrantEntity findByActionForUpdate(
                String t, String c, String o, String action) {
            order.add("grant-action");
            if (failRead) throw new DataAccessResourceFailureException("db unavailable");
            return grant != null && t.equals(grant.getTenantId()) && c.equals(grant.getClientId())
                    && o.equals(grant.getOwnerJiacn())
                    && action.equals(grant.getSourceBusinessActionId()) ? grant : null;
        }
        @Override public AgentTaskExecutionGrantEntity findByGrantForUpdate(
                String t, String c, String o, String task, String id) { throw new AssertionError(); }
        @Override public AgentTaskExecutionGrantEntity findActiveByTask(
                String t, String c, String o, String task) { return active; }
        @Override public AgentTaskExecutionGrantEntity findByGrant(
                String t, String c, String o, String task, String id) { throw new AssertionError(); }
        @Override public String latestAssignmentEventJson(
                String t, String c, String o, String task) { return eventJson; }
        @Override public void insert(AgentTaskExecutionGrantEntity value) {
            writeCalls++; throw new AssertionError("read must not insert grant");
        }
        @Override public int supersedeActiveForTask(
                String t, String c, String o, String task, long at) {
            writeCalls++; throw new AssertionError("read must not supersede grant");
        }
        @Override public boolean revoke(String t, String c, String o, String task,
                String id, long version, String key, String hash, long at) {
            writeCalls++; throw new AssertionError("read must not revoke grant");
        }
    }

    private static final class ReadBootstrapDao implements AgentTaskBountyBootstrapOutboxDao {
        private final List<String> order;
        private AgentTaskBountyBootstrapOutboxEntity row;
        private int writeCalls;
        private ReadBootstrapDao(List<String> order, AgentTaskBountyBootstrapOutboxEntity row) {
            this.order = order;
            this.row = row;
        }
        @Override public AgentTaskBountyBootstrapOutboxEntity findByActionForUpdate(
                String t, String c, String o, String action) {
            order.add("bootstrap-action");
            return row != null && t.equals(row.getTenantId()) && c.equals(row.getClientId())
                    && o.equals(row.getOwnerJiacn())
                    && action.equals(row.getSourceBusinessActionId()) ? row : null;
        }
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableForUpdate(
                String t, String c, String o, long now) { throw new AssertionError("read must not claim"); }
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(
                long now) { throw new AssertionError("read must not discover"); }
        @Override public AgentTaskBountyBootstrapOutboxEntity findByBootstrapForUpdate(
                String t, String c, String o, String id) { throw new AssertionError(); }
        @Override public void insert(AgentTaskBountyBootstrapOutboxEntity value) {
            writeCalls++; throw new AssertionError("read must not insert bootstrap");
        }
        @Override public boolean claim(AgentTaskBountyBootstrapOutboxEntity value,
                String owner, long until, long now) {
            writeCalls++; throw new AssertionError("read must not claim bootstrap");
        }
        @Override public boolean reconcile(AgentTaskBountyBootstrapOutboxEntity value,
                String status, Long next, String conversation, String request, String error,
                Long reconciled, long now) {
            writeCalls++; throw new AssertionError("read must not reconcile bootstrap");
        }
    }

    private AgentTaskBountyBootstrapOutboxEntity valid(String owner, String action) {
        List<AgentTaskBountyBootstrapClaimDTO.ReferenceSummary> references = List.of(
                new AgentTaskBountyBootstrapClaimDTO.ReferenceSummary(
                        "file-1", 2, "REFERENCE", "image/png", 123, "a".repeat(64)));
        String referenceJson = AgentTaskBountyBootstrapPayload.referencesJson(json, references);
        String referenceHash = AgentTaskBountyBootstrapPayload.referenceHash(json, references);
        String payloadHash = AgentTaskBountyBootstrapPayload.payloadHash("0", "client", owner,
                "task-1", action, 1, 3, "agent-1", "grant-1", 1,
                "GENERATE_IMAGE", referenceHash);
        AgentTaskBountyBootstrapOutboxEntity row = new AgentTaskBountyBootstrapOutboxEntity()
                .setBootstrapId("bootstrap-1");
        row.setTenantId("0");
        row.setClientId("client");
        row.setOwnerJiacn(owner).setTaskId("task-1").setSourceBusinessActionId(action)
                .setPayloadHash(payloadHash).setRequirementRevision(1L)
                .setRequirementAnchor(AgentTaskBountyBootstrapPayload.REQUIREMENT_ANCHOR)
                .setAssignmentRevision(3L).setTargetAgentId("agent-1").setGrantId("grant-1")
                .setGrantVersion(1L).setPermittedOperation("GENERATE_IMAGE")
                .setReferenceSummaryJson(referenceJson).setReferenceSummarySha256(referenceHash)
                .setStatus("PENDING").setAttemptCount(0).setVersion(0L).setCreatedAt(1L);
        return row;
    }

    private static AgentTaskExecutionGrantService.Scope scope() {
        return new AgentTaskExecutionGrantService.Scope("0", "client", "owner");
    }

    private static final class DirectPlatformTransactionManager
            implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    }

    private static class MemoryDao implements AgentTaskBountyBootstrapOutboxDao {
        private final Set<String> inFlight = new HashSet<>();
        private final Map<String,AgentTaskBountyBootstrapOutboxEntity> rows=new LinkedHashMap<>();
        @Override public AgentTaskBountyBootstrapOutboxEntity findByActionForUpdate(
                String t,String c,String o,String action) {
            return rows.values().stream().filter(row -> t.equals(row.getTenantId())
                    && c.equals(row.getClientId()) && o.equals(row.getOwnerJiacn())
                    && action.equals(row.getSourceBusinessActionId())).findFirst().orElse(null);
        }
        @Override public AgentTaskBountyBootstrapOutboxEntity findClaimableForUpdate(
                String t,String c,String o,long now) {
            return rows.values().stream().filter(row -> t.equals(row.getTenantId())
                    && c.equals(row.getClientId()) && o.equals(row.getOwnerJiacn())
                    && ((List.of("PENDING","RETRY").contains(row.getStatus())
                         && (row.getNextRetryAt()==null || row.getNextRetryAt()<=now))
                        || ("CLAIMED".equals(row.getStatus()) && row.getLeaseUntil()!=null
                            && row.getLeaseUntil()<=now))).findFirst().orElse(null);
        }
        @Override public synchronized AgentTaskBountyBootstrapOutboxEntity findClaimableAvailableForUpdate(
                long now) {
            for (AgentTaskBountyBootstrapOutboxEntity row : rows.values()) {
                String c = row.getClientId(), o = row.getOwnerJiacn();
                // Mirrors the SQL scope predicates and simulated SKIP LOCKED selection.
                if (!"0".equals(row.getTenantId()) || c == null || c.isBlank()
                        || !c.equals(c.strip()) || o == null || o.isBlank()
                        || "0".equals(o) || !o.equals(o.strip())
                        || c.chars().anyMatch(Character::isISOControl)
                        || o.chars().anyMatch(Character::isISOControl)
                        || inFlight.contains(row.getBootstrapId())) continue;
                if ((List.of("PENDING","RETRY").contains(row.getStatus())
                        && (row.getNextRetryAt()==null || row.getNextRetryAt()<=now))
                        || ("CLAIMED".equals(row.getStatus()) && row.getLeaseUntil()!=null
                        && row.getLeaseUntil()<=now)) {
                    inFlight.add(row.getBootstrapId());
                    return row;
                }
            }
            return null;
        }
        @Override public AgentTaskBountyBootstrapOutboxEntity findByBootstrapForUpdate(
                String t,String c,String o,String id) {
            AgentTaskBountyBootstrapOutboxEntity row=rows.get(id);
            return row!=null && t.equals(row.getTenantId()) && c.equals(row.getClientId())
                    && o.equals(row.getOwnerJiacn()) ? row : null;
        }
        @Override public void insert(AgentTaskBountyBootstrapOutboxEntity row) {
            rows.put(row.getBootstrapId(),row);
        }
        @Override public synchronized boolean claim(AgentTaskBountyBootstrapOutboxEntity row,
                String owner, long until, long now) {
            inFlight.remove(row.getBootstrapId());
            return true;
        }
        @Override public boolean reconcile(AgentTaskBountyBootstrapOutboxEntity row,String status,
                Long next,String conversation,String request,String error,Long reconciled,long now) {
            return true;
        }
    }
}
