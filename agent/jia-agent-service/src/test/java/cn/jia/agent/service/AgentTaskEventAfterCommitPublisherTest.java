package cn.jia.agent.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.jia.agent.service.AgentTaskEventBroker.TaskScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        publisher = new AgentTaskEventAfterCommitPublisher(broker, transactionManager);
        received = new CopyOnWriteArrayList<>();
        subscription = broker.stream(scope)
                .subscribe(wakeup -> received.add(wakeup.eventVersion()));
    }

    @AfterEach
    void tearDown() {
        if (subscription != null) {
            subscription.dispose();
        }
        if (publisher != null) {
            publisher.close();
        }
        if (broker != null) {
            broker.close();
        }
        if (jdbc != null) {
            jdbc.execute("DROP ALL OBJECTS DELETE FILES");
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void unrelatedTransactionHasNoSynchronizationAndPublisherUsesExactlyOneLazySynchronization() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
            jdbc.update("UPDATE commit_probe SET probe_value=1 WHERE id=1");
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
        });

        transaction.executeWithoutResult(status -> {
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size());
            publisher.enqueue(scope, 1L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            publisher.enqueue(scope, 2L);
            publisher.enqueue(scope, 3L);
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            assertTrue(received.isEmpty());
        });

        awaitReceived(List.of(1L, 2L, 3L));
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
        transaction.executeWithoutResult(status -> publisher.enqueue(scope, 3L));
        awaitReceived(List.of(3L));
    }

    @Test
    void requiredNestingSharesOneBufferAndOrdering() {
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
        });

        awaitReceived(List.of(1L, 2L, 3L));
    }

    @Test
    void requiresNewUsesIndependentBufferAndRollbackDoesNotPoisonOuter() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            requiresNew.executeWithoutResult(inner -> publisher.enqueue(scope, 2L));
            awaitReceived(List.of(2L));

            requiresNew.executeWithoutResult(inner -> {
                publisher.enqueue(scope, 99L);
                inner.setRollbackOnly();
            });
            publisher.enqueue(scope, 3L);
        });

        awaitReceived(List.of(2L, 1L, 3L));
    }

    @Test
    void knownSavepointRollbackTruncatesBufferAndClearsNewerCheckpoints() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            Object first = status.createSavepoint();
            publisher.enqueue(scope, 2L);
            Object second = status.createSavepoint();
            publisher.enqueue(scope, 3L);
            status.rollbackToSavepoint(second);
            publisher.enqueue(scope, 4L);
            status.rollbackToSavepoint(first);
            publisher.enqueue(scope, 5L);
        });

        awaitReceived(List.of(1L, 5L));
    }

    @Test
    void unknownPreRegistrationSavepointRollbackClearsBufferButAllowsLaterOuterEnqueue() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            publisher.enqueue(scope, 1L);
            publisher.enqueue(scope, 2L);
            status.rollbackToSavepoint(savepoint);
            publisher.enqueue(scope, 3L);
        });

        awaitReceived(List.of(3L));
    }

    @Test
    void savepointRolledBackBeforeFirstEnqueueLeavesLaterOuterEventValid() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            jdbc.update("UPDATE commit_probe SET probe_value=99 WHERE id=1");
            status.rollbackToSavepoint(savepoint);
            publisher.enqueue(scope, 1L);
            jdbc.update("UPDATE commit_probe SET probe_value=1 WHERE id=1");
        });

        assertEquals(1, jdbc.queryForObject(
                "SELECT probe_value FROM commit_probe WHERE id=1", Integer.class));
        awaitReceived(List.of(1L));
    }

    @Test
    void savepointReleasedBeforeFirstEnqueueLeavesLaterOuterEventValid() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            Object savepoint = status.createSavepoint();
            status.releaseSavepoint(savepoint);
            publisher.enqueue(scope, 1L);
        });

        awaitReceived(List.of(1L));
    }

    @Test
    void nestedCommitRetainsInnerWakeupsInOuterOrder() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            nested.executeWithoutResult(inner -> publisher.enqueue(scope, 2L));
            publisher.enqueue(scope, 3L);
        });

        awaitReceived(List.of(1L, 2L, 3L));
    }

    @Test
    void nestedRollbackTruncatesInnerWakeupsAndOuterContinues() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            nested.executeWithoutResult(inner -> {
                publisher.enqueue(scope, 2L);
                inner.setRollbackOnly();
            });
            publisher.enqueue(scope, 3L);
        });

        awaitReceived(List.of(1L, 3L));
    }

    @Test
    void nestedFirstUseRollbackConservativelyClearsInnerAndOuterCanContinue() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> {
            nested.executeWithoutResult(inner -> {
                publisher.enqueue(scope, 1L);
                inner.setRollbackOnly();
            });
            publisher.enqueue(scope, 2L);
        });

        awaitReceived(List.of(2L));
    }

    @Test
    void nestedFirstUseCommitRetainsInnerAndOuterWakeups() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);

        outer.executeWithoutResult(status -> {
            nested.executeWithoutResult(inner -> publisher.enqueue(scope, 1L));
            publisher.enqueue(scope, 2L);
        });

        awaitReceived(List.of(1L, 2L));
    }

    @Test
    void closeIsListenerFreeThreadLocalFreeConcurrentAndSuppressesActiveTransactionWakeups()
            throws Exception {
        int listenersBefore = transactionManager.getTransactionExecutionListeners().size();
        assertFalse(Arrays.stream(AgentTaskEventAfterCommitPublisher.class.getDeclaredFields())
                .anyMatch(field -> ThreadLocal.class.isAssignableFrom(field.getType())));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            publisher.enqueue(scope, 1L);
            jdbc.update("UPDATE commit_probe SET probe_value=1 WHERE id=1");
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<?>> closes = new java.util.ArrayList<>();
                for (int index = 0; index < 16; index++) {
                    closes.add(executor.submit(() -> {
                        await(start);
                        publisher.close();
                    }));
                }
                start.countDown();
                for (Future<?> close : closes) {
                    awaitFuture(close);
                }
            } finally {
                executor.shutdownNow();
                awaitTermination(executor);
            }
        });

        assertTrue(publisher.isClosed());
        assertEquals(listenersBefore,
                transactionManager.getTransactionExecutionListeners().size());
        assertEquals(1, jdbc.queryForObject(
                "SELECT probe_value FROM commit_probe WHERE id=1", Integer.class));
        assertTrue(received.isEmpty());
        assertEquals(0L, publisher.publishedWakeupCount());
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(
                status -> publisher.enqueue(scope, 2L)));
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
    void brokerFailureAfterCommitIsWarnLoggedContainedAndLaterWakeupsStillDrain() {
        subscription.dispose();
        publisher.close();
        broker.close();
        AgentTaskEventBroker faultyBroker = new AgentTaskEventBroker() {
            @Override
            public void publish(TaskScope taskScope, long eventVersion) {
                if (eventVersion == 1L) {
                    throw new IllegalStateException("injected broker failure for " + taskScope);
                }
                super.publish(taskScope, eventVersion);
            }
        };
        broker = faultyBroker;
        AgentTaskEventAfterCommitPublisher isolatedPublisher =
                new AgentTaskEventAfterCommitPublisher(faultyBroker, transactionManager);
        publisher = isolatedPublisher;
        List<Long> later = new CopyOnWriteArrayList<>();
        subscription = faultyBroker.stream(scope)
                .subscribe(wakeup -> later.add(wakeup.eventVersion()));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        Logger logger = (Logger) LoggerFactory.getLogger(AgentTaskEventAfterCommitPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            transaction.executeWithoutResult(status -> {
                jdbc.update("UPDATE commit_probe SET probe_value=1 WHERE id=1");
                isolatedPublisher.enqueue(scope, 1L);
                isolatedPublisher.enqueue(scope, 2L);
                isolatedPublisher.enqueue(scope, 3L);
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT probe_value FROM commit_probe WHERE id=1", Integer.class));
        awaitCondition(() -> later.equals(List.of(2L, 3L)));
        assertEquals(2L, isolatedPublisher.publishedWakeupCount());
        assertEquals(1L, isolatedPublisher.publicationFailureCount());
        assertEquals(1, appender.list.size());
        ILoggingEvent warning = appender.list.get(0);
        assertEquals(Level.WARN, warning.getLevel());
        assertEquals("Task event after-commit wakeup failed: eventVersion=1, "
                + "failureType=IllegalStateException", warning.getFormattedMessage());
        assertFalse(warning.getFormattedMessage().contains(scope.tenantId()));
        assertFalse(warning.getFormattedMessage().contains(scope.clientId()));
        assertFalse(warning.getFormattedMessage().contains(scope.taskId()));
        assertFalse(warning.getFormattedMessage().contains("injected broker failure"));
    }

    private void awaitReceived(List<Long> expected) {
        awaitCondition(() -> received.equals(expected));
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
    }

    private static void awaitFuture(Future<?> future) {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitTermination(ExecutorService executor) {
        try {
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
