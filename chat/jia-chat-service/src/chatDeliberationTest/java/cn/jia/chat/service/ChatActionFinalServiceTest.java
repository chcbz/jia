package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
        when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(snapshot);
    }
    private ChatActionFinalService.Prepared prepare(String raw) { return service.prepare(turn,snapshot,"处理中",3,raw,null); }
    private Map<String,Object> persist(String raw) {
        var prepared=prepare(raw); var view=service.persist(prepared,9,12);
        turn.setFinalMessageId(9L).setFinalDigest(prepared.validated().finalDigest()); return view;
    }
    @Test void actionPersistsItsOwnKindAndIndependentStableOutboxWithoutConsentOrProposal() {
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
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) { return (Map<String,Object>)value; }
}
