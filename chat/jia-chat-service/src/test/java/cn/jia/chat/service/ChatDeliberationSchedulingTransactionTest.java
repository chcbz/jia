package cn.jia.chat.service;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import cn.jia.task.scheduling.ScheduleKey;
import cn.jia.task.scheduling.ScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatDeliberationSchedulingTransactionTest {
    @Test void committedOutboxHintPersistsWakeBeforeOriginalJdbcCleanup() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(source);
        var manager=new DataSourceTransactionManager(source);
        jdbc.execute("CREATE TABLE scheduled_hint (due_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE original_fact (id INT PRIMARY KEY)");
        ScheduleService schedules=new ScheduleService() {
            @Override public void signal(ScheduleKey key,long at) {
                assertEquals(new ScheduleKey("client",ChatDeliberationOutboxRelay.HANDLER,"recovery","node-a"),key);
                jdbc.update("INSERT INTO scheduled_hint VALUES (?)",at);
            }
            @Override public void reschedule(ScheduleKey key,long at) { fail("Only the earlier deadline is signaled"); }
            @Override public void cancel(ScheduleKey key) { fail("Stopping does not cancel accepted work"); }
        };
        var outbox=mock(ChatDeliberationOutboxService.class);
        when(outbox.discover(anyLong(),anyInt())).thenReturn(List.of());
        when(outbox.nextDueAt(anyLong())).thenReturn(null);
        var now=new AtomicLong(1000);
        var relay=new ChatDeliberationOutboxRelay(outbox,null,null,null,null,null,null);
        relay.configureScheduling(schedules,manager,new FixedTenantAdmissionPolicy("blue","client"),"node-a");
        relay.scheduling(Executors::newSingleThreadScheduledExecutor,now::get);
        try {
            relay.start();relay.executeScheduled(null);verifyNoInteractions(outbox);
            relay.onApplicationReady(null);relay.executeScheduled(null);now.set(1100);
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                jdbc.update("INSERT INTO original_fact VALUES (1)");
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        relay.onWorkAvailable(new ChatDeliberationOutboxWakeup(now.get()));
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
