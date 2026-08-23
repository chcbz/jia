package cn.jia.agent.service.impl;

import cn.jia.agent.common.TaskEventType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C01B-E21 real Spring-proxy outer transaction participation and rollback evidence. */
class AgentTaskEventSpringOuterTransactionTest {
    private AgentTaskEventTestFixture fixture;
    private OuterMutationService service;

    @BeforeEach
    void setUp() throws Exception {
        fixture = AgentTaskEventTestFixture.h2("spring_outer");
        fixture.seedTask();
        service = transactionalProxy(new OuterMutationServiceImpl(fixture));
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void checkedFailureFromRealTransactionalProxyRollsBackBusinessEventAndVersion() {
        assertThrows(OuterMutationFailure.class, service::mutateAppendThenFail);

        assertTrue(service.observedTransaction());
        assertEquals("open", rewardStatus());
        assertEquals(0, fixture.eventCount());
        assertEquals(0L, fixture.currentEventVersion());
        assertEquals(0L, fixture.maxEventVersion());
    }

    @Test
    void successfulRealTransactionalProxyCommitsBusinessEventAndVersionTogether()
            throws Exception {
        service.mutateAppendThenCommit();

        assertTrue(service.observedTransaction());
        assertEquals("working", rewardStatus());
        assertEquals(1, fixture.eventCount());
        assertEquals(1L, fixture.currentEventVersion());
        assertEquals(1L, fixture.maxEventVersion());
    }

    private String rewardStatus() {
        return fixture.jdbc.queryForObject("""
                SELECT reward_status
                FROM agent_task_meta
                WHERE tenant_id=? AND client_id=? AND task_id=?
                """, String.class, AgentTaskEventTestFixture.TENANT,
                AgentTaskEventTestFixture.CLIENT, AgentTaskEventTestFixture.TASK);
    }

    private OuterMutationService transactionalProxy(OuterMutationService target) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(fixture.transactionManager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvice(interceptor);
        return (OuterMutationService) factory.getProxy();
    }

    interface OuterMutationService {
        void mutateAppendThenFail() throws OuterMutationFailure;

        void mutateAppendThenCommit() throws Exception;

        boolean observedTransaction();
    }

    static final class OuterMutationServiceImpl implements OuterMutationService {
        private final AgentTaskEventTestFixture fixture;
        private boolean observedTransaction;

        OuterMutationServiceImpl(AgentTaskEventTestFixture fixture) {
            this.fixture = fixture;
        }

        @Override
        @Transactional(rollbackFor = Exception.class)
        public void mutateAppendThenFail() throws OuterMutationFailure {
            mutateAndAppend("evt-outer-fail");
            throw new OuterMutationFailure();
        }

        @Override
        @Transactional(rollbackFor = Exception.class)
        public void mutateAppendThenCommit() {
            mutateAndAppend("evt-outer-commit");
        }

        @Override
        public boolean observedTransaction() {
            return observedTransaction;
        }

        private void mutateAndAppend(String eventId) {
            observedTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            fixture.jdbc.update("""
                    UPDATE agent_task_meta
                    SET reward_status='working'
                    WHERE tenant_id=? AND client_id=? AND task_id=?
                    """, AgentTaskEventTestFixture.TENANT,
                    AgentTaskEventTestFixture.CLIENT, AgentTaskEventTestFixture.TASK);
            fixture.writer.append(fixture.command(eventId)
                    .setEventType(TaskEventType.TASK_STARTED)
                    .setEventJson(
                            "{\"fromStatus\":\"open\",\"toStatus\":\"working\"}"));
        }
    }

    static final class OuterMutationFailure extends Exception {
    }
}
