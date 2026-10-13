package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatRequestEntity;
import cn.jia.chat.deliberation.ChatConversationEventEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transaction diagnostic; collaborator markers do not claim production MySQL locks. */
class ChatTextFileActionTransactionTest {
    @Test void mandatoryTransactionAndRollbackCoverExecutionAndChatWrites() {
        for(String failAt:List.of("execution","request","step","link","admission","event","none")) {
            var x=new ChatTextFileActionExecutionServiceTest.Fixture();var f=x.f;
            var source=new DriverManagerDataSource("jdbc:h2:mem:text_tx_"+UUID.randomUUID(),"sa","");
            var manager=new DataSourceTransactionManager(source);
            try(var connection=source.getConnection();var context=new AnnotationConfigApplicationContext()) {
                var jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE writes(label VARCHAR(30) PRIMARY KEY)");
                java.util.function.Function<String,Integer> write=label->{jdbc.update("INSERT INTO writes(label) VALUES(?)",label);
                    if(label.equals(failAt))throw new IllegalStateException("injected-"+label);return 1;};
                doAnswer(i->{
                    write.apply("execution");
                    var scope=(cn.jia.agent.service.PersonalWorkspaceExecutionService.OwnerScope)i.getArgument(0);
                    var cmd=(cn.jia.agent.service.PersonalWorkspaceExecutionService.ConversationCreate)i.getArgument(1);
                    return new cn.jia.agent.service.PersonalWorkspaceExecutionService.ExecutionView(
                        cn.jia.agent.service.PersonalWorkspaceExecutionService.textFileExecutionId(scope,cmd.intentId()),cmd.taskId(),"run",
                        cmd.conversationId(),cmd.targetAgentId(),"QUEUED",null,null,1,"text/plain",List.of(),null,"CONVERSATION",null,null,null);
                }).when(x.executions).createConversation(any(),any());
                doAnswer(i->{ChatRequestEntity row=i.getArgument(0);row.setId(3L);return write.apply("request");}).when(f.base.dao).insertRequest(any());
                doAnswer(i->write.apply("step")).when(f.steps).insertStep(any());
                doAnswer(i->write.apply("link")).when(f.steps).insertLink(any());
                doAnswer(i->write.apply("admission")).when(f.base.typed).insertAdmission(any());
                doAnswer(i->{ChatConversationEventEntity row=i.getArgument(0);row.setEventSequence(1L);return write.apply("event");}).when(f.base.dao).insertEvent(any());
                context.register(ChatActionExecutionTransactionTest.TxConfig.class);
                context.registerBean("transactionManager",DataSourceTransactionManager.class,()->manager);
                context.registerBean(ChatTextFileActionExecutionService.class,()->x.service);context.refresh();
                var service=context.getBean(ChatTextFileActionExecutionService.class);
                assertThrows(IllegalTransactionStateException.class,()->service.admit(f.action));
                var tx=new TransactionTemplate(manager);
                if("none".equals(failAt)) { tx.execute(status->service.admit(f.action));assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class)); }
                else { assertThrows(RuntimeException.class,()->tx.execute(status->service.admit(f.action)));
                    assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writes",Integer.class)); }
            } catch (java.sql.SQLException error) { throw new AssertionError(error); }
        }
    }
}
