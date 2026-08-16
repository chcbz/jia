package cn.jia.agent.service;

import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventAfterCommitPublisherTest {
    private final TaskScope scope = new TaskScope("tenant-tx", "client-tx", "task-tx");
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private AgentTaskEventBroker broker;
    private AgentTaskEventAfterCommitPublisher publisher;
    private List<Long> received;
    private reactor.core.Disposable subscription;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:c02_after_commit_" + System.nanoTime()
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE commit_probe (id INT PRIMARY KEY, probe_value INT NOT NULL)");
        jdbc.update("INSERT INTO commit_probe (id, probe_value) VALUES (1, 0)");
        transactionManager = new DataSourceTransactionManager(dataSource);
        broker = new AgentTaskEventBroker();
        publisher = new AgentTaskEventAfterCommitPublisher(broker);
        received = new CopyOnWriteArrayList<>();
        subscription = broker.stream(scope)
                .subscribe(wakeup -> received.add(wakeup.eventVersion()));
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (jdbc != null) {
            jdbc.execute("DROP ALL OBJECTS DELETE FILES");
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void commitPublishesOnceInAppendOrderUsingOneTransactionSynchronization() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
            publisher.enqueue(scope, 1L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            publisher.enqueue(scope, 2L);
            publisher.enqueue(scope, 3L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            assertTrue(received.isEmpty());
        });

        assertEquals(List.of(1L, 2L, 3L), received);
        assertEquals(3L, publisher.publishedWakeupCount());
        assertEquals(0L, publisher.publicationFailureCount());
    }

    @Test
    void outerRollbackPublishesNothingAndDiscardsTheWholeBuffer() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            publisher.enqueue(scope, 2L);
            status.setRollbackOnly();
        });

        assertTrue(received.isEmpty());
        assertEquals(0L, publisher.publishedWakeupCount());
    }

    @Test
    void requiredNestingSharesTheOuterBufferAndOrdering() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate required = new TransactionTemplate(transactionManager);
        required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            required.executeWithoutResult(inner -> {
                publisher.enqueue(scope, 2L);
                assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            });
            publisher.enqueue(scope, 3L);
            assertTrue(received.isEmpty());
        });

        assertEquals(List.of(1L, 2L, 3L), received);
    }

    @Test
    void requiresNewUsesAnIndependentBufferAndRollbackDoesNotPoisonOuter() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            requiresNew.executeWithoutResult(inner -> publisher.enqueue(scope, 2L));
            assertEquals(List.of(2L), received);

            requiresNew.executeWithoutResult(inner -> {
                publisher.enqueue(scope, 99L);
                inner.setRollbackOnly();
            });
            publisher.enqueue(scope, 3L);
        });

        assertEquals(List.of(2L, 1L, 3L), received);
    }

    @Test
    void savepointNestingIsRejectedBeforeNestedTaskWorkCanProceed() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            assertThrows(NestedTransactionNotSupportedException.class,
                    () -> nested.executeWithoutResult(inner -> publisher.enqueue(scope, 2L)));
            status.setRollbackOnly();
        });

        assertTrue(received.isEmpty());
    }

    @Test
    void firstRegistrationInsideNestedRollbackCannotPublishAPhantomWakeup() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> nested.executeWithoutResult(inner -> {
            publisher.enqueue(scope, 1L);
            inner.setRollbackOnly();
        }));

        assertTrue(received.isEmpty());
        assertEquals(0L, publisher.publishedWakeupCount());
    }

    @Test
    void enqueueFailsClosedWithoutBothActualTransactionAndSynchronization() {
        assertThrows(IllegalStateException.class, () -> publisher.enqueue(scope, 1L));

        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThrows(IllegalStateException.class, () -> publisher.enqueue(scope, 1L));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> publisher.enqueue(scope, 1L));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void brokerFailureAfterCommitIsContainedAndLaterWakeupsStillDrain() {
        subscription.dispose();
        AgentTaskEventBroker faultyBroker = new AgentTaskEventBroker() {
            @Override
            public void publish(TaskScope taskScope, long eventVersion) {
                if (eventVersion == 1L) {
                    throw new IllegalStateException("injected broker failure");
                }
                super.publish(taskScope, eventVersion);
            }
        };
        AgentTaskEventAfterCommitPublisher isolatedPublisher =
                new AgentTaskEventAfterCommitPublisher(faultyBroker);
        List<Long> later = new CopyOnWriteArrayList<>();
        subscription = faultyBroker.stream(scope)
                .subscribe(wakeup -> later.add(wakeup.eventVersion()));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            jdbc.update("UPDATE commit_probe SET probe_value=1 WHERE id=1");
            isolatedPublisher.enqueue(scope, 1L);
            isolatedPublisher.enqueue(scope, 2L);
            isolatedPublisher.enqueue(scope, 3L);
        });

        assertEquals(1, jdbc.queryForObject(
                "SELECT probe_value FROM commit_probe WHERE id=1", Integer.class));
        assertEquals(List.of(2L, 3L), later);
        assertEquals(2L, isolatedPublisher.publishedWakeupCount());
        assertEquals(1L, isolatedPublisher.publicationFailureCount());
    }
}
