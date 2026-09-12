package cn.jia.agent.service.funding;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.entity.funding.AgentTaskFundingCompleteDTO;
import cn.jia.agent.mapper.AgentTaskBountyQuoteMapper;
import cn.jia.agent.mapper.AgentTaskFundingMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskSettlementMapper;
import cn.jia.agent.service.AgentTaskEventWriter;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.economy.bounty.FundedBountyCaptureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FundedBountySettlementDeliveryPolicyGuardTest {
    private static final FundedBountyActor ACTOR =
            new FundedBountyActor("tenant", "client", "user");
    private static final String TASK = "task-1";
    private static final String KEY = "018f0000-0000-7000-8000-000000000009";

    private AgentTaskFundingMapper funding;
    private AgentTaskBountyQuoteMapper quotes;
    private AgentTaskSettlementMapper settlements;
    private AgentTaskMetaMapper tasks;
    private AgentTaskEventWriter events;
    private FundedBountyCaptureService capture;
    private AgentTaskMetaEntity root;
    private FundedBountySettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        funding = mock(AgentTaskFundingMapper.class);
        quotes = mock(AgentTaskBountyQuoteMapper.class);
        settlements = mock(AgentTaskSettlementMapper.class);
        tasks = mock(AgentTaskMetaMapper.class);
        events = mock(AgentTaskEventWriter.class);
        capture = mock(FundedBountyCaptureService.class);
        AgentTaskMutationTransaction roots = mock(AgentTaskMutationTransaction.class);
        root = new AgentTaskMetaEntity()
                .setTaskId(TASK).setRewardStatus("running")
                .setTaskVersion(4L).setCurrentEventVersion(0L);
        root.setTenantId("tenant");
        root.setClientId("client");
        when(roots.executeWithLockedTaskRoot(
                eq("tenant"), eq("client"), eq(TASK), any())).thenAnswer(invocation ->
                ((AgentTaskMutationTransaction.LockedTaskMutation<?>)
                        invocation.getArgument(3)).apply(root));
        service = new FundedBountySettlementServiceImpl(
                funding, quotes, settlements, tasks, roots, events, capture,
                transactionManager());
    }

    @Test
    void policy1CompletionFailsBeforeFinancialOrTaskMutation() {
        root.setDeliveryPolicyVersion(1);

        FundedBountyException denied = assertThrows(
                FundedBountyException.class, () -> service.complete(
                        ACTOR, KEY, TASK, new AgentTaskFundingCompleteDTO("4", "1")));

        assertEquals("FUNDED_BOUNTY_CONFLICT", denied.code());
        verifyNoInteractions(funding, quotes, settlements, tasks, events);
    }

    @Test
    void unsupportedPolicyFailsClosedBeforeFinancialOrTaskMutation() {
        root.setDeliveryPolicyVersion(2);

        FundedBountyException denied = assertThrows(
                FundedBountyException.class, () -> service.complete(
                        ACTOR, KEY, TASK, new AgentTaskFundingCompleteDTO("4", "1")));

        assertEquals("FUNDED_BOUNTY_UNAVAILABLE", denied.code());
        verifyNoInteractions(funding, quotes, settlements, tasks, events);
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
}
