package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring/JDBC transaction ownership, not a substitute for isolated MySQL verification. */
class ChatActionContinuationTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    static class TxConfig { }

    @Test void rejectedActionEventAndDeadSettlementShareOneTransaction() {
        for(String failAt:List.of("event","version","settle","none")) {
            var f=new ChatActionContinuationTest.Fixture(); var consumer=f.consumer(); var claim=f.claim();
            var source=new DriverManagerDataSource("jdbc:h2:mem:rejected_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            var evidence=new JdbcTemplate(source); evidence.execute("CREATE TABLE writes(label VARCHAR(20) PRIMARY KEY)");
            java.util.function.Function<String,Integer> write=label->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                evidence.update("INSERT INTO writes(label) VALUES(?)",label);
                if(label.equals(failAt))throw new IllegalStateException("injected-"+label); return 1;
            };
            doAnswer(i->{ChatConversationEventEntity event=i.getArgument(0);event.setEventSequence(1L);return write.apply("event");})
                    .when(f.dao).insertEvent(any());
            doAnswer(i->write.apply("version")).when(f.dao).assignEventVersion(anyLong());
            doAnswer(i->write.apply("settle")).when(f.dao).settleOutbox(eq(claim.row()),eq("DEAD"),isNull(),eq("ACTION_REQUEST_CHANGED"),isNull(),anyLong());
            try(var context=new AnnotationConfigApplicationContext()) {
                context.register(TxConfig.class);
                context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
                context.registerBean(ChatActionDispatchService.class,()->consumer); context.refresh();
                var actual=context.getBean(ChatActionDispatchService.class);
                if("none".equals(failAt)) {actual.reject(claim);assertEquals(3,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                else {assertEquals("injected-"+failAt,assertThrows(RuntimeException.class,()->actual.reject(claim)).getMessage());
                    assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                assertEquals(1,f.requests.size()); assertEquals(1,f.messages.size()); assertEquals(0,f.children.size());
            }
        }
    }

    @Test void childAdmissionAndClaimSettlementRollBackTogetherAtEveryLateWriteFailure() {
        for (String failAt:List.of("request","snapshot","turn","dispatch","admission","event","event-version","settle","none")) {
            var f=new ChatActionContinuationTest.Fixture(); var consumer=f.consumer(); var claim=f.claim();
            var source=new DriverManagerDataSource("jdbc:h2:mem:action_tx_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
            var evidence=new JdbcTemplate(source); evidence.execute("CREATE TABLE writes(label VARCHAR(30) PRIMARY KEY)");
            java.util.function.Function<String,Integer> write=label->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                evidence.update("INSERT INTO writes(label) VALUES(?)",label);
                if(label.equals(failAt)) throw new IllegalStateException("injected-"+label);
                return 1;
            };
            doAnswer(i->{ChatRequestEntity row=i.getArgument(0);row.setId(2L);return write.apply("request");}).when(f.dao).insertRequest(any());
            doAnswer(i->write.apply("snapshot")).when(f.dao).insertSnapshot(any());
            doAnswer(i->write.apply("turn")).when(f.dao).insertTurn(any());
            doAnswer(i->write.apply("dispatch")).when(f.dao).insertOutbox(any());
            doAnswer(i->write.apply("admission")).when(f.typed).insertAdmission(any());
            doAnswer(i->{ChatConversationEventEntity row=i.getArgument(0);row.setEventSequence(1L);return write.apply("event");}).when(f.dao).insertEvent(any());
            doAnswer(i->write.apply("event-version")).when(f.dao).assignEventVersion(anyLong());
            doAnswer(i->write.apply("settle")).when(f.dao).settleOutbox(eq(claim.row()),eq("SENT"),isNull(),isNull(),anyLong(),anyLong());
            try(var context=new AnnotationConfigApplicationContext()) {
                context.register(TxConfig.class);
                context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
                context.registerBean(ChatActionDispatchService.class,()->consumer);
                context.refresh();
                var proxied=context.getBean(ChatActionDispatchService.class);
                assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(proxied));
                if("none".equals(failAt)) {proxied.consume(claim);assertEquals(8,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                else {var failed=assertThrows(RuntimeException.class,()->proxied.consume(claim),failAt);assertEquals("injected-"+failAt,failed.getMessage());assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class),failAt);}
                assertEquals(1,f.messages.size());
            }
        }
    }
}
