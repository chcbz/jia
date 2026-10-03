package cn.jia.chat.service;

import cn.jia.agent.service.ControlledImageFollowupAuthorityService;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import cn.jia.chat.deliberation.ChatRequestEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring/JDBC rollback diagnostic, not a real MySQL/Provider/Flow result. */
class ChatActionExecutionTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class TxConfig { }

    @Test void executionChildRequiresTheConsumerTransaction() {
        var f=new ChatActionExecutionServiceTest.Fixture(false);
        var source=new DriverManagerDataSource("jdbc:h2:mem:action_required_"+UUID.randomUUID(),"sa","");
        try(var context=new AnnotationConfigApplicationContext()) {
            context.register(TxConfig.class);
            context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
            context.registerBean(ChatActionExecutionService.class,()->f.service);context.refresh();
            assertThrows(IllegalTransactionStateException.class,()->context.getBean(ChatActionExecutionService.class).admit(f.action));
            verifyNoInteractions(f.authority);assertEquals(2,f.base.requests.size());
        }
    }

    @Test void reservationAndChatWritesAndClaimSettleShareOneAtomicTransaction() {
        var stages=List.of("authority","reservation","request","step","link","admission","event","event-version","settle");
        for(String failAt:List.of("authority","reservation","request","step","link","admission","event","event-version","settle","none")) {
            var f=new ChatActionExecutionServiceTest.Fixture(false); var claim=f.claim();
            var source=new DriverManagerDataSource("jdbc:h2:mem:execute_tx_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
            var evidence=new JdbcTemplate(source);evidence.execute("CREATE TABLE writes(label VARCHAR(30) PRIMARY KEY)");
            var reached=new AtomicInteger();
            java.util.function.Function<String,Integer> write=label->{
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());reached.incrementAndGet();
                evidence.update("INSERT INTO writes(label) VALUES(?)",label);
                if(label.equals(failAt))throw new IllegalStateException("injected-"+label);return 1;
            };
            when(f.authority.admitOrdinaryAction(any(),any(),any())).thenAnswer(i->{
                var command=(ControlledImageFollowupAuthorityService.OrdinaryActionCommand)i.getArgument(1);
                var check=(ControlledImageFollowupAuthorityService.LateCheck)i.getArgument(2);check.verify();
                write.apply("authority");check.verify();write.apply("reservation");check.verify();
                return new ControlledImageFollowupAuthorityService.Reservation(command.executionId(),command.runId(),"consent","operation",2,2,"a".repeat(64));
            });
            doAnswer(i->{ChatRequestEntity row=i.getArgument(0);row.setId(3L);return write.apply("request");}).when(f.base.dao).insertRequest(any());
            doAnswer(i->write.apply("step")).when(f.steps).insertStep(any());
            doAnswer(i->write.apply("link")).when(f.steps).insertLink(any());
            doAnswer(i->write.apply("admission")).when(f.base.typed).insertAdmission(any());
            doAnswer(i->{ChatConversationEventEntity row=i.getArgument(0);row.setEventSequence(1L);return write.apply("event");}).when(f.base.dao).insertEvent(any());
            doAnswer(i->write.apply("event-version")).when(f.base.dao).assignEventVersion(anyLong());
            doAnswer(i->write.apply("settle")).when(f.base.dao).settleOutbox(eq(claim.row()),eq("SENT"),isNull(),isNull(),anyLong(),anyLong());
            try(var context=new AnnotationConfigApplicationContext()) {
                context.register(TxConfig.class);
                context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
                context.registerBean(ChatActionExecutionService.class,()->f.service);
                context.registerBean(ChatActionDispatchService.class,()->f.consumer(context.getBean(ChatActionExecutionService.class)));context.refresh();
                var consumer=context.getBean(ChatActionDispatchService.class);
                assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(context.getBean(ChatActionExecutionService.class)));
                if("none".equals(failAt)) {consumer.consume(claim);assertEquals(9,reached.get());assertEquals(9,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                else {var error=assertThrows(RuntimeException.class,()->consumer.consume(claim));assertEquals("injected-"+failAt,error.getMessage());
                    assertEquals(stages.indexOf(failAt)+1,reached.get());assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));}
                assertEquals(2,f.base.messages.size());
            }
        }
    }
}
