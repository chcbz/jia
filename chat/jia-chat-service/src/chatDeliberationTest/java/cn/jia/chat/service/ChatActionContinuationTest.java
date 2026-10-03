package cn.jia.chat.service;

import cn.jia.agent.entity.AgentTaskDTO;
import cn.jia.agent.entity.AgentTaskMetaEntity;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskMutationTransaction;
import cn.jia.chat.api.ChatTypedInspectionWire;
import cn.jia.chat.archive.conversation.ChatConversationArchiveStore;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatMessageDao;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.entity.ChatConversationEntity;
import cn.jia.chat.entity.ChatMessageEntity;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import cn.jia.chat.handler.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatActionContinuationTest {
    @Test void automaticInspectionReusesRealUserAndKeepsAgentInstructionSeparateAndReplayStable() {
        var f=new Fixture(); String originalMetadata=f.user().getMetadata();
        var context=f.childContext();
        var child=f.deliberation.admitInspectionContinuation(f.action,context,f.chatScope);
        var replay=f.deliberation.admitInspectionContinuation(f.action,context,f.chatScope);
        assertTrue(replay.replay()); assertEquals(child.requestId(),replay.requestId());
        assertEquals("901",child.userMessageId()); assertEquals(1,f.messages.size()); assertEquals(2,f.requests.size());
        assertEquals(originalMetadata,f.user().getMetadata());
        var dispatch=child.dispatches().getFirst();
        var wire=f.outboxes.values().stream().filter(o->dispatch.turnId().equals(o.getTurnId())).findFirst().orElseThrow();
        var payload=f.map(wire.getPayloadJson());
        assertEquals("请整理资料",payload.get("content"));
        var facts=dispatch.factsManifest();
        var lineage=f.object(facts.get("actionContinuation"));
        assertEquals("AGENT_ACTION",lineage.get("origin")); assertEquals("901",lineage.get("originalUserMessageId"));
        assertEquals("查看已选资料",lineage.get("instruction"));
        var authorized=f.object(facts.get("authorizedContext"));
        assertEquals("901",f.object(authorized.get("currentUserMessage")).get("messageId"));
        assertFalse(((List<?>)authorized.get("sourceMessageIds")).contains("901"));
        assertEquals("请整理资料",f.user().getContent());
    }

    @Test void foreignDeletedAndNonUserOriginalRowsCannotBeReplacedBySyntheticMessages() {
        for (String drift:List.of("missing","owner","client","tenant","conversation","type","sender")) {
            var f=new Fixture(); var context=f.childContext();
            switch(drift) {
                case "missing" -> when(f.messageDao.selectById(901L)).thenReturn(null);
                case "owner" -> f.user().setJiacn("foreign");
                case "client" -> f.user().setClientId("foreign");
                case "tenant" -> f.user().setTenantId("foreign");
                case "conversation" -> f.user().setConversationId("43");
                case "type" -> f.user().setMessageType("AI");
                case "sender" -> f.user().setSenderType("agent");
            }
            assertThrows(ChatDeliberationException.class,()->f.deliberation.admitInspectionContinuation(f.action,context,f.chatScope),drift);
            assertEquals(1,f.messages.size()); assertEquals(1,f.requests.size());
        }
    }

    @Test void staleFinalAndChangedManifestScopeOrSourcesCannotAdmit() {
        var f=new Fixture(); var context=f.childContext();
        f.parentTurn.setFinalDigest("sha256:"+"f".repeat(64));
        assertThrows(ChatDeliberationException.class,()->f.deliberation.admitInspectionContinuation(f.action,context,f.chatScope));
        f.parentTurn.setFinalDigest(f.action.outcome().finalDigest());
        var foreign=f.context("foreign-request","file");
        assertThrows(ChatDeliberationException.class,()->f.deliberation.admitInspectionContinuation(f.action,foreign,f.chatScope));
        var different=f.context(f.childId(),"other-file");
        assertThrows(ChatDeliberationException.class,()->f.deliberation.admitInspectionContinuation(f.action,different,f.chatScope));
        assertEquals(1,f.requests.size());
    }

    @Test void consumerLocksTaskBindingConversationAndFenceThenPersistsOneChildWithoutSse() {
        var f=new Fixture(); var consumer=f.consumer(); var claim=f.claim();
        consumer.consume(claim);
        assertEquals("SENT",claim.row().getStatus()); assertEquals(1,f.messages.size()); assertEquals(2,f.requests.size());
        assertEquals(1,f.children.size()); assertEquals(1,f.events.size());
        assertEquals("action_started",f.events.getFirst().getEventType());
        var child=f.children.values().iterator().next();
        assertEquals(901L,child.userMessageId()); assertEquals(f.action.outcome().outcomeId(),child.parentOutcomeId());
        var order=inOrder(f.tasks,f.bindings,f.conversations,f.dao);
        order.verify(f.tasks).executeWithLockedTaskRootInOwnerScope(eq("0"),eq("client"),eq("owner"),eq("task"),any());
        order.verify(f.bindings).lock(any(),eq("task"));
        order.verify(f.conversations).lockScopedById("owner","client","42");
        order.verify(f.dao).lockOutboxById("0","owner","client",claim.row().getEventId());
    }

    @Test void changedAssignmentBecomesDurableFailureWithoutAnotherAttemptOrUserMessage() {
        var f=new Fixture(); var consumer=f.consumer(); var claim=f.claim();
        f.root.setAssignedAgentId("other");
        assertThrows(ChatActionDispatchService.Rejected.class,()->consumer.consume(claim));
        when(f.dao.settleOutbox(eq(claim.row()),eq("DEAD"),isNull(),eq("ACTION_REQUEST_CHANGED"),isNull(),anyLong())).thenReturn(1);
        consumer.reject(claim);
        assertEquals("DEAD",claim.row().getStatus()); assertEquals(1,f.requests.size());
        assertEquals(1,f.messages.size()); assertEquals(0,f.children.size()); assertEquals(1,f.events.size());
        assertEquals("action_failed",f.events.getFirst().getEventType());
        verify(f.dao,never()).insertRequest(argThat(r->!r.getRequestId().equals(f.action.outcome().requestId())));
        verify(f.dao,never()).settleOutbox(any(),eq("RETRY"),any(),any(),any(),anyLong());
    }

    @Test void rejectionCannotDiscardAnExistingChildOrTakeOverAStaleClaim() {
        var f=new Fixture(); var consumer=f.consumer(); consumer.consume(f.claim());
        var next=f.claim(); int events=f.events.size();
        assertThrows(ChatDeliberationException.class,()->consumer.reject(next));
        assertEquals(events,f.events.size()); assertEquals(1,f.children.size());
        verify(f.dao,never()).settleOutbox(any(),eq("DEAD"),any(),any(),any(),anyLong());
        when(f.dao.lockOutboxById(anyString(),anyString(),anyString(),anyString()))
                .thenReturn(new ChatDispatchOutboxEntity().setStatus("CLAIMED").setLeaseOwner("foreign").setFencingToken(2L).setVersion(2L));
        assertThrows(IllegalStateException.class,()->consumer.reject(next));
        assertEquals(events,f.events.size());
    }

    @Test void recoveredClaimUsesOriginalChildEvenWhenRuntimeHasDisconnected() {
        var f=new Fixture(); var consumer=f.consumer(); consumer.consume(f.claim());
        int dispatches=f.outboxes.size();
        f.registry.remove("session");
        consumer.consume(f.claim());
        assertEquals(2,f.requests.size()); assertEquals(1,f.messages.size()); assertEquals(1,f.children.size());
        assertEquals(1,f.events.size()); assertEquals(dispatches,f.outboxes.size());
    }

    @Test void ordinaryChatActionResolvesTheSameExactSourceIntoNativeInspectionWithoutUserConfirmation() {
        var f=new Fixture(true); var consumer=f.consumer(); consumer.consume(f.claim());
        assertEquals("CHAT",f.parentTurn.getRoute()); assertEquals(1,f.messages.size());
        var child=f.children.values().iterator().next();
        assertEquals(f.action.admission().userMessageId(),child.userMessageId());
        var source=ChatTypedInspectionContextService.sources(child.sourceCatalogJson()).getFirst();
        assertEquals(f.action.validated().interactionOutcome().action().sourceRefIds(),List.of(source.get("sourceRefId")));
        assertTrue(f.turns.values().stream().anyMatch(t->child.requestId().equals(t.getRequestId()) && "INSPECT".equals(t.getRoute())));
        assertNull(f.action.validated().inspectionInputReceipt()); // Catalogue-only CHAT never pretends to have read bytes.
    }

    @Test void assignmentConversationAndStolenLeaseFailBeforeChildWrites() {
        for(String drift:List.of("assignment","target","generation","scope","fence")) {
            var f=new Fixture(); var consumer=f.consumer(); var claim=f.claim();
            switch(drift) {
                case "assignment" -> when(f.bindings.lock(any(),anyString())).thenReturn(new ChatBountyBindingStore.Binding(4,42L));
                case "target" -> f.root.setAssignedAgentId("other");
                case "generation" -> f.conversation.setLifecycleGeneration(2L);
                case "scope" -> f.conversation.setJiacn("other");
                case "fence" -> when(f.dao.lockOutboxById(anyString(),anyString(),anyString(),anyString()))
                        .thenReturn(new ChatDispatchOutboxEntity().setStatus("CLAIMED").setLeaseOwner("other").setFencingToken(2L).setVersion(2L));
            }
            assertThrows(RuntimeException.class,()->consumer.consume(claim),drift);
            assertEquals(1,f.requests.size()); assertEquals(1,f.messages.size()); assertEquals(0,f.children.size());
        }
    }

    static class Fixture {
        final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
        final ChatConversationDao conversations=mock(ChatConversationDao.class);
        final ChatMessageDao messageDao=mock(ChatMessageDao.class);
        final ChatTypedDeliberationStore typed=mock(ChatTypedDeliberationStore.class);
        final AgentService agents=mock(AgentService.class);
        final AgentTaskMutationTransaction tasks=mock(AgentTaskMutationTransaction.class);
        final ChatBountyBindingStore bindings=mock(ChatBountyBindingStore.class);
        final JuyitingConversationScopeService scopes=mock(JuyitingConversationScopeService.class);
        final ChatActionFinalService finals=mock(ChatActionFinalService.class);
        final AgentTaskMetaEntity root=new AgentTaskMetaEntity();
        final List<ChatMessageEntity> messages=new ArrayList<>();
        final Map<String,ChatRequestEntity> requests=new LinkedHashMap<>();
        final Map<String,ChatTurnEntity> turns=new LinkedHashMap<>();
        final Map<String,ChatContextSnapshotEntity> snapshots=new LinkedHashMap<>();
        final Map<String,ChatDispatchOutboxEntity> outboxes=new LinkedHashMap<>();
        final Map<String,ChatTypedDeliberationStore.Admission> children=new LinkedHashMap<>();
        final List<ChatConversationEventEntity> events=new ArrayList<>();
        final TypedInspectionSessionRegistry registry=new TypedInspectionSessionRegistry();
        final boolean mixedMaterials;
        final ChatTypedInspectionContextService inspections;
        final ChatConversationEntity conversation=new ChatConversationEntity().setId(42L).setJiacn("owner").setConversationType("juyiting")
                .setConversationScopeType("bounty").setConversationScopeKey("task:task").setTaskId("task").setLifecycleGeneration(1L).setTargetAgentIds("[\"agent\"]");
        final JuyitingConversationScope chatScope=new JuyitingConversationScope("bounty","task:task","task","agent",List.of("agent"),List.of("agent"));
        final ChatDeliberationService deliberation=new ChatDeliberationService(dao,conversations,messageDao,agents);
        final ChatActionFinalService.BoundAction action;
        final ChatTurnEntity parentTurn;

        Fixture() { this(false); }
        Fixture(boolean chatParent) { this(chatParent,false); }
        Fixture(boolean chatParent,boolean mixedMaterials) {
            this.mixedMaterials=mixedMaterials;
            conversation.setTenantId("0"); conversation.setClientId("client");
            root.setAssignedAgentId("agent");root.setTaskVersion(3L);
            when(conversations.lockScopedById("owner","client","42")).thenReturn(conversation);
            when(conversations.findScopedById("owner","client","42")).thenReturn(conversation);
            when(messageDao.findOwnedByConversationIdWithLimit("owner","client","42",200)).thenAnswer(i->List.copyOf(messages));
            when(messageDao.insertScoped(anyString(),anyString(),any())).thenAnswer(i->{ChatMessageEntity m=i.getArgument(2);m.setId(901L+(long)messages.size());messages.add(m);return 1;});
            when(messageDao.selectById(any())).thenAnswer(i->messages.stream().filter(m->m.getId().equals(i.getArgument(0))).findFirst().orElse(null));
            when(dao.insertRequest(any())).thenAnswer(i->{ChatRequestEntity r=i.getArgument(0);r.setId((long)requests.size()+1);requests.put(r.getRequestId(),r);return 1;});
            when(dao.findRequest(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->requests.get(i.getArgument(3)));
            when(dao.lockRequest(anyString(),anyString(),anyString(),anyString(),anyLong())).thenAnswer(i->requests.get(i.getArgument(3)));
            when(dao.insertTurn(any())).thenAnswer(i->{ChatTurnEntity r=i.getArgument(0);turns.put(r.getTurnId(),r);return 1;});
            when(dao.findTurn(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->turns.get(i.getArgument(3)));
            when(dao.lockTurn(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->turns.get(i.getArgument(3)));
            when(dao.findTurnsByRequest(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->turns.values().stream().filter(t->t.getRequestId().equals(i.getArgument(3))).toList());
            when(dao.insertSnapshot(any())).thenAnswer(i->{ChatContextSnapshotEntity r=i.getArgument(0);snapshots.put(r.getSnapshotId(),r);return 1;});
            when(dao.findSnapshot(anyString(),anyString(),anyString(),anyString())).thenAnswer(i->snapshots.get(i.getArgument(3)));
            when(dao.insertOutbox(any())).thenAnswer(i->{ChatDispatchOutboxEntity r=i.getArgument(0);outboxes.put(r.getEventId(),r);return 1;});
            when(dao.insertEvent(any())).thenAnswer(i->{ChatConversationEventEntity e=i.getArgument(0);e.setEventSequence((long)events.size()+1);events.add(e);return 1;});
            when(dao.assignEventVersion(anyLong())).thenReturn(1);
            var task=new AgentTaskDTO();task.setId("task");task.setTenantId("0");task.setClientId("client");task.setAssignedAgentId("agent");task.setTaskVersion("3");
            when(agents.getTask("task")).thenReturn(task);
            registry.register("session","0","owner","client","agent",Map.ofEntries(Map.entry("schemaVersion",1),
                    Map.entry("contract","juyiting-typed-inspection-v1"),Map.entry("enabled",true),Map.entry("profileId","profile"),
                    Map.entry("engineContractId","engine"),Map.entry("enginePolicyDigest",digest('a')),Map.entry("toolPolicyDigest",digest('b')),
                    Map.entry("inputPolicyDigest",digest('c')),Map.entry("toolPolicy","STRICT_NO_TOOLS"),Map.entry("recovery","durable-inbox-turn-readback-v1"),
                    Map.entry("supportedInputs",mixedMaterials?ChatMixedMaterialWireTest.supportedInputs():List.of(Map.of("mediaKind","text","mimeType","text/plain","carrier","DIRECT_TEXT","carrierContractDigest",digest('d'))))),()->true);
            var jdbc=mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(invocation -> {
                if (!mixedMaterials) return List.of(Map.of("content_mime_type","text/plain","content_hash","a".repeat(64),"byte_length",12L));
                String sql=invocation.getArgument(0);
                String file=invocation.getArgument(sql.contains("WHERE BINARY l.tenant_id")?5:9);
                int index=Integer.parseInt(file.substring(file.lastIndexOf('-')+1));
                byte[] bytes=ChatMixedMaterialWireTest.materialBytes(index);
                return List.of(Map.of("content_mime_type",ChatMixedMaterialWireTest.mime(index),
                        "content_hash",ChatMixedMaterialWireTest.sha(bytes),"byte_length",(long)bytes.length));
            });
            var capabilities=mock(ChatActionCapabilityService.class);
            when(capabilities.available(any(),anyList())).thenReturn(List.of(Map.of("actionId","inspect-materials","kind","INSPECT_INPUTS", "operation","INSPECT_INPUTS",
                    "inputMediaTypes",mixedMaterials?List.of("text","image","audio","file"):List.of("text"),"minSources",1,"maxSources",32)));
            inspections=new ChatTypedInspectionContextService(jdbc,mock(ChatConversationArchiveStore.class),registry,capabilities,true);
            var parentContext=context("parent","file");
            var input=new ChatMessageDTO();input.setRequestId("parent");input.setContent("请整理资料");
            var sender=new ServerResolvedSender("user","用户","owner","client",DisplayNameSource.NICKNAME);
            String parentCatalog=parentContext.admissionEnvelopeJson();
            Map<String,Object> parentFacts=object(parentContext.typedInspection().get("discussionFacts"));
            ChatDeliberationService.Admission admitted;
            if (chatParent) {
                var chatSessions=new cn.jia.chat.handler.TypedDeliberationSessionRegistry();
                chatSessions.register("chat-session","0","owner","client","agent",Map.of("schemaVersion",3,"state","READY", "carrier","CHAT_MESSAGE_FINAL_SIDECAR_V3",
                        "referenceModes",List.of("NONE","AVAILABLE"),"outcomeKinds",List.of("ANSWER","CLARIFY","ACTION_REQUEST"),
                        "engine","CODEX_APP_SERVER_NATIVE_OUTPUT_SCHEMA","strictNoToolsVerified",false,"toolPolicy","read-only-constrained"));
                var schema=mock(cn.jia.chat.config.ChatTypedDeliberationSchemaInitializer.class);when(schema.ready()).thenReturn(true);
                var chatContext=new ChatTypedDeliberationContextService(jdbc,chatSessions,schema,capabilities,true).resolve(
                        new ChatTypedDeliberationContextService.Scope("0","owner","client","42",1),"task","agent",selectors("file").stream().map(s ->
                                new cn.jia.chat.api.ChatTypedDeliberationWire.SourceSelector(s.kind(),s.fileId(),s.version(),s.purpose(),s.assetId(),s.assetRevision())).toList());
                parentCatalog=chatContext.sourceCatalogJson();parentFacts=chatContext.facts();
                admitted=deliberation.admit("0",sender,"42",1,chatScope,InteractionRoute.CHAT,input,null,parentFacts);
            } else admitted=deliberation.admitInspection("0",sender,"42",1,chatScope,input,null,parentContext.typedInspection(),null);
            var d=admitted.dispatches().getFirst(); parentTurn=turns.get(d.turnId());
            Map<String,Object> bound=new LinkedHashMap<>();bound.put("tenantId","0");bound.put("ownerJiacn","owner");bound.put("clientId","client");bound.put("conversationId","42");
            bound.put("conversationGeneration","1");bound.put("requestId","parent");bound.put("requestRevision","1");bound.put("turnId",d.turnId());bound.put("dispatchId",d.dispatchId());
            bound.put("snapshotId",d.contextSnapshotId());bound.put("contextDigest",d.contextHash());bound.put("targetAgentId","agent");bound.put("route",chatParent?"CHAT":"INSPECT");bound.put("taskId","task");
            var sources=ChatTypedInspectionContextService.sources(parentContext.admissionEnvelopeJson());
            var receipt=new LinkedHashMap<String,Object>();receipt.put("schemaVersion",1);receipt.put("authorizationId",parentContext.typedInspection().get("authorizationId"));
            receipt.put("manifestDigest",parentContext.typedInspection().get("manifestDigest"));receipt.put("sources",sources.stream().map(s->Map.of("sourceRefId",s.get("sourceRefId"),"sha256",s.get("sha256"),"byteLength",s.get("byteLength"),"carrier",s.get("carrier"),"contributionDigest",digest('e'))).toList());
            receipt.put("inputDigest",ChatDeliberationService.digest(receipt));receipt.put("engineThreadId","native-thread");receipt.put("engineTurnId","native-turn");
            var outcome=Map.of("schemaVersion",3,"kind","ACTION_REQUEST","text","继续处理","action",Map.of("actionId","inspect-materials","instruction",mixedMaterials?"逐项核对所选资料并汇总。".repeat(400):"查看已选资料","sourceRefIds",sources.stream().map(s->s.get("sourceRefId")).toList()));
            var union=new LinkedHashMap<String,Object>(outcome);union.put("clarification",null);
            var authority=Map.of("authorizationId",parentContext.typedInspection().get("authorizationId"),"manifestDigest",parentContext.typedInspection().get("manifestDigest"),"sources",sources);
            var validated=ChatActionFinalValidator.validateJson(bound,parentFacts,chatParent?null:authority,"继续处理",3,CanonicalContextJson.write(union),chatParent?null:CanonicalContextJson.write(receipt));
            parentTurn.setFinalDigest(validated.finalDigest()).setFinalMessageId(999L).setState(ChatDeliberationStates.FINAL_PERSISTED);
            var scope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);
            var parentAdmission=new ChatTypedDeliberationStore.Admission("parent-admit",scope,"parent-key",digest('b'),digest('c'),"DISCUSSION","task",3,null,null,"parent",1,901,
                    CanonicalContextJson.write(List.of(d.turnId())),parentCatalog,"ADMITTED",0,1,1);
            var stored=new ChatTypedDeliberationStore.Outcome("parent-outcome",scope,"parent",1,d.turnId(),"task",3,999,validated.finalDigest(),"ACTION_REQUEST","继续处理",
                    CanonicalContextJson.write(bound),CanonicalContextJson.write(ChatActionFinalValidator.factsMap(validated.dispatchFacts())),"unused-in-fixture",parentCatalog,1);
            action=new ChatActionFinalService.BoundAction(stored,parentAdmission,validated);
        }
        ChatMessageEntity user(){return messages.getFirst();}
        String childId(){return ChatDeliberationService.inspectionContinuationRequestId(ChatActionFinalValidator.actionEventId(action.validated()));}
        ChatTypedInspectionContextService.Context childContext(){return context(childId(),"file");}
        ChatTypedInspectionContextService.Context context(String request,String file){return inspections.resolve(new ChatTypedInspectionContextService.Scope("0","owner","client","42",1,"task",3,request,1,"agent"),selectors(file));}
        List<ChatTypedInspectionWire.SourceSelector> selectors(String file) {
            if (!mixedMaterials) return List.of(new ChatTypedInspectionWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION",file,"1","INPUT",null,null));
            return java.util.stream.IntStream.range(0,32).mapToObj(i->new ChatTypedInspectionWire.SourceSelector(
                    "TASK_LINKED_WORKSPACE_VERSION",file+"-"+i,"1",i%2==0?"INPUT":"REFERENCE",null,null)).toList();
        }
        @SuppressWarnings("unchecked") Map<String,Object> object(Object value){return (Map<String,Object>)value;}
        @SuppressWarnings("unchecked") Map<String,Object> map(String json){return cn.jia.core.util.JsonUtil.getMapper().readValue(json,Map.class);}
        ChatDeliberationOutboxService.Claim claim(){
            var row=new ChatDispatchOutboxEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client").setEventId(ChatActionFinalValidator.actionEventId(action.validated()))
                    .setEventType(ChatActionFinalService.ACTION_EVENT).setTurnId(action.outcome().turnId()).setDispatchId(parentTurn.getDispatchId()).setStatus("CLAIMED")
                    .setVersion(1L).setFencingToken(1L).setLeaseOwner("worker").setLeaseUntil(System.currentTimeMillis()+300_000L);
            when(dao.lockOutboxById("0","owner","client",row.getEventId())).thenReturn(row);
            when(dao.settleOutbox(eq(row),eq("SENT"),isNull(),isNull(),anyLong(),anyLong())).thenReturn(1);
            return new ChatDeliberationOutboxService.Claim(row,true);
        }
        @SuppressWarnings("unchecked") ChatActionDispatchService consumer(){
            when(finals.loadAction(any())).thenReturn(action);
            when(tasks.executeWithLockedTaskRootInOwnerScope(anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(i->((AgentTaskMutationTransaction.LockedTaskMutation<?>)i.getArgument(4)).apply(root));
            when(bindings.lock(any(),eq("task"))).thenReturn(new ChatBountyBindingStore.Binding(3,42L));
            when(scopes.parsePersistedTargetAgentIds(anyString())).thenReturn(List.of("agent"));
            when(typed.findAdmissionByKey(any(),anyString(),eq(true))).thenAnswer(i->children.get(i.getArgument(1)));
            when(typed.insertAdmission(any())).thenAnswer(i->{ChatTypedDeliberationStore.Admission row=i.getArgument(0);children.put(row.idempotencyKey(),row);return 1;});
            return new ChatActionDispatchService(finals,tasks,bindings,conversations,scopes,typed,inspections,deliberation,dao,new ChatDeliberationOutboxService(dao),mock(ObjectProvider.class));
        }
        static String digest(char c){return "sha256:"+String.valueOf(c).repeat(64);}
    }
}
