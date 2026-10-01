package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.api.ChatTypedDeliberationWire;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual Spring class proxies prove the existing final transaction owns every typed sidecar write. */
class ChatTypedDeliberationSpringTransactionTest {
    @Test void everyLateFailureRollsBackMessageOutcomeTurnEventOutboxAndAggregate() {
        for(FailurePoint point:List.of(FailurePoint.MESSAGE,FailurePoint.OUTCOME,FailurePoint.TURN,
                FailurePoint.EVENT,FailurePoint.OUTBOX,FailurePoint.AGGREGATE,FailurePoint.NONE)){
            try(Fixture fixture=fixture(point)){
                if(point==FailurePoint.NONE){
                    var result=fixture.service.persistFinal("0","owner","client","42",1,"agent","request",
                            "turn","dispatch","snapshot","sha256:"+"a".repeat(64),"答复🌏",1,
                            "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"答复🌏\",\"clarification\":null,\"proposal\":null}",agent());
                    assertEquals(ChatDeliberationService.FinalStatus.PERSISTED,result.status());
                    assertEquals(6,fixture.count());
                }else{
                    assertThrows(ChatDeliberationException.class,()->fixture.service.persistFinal(
                            "0","owner","client","42",1,"agent","request","turn","dispatch","snapshot",
                            "sha256:"+"a".repeat(64),"答复🌏",1,
                            "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"答复🌏\",\"clarification\":null,\"proposal\":null}",agent()),point.name());
                    assertEquals(0,fixture.count(),point.name());
                }
            }
        }
    }


    @Test void pendingAndProposalFailuresRollBackMessageAndOutcomeInTheSameTransaction() {
        for (FailurePoint point : List.of(FailurePoint.PENDING, FailurePoint.PROPOSAL)) {
            try (Fixture fixture=fixture(point)) {
                String content=point==FailurePoint.PENDING?"需要哪张图？":"可以生成一张图";
                String raw=point==FailurePoint.PENDING
                        ? "{\"schemaVersion\":1,\"kind\":\"CLARIFY\",\"text\":\"需要哪张图？\",\"clarification\":{\"question\":\"请选择图片\",\"requiredFacts\":[\"SOURCE_SELECTION\"]},\"proposal\":null}"
                        : "{\"schemaVersion\":1,\"kind\":\"EXECUTION_PROPOSAL\",\"text\":\"可以生成一张图\",\"clarification\":null,\"proposal\":{\"operation\":\"GENERATE_IMAGE\",\"instruction\":\"生成黄鹂图\",\"sourceRefIds\":[]}}";
                assertThrows(ChatDeliberationException.class,()->fixture.service.persistFinal(
                        "0","owner","client","42",1,"agent","request","turn","dispatch",
                        "snapshot","sha256:"+"a".repeat(64),content,1,raw,agent()),point.name());
                assertEquals(0,fixture.count(),point.name());
            }
        }
    }

    @Test void clarificationResumeCasRequestEventAndAdmissionShareOneActualTransaction() {
        for(boolean failAdmission:List.of(false,true)){
            DriverManagerDataSource source=new DriverManagerDataSource(
                    "jdbc:h2:mem:typed_resume_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
            JdbcTemplate evidence=new JdbcTemplate(source);
            evidence.execute("CREATE TABLE typed_resume_evidence(label VARCHAR(40) PRIMARY KEY)");
            AgentTaskMutationTransaction mutations=mock(AgentTaskMutationTransaction.class);
            ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
            ChatConversationDao conversations=mock(ChatConversationDao.class);
            JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
            ChatDeliberationService deliberation=mock(ChatDeliberationService.class);
            ChatDeliberationDao events=mock(ChatDeliberationDao.class);
            ChatTypedDeliberationContextService contexts=mock(ChatTypedDeliberationContextService.class);
            ChatTypedDeliberationService typed=mock(ChatTypedDeliberationService.class);
            ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
            var storeScope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);
            when(typed.store()).thenReturn(store);
            when(mutations.executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any()))
                    .thenAnswer(invocation->{active(evidence);AgentTaskMutationTransaction.LockedTaskMutation<?> action=invocation.getArgument(4);
                        return action.apply(new AgentTaskMetaEntity().setTaskVersion(4L).setAssignedAgentId("agent"));});
            when(bindings.lock(new ChatBountyBindingStore.Scope("0","owner","client"),"task"))
                    .thenReturn(new ChatBountyBindingStore.Binding(3,42L));
            ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setJiacn("owner")
                    .setConversationType("juyiting").setConversationScopeType("bounty")
                    .setConversationScopeKey("task:task").setTaskId("task")
                    .setTargetAgentIds("[\"agent\"]").setLifecycleGeneration(1L);
            conversation.setTenantId("0");conversation.setClientId("client");
            when(conversations.lockScopedById("owner","client","42")).thenReturn(conversation);
            when(scopes.parsePersistedTargetAgentIds("[\"agent\"]")).thenReturn(List.of("agent"));
            var outcome=new ChatTypedDeliberationStore.Outcome("out",storeScope,"parent",1,"parent-turn",
                    "task",3,5,"sha256:"+"d".repeat(64),"CLARIFY","请选择","{}","{}","{}","[]",1);
            var pending=new ChatTypedDeliberationStore.PendingQuestion("pending","out",storeScope,"OPEN",0,
                    "哪张？","[\"SOURCE_SELECTION\"]",null,null,null,1,1);
            when(typed.requireParent(storeScope,"out")).thenReturn(outcome);
            when(typed.requirePending(storeScope,"pending")).thenReturn(pending);
            when(contexts.resolve(any(),eq("task"),eq("agent"),eq(List.of()))).thenReturn(
                    new ChatTypedDeliberationContextService.Context(Map.of("schemaVersion",1,
                            "referenceMode","NONE","supportedOperations",List.of("GENERATE_IMAGE","EDIT_IMAGE"),
                            "availableSources",List.of()),"[]",List.of(),Map.of("schemaVersion",1,"state","READY")));
            when(deliberation.admit(anyString(),any(),eq("42"),eq(1L),any(),any(),any(),isNull(),any(),any()))
                    .thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_resume_evidence VALUES ('request')");
                        cn.jia.chat.handler.dto.ChatMessageDTO input=invocation.getArgument(6);String request=input.getRequestId();
                        return new ChatDeliberationService.Admission(request,1,"8","42",1,InteractionRoute.CHAT,
                                List.of(new ChatDeliberationService.Dispatch(request,"turn","dispatch","event","agent",
                                        "CHAT","RECEIVED","snapshot","sha256:"+"a".repeat(64),Map.of(),Map.of())),false);});
            when(store.answerPending(same(pending),eq(0L),anyString(),eq("key"),anyString(),anyLong()))
                    .thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_resume_evidence VALUES ('pending')");return 1;});
            when(events.findTurn(eq("0"),eq("owner"),eq("client"),eq("turn")))
                    .thenReturn(new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                            .setConversationId("42").setConversationGeneration(1L).setRequestId("reply")
                            .setRequestRevision(1L).setTurnId("turn").setDispatchId("dispatch")
                            .setSnapshotId("snapshot").setTargetAgentId("agent").setRoute("CHAT"));
            when(deliberation.persistTypedQuestionAnswered(any(),eq("pending"),eq(1L),anyString(),eq("out"),anyLong()))
                    .thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_resume_evidence VALUES ('event')");return new ChatConversationEventEntity();});
            when(deliberation.getRequest(eq("0"),eq("owner"),eq("client"),anyString()))
                    .thenAnswer(invocation->new ChatDeliberationService.RequestView(invocation.getArgument(3),"1",
                            "42","1","8","RUNNING","0",List.of(),List.of()));
            when(events.eventHighWatermark("0","owner","client","42",1)).thenReturn(7L);
            when(store.insertAdmission(any())).thenAnswer(invocation->{active(evidence);
                evidence.update("INSERT INTO typed_resume_evidence VALUES ('admission')");return failAdmission?0:1;});

            try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
                context.register(TransactionConfig.class);context.registerBean(DataSource.class,()->source);
                context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
                context.registerBean(AgentTaskMutationTransaction.class,()->mutations);
                context.registerBean(ChatBountyBindingStore.class,()->bindings);context.registerBean(ChatConversationDao.class,()->conversations);
                context.registerBean(JuyitingConversationScopeService.class,()->scopes);context.registerBean(ChatDeliberationService.class,()->deliberation);
                context.registerBean(ChatDeliberationDao.class,()->events);context.registerBean(ChatTypedDeliberationContextService.class,()->contexts);
                context.registerBean(ChatTypedDeliberationService.class,()->typed);context.registerBean(ChatTypedDiscussionAdmissionService.class,
                        ()->new ChatTypedDiscussionAdmissionService(mutations,bindings,conversations,scopes,deliberation,events,contexts,typed));
                context.refresh();var service=context.getBean(ChatTypedDiscussionAdmissionService.class);
                assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(service));
                var command=new ChatTypedDeliberationWire.DiscussionCommand("CLARIFICATION_REPLY","task",3,
                        "用上一张","out",0L,"pending",0L,List.of());
                if(failAdmission)assertThrows(ChatDeliberationException.class,()->service.admit("0",
                        new ServerResolvedSender("user","Human","owner","client",DisplayNameSource.JIACN),"42","key",command));
                else assertFalse(service.admit("0",new ServerResolvedSender("user","Human","owner","client",
                        DisplayNameSource.JIACN),"42","key",command).replay());
            }
            assertEquals(failAdmission?0:4,evidence.queryForObject(
                    "SELECT COUNT(*) FROM typed_resume_evidence",Integer.class));
        }
    }

    @Test void productionBeansAreClassProxiedAndTransactionalMethodIsNotFinal() {
        try(Fixture fixture=fixture(FailurePoint.NONE)){
            assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(fixture.service));
            assertTrue(org.springframework.aop.support.AopUtils.isCglibProxy(
                    fixture.context.getBean(ChatTypedDeliberationService.class)));
            assertFalse(java.lang.reflect.Modifier.isFinal(ChatDeliberationService.class.getModifiers()));
            assertFalse(java.lang.reflect.Modifier.isFinal(ChatTypedDeliberationService.class.getModifiers()));
        }
    }

    private static Fixture fixture(FailurePoint point) {
        DriverManagerDataSource source=new DriverManagerDataSource(
                "jdbc:h2:mem:typed_final_"+UUID.randomUUID()+";MODE=MYSQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate evidence=new JdbcTemplate(source);
        evidence.execute("CREATE TABLE typed_atomic_evidence(label VARCHAR(40) PRIMARY KEY)");

        ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
        ChatConversationDao conversations=mock(ChatConversationDao.class);
        ChatMessageDao messages=mock(ChatMessageDao.class);
        AgentService agents=mock(AgentService.class);
        ChatTypedDeliberationStore typedStore=mock(ChatTypedDeliberationStore.class);

        ChatConversationEntity conversation=new ChatConversationEntity().setId(42L)
                .setConversationType("juyiting").setLifecycleGeneration(1L);
        conversation.setTenantId("0");conversation.setClientId("client");conversation.setJiacn("owner");
        when(conversations.findScopedById("owner","client","42")).thenReturn(conversation);
        when(conversations.lockScopedById("owner","client","42")).thenReturn(conversation);
        ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("42").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot")
                .setContextDigest("sha256:"+"a".repeat(64)).setTargetAgentId("agent").setRoute("CHAT")
                .setState(ChatDeliberationStates.RECEIVED).setStateVersion(0L).setLastDeltaSeq(0L)
                .setCreatedAt(1L).setUpdatedAt(1L);
        ChatContextSnapshotEntity snapshot=new ChatContextSnapshotEntity().setSnapshotId("snapshot")
                .setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("42")
                .setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTargetAgentId("agent").setContextDigest("sha256:"+"a".repeat(64))
                .setFactsManifestJson(CanonicalContextJson.write(Map.of(
                        "task",Map.of("id","task"),"typedDeliberation",Map.of(
                                "schemaVersion",1,"referenceMode","NONE",
                                "supportedOperations",List.of("GENERATE_IMAGE","EDIT_IMAGE"),
                                "availableSources",List.of()))));
        ChatRequestEntity request=new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner")
                .setClientId("client").setRequestId("request").setRequestRevision(1L)
                .setConversationId("42").setConversationGeneration(1L).setUserMessageId(7L)
                .setAggregateState(ChatDeliberationStates.RUNNING).setStateVersion(0L);
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(dao.lockTurn("0","owner","client","turn")).thenReturn(turn);
        when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(snapshot);
        when(dao.findRequest("0","owner","client","request")).thenReturn(request);
        when(dao.findTurnsByRequest("0","owner","client","request")).thenReturn(List.of(turn));
        var scope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);
        when(typedStore.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission(
                "admission",scope,"key","sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),
                "DISCUSSION","task",3,null,null,"request",1,7,"[\"turn\"]","[]","RUNNING",0,1,1));
        when(typedStore.findOutcomeByTurn(scope,"turn",true)).thenReturn(null);

        when(messages.insertScoped(eq("0"),eq("client"),any(ChatMessageEntity.class))).thenAnswer(invocation->{
            active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('message')");
            ChatMessageEntity message=invocation.getArgument(2);message.setId(9L);
            return point==FailurePoint.MESSAGE?0:1;
        });
        when(typedStore.insertOutcome(any())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('outcome')");return point==FailurePoint.OUTCOME?0:1;});
        when(typedStore.insertPending(any())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('pending')");return point==FailurePoint.PENDING?0:1;});
        when(typedStore.findPendingByOutcome(eq(scope),anyString(),eq(false))).thenAnswer(invocation->
                new ChatTypedDeliberationStore.PendingQuestion("pending",invocation.getArgument(1),scope,"OPEN",0,
                        "请选择图片","[\"SOURCE_SELECTION\"]",null,null,null,1,1));
        when(typedStore.insertProposal(any())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('proposal')");return point==FailurePoint.PROPOSAL?0:1;});
        when(typedStore.findProposalByOutcome(eq(scope),anyString())).thenAnswer(invocation->
                new ChatTypedDeliberationStore.Proposal("proposal",invocation.getArgument(1),scope,"PROPOSED",0,
                        "GENERATE_IMAGE","生成黄鹂图","[]","[]",null,null,1));
        when(dao.persistFinal(same(turn),anyString(),eq(9L),anyLong())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('turn')");return point==FailurePoint.TURN?0:1;});
        when(dao.insertEvent(any())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('event')");ChatConversationEventEntity event=invocation.getArgument(0);event.setEventSequence(1L);return point==FailurePoint.EVENT?0:1;});
        when(dao.assignEventVersion(1L)).thenReturn(1);
        when(dao.insertOutbox(any())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('outbox')");return point==FailurePoint.OUTBOX?0:1;});
        when(dao.updateRequestState(same(request),eq(ChatDeliberationStates.COMPLETED),anyLong())).thenAnswer(invocation->{active(evidence);evidence.update("INSERT INTO typed_atomic_evidence VALUES ('aggregate')");return point==FailurePoint.AGGREGATE?0:1;});

        AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        context.register(TransactionConfig.class);
        context.registerBean(DataSource.class,()->source);
        context.registerBean(DataSourceTransactionManager.class,()->new DataSourceTransactionManager(source));
        context.registerBean(ChatTypedDeliberationStore.class,()->typedStore);
        context.registerBean(ChatTypedDeliberationService.class,()->new ChatTypedDeliberationService(typedStore));
        context.registerBean(ChatDeliberationService.class,()->{
            ChatDeliberationService service=new ChatDeliberationService(dao,conversations,messages,agents);
            service.setTypedDeliberation(context.getBean(ChatTypedDeliberationService.class));return service;
        });
        context.refresh();
        return new Fixture(context,context.getBean(ChatDeliberationService.class),evidence);
    }

    private static void active(JdbcTemplate ignored) {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
    }
    private static ServerResolvedAgentSender agent(){return new ServerResolvedAgentSender(
            ServerResolvedAgentSender.AGENT_TYPE,"Agent","owner","client","agent");}

    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement(proxyTargetClass=true)
    static class TransactionConfig { }
    private enum FailurePoint { MESSAGE,OUTCOME,PENDING,PROPOSAL,TURN,EVENT,OUTBOX,AGGREGATE,NONE }
    private record Fixture(AnnotationConfigApplicationContext context,ChatDeliberationService service,
            JdbcTemplate evidence) implements AutoCloseable {
        int count(){return evidence.queryForObject("SELECT COUNT(*) FROM typed_atomic_evidence",Integer.class);}
        @Override public void close(){context.close();}
    }
}
