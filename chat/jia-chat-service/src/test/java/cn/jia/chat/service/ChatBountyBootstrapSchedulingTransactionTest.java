package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskBountyBootstrapWakeup;
import cn.jia.agent.service.AgentTaskBountyBootstrapOutboxService;
import cn.jia.chat.config.ChatTypedDeliberationReadiness.Readiness;
import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import cn.jia.task.scheduling.ScheduledInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real JDBC/Spring afterCommit boundary, separate from task's already-tested production MySQL SQL. */
class ChatBountyBootstrapSchedulingTransactionTest {
    @Test void scheduleWriteIsCommittedWhileOriginalAfterCommitCallbackIsStillRunning() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        var manager=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE scheduled_hint (due_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE original_fact (id INT PRIMARY KEY)");
        ScheduleService schedules=new ScheduleService() {
            @Override public void signal(ScheduleKey key,long at) { jdbc.update("INSERT INTO scheduled_hint VALUES (?)",at); }
            @Override public void reschedule(ScheduleKey key,long at) { fail("No independent rescheduling protocol"); }
            @Override public void cancel(ScheduleKey key) { fail("No business cancellation"); }
        };
        var contexts=mock(ChatTypedDeliberationContextService.class);
        when(contexts.bootstrapReadiness()).thenReturn(Readiness.READY);
        var now=new AtomicLong(1000);
        var relay=new ChatBountyBootstrapRelay(mock(AgentTaskBountyBootstrapOutboxService.class),
                mock(ChatBountyBootstrapAdmissionService.class),contexts,schedules,manager,
                new FixedTenantAdmissionPolicy("0",null),"node-a",now::get);
        try {
            relay.start();relay.onApplicationReady(null);
            relay.executeScheduled(new ScheduledInvocation("0",new ScheduleKey("",ChatBountyBootstrapRelay.HANDLER,"recovery","node-a"),1));
            now.set(1100);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO original_fact VALUES (1)");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        relay.onWorkAvailable(new AgentTaskBountyBootstrapWakeup(now.get()));
                        // Explicit raw connection, NOT JdbcTemplate's still-bound original resource.
                        // REQUIRED writes here would remain invisible until original cleanup (or be lost).
                        try (var connection=source.getConnection();var query=connection.createStatement();
                                var rows=query.executeQuery("SELECT COUNT(*) FROM scheduled_hint WHERE due_at=1100")) {
                            assertTrue(rows.next());assertEquals(1,rows.getInt(1));
                        } catch (SQLException failure) { throw new AssertionError(failure); }
                    }
                });
            });
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM original_fact",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM scheduled_hint WHERE due_at=1100",Integer.class));
        } finally { relay.stop();jdbc.execute("SHUTDOWN"); }
    }
}
