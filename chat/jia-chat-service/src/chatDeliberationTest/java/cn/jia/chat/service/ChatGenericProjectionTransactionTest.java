package cn.jia.chat.service;
import cn.jia.agent.service.*;
import cn.jia.chat.dao.*;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ChatGenericProjectionTransactionTest {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true) static class TxConfig { }
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);final ChatDeliberationService requests=mock(ChatDeliberationService.class);
    final ChatDeliberationDao events=mock(ChatDeliberationDao.class);final ChatConversationDao conversations=mock(ChatConversationDao.class);
    final ChatMessageDao messages=mock(ChatMessageDao.class);final PersonalWorkspaceExecutionService files=mock(PersonalWorkspaceExecutionService.class);
    final ChatConversationEventBroker broker=mock(ChatConversationEventBroker.class);final SkillExecutionService skills=mock(SkillExecutionService.class);
    final AgentTaskMutationTransaction roots=mock(AgentTaskMutationTransaction.class);
    final ChatBountyAssetProjector projector=new ChatBountyAssetProjector(jdbc,requests,events,conversations,messages,files,broker);
    final ChatBountyAssetProjector.Candidate candidate=new ChatBountyAssetProjector.Candidate("0","owner","client","req","step","exec");
    final PersonalWorkspaceExecutionService.OwnerScope owner=new PersonalWorkspaceExecutionService.OwnerScope("0","client","owner");
    Map<String,Object> manifest;
    void ready() {
        projector.setSkillProjectionServices(skills,roots);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(i->((String)i.getArgument(0)).startsWith("SELECT task_id")?List.of(Map.of("task_id","task")):List.of());
        when(roots.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i-> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return ((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(null);});
        when(requests.getRequest("0","owner","client","req")).thenReturn(new ChatDeliberationService.RequestView("req","1","42","1","7","RUNNING","1",List.of(),List.of(
                new ChatDeliberationService.StepView("step","1","task","3","agent","EXECUTE","RUNNING","2","intent","exec","RUNNING"))));
        when(files.get(owner,"exec")).thenReturn(new PersonalWorkspaceExecutionService.ExecutionView("exec","task","run","42","agent","RESULT_COMMITTED",null,null,1,null,List.of(),null,"CONVERSATION",null,null,null));
        when(files.listConversationOutputs(owner,"task","run")).thenReturn(List.of());
        var live=new ChatConversationEntity().setId(42L).setJiacn("owner").setTaskId("task").setLifecycleGeneration(1L).setConversationScopeType("bounty").setConversationScopeKey("task:task");live.setTenantId("0");live.setClientId("client");
        when(conversations.lockScopedById("owner","client","42")).thenReturn(live);
        manifest=new LinkedHashMap<>();manifest.put("schemaVersion",1);manifest.put("data",List.of("真实数据",Map.of("value",0.1)));manifest.put("artifacts",List.of());manifest.put("inputReceipts",List.of());
        manifest.put("resultDigest",ChatSkillSnapshotJson.sha(manifest));manifest.put("manifestId","manifest");
        when(skills.result(owner,"exec")).thenReturn(new SkillExecutionService.ResultView("exec","RESULT_COMMITTED","ARCHIVING",null,"a".repeat(64),manifest));
        when(events.findRequest("0","owner","client","req")).thenReturn(new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setRequestId("req").setConversationId("42").setAggregateState("RUNNING").setStateVersion(1L));
    }
    @Test void dataMessageEventStepLinkRequestAndCoreMarkAreAtomicWithAfterCommitSignal() {
        for(String fail:List.of("message","event","version","link","step","request","mark","none")) {
            var f=new ChatGenericProjectionTransactionTest();f.ready();
            var source=new DriverManagerDataSource("jdbc:h2:mem:skillresult_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            var evidence=new JdbcTemplate(source);evidence.execute("CREATE TABLE writes(label VARCHAR(20) PRIMARY KEY)");
            java.util.function.Function<String,Integer> write=label->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                evidence.update("INSERT INTO writes VALUES(?)",label);if(label.equals(fail))throw new IllegalStateException("injected-"+label);return 1;};
            when(f.messages.insertScoped(eq("0"),eq("client"),any())).thenAnswer(i->{((ChatMessageEntity)i.getArgument(2)).setId(100L);return write.apply("message");});
            when(f.events.insertEvent(any())).thenAnswer(i->{((ChatConversationEventEntity)i.getArgument(0)).setEventSequence(9L);return write.apply("event");});
            when(f.events.assignEventVersion(9L)).thenAnswer(i->write.apply("version"));
            when(f.jdbc.update(contains("UPDATE chat_step_execution_link"),any(Object[].class))).thenAnswer(i->write.apply("link"));
            when(f.jdbc.update(contains("UPDATE chat_interaction_step"),any(Object[].class))).thenAnswer(i->write.apply("step"));
            when(f.events.updateRequestState(any(),eq("RESULT_COMMITTED"),anyLong())).thenAnswer(i->write.apply("request"));
            doAnswer(i->{write.apply("mark");return null;}).when(f.skills).markProjected(eq(f.owner),eq("exec"),eq((String)f.manifest.get("resultDigest")));
            try(var context=new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",Map.of("chat.bounty-asset.enabled","true")));
                context.register(TxConfig.class);context.registerBean("transactionManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
                context.registerBean(SkillExecutionService.class,()->f.skills);context.registerBean(AgentTaskMutationTransaction.class,()->f.roots);
                context.registerBean(ChatBountyAssetProjector.class,()->f.projector);context.refresh();var actual=context.getBean(ChatBountyAssetProjector.class);
                if("none".equals(fail)) {
                    assertEquals(1,actual.project(f.candidate));assertEquals(7,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));
                    verify(f.broker).publishIfSubscribed(eq("42"),eq(1L),any(),argThat(frame->"exec".equals(frame.get("executionId"))&&((List<?>)frame.get("parts")).isEmpty()));
                    var order=inOrder(f.messages,f.events,f.skills);order.verify(f.messages).insertScoped(anyString(),anyString(),any());order.verify(f.events).insertEvent(any());
                    order.verify(f.events).assignEventVersion(9L);order.verify(f.events).findRequest(anyString(),anyString(),anyString(),anyString());
                    order.verify(f.events).updateRequestState(any(),eq("RESULT_COMMITTED"),anyLong());order.verify(f.skills).markProjected(any(),anyString(),anyString());
                } else {assertEquals("injected-"+fail,assertThrows(RuntimeException.class,()->actual.project(f.candidate)).getMessage());
                    assertEquals(0,evidence.queryForObject("SELECT COUNT(*) FROM writes",Integer.class));verifyNoInteractions(f.broker);}
                verify(f.jdbc,never()).update(contains("INSERT INTO chat_conversation_asset"),any(Object[].class));
            }
        }
    }
    @Test void repairReadsOriginalDataReceiptAndCannotRepeatMessageOrEffect() {
        ready();TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            var prior=new LinkedHashMap<String,Object>();prior.put("executionId","exec");prior.put("resultDigest",manifest.get("resultDigest"));prior.put("conversationId","42");prior.put("requestId","req");prior.put("data",manifest.get("data"));
            when(jdbc.queryForList(contains("payload_json"),any(Object[].class))).thenReturn(List.of(Map.of("payload_json",ChatSkillSnapshotJson.write(prior))));
            when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);when(events.updateRequestState(any(),eq("RESULT_COMMITTED"),anyLong())).thenReturn(1);
            assertEquals(0,projector.project(candidate));verifyNoInteractions(messages);verify(events,never()).insertEvent(any());
            verify(skills).markProjected(owner,"exec",(String)manifest.get("resultDigest"));verify(skills,never()).create(any(),any());
        } finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
