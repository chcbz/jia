package cn.jia.chat.service;

import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ChatCompletedMessageSourceServiceTest {
    static final String TEXT="原始文字\n第二行  \n";
    static final class Fixture {
        final ChatDeliberationService deliberations=mock(ChatDeliberationService.class);
        final ChatInteractionStepStore steps=mock(ChatInteractionStepStore.class);
        final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
        final ChatDeliberationDao dao=mock(ChatDeliberationDao.class);
        final TypedInspectionSessionRegistry sessions=mock(TypedInspectionSessionRegistry.class);
        final ChatCompletedMessageSourceStore messages=mock(ChatCompletedMessageSourceStore.class);
        final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);
        final ChatSelectedOutputFinalizationService.Scope owner=new ChatSelectedOutputFinalizationService.Scope("0","client","owner");
        final AtomicReference<ChatTypedDeliberationStore.Outcome> row=new AtomicReference<>();
        final ChatTurnEntity turn=new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
                .setConversationId("42").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
                .setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot").setContextDigest("sha256:"+"a".repeat(64))
                .setTargetAgentId("agent").setRoute("CHAT");
        final ChatContextSnapshotEntity snapshot=new ChatContextSnapshotEntity().setSnapshotId("snapshot")
                .setTenantId("0").setOwnerJiacn("owner").setClientId("client").setConversationId("42").setConversationGeneration(1L)
                .setRequestId("request").setRequestRevision(1L).setTargetAgentId("agent").setRoute("CHAT")
                .setContextDigest("sha256:"+"a".repeat(64)).setFactsManifestJson(CanonicalContextJson.write(Map.of("task",Map.of("id","task"),
                        "typedDeliberation",Map.of("schemaVersion",3,"availableSources",List.of(),"inspectedSourceRefIds",List.of(),
                                "availableActions",List.of(Map.of("actionId","write-document","kind","EXECUTE","operation","WRITE_DOCUMENT",
                                        "inputMediaTypes",List.of("text","file"),"minSources",0,"maxSources",32))))));
        final ChatActionFinalService finals=new ChatActionFinalService(store,dao,sessions);
        final ChatCompletedMessageSourceService service=new ChatCompletedMessageSourceService(deliberations,steps,store,finals,messages);
        ChatCompletedMessageSourceService.Command command;
        Fixture(){this("ANSWER");}
        Fixture(String kind) {
            when(store.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission("admission",scope,"key",
                    "sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),"DISCUSSION","task",3,null,null,"request",1,8,"[\"turn\"]","[]","ADMITTED",0,1,1));
            when(store.findOutcomeByTurn(eq(scope),eq("turn"),anyBoolean())).thenAnswer(i->row.get());
            when(store.findOutcomeByRequest(scope,"request")).thenAnswer(i->row.get());
            when(store.insertOutcome(any())).thenAnswer(i->{row.set(i.getArgument(0));return 1;});
            when(store.insertPending(any())).thenReturn(1);
            when(dao.insertOutbox(any())).thenReturn(1);
            when(dao.findTurn("0","owner","client","turn")).thenReturn(turn);
            when(dao.findSnapshot("0","owner","client","snapshot")).thenReturn(snapshot);
            var raw=new LinkedHashMap<String,Object>();raw.put("schemaVersion",3);raw.put("kind",kind);raw.put("text",TEXT);
            raw.put("clarification","CLARIFY".equals(kind)?Map.of("question","用途？","requiredFacts",List.of("用途")):null);
            raw.put("action","ACTION_REQUEST".equals(kind)?Map.of("actionId","write-document","instruction","整理","sourceRefIds",List.of()):null);
            var prepared=finals.prepare(turn,snapshot,TEXT,3,CanonicalContextJson.write(raw),null);
            finals.persist(prepared,9,12);turn.setFinalMessageId(9L).setFinalDigest(prepared.validated().finalDigest());
            when(deliberations.getRequest("0","owner","client","request")).thenReturn(request("COMPLETED","FINAL_PERSISTED","9"));
            when(steps.findSteps("0","owner","client","request",1)).thenReturn(List.of(new ChatInteractionStepStore.Step(
                    "step","0","owner","client","request",1,1,"42",1,"task",3,"grant",2,"agent","CHAT","COMPLETED",1,"digest",1,2)));
            var metadata=Map.of("requestId","request","turnId","turn","contextSnapshotId","snapshot","finalDigest",row.get().finalDigest(),
                    "dispatchId","dispatch","targetAgentId","agent","agentId","agent","route","CHAT","outcomeId",row.get().outcomeId());
            when(messages.find("0","owner","client","task","42",1,9)).thenReturn(new ChatCompletedMessageSourceStore.Message(TEXT,
                    CanonicalContextJson.write(metadata),"ASSISTANT","agent"));
            command=new ChatCompletedMessageSourceService.Command("task","42",3,"request","step",
                    new SelectedOutputFinalizationDigest.MessageSource("turn","9","snapshot",row.get().finalDigest()),sha(TEXT),"Text","final");
            clearInvocations(store,dao,sessions,steps,messages,deliberations);
        }
        ChatDeliberationService.RequestView request(String state,String turnState,String messageId) {
            return new ChatDeliberationService.RequestView("request","1","42","1","8",state,"1",List.of(
                    new ChatDeliberationService.TurnView("turn","request","1","42","1","agent","snapshot","dispatch","CHAT",turnState,
                            "1","0",null,messageId,"1","2")),List.of(new ChatDeliberationService.StepView("step","1","task","3","agent",
                    "CHAT","COMPLETED","1",null,null,null)));
        }
    }
    @Test void actualFinalReaderAuthenticatesSnapshotAndPreservesOriginalUtf8WithoutExecutionOrWrites() {
        Fixture f=new Fixture();var resolved=f.service.resolve(f.owner,f.command);
        assertEquals(1,resolved.generation());assertEquals("agent",resolved.targetAgentId());assertEquals("grant",resolved.grantId());
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8),resolved.output().bytes());
        assertNull(resolved.output().executionId());assertNull(resolved.output().runId());assertNull(resolved.output().outputId());
        verify(f.store,never()).insertOutcome(any());verify(f.steps,never()).insertStep(any());verify(f.dao,never()).insertOutbox(any());
        verifyNoInteractions(f.sessions);
    }
    @Test void incompleteRequestsTurnsAndDifferentMessageCannotBePromoted() {
        for(var request:List.of(new Fixture().request("RUNNING","FINAL_PERSISTED","9"),
                new Fixture().request("COMPLETED","STREAMING","9"),new Fixture().request("COMPLETED","FINAL_PERSISTED","10"))) {
            Fixture f=new Fixture();when(f.deliberations.getRequest("0","owner","client","request")).thenReturn(request);
            assertThrows(ChatDeliberationException.class,()->f.service.resolve(f.owner,f.command));verifyNoInteractions(f.messages);
        }
    }
    @Test void clarificationAndActionAreNotDeliverableEvenWithCompletedMessage() {
        for(String kind:List.of("CLARIFY","ACTION_REQUEST")) {
            Fixture f=new Fixture(kind);assertThrows(ChatDeliberationException.class,()->f.service.resolve(f.owner,f.command));
            verifyNoInteractions(f.messages,f.sessions);verify(f.store,never()).insertOutcome(any());
        }
    }
    @Test void realSnapshotAndFinalDigestCorruptionFailClosed() {
        Fixture f=new Fixture();f.snapshot.setContextDigest("sha256:"+"d".repeat(64));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,
                assertThrows(ChatDeliberationException.class,()->f.service.resolve(f.owner,f.command)).reason());
        verifyNoInteractions(f.messages);
        Fixture g=new Fixture();g.turn.setFinalDigest("sha256:"+"e".repeat(64));
        assertThrows(ChatDeliberationException.class,()->g.service.resolve(g.owner,g.command));verifyNoInteractions(g.messages);
    }
    @Test void messageAclContentMetadataAndDisplayedHashMustMatch() {
        Fixture f=new Fixture();assertThrows(ChatDeliberationException.class,()->f.service.resolve(
                new ChatSelectedOutputFinalizationService.Scope("0","client","another"),f.command));
        for(var stored:Arrays.asList(null,new ChatCompletedMessageSourceStore.Message(TEXT,"{}","ASSISTANT","agent"),
                new ChatCompletedMessageSourceStore.Message("changed","{}","ASSISTANT","agent"),
                new ChatCompletedMessageSourceStore.Message(TEXT,"not-json","ASSISTANT","agent"))) {
            Fixture g=new Fixture();when(g.messages.find("0","owner","client","task","42",1,9)).thenReturn(stored);
            assertThrows(ChatDeliberationException.class,()->g.service.resolve(g.owner,g.command));
        }
        Fixture h=new Fixture();var c=h.command;
        var changed=new ChatCompletedMessageSourceService.Command(c.taskId(),c.conversationId(),c.expectedAssignmentRevision(),c.requestId(),
                c.stepId(),c.messageSource(),"0".repeat(64),c.title(),c.purpose());
        assertThrows(ChatDeliberationException.class,()->h.service.resolve(h.owner,changed));
    }
    private static String sha(String text) {try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
}
