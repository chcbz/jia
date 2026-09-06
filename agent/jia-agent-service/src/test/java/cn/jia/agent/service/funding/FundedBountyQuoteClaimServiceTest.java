package cn.jia.agent.service.funding;

import cn.jia.agent.common.AgentConstants;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.entity.AgentRuntimeEntity;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentModelPreferenceDTO;
import cn.jia.agent.entity.funding.AgentTaskClaimOperationEntity;
import cn.jia.agent.entity.funding.AgentTaskClaimRequestDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.entity.funding.AgentTaskQuoteEntity;
import cn.jia.agent.entity.funding.AgentTaskQuoteRequestDTO;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.agent.service.impl.AgentCommandTransportCapture;
import cn.jia.agent.service.impl.AgentLegacyTaskCompatibilityService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundedBountyQuoteClaimServiceTest {
    private static final FundedBountyActor ACTOR = new FundedBountyActor("tenant", "client", "user");
    private static final String TASK = "task-1";
    private static final String AGENT = "agt_00000000000000000000000000000001";
    private static final String QUOTE = "q_00000000000000000000000000000001";
    private static final byte[] HASH = new byte[32];

    @Test
    void quoteIdempotencyReplayReturnsOriginalImmutableExpiryAndDifferentHashConflicts() {
        Fixture fixture = fixture(new TrackingTransactionManager());
        AgentTaskQuoteEntity replay = replayQuote();
        when(fixture.quoteMapper.selectQuoteByActorKeyForUpdate(
                eq("tenant"), eq("client"), eq("USER"), eq("user"),
                aryEq(key(0).getBytes(StandardCharsets.US_ASCII))))
                .thenReturn(replay);

        var first = fixture.service.quote(ACTOR, key(0), HASH, TASK, quoteRequest());
        var second = fixture.service.quote(ACTOR, key(0), HASH, TASK, quoteRequest());
        assertEquals("999999", first.expiresAt());
        assertEquals(first, second);

        byte[] changed = HASH.clone();
        changed[0] = 1;
        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                fixture.service.quote(ACTOR, key(0), changed, TASK, quoteRequest()));
        assertEquals("IDEMPOTENCY_CONFLICT", failure.code());
        verify(fixture.quoteMapper, never()).insertQuote(any());
    }

    @Test
    void completedClaimIdempotencyReplayReturnsOriginalReceiptAfterTaskChanged() {
        Fixture fixture = fixture(new TrackingTransactionManager());
        AgentTaskClaimOperationEntity operation = new AgentTaskClaimOperationEntity();
        operation.setTaskId(TASK);
        operation.setAgentId(AGENT);
        operation.setQuoteId(QUOTE);
        operation.setRequestHash(HASH);
        operation.setStatus("COMPLETED");
        operation.setReceiptTaskVersion(1L);
        operation.setClaimedAt(100L);
        when(fixture.quoteMapper.selectClaimOperationForUpdate(
                eq("tenant"), eq("client"), eq("USER"), eq("user"),
                aryEq(key(8).getBytes(StandardCharsets.US_ASCII))))
                .thenReturn(operation);
        fixture.root.setRewardStatus(AgentConstants.TASK_STATUS_RUNNING);
        fixture.root.setTaskVersion(9L);

        var receipt = fixture.service.claim(ACTOR, key(8), HASH, TASK, claim(AGENT, false));

        assertEquals("1", receipt.taskVersion());
        assertEquals("100", receipt.claimedAt());
        verify(fixture.quoteMapper, never()).insertClaimOperation(any());
        verify(fixture.assignment, never()).assignResolvedVersioned(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong(), any());
    }

    @Test
    void expiredQuoteCannotClaimAndCreatesNoOperationOrAssignment() {
        Fixture fixture = fixture(new TrackingTransactionManager());
        fixture.claimQuote.setExpiresAt(1L);

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                fixture.service.claim(ACTOR, key(1), HASH, TASK, claim(AGENT, false)));

        assertEquals("QUOTE_EXPIRED", failure.code());
        verify(fixture.quoteMapper, never()).insertClaimOperation(any());
        verify(fixture.assignment, never()).assignResolvedVersioned(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong(), any());
    }

    @Test
    void quoteAgentMismatchFailsClosedWithoutRevealingCounterparty() {
        Fixture fixture = fixture(new TrackingTransactionManager());

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                fixture.service.claim(ACTOR, key(2), HASH, TASK,
                        claim("agt_00000000000000000000000000000002", false)));

        assertEquals("TASK_OR_COUNTERPARTY_NOT_FOUND", failure.code());
        verify(fixture.quoteMapper, never()).insertClaimOperation(any());
    }

    @Test
    void insufficientWorstCaseBudgetCannotClaim() {
        Fixture fixture = fixture(new TrackingTransactionManager());
        fixture.claimQuote.setBudgetCovered(false);

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                fixture.service.claim(ACTOR, key(3), HASH, TASK, claim(AGENT, false)));

        assertEquals("INSUFFICIENT_BOUNTY_BUDGET", failure.code());
        verify(fixture.assignment, never()).assignResolvedVersioned(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong(), any());
    }

    @Test
    void rollbackAfterDurableCaptureFailureCannotCommitPartialClaim() {
        TrackingTransactionManager manager = new TrackingTransactionManager();
        Fixture fixture = fixture(manager);
        stubSuccessfulAssignment(fixture);
        when(fixture.transport.captureTaskInvites(any(), anyList(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("outbox unavailable"));

        assertThrows(IllegalStateException.class, () ->
                fixture.service.claim(ACTOR, key(4), HASH, TASK, claim(AGENT, false)));

        assertTrue(manager.rolledBack.get());
        assertFalse(manager.committed.get());
        verify(fixture.quoteMapper).markClaimed("tenant", "client", TASK, QUOTE, 100L);
        verify(fixture.quoteMapper).completeClaimOperation(
                anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyLong());
    }

    @Test
    void durableInviteCaptureOccursInsideTransactionBeforeCommit() {
        TrackingTransactionManager manager = new TrackingTransactionManager();
        Fixture fixture = fixture(manager);
        stubSuccessfulAssignment(fixture);
        when(fixture.transport.captureTaskInvites(any(), anyList(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    assertFalse(manager.committed.get());
                    assertFalse(manager.rolledBack.get());
                    return true;
                });

        var receipt = fixture.service.claim(ACTOR, key(5), HASH, TASK, claim(AGENT, false));

        assertEquals("1", receipt.taskVersion());
        assertTrue(manager.committed.get());
        assertFalse(manager.rolledBack.get());
    }

    @Test
    void parallelClaimsHaveAtMostOneWinner() throws Exception {
        TrackingTransactionManager manager = new TrackingTransactionManager();
        Fixture fixture = fixture(manager);
        Object rootLock = new Object();
        when(fixture.mutation.executeWithLockedTaskRoot(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    synchronized (rootLock) {
                        return ((AgentTaskMutationTransaction.LockedTaskMutation<?>) invocation.getArgument(3))
                                .apply(fixture.root);
                    }
                });
        stubSuccessfulAssignment(fixture);
        when(fixture.quoteMapper.markClaimed(anyString(), anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    fixture.claimQuote.setStatus("CLAIMED");
                    return 1;
                });
        when(fixture.transport.captureTaskInvites(any(), anyList(), anyString(), anyLong())).thenReturn(true);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> claimResult(fixture, key(6), start));
            Future<Boolean> second = pool.submit(() -> claimResult(fixture, key(7), start));
            start.countDown();
            int winners = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean claimResult(Fixture fixture, String key, CountDownLatch start) throws Exception {
        start.await();
        try {
            fixture.service.claim(ACTOR, key, HASH, TASK, claim(AGENT, false));
            return true;
        } catch (FundedBountyException expectedLoser) {
            return false;
        }
    }

    private static void stubSuccessfulAssignment(Fixture fixture) {
        when(fixture.assignment.assignResolvedVersioned(
                anyString(), anyString(), anyString(), anyList(), anyBoolean(), anyLong(), any()))
                .thenAnswer(invocation -> {
                    AgentLegacyTaskCompatibilityService.AssignmentPrecommitValidator validator =
                            invocation.getArgument(6);
                    validator.validate(fixture.root, List.of(AGENT));
                    fixture.root.setRewardStatus(AgentConstants.TASK_STATUS_ASSIGNED);
                    fixture.root.setAssignedAt(100L);
                    fixture.root.setTaskVersion(1L);
                    return new AgentLegacyTaskCompatibilityService.AssignOutcome(
                            List.of(AGENT), true, "evt-assigned", 100L);
                });
    }

    private static Fixture fixture(TrackingTransactionManager manager) {
        AgentTaskBountyQuoteMapper quoteMapper = mock(AgentTaskBountyQuoteMapper.class);
        AgentTaskFundingMapper fundingMapper = mock(AgentTaskFundingMapper.class);
        AgentTaskMutationTransaction mutation = mock(AgentTaskMutationTransaction.class);
        AgentIdentityService identity = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimeDao = mock(AgentRuntimeDao.class);
        AgentLegacyTaskCompatibilityService assignment = mock(AgentLegacyTaskCompatibilityService.class);
        AgentCommandTransportCapture transport = mock(AgentCommandTransportCapture.class);
        FailClosedFundedBountySkillEntitlementLookup skillLookup =
                new FailClosedFundedBountySkillEntitlementLookup();
        FundedBountyQuoteClaimServiceImpl service = new FundedBountyQuoteClaimServiceImpl(
                quoteMapper, fundingMapper, mutation, identity, runtimeDao,
                assignment, transport, skillLookup, manager);

        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTaskId(TASK);
        root.setTenantId("tenant");
        root.setClientId("client");
        root.setRewardStatus(AgentConstants.TASK_STATUS_OPEN);
        root.setTaskVersion(0L);
        root.setCurrentEventVersion(0L);
        root.setRequiredAbilities("[]");
        when(mutation.executeWithLockedTaskRoot(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(root));

        AgentTaskFundingEntity funding = new AgentTaskFundingEntity();
        funding.setTaskId(TASK);
        funding.setTenantId("tenant");
        funding.setClientId("client");
        funding.setPayerPrincipalType("USER");
        funding.setPayerPrincipalId("user");
        funding.setFundingStatus("FUNDS_HELD");
        funding.setRemainingMicro(1_000_000_000L);
        funding.setRequiredSkillRequirements("[]");
        funding.setVersion(1L);
        when(fundingMapper.selectFundingForUpdate("tenant", "client", TASK)).thenReturn(funding);

        AgentTaskQuoteEntity quote = new AgentTaskQuoteEntity();
        quote.setQuoteId(QUOTE);
        quote.setTaskId(TASK);
        quote.setAgentId(AGENT);
        quote.setTenantId("tenant");
        quote.setClientId("client");
        quote.setPrincipalType("USER");
        quote.setPrincipalId("user");
        quote.setTaskVersion(0L);
        quote.setVerifiedSkillMatch(true);
        quote.setBudgetCovered(true);
        quote.setStatus("OPEN");
        quote.setExpiresAt(Long.MAX_VALUE);
        quote.setSkillSetHash(skillLookup.lookup(ACTOR, AGENT, List.of()).skillSetHash());
        when(quoteMapper.selectQuoteForUpdate("tenant", "client", TASK, QUOTE)).thenReturn(quote);
        when(quoteMapper.insertClaimOperation(any())).thenReturn(1);
        when(quoteMapper.markClaimed(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(1);
        when(quoteMapper.completeClaimOperation(
                anyString(), anyString(), anyString(), anyString(), any(), anyLong(), anyLong())).thenReturn(1);

        AgentRuntimeEntity runtime = new AgentRuntimeEntity();
        runtime.setAgentId(AGENT);
        runtime.setClientId("client");
        runtime.setOwnerJiacn("tenant");
        runtime.setTenantId("tenant"); runtime.setBindingId(1L);
        cn.jia.agent.entity.AgentIdentityRegistryEntity identityRow = new cn.jia.agent.entity.AgentIdentityRegistryEntity();
        identityRow.setCanonicalAgentId(AGENT); identityRow.setBindingId(1L);
        identityRow.setTenantId("tenant"); identityRow.setOwnerJiacn("tenant"); identityRow.setClientId("client");
        when(identity.requireActiveIdentityForBinding("tenant", "client", "tenant", 1L, AGENT)).thenReturn(identityRow);
        runtime.setStatus(AgentConstants.STATUS_ONLINE);
        runtime.setAbilities("[]");
        when(runtimeDao.findByAgentIdForUpdate(AGENT)).thenReturn(runtime);
        return new Fixture(service, quoteMapper, fundingMapper, mutation, assignment,
                transport, root, quote);
    }

    private static AgentTaskQuoteRequestDTO quoteRequest() {
        AgentModelPreferenceDTO model = new AgentModelPreferenceDTO();
        model.setProvider(FundedBountyPreviewPriceBook.PROVIDER);
        model.setModel(FundedBountyPreviewPriceBook.MODEL);
        AgentTaskQuoteRequestDTO request = new AgentTaskQuoteRequestDTO();
        request.setAgentId(AGENT);
        request.setModelPreference(model);
        request.setContextRevision("0");
        request.setMinimumAcceptedPayoutMicro("1");
        return request;
    }

    private static AgentTaskQuoteEntity replayQuote() {
        AgentTaskQuoteEntity quote = new AgentTaskQuoteEntity();
        quote.setQuoteId(QUOTE);
        quote.setTaskId(TASK);
        quote.setAgentId(AGENT);
        quote.setRequestHash(HASH);
        quote.setTaskVersion(0L);
        quote.setPriceBookVersion(FundedBountyPreviewPriceBook.VERSION);
        quote.setTaskInputHash("sha256:" + "0".repeat(64));
        quote.setSkillSetHash("sha256:" + "1".repeat(64));
        quote.setModelRouteVersion(FundedBountyPreviewPriceBook.MODEL_ROUTE_VERSION);
        quote.setEstimatedInputTokens(1L);
        quote.setEstimatedCachedInputTokens(2L);
        quote.setEstimatedOutputTokens(3L);
        quote.setEstimatedReasoningTokens(4L);
        quote.setEstimatedComputeMicro(5L);
        quote.setWorstComputeMicro(6L);
        quote.setPlatformFeeMicro(7L);
        quote.setGrossAllocationMicro(100L);
        quote.setEstimatedAgentPayoutMicro(88L);
        quote.setWorstAgentPayoutMicro(87L);
        quote.setMinimumAcceptedPayoutMicro(80L);
        quote.setBudgetHeadroomMicro(7L);
        quote.setVerifiedSkillMatch(true);
        quote.setAdvisoryAbilityMatch(true);
        quote.setRecommendation("recommended");
        quote.setReasonCodes("[]");
        quote.setExpiresAt(999999L);
        return quote;
    }

    private static AgentTaskClaimRequestDTO claim(String agentId, boolean allowQueue) {
        AgentTaskClaimRequestDTO request = new AgentTaskClaimRequestDTO();
        request.setAgentId(agentId);
        request.setQuoteId(QUOTE);
        request.setTaskVersion("0");
        request.setAllowQueue(allowQueue);
        return request;
    }

    private static String key(int suffix) {
        return "018f0000-0000-7000-8000-" + String.format("%012d", suffix);
    }

    private record Fixture(FundedBountyQuoteClaimServiceImpl service,
            AgentTaskBountyQuoteMapper quoteMapper, AgentTaskFundingMapper fundingMapper,
            AgentTaskMutationTransaction mutation, AgentLegacyTaskCompatibilityService assignment,
            AgentCommandTransportCapture transport, AgentTaskMetaEntity root,
            AgentTaskQuoteEntity claimQuote) {
    }

    private static final class TrackingTransactionManager implements PlatformTransactionManager {
        private final AtomicBoolean committed = new AtomicBoolean();
        private final AtomicBoolean rolledBack = new AtomicBoolean();
        private final AtomicInteger active = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            active.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active.decrementAndGet();
            committed.set(true);
        }

        @Override
        public void rollback(TransactionStatus status) {
            active.decrementAndGet();
            rolledBack.set(true);
        }
    }
}
