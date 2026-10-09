package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatActionFinalServiceTest {
    private final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
    private final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
    private final TypedInspectionSessionRegistry sessions=mock(TypedInspectionSessionRegistry.class);
    private final ChatActionFinalService service=new ChatActionFinalService(store,dao,sessions);
    private final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);
    private final AtomicReference<ChatTypedDeliberationStore.Outcome> row=new AtomicReference<>();
    private final AtomicReference<ChatTypedDeliberationStore.PendingQuestion> pending=new AtomicReference<>();
    private final AtomicReference<ChatDispatchOutboxEntity> event=new AtomicReference<>();
    private final ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
            .setConversationId("42").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
            .setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot").setContextDigest("sha256:"+"a".repeat(64))
            .setTargetAgentId("agent").setRoute("CHAT");
    private final ChatContextSnapshotEntity snapshot=new ChatContextSnapshotEntity().setSnapshotId("snapshot")
            .setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("42").setConversationGeneration(1L)
            .setRequestId("request").setRequestRevision(1L).setTargetAgentId("agent").setRoute("CHAT")
            .setContextDigest("sha256:"+"a".repeat(64)).setFactsManifestJson(CanonicalContextJson.write(Map.of("task",Map.of("id","task"),
                    "typedDeliberation",Map.of("schemaVersion",3,"availableSources",List.of(),"inspectedSourceRefIds",List.of(),
                            "availableActions",List.of(Map.of("actionId","write-document","kind","EXECUTE","operation","WRITE_DOCUMENT",
                                    "inputMediaTypes",List.of("text","file"),"minSources",0,"maxSources",32))))));
    private static final String ACTION="{\"schemaVersion\":3,\"kind\":\"ACTION_REQUEST\",\"text\":\"处理中\",\"clarification\":null,"
            +"\"action\":{\"actionId\":\"write-document\",\"instruction\":\"整理文档\",\"sourceRefIds\":[]}}";
    private static final String ANSWER="{\"schemaVersion\":3,\"kind\":\"ANSWER\",\"text\":\"处理中\",\"clarification\":null,\"action\":null}";
    private static final String CLARIFY="{\"schemaVersion\":3,\"kind\":\"CLARIFY\",\"text\":\"处理中\",\"action\":null,"
            +"\"clarification\":{\"question\":\"用于哪里？\",\"requiredFacts\":[\"用途\"]}}";

    private void ready() {
        when(store.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission("admission",scope,"key",
                "sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),"DISCUSSION","task",3,null,null,"request",1,8,"[\"turn\"]","[]","ADMITTED",0,1,1));
        when(store.findOutcomeByTurn(scope,"turn",true)).thenAnswer(call->row.get());
        when(store.findOutcomeByRequest(scope,"request")).thenAnswer(call->row.get());
        when(store.insertOutcome(any())).thenAnswer(call->{row.set(call.getArgument(0));return 1;});
        when(store.insertPending(any())).thenAnswer(call->{pending.set(call.getArgument(0));return 1;});
        when(store.findPendingByOutcome(eq(scope),anyString(),eq(false))).thenAnswer(call->pending.get());
        when(dao.insertOutbox(any())).thenAnswer(call->{event.set(call.getArgument(0));return 1;});
        when(dao.findOutboxById(eq("0"),eq("owner"),eq("client"),anyString())).thenAnswer(call->event.get());
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(snapshot);
    }
    private ChatActionFinalService.Prepared prepare(String raw) { return service.prepare(turn,snapshot,"处理中",3,raw,null); }
    private Map<String,Object> persist(String raw) {
        var prepared=prepare(raw); var view=service.persist(prepared,9,12);
        turn.setFinalMessageId(9L).setFinalDigest(prepared.validated().finalDigest()); return view;
    }
    @Test void explicitRelationsBindTheAdmittedParentAndFrozenDigestAndPersistOnRead() throws Exception {
        List<Map<String,Object>> projections=new ArrayList<>();
        for(String mode:List.of("APPEND","REPLACE","RESET")) {
            var f=new ChatCompletedMessageSourceServiceTest.Fixture();var parent=f.row.get();
            String parentId=parent.outcomeId();String digest=parent.finalDigest();
            var initial=f.finals.readIfV3(f.scope,parent.requestId(),parent.turnId(),1,"CHAT");
            var facts=new LinkedHashMap<String,Object>();facts.put("task",Map.of("id","task"));
            facts.put("typedDeliberation",ChatActionFinalValidator.factsMap(ChatActionOutcomeContract.factsJson(parent.factsJson())));
            facts.put("typedDeliberationAdmission",Map.of("intent","DISCUSSION","parentOutcomeId",parentId,
                    "deliveryParent",Map.of("outcomeId",parentId,"finalDigest",digest)));
            var childTurn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                    .setConversationId("42").setConversationGeneration(1L).setRequestId("child").setRequestRevision(1L)
                    .setTurnId("child-turn").setDispatchId("child-dispatch").setSnapshotId("child-snapshot")
                    .setContextDigest("sha256:"+"f".repeat(64)).setTargetAgentId("agent").setRoute("CHAT");
            var childSnapshot=new ChatContextSnapshotEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                    .setConversationId("42").setConversationGeneration(1L).setRequestId("child").setRequestRevision(1L)
                    .setSnapshotId("child-snapshot").setContextDigest(childTurn.getContextDigest()).setTargetAgentId("agent")
                    .setRoute("CHAT").setFactsManifestJson(CanonicalContextJson.write(facts));
            when(f.dao.findTurn("0","owner","client","child-turn")).thenReturn(childTurn);
            when(f.dao.findSnapshot("0","owner","client","child-snapshot")).thenReturn(childSnapshot);
            when(f.store.findAdmissionByRequest(f.scope,"child")).thenReturn(new ChatTypedDeliberationStore.Admission("child-admission",f.scope,"child-key",
                    "sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),"DISCUSSION","task",3,parentId,null,"child",1,10,
                    "[\"child-turn\"]","[]","ADMITTED",0,1,1));
            when(f.store.findOutcome(f.scope,parentId,false)).thenReturn(parent);
            when(f.store.findOutcomeByRequest(f.scope,"child")).thenAnswer(i->f.row.get());
            var relation=Map.of("mode",mode,"parentOutcomeId",parentId,"parentFinalDigest",digest);
            var raw=new LinkedHashMap<String,Object>();raw.put("schemaVersion",3);raw.put("kind","ANSWER");raw.put("text","修改原文  ");
            raw.put("clarification",null);raw.put("action",null);raw.put("deliverable",true);raw.put("deliveryRelation",relation);
            var prepared=f.finals.prepare(childTurn,childSnapshot,"修改原文  ",3,CanonicalContextJson.write(raw),null);
            var view=f.finals.persist(prepared,11,20);childTurn.setFinalMessageId(11L).setFinalDigest(prepared.validated().finalDigest());
            assertEquals(relation,view.get("deliveryRelation"));assertEquals(view,f.finals.readIfV3(f.scope,"child","child-turn",1,"CHAT").get("outcome"));
            projections.add(Map.of("mode",mode,"initial",initial,"updated",f.finals.readIfV3(f.scope,"child","child-turn",1,"CHAT")));
            assertEquals("修改原文  ",view.get("text"));verifyNoInteractions(f.messages,f.sessions);
            raw.put("deliveryRelation",Map.of("mode",mode,"parentOutcomeId",parentId,"parentFinalDigest","sha256:"+"0".repeat(64)));
            assertThrows(ChatDeliberationException.class,()->f.finals.prepare(childTurn,childSnapshot,"修改原文  ",3,CanonicalContextJson.write(raw),null));
            var changed=new LinkedHashMap<>(facts);changed.put("typedDeliberationAdmission",Map.of("parentOutcomeId","another",
                    "deliveryParent",Map.of("outcomeId","another","finalDigest",digest)));
            childSnapshot.setFactsManifestJson(CanonicalContextJson.write(changed));
            assertThrows(ChatDeliberationException.class,()->f.finals.readIfV3(f.scope,"child","child-turn",1,"CHAT"));
        }
        String output=System.getenv("CYF_TEXT_RELATION_PROJECTION_OUTPUT");
        if(output!=null)java.nio.file.Files.writeString(java.nio.file.Path.of(output),
                cn.jia.core.util.JsonUtil.toJson(projections),java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void relationWithoutServerAdvertisedCompletedDeliveryParentCannotPublish() {
        ready();String raw=ANSWER.replace("\"action\":null}","\"action\":null,\"deliverable\":true,\"deliveryRelation\":{\"mode\":\"REPLACE\",\"parentOutcomeId\":\"parent\",\"parentFinalDigest\":\"sha256:"+"a".repeat(64)+"\"}}");
        assertThrows(ChatDeliberationException.class,()->prepare(raw));verify(store,never()).insertOutcome(any());
    }

    @Test void onlyExplicitTextDeliveryProjectsActualMessageSnapshotRefsAndReadRevalidatesMarker() throws Exception {
        ready(); var view=persist(ANSWER.replace("\"action\":null}","\"action\":null,\"deliverable\":true}"));
        assertEquals(true,view.get("deliverable"));
        assertEquals(Map.of("turnId","turn","messageId","9","snapshotId","snapshot","finalDigest",row.get().finalDigest()),view.get("messageSource"));
        assertEquals(view,service.readIfV3(scope,"request","turn",1,"CHAT").get("outcome"));
        String output=System.getenv("CYF_TEXT_DELIVERABLE_PROJECTION_OUTPUT");
        if(output!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(output),
                cn.jia.core.util.JsonUtil.toJson(service.readIfV3(scope,"request","turn",1,"CHAT")), java.nio.charset.StandardCharsets.UTF_8);
        verify(store,never()).insertProposal(any()); verify(dao,never()).insertOutbox(any());
        var original=row.get(); row.set(new ChatTypedDeliberationStore.Outcome(original.outcomeId(),original.scope(),original.requestId(),
                original.requestRevision(),original.turnId(),original.taskId(),original.assignmentRevision(),original.assistantMessageId(),
                original.finalDigest(),original.kind(),original.text(),original.bindingJson(),original.factsJson(),
                original.outcomeJson().replace("\"deliverable\":true","\"deliverable\":false"),original.sourceCatalogJson(),original.createdAt()));
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
    }
    @Test void ordinaryAnswerOrExplicitFalseDoesNotExposeTextDeliverySource() {
        ready(); var view=persist(ANSWER); assertFalse(view.containsKey("deliverable")); assertFalse(view.containsKey("messageSource"));
        row.set(null);turn.setFinalMessageId(null).setFinalDigest(null);
        view=persist(ANSWER.replace("\"action\":null}","\"action\":null,\"deliverable\":false}"));
        assertEquals(false,view.get("deliverable")); assertFalse(view.containsKey("messageSource"));
    }
    @Test void actionPersistsItsOwnKindAndIndependentStableOutboxWithoutConsentOrProposal() throws Exception {
        ready(); var view=persist(ACTION);
        assertEquals("ACTION_REQUEST",row.get().kind()); assertEquals("ACTION_REQUESTED",event.get().getEventType());
        assertEquals("READY",event.get().getStatus()); assertEquals(0,event.get().getAttemptCount());
        assertEquals(event.get().getEventId(),map(view.get("action")).get("actionRequestId"));
        assertTrue(event.get().getPayloadJson().contains(row.get().finalDigest()));
        assertFalse(event.get().getPayloadJson().contains("instruction"));
        verify(store,never()).insertProposal(any()); verify(store,never()).insertPending(any()); verifyNoInteractions(sessions);
        var read=service.readIfV3(scope,"request","turn",1,"CHAT");
        assertEquals(3,read.get("schemaVersion")); assertEquals("CHAT",read.get("route")); assertEquals("READY",read.get("state"));
        assertEquals(view,read.get("outcome")); assertNull(read.get("inspection"));
        try (var input=getClass().getResourceAsStream("/contracts/action-final-projection-v3.json")) {
            assertNotNull(input);
            var fixture=cn.jia.core.util.JsonUtil.getMapper().readValue(input,Map.class);
            assertEquals(fixture.get("chatAction"),read);
        }
    }
    @Test void actionConsumptionReadsImmutableFinalAndRejectsEventScopePayloadAndIdentityDrift() {
        ready(); persist(ACTION);
        var loaded=service.loadAction(event.get());
        assertEquals(row.get(),loaded.outcome());
        assertEquals("write-document",loaded.validated().interactionOutcome().action().actionId());
        var originalPayload=event.get().getPayloadJson();
        event.get().setPayloadJson(originalPayload.replace("write-document","generate-image"));
        assertThrows(ChatDeliberationException.class,()->service.loadAction(event.get()));
        event.get().setPayloadJson(originalPayload.replace("{","{\"instruction\":\"invented\","));
        assertThrows(ChatDeliberationException.class,()->service.loadAction(event.get()));
        event.get().setPayloadJson(originalPayload).setEventId("act_foreign");
        assertThrows(ChatDeliberationException.class,()->service.loadAction(event.get()));
        event.get().setOwnerJiacn("foreign");
        assertThrows(ChatDeliberationException.class,()->service.loadAction(event.get()));
        verifyNoInteractions(sessions);
    }

    @Test void actionProgressReadsDurableChildWithoutChangingFinalOrAdmittingAgain() {
        ready(); var immutable=persist(ACTION);
        assertEquals("QUEUED", map(service.readIfV3(scope,"request","turn",1,"CHAT").get("actionProgress")).get("state"));
        var child=progressChild("RUNNING",0);
        for (String state:List.of("RUNNING","OUTPUT_COMMITTED","FAILED","CANCELLED")) {
            child.setAggregateState(state).setStateVersion(child.getStateVersion()+1);
            var read=service.readIfV3(scope,"request","turn",1,"CHAT");
            assertEquals(immutable,read.get("outcome"));
            var progress=map(read.get("actionProgress"));
            assertEquals("OUTPUT_COMMITTED".equals(state)?"COMPLETED":state,progress.get("state"));
            assertEquals(child.getRequestId(),progress.get("childRequestId"));
            assertEquals(Long.toString(child.getStateVersion()),progress.get("childStateVersion"));
            assertEquals("EXECUTE",progress.get("childRoute"));
        }
        verify(dao,times(1)).insertOutbox(any()); verify(store,times(1)).insertOutcome(any());
        verify(dao,never()).insertRequest(any()); verify(store,never()).insertAdmission(any());
    }

    @Test void actionProgressRejectsMissingForeignAndNonProjectedChild() {
        ready(); persist(ACTION); event.get().setStatus("SENT");
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
        var child=progressChild("RUNNING",0);
        child.setOwnerJiacn("foreign");
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
        child.setOwnerJiacn("owner").setConversationGeneration(2L);
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
        child.setConversationGeneration(1L).setAggregateState("COMPLETED");
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
        child.setAggregateState("RUNNING").setStateVersion(-1L);
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
    }

    @Test void failedAdmissionProjectsSafeTerminalStateWithoutLeakingInternalErrors() {
        ready(); persist(ACTION); event.get().setStatus("DEAD").setVersion(3L).setLastError("secret-runtime-details");
        var progress=map(service.readIfV3(scope,"request","turn",1,"CHAT").get("actionProgress"));
        assertEquals("FAILED",progress.get("state")); assertEquals("3",progress.get("dispatchVersion"));
        assertNull(progress.get("childRequestId")); assertNull(progress.get("childStateVersion"));
        assertFalse(progress.toString().contains("secret"));
        verify(dao,never()).lockOutboxById(anyString(),anyString(),anyString(),anyString());
    }

    private ChatRequestEntity progressChild(String state,long version) {
        String actionId=event.get().getEventId();
        String id=cn.jia.chat.api.ChatBountyInteractionV3Wire.shaText("action-execute\n"+actionId);
        event.get().setStatus("SENT").setVersion(2L);
        var admission=new ChatTypedDeliberationStore.Admission("child-admission",scope,actionId,"digest","body",
                "DISCUSSION","task",3,row.get().outcomeId(),null,id,1,8,"[]","[]","ADMITTED",0,1,1);
        when(store.findAdmissionByKey(scope,actionId,false)).thenReturn(admission);
        var child=new ChatRequestEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setRequestId(id).setRequestRevision(1L).setUserMessageId(8L).setConversationId("42")
                .setConversationGeneration(1L).setAggregateState(state).setStateVersion(version);
        when(dao.findRequest("0","owner","client",id)).thenReturn(child); return child;
    }

    @Test void answersDoNotCreateActionEventsAndPendingReadDoesNotPretendCompletion() {
        ready(); var read=service.readIfV3(scope,"request","turn",1,"CHAT");
        assertEquals("PENDING",read.get("state")); assertNull(read.get("outcome"));
        var view=persist(ANSWER); assertEquals("ANSWER",view.get("kind")); assertNull(view.get("action"));
        verify(dao,never()).insertOutbox(any()); verify(store,never()).insertProposal(any());
    }
    @Test void clarificationUsesExistingScopedQuestionCasAndProjectsLatestReply() {
        ready(); var view=persist(CLARIFY); var open=pending.get();
        assertEquals("OPEN",map(view.get("clarification")).get("state"));
        assertEquals(List.of("用途"),map(view.get("clarification")).get("requiredFacts"));
        pending.set(new ChatTypedDeliberationStore.PendingQuestion(open.pendingQuestionId(),open.outcomeId(),scope,"ANSWERED",1,
                open.question(),open.requiredFactsJson(),"reply","reply-key","sha256:"+"d".repeat(64),open.createdAt(),13));
        var read=map(service.readIfV3(scope,"request","turn",1,"CHAT").get("outcome"));
        assertEquals("reply",map(read.get("clarification")).get("replyRequestId"));
        assertEquals("1",map(read.get("clarification")).get("stateVersion")); verify(dao,never()).insertOutbox(any());
    }
    @Test void replayKeepsOneOutcomeAndOneActionEventAndChangedUnionConflicts() {
        ready(); var first=persist(ACTION); var replay=prepare(ACTION);
        assertNotNull(replay.existing()); assertEquals(first,service.persist(replay,9,99));
        verify(store,times(1)).insertOutcome(any()); verify(dao,times(1)).insertOutbox(any());
        var changed=assertThrows(ChatDeliberationException.class,()->prepare(ACTION.replace("整理文档","改写文档")));
        assertEquals(ChatDeliberationException.Reason.CONFLICT,changed.reason());
    }
    @Test void staleSnapshotAndAdmissionNeverReachWrites() {
        ready(); snapshot.setOwnerJiacn("foreign");
        assertThrows(ChatDeliberationException.class,()->prepare(ACTION));
        snapshot.setOwnerJiacn("owner"); snapshot.setContextDigest("sha256:"+"d".repeat(64));
        assertThrows(ChatDeliberationException.class,()->prepare(ACTION));
        snapshot.setContextDigest(turn.getContextDigest());
        when(store.findAdmissionByRequest(scope,"request")).thenReturn(null);
        assertThrows(ChatDeliberationException.class,()->prepare(ACTION));
        verify(store,never()).insertOutcome(any()); verify(dao,never()).insertOutbox(any());
    }
    @Test void corruptedStoredDigestAndForeignScopeAreNotProjected() {
        ready(); persist(ACTION); turn.setFinalDigest("sha256:"+"f".repeat(64));
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"INSPECT"));
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(new ChatTypedDeliberationStore.Scope("0","other","client","42",1),"request","turn",1,"CHAT"));
    }
    @Test void textAndContractMismatchAreRejectedBeforeInsert() {
        ready(); assertThrows(ChatDeliberationException.class,()->service.prepare(turn,snapshot,"other",3,ACTION,null));
        assertThrows(ChatDeliberationException.class,()->service.prepare(turn,snapshot,"处理中",1,ACTION,null));
        assertThrows(ChatDeliberationException.class,()->service.prepare(turn,snapshot,"处理中",3,ACTION,"{}"));
        verify(store,never()).insertOutcome(any()); verify(dao,never()).insertOutbox(any());
    }
    @Test void duplicateWithHalfPersistedFinalIsAnError() {
        ready(); turn.setFinalDigest("sha256:"+"a".repeat(64));
        assertThrows(ChatDeliberationException.class,()->prepare(ACTION));
        assertThrows(ChatDeliberationException.class,()->service.readIfV3(scope,"request","turn",1,"CHAT"));
    }
    @Test void legacySnapshotsAreNotConvertedToAutonomousActions() {
        ready(); snapshot.setFactsManifestJson(CanonicalContextJson.write(Map.of("task",Map.of("id","task"),
                "typedDeliberation",Map.of("schemaVersion",1,"referenceMode","NONE","supportedOperations",List.of(),"availableSources",List.of()))));
        assertNull(service.readIfV3(scope,"request","turn",1,"CHAT"));
        assertThrows(ChatDeliberationException.class,()->prepare(ACTION)); verify(store,never()).insertOutcome(any());
    }
    @Test void inspectionPersistsExactInputReceiptAndReadsAfterRuntimeDisconnect() {
        ready();
        var registry=new TypedInspectionSessionRegistry();
        registry.register("inspection-session","0","owner","client","agent",Map.ofEntries(
                Map.entry("schemaVersion",1),Map.entry("contract","juyiting-typed-inspection-v1"),Map.entry("enabled",true),
                Map.entry("profileId","profile"),Map.entry("engineContractId","engine"),Map.entry("enginePolicyDigest",digest('a')),
                Map.entry("toolPolicyDigest",digest('b')),Map.entry("inputPolicyDigest",digest('c')),Map.entry("toolPolicy","STRICT_NO_TOOLS"),
                Map.entry("recovery","durable-inbox-turn-readback-v1"),Map.entry("supportedInputs",List.of(Map.of(
                        "mediaKind","text","mimeType","text/plain","carrier","DIRECT_TEXT","carrierContractDigest",digest('d'))))),()->true);
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("content_mime_type","text/plain",
                "content_hash","a".repeat(64),"byte_length",12L)));
        var contextService=new ChatTypedInspectionContextService(jdbc,mock(cn.jia.chat.archive.conversation.ChatConversationArchiveStore.class),
                registry,mock(ChatActionCapabilityService.class),true);
        var context=contextService.resolve(new ChatTypedInspectionContextService.Scope("0","owner","client","42",1,"task",3,"request",1,"agent"),
                List.of(new cn.jia.chat.api.ChatTypedInspectionWire.SourceSelector("TASK_LINKED_WORKSPACE_VERSION","file","1","INPUT",null,null)));
        turn.setRoute("INSPECT");snapshot.setRoute("INSPECT").setFactsManifestJson(CanonicalContextJson.write(Map.of(
                "task",Map.of("id","task"),"typedInspection",context.typedInspection())));
        when(store.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission("admission",scope,"key",digest('b'),
                digest('c'),"DISCUSSION","task",3,null,null,"request",1,8,"[\"turn\"]",context.admissionEnvelopeJson(),"ADMITTED",0,1,1));
        var sources=ChatTypedInspectionContextService.sources(context.admissionEnvelopeJson()).stream().map(source->Map.of(
                "sourceRefId",source.get("sourceRefId"),"sha256",source.get("sha256"),"byteLength",source.get("byteLength"),
                "carrier",source.get("carrier"),"contributionDigest",digest('e'))).toList();
        var receipt=new java.util.LinkedHashMap<String,Object>();receipt.put("schemaVersion",1);
        receipt.put("authorizationId",context.typedInspection().get("authorizationId"));receipt.put("manifestDigest",context.typedInspection().get("manifestDigest"));
        receipt.put("sources",sources);receipt.put("inputDigest",ChatDeliberationService.digest(receipt));
        receipt.put("engineThreadId","native-thread");receipt.put("engineTurnId","native-turn");
        var inspecting=new ChatActionFinalService(store,dao,registry);
        assertThrows(ChatDeliberationException.class,()->inspecting.prepare(turn,snapshot,"处理中",3,ANSWER,null));
        var prepared=inspecting.prepare(turn,snapshot,"处理中",3,ANSWER,CanonicalContextJson.write(receipt));
        inspecting.persist(prepared,9,12);turn.setFinalMessageId(9L).setFinalDigest(prepared.validated().finalDigest());
        registry.remove("inspection-session");
        var read=inspecting.readIfV3(scope,"request","turn",1,"INSPECT");
        assertEquals(receipt.get("inputDigest"),map(map(read.get("inspection")).get("inputSummary")).get("inputDigest"));
        assertFalse(CanonicalContextJson.write(read).contains("native-thread"));
        assertThrows(ChatDeliberationException.class,()->inspecting.prepare(turn,snapshot,"处理中",3,ANSWER,CanonicalContextJson.write(receipt)));
    }
    private static String digest(char c) { return "sha256:"+String.valueOf(c).repeat(64); }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
}
