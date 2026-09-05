package cn.jia.agent.service.funding;

import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentTaskFundingCancelReceiptDTO;
import cn.jia.agent.entity.funding.AgentTaskFundingEntity;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.economy.bounty.FundedBountyLedgerService;
import cn.jia.task.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FundedBountyServiceReplayTest {
    private static final String KEY = "018f0000-0000-7000-8000-000000000007";

    @Test
    void refundedCancellationReplayReturnsPersistedReceiptEvenIfTaskVersionLaterChanges() {
        Fixture fixture = fixture();
        byte[] hash = new byte[32];
        AgentTaskFundingEntity funding = refunded(hash);
        when(fixture.mapper.selectFundingForUpdate("tenant", "client", "task-1")).thenReturn(funding);
        AgentTaskMetaEntity root = root(99L);
        when(fixture.mutation.executeWithLockedTaskRoot(eq("tenant"), eq("client"), eq("task-1"), any()))
                .thenAnswer(invocation -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(root));

        AgentTaskFundingCancelReceiptDTO receipt = fixture.service.cancel(
                new FundedBountyActor("tenant", "client", "user-1"), KEY, hash, "task-1", 0L);

        assertEquals("1", receipt.taskVersion());
        assertEquals("37", receipt.refundedMicro());
        assertEquals("2", receipt.fundingVersion());
        verify(fixture.ledger, never()).refund(any());
    }

    @Test
    void refundedCancellationWithDifferentBodyHashIsIdempotencyConflict() {
        Fixture fixture = fixture();
        byte[] original = new byte[32];
        byte[] changed = new byte[32];
        changed[0] = 1;
        when(fixture.mapper.selectFundingForUpdate("tenant", "client", "task-1"))
                .thenReturn(refunded(original));
        when(fixture.mutation.executeWithLockedTaskRoot(eq("tenant"), eq("client"), eq("task-1"), any()))
                .thenAnswer(invocation -> ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(root(1L)));

        FundedBountyException failure = assertThrows(FundedBountyException.class, () ->
                fixture.service.cancel(new FundedBountyActor("tenant", "client", "user-1"),
                        KEY, changed, "task-1", 0L));

        assertEquals("IDEMPOTENCY_CONFLICT", failure.code());
        verify(fixture.ledger, never()).refund(any());
    }

    private static Fixture fixture() {
        AgentTaskFundingMapper mapper = mock(AgentTaskFundingMapper.class);
        AgentTaskMutationTransaction mutation = mock(AgentTaskMutationTransaction.class);
        FundedBountyLedgerService ledger = mock(FundedBountyLedgerService.class);
        @SuppressWarnings("unchecked") ObjectProvider<TaskService> tasks = mock(ObjectProvider.class);
        FundedBountyServiceImpl service = new FundedBountyServiceImpl(mapper,
                mock(AgentTaskMetaDao.class), mutation, mock(AgentTaskEventWriter.class),
                tasks, ledger, transactionManager());
        return new Fixture(service, mapper, mutation, ledger);
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }

    private static AgentTaskMetaEntity root(long version) {
        AgentTaskMetaEntity root = new AgentTaskMetaEntity();
        root.setTaskId("task-1");
        root.setTaskVersion(version);
        root.setCurrentEventVersion(1L);
        root.setTenantId("tenant");
        root.setClientId("client");
        root.setRewardStatus("cancelled");
        return root;
    }

    private static AgentTaskFundingEntity refunded(byte[] hash) {
        AgentTaskFundingEntity funding = new AgentTaskFundingEntity();
        funding.setTaskId("task-1");
        funding.setFundingMode("FUNDED_SINGLE_AGENT");
        funding.setFundingStatus("REFUNDED");
        funding.setPayerPrincipalType("USER");
        funding.setPayerPrincipalId("user-1");
        funding.setSettlementPolicy("GROSS_INCLUSIVE");
        funding.setGrossBountyAmountMicro(37L);
        funding.setRemainingMicro(0L);
        funding.setEscrowId("esc-1");
        funding.setEscrowVersion(2L);
        funding.setReserveTransactionId("etx-reserve");
        funding.setCancelIdempotencyKey(KEY.getBytes(StandardCharsets.US_ASCII));
        funding.setCancelRequestHash(hash);
        funding.setRefundTransactionId("etx-refund");
        funding.setCancelRefundedMicro(37L);
        funding.setCancelTaskVersion(1L);
        funding.setRefundedAt(100L);
        funding.setVersion(2L);
        funding.setTenantId("tenant");
        funding.setClientId("client");
        return funding;
    }

    private record Fixture(FundedBountyServiceImpl service, AgentTaskFundingMapper mapper,
            AgentTaskMutationTransaction mutation, FundedBountyLedgerService ledger) { }
}
