package cn.jia.agent.service.impl;

import cn.jia.agent.dao.AgentTaskBountyBootstrapOutboxDao;
import cn.jia.agent.entity.AgentTaskBountyBootstrapClaimDTO;
import cn.jia.agent.entity.AgentTaskBountyBootstrapOutboxEntity;
import cn.jia.agent.entity.AgentTaskBountyBootstrapReconcileDTO;
import cn.jia.agent.service.AgentTaskExecutionGrantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AgentTaskBountyBootstrapOutboxServiceImplTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryDao dao = new MemoryDao();
    private AgentTaskBountyBootstrapOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentTaskBountyBootstrapOutboxServiceImpl(dao, json,
                new DirectPlatformTransactionManager());
        dao.insert(valid("owner", "action-1"));
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
        var second = service.claimNext(scope(), "chat-b", 1101);
        assertEquals(first.bootstrapId(), second.bootstrapId());
        assertEquals(first.claimAttempt() + 1, second.claimAttempt());
        assertTrue(second.outboxVersion() > first.outboxVersion());
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
                new DirectPlatformTransactionManager());
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
