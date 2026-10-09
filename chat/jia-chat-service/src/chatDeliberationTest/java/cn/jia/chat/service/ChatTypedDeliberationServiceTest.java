package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatContextSnapshotEntity;
import cn.jia.chat.deliberation.ChatTurnEntity;
import cn.jia.chat.deliberation.ChatTypedDeliberationStore;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatTypedDeliberationServiceTest {
    private final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
    private final ChatTypedDeliberationService service=new ChatTypedDeliberationService(store);
    private final ChatTypedDeliberationStore.Scope scope=new ChatTypedDeliberationStore.Scope("0","owner","client","42",1);

    @Test void answerPersistsCompleteCanonicalDigestAndImmutableProjection() {
        ready("[]");when(store.insertOutcome(any())).thenReturn(1);
        var prepared=service.prepare(turn(),snapshot(facts(List.of(),List.of())),"答复🌏",1,
                "{\"schemaVersion\":1.0,\"kind\":\"ANSWER\",\"text\":\"答复🌏\",\"clarification\":null,\"proposal\":null}");
        var persisted=service.persist(prepared,91,10);
        assertEquals("ANSWER",persisted.view().kind());assertNull(persisted.view().clarification());
        assertTrue(persisted.view().finalDigest().matches("sha256:[0-9a-f]{64}"));
        assertThrows(UnsupportedOperationException.class,()->persisted.eventView().put("kind","CLARIFY"));
        verify(store).insertOutcome(argThat(row->row.assistantMessageId()==91&&row.bindingJson().contains("\"dispatchId\":\"dispatch\"")&&row.outcomeJson().contains("答复🌏")));
        verify(store,never()).insertPending(any());verify(store,never()).insertProposal(any());
    }

    @Test void clarificationCreatesOpenQuestionAndChangedUnionConflictsOnReplay() {
        ready("[]");when(store.insertOutcome(any())).thenReturn(1);when(store.insertPending(any())).thenReturn(1);
        when(store.findPendingByOutcome(eq(scope),anyString(),eq(false))).thenReturn(
                new ChatTypedDeliberationStore.PendingQuestion("pending","out",scope,"OPEN",0,
                        "请选择当前图片","[\"SOURCE_SELECTION\"]",null,null,null,11,11));
        var prepared=service.prepare(turn(),snapshot(facts(List.of(),List.of())),"需要哪张图？",1,
                "{\"schemaVersion\":1,\"kind\":\"CLARIFY\",\"text\":\"需要哪张图？\",\"clarification\":{\"question\":\"请选择当前图片\",\"requiredFacts\":[\"SOURCE_SELECTION\"]},\"proposal\":null}");
        service.persist(prepared,92,11);
        verify(store).insertPending(argThat(row->"OPEN".equals(row.state())&&row.stateVersion()==0&&row.replyRequestId()==null));
        var existing=new ChatTypedDeliberationStore.Outcome("out",scope,"request",1,"turn","task",3,92,
                prepared.validated().finalDigest(),"CLARIFY","需要哪张图？","{}","{}","{}","[]",11);
        when(store.findOutcomeByTurn(scope,"turn",true)).thenReturn(existing);
        assertEquals(ChatDeliberationException.Reason.CONFLICT,assertThrows(ChatDeliberationException.class,()->service.prepare(
                turn().setFinalDigest(prepared.validated().finalDigest()),snapshot(facts(List.of(),List.of())),"已经知道了",1,
                "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"已经知道了\",\"clarification\":null,\"proposal\":null}")).reason());
    }

    @Test void editProposalProjectsOnlyServerFrozenSelectorAndParent() {
        Map<String,Object> source=new java.util.LinkedHashMap<>();
        source.put("sourceKind","CURRENT_CONVERSATION_ASSET");source.put("selectorKind","CURRENT_CONVERSATION_ASSET");
        source.put("selector",selector());source.put("mediaType","image");source.put("contentMimeType","image/png");
        source.put("contentHash","a".repeat(64));source.put("byteLength","12");
        source.put("parentRequestId","prior-request");source.put("parentStepId","prior-step");
        String sourceRef=ChatTypedDeliberationContextService.sourceRefId(
                new ChatTypedDeliberationContextService.Scope("0","owner","client","42",1),source);
        source.put("sourceRefId",sourceRef);String catalog=CanonicalContextJson.write(List.of(source));
        ready(catalog);when(store.insertOutcome(any())).thenReturn(1);when(store.insertProposal(any())).thenReturn(1);
        when(store.findProposalByOutcome(eq(scope),anyString())).thenReturn(new ChatTypedDeliberationStore.Proposal(
                "proposal","out",scope,"PROPOSED",0,"EDIT_IMAGE","把鸟换成黄鹂",
                CanonicalContextJson.write(List.of(sourceRef)),CanonicalContextJson.write(List.of(selector())),
                "prior-request","prior-step",12));
        var prepared=service.prepare(turn(),snapshot(facts(List.of("EDIT_IMAGE"),List.of(Map.of("sourceRefId",sourceRef,"kind","CURRENT_CONVERSATION_ASSET","mediaType","image")))),"可以把鸟换成黄鹂",1,
                "{\"schemaVersion\":1,\"kind\":\"EXECUTION_PROPOSAL\",\"text\":\"可以把鸟换成黄鹂\",\"clarification\":null,\"proposal\":{\"operation\":\"EDIT_IMAGE\",\"instruction\":\"把鸟换成黄鹂\",\"sourceRefIds\":[\""+sourceRef+"\"]}}");
        service.persist(prepared,93,12);
        verify(store).insertProposal(argThat(row->"prior-request".equals(row.parentRequestId())&&"prior-step".equals(row.parentStepId())&&row.sourceSelectorsJson().contains("CURRENT_CONVERSATION_ASSET")));
    }


    @Test void admissionReceiptOrCatalogMismatchFailsClosed() {
        ready("[]");
        when(store.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission(
                "admission",scope,"key","sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),
                "DISCUSSION","task",3,null,null,"request",1,8,"[\"other-turn\"]","[]","RUNNING",0,7,1));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,assertThrows(ChatDeliberationException.class,
                ()->service.prepare(turn(),snapshot(facts(List.of(),List.of())),"答复",1,
                        "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"答复\",\"clarification\":null,\"proposal\":null}")).reason());

        Map<String,Object> forged=new java.util.LinkedHashMap<>();forged.put("sourceRefId","source_forged");
        forged.put("sourceKind","CURRENT_CONVERSATION_ASSET");forged.put("selectorKind","CURRENT_CONVERSATION_ASSET");
        forged.put("selector",selector());forged.put("mediaType","image");forged.put("contentMimeType","image/png");
        forged.put("contentHash","a".repeat(64));forged.put("byteLength","12");forged.put("parentRequestId","r");forged.put("parentStepId","s");
        ready(CanonicalContextJson.write(List.of(forged)));
        assertEquals(ChatDeliberationException.Reason.PERSISTENCE_ERROR,assertThrows(ChatDeliberationException.class,
                ()->service.prepare(turn(),snapshot(facts(List.of("EDIT_IMAGE"),List.of(Map.of(
                        "sourceRefId","source_forged","kind","CURRENT_CONVERSATION_ASSET","mediaType","image")))),"答复",1,
                        "{\"schemaVersion\":1,\"kind\":\"ANSWER\",\"text\":\"答复\",\"clarification\":null,\"proposal\":null}")).reason());
    }

    @Test void plainTurnForbidsSidecarAndTypedTurnRequiresPair() {
        ChatContextSnapshotEntity plain=snapshot(Map.of("task",Map.of("id","task")));
        assertNull(service.prepare(turn(),plain,"普通答复",null,null));
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,assertThrows(ChatDeliberationException.class,
                ()->service.prepare(turn(),plain,"普通答复",1,"{}" )).reason());
        assertEquals(ChatDeliberationException.Reason.INVALID_REQUEST,assertThrows(ChatDeliberationException.class,
                ()->service.prepare(turn(),snapshot(facts(List.of(),List.of())),"答复",null,null)).reason());
    }

    private void ready(String catalog){when(store.findAdmissionByRequest(scope,"request")).thenReturn(new ChatTypedDeliberationStore.Admission(
            "admission",scope,"key","sha256:"+"b".repeat(64),"sha256:"+"c".repeat(64),"DISCUSSION","task",3,null,null,"request",1,8,"[\"turn\"]",catalog,"RUNNING",0,7,1));}
    private static ChatTurnEntity turn(){return new ChatTurnEntity().setTenantId("0").setOwnerJiacn("owner").setClientId("client")
            .setConversationId("42").setConversationGeneration(1L).setRequestId("request").setRequestRevision(1L)
            .setTurnId("turn").setDispatchId("dispatch").setSnapshotId("snapshot").setContextDigest("sha256:"+"a".repeat(64))
            .setTargetAgentId("agent").setRoute("CHAT");}
    private static ChatContextSnapshotEntity snapshot(Map<String,Object> facts){return new ChatContextSnapshotEntity().setFactsManifestJson(CanonicalContextJson.write(facts));}
    private static Map<String,Object> facts(List<String> operations,List<Map<String,Object>> sources){return Map.of(
            "task",Map.of("id","task"),"typedDeliberation",Map.of("schemaVersion",1,"referenceMode",sources.isEmpty()?"NONE":"AVAILABLE","supportedOperations",operations,"availableSources",sources));}
    private static Map<String,Object> selector(){var m=new java.util.LinkedHashMap<String,Object>();m.put("kind","CURRENT_CONVERSATION_ASSET");m.put("fileId",null);m.put("version",null);m.put("purpose",null);m.put("assetId","asset");m.put("assetRevision","1");return m;}
}
