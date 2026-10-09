package cn.jia.chat.service;

import cn.jia.chat.deliberation.*;
import cn.jia.chat.handler.TypedInspectionSessionRegistry;
import cn.jia.core.util.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exact Node-native final from API-emitted complete wire. No model/DB/production claim. */
class ChatMixedMaterialReceiptTest {
    @Test void actualNativeInspectionReceiptPassesServerSnapshotAdmissionAndProfileChecks() throws Exception {
        var fixture=fixture();var f=new BoundInspection(fixture);
        var result=f.prepare(map(map(fixture.get("final")).get("extra")));
        assertEquals("ANSWER",result.validated().interactionOutcome().kind());
        assertEquals(32,result.validated().inspectionInputReceipt().sources().size());
        assertEquals("已查阅全部资料。",result.validated().content());
        assertEquals(f.wire.get("contextHash"),result.validated().binding().get("contextDigest"));
        assertEquals(f.wire.get("requestId"),result.validated().binding().get("requestId"));
        assertEquals("mixed-api-thread",result.validated().inspectionInputReceipt().engineThreadId());
        assertEquals("mixed-api-turn",result.validated().inspectionInputReceipt().engineTurnId());
        verify(f.store,never()).insertOutcome(any());
    }

    @Test void actualNativeChatActionMatchesAutomaticChildWithoutFabricatedUserApproval() throws Exception {
        var fixture=fixture();var wire=map(fixture.get("chat"));var result=map(fixture.get("chatFinal"));
        var validated=ChatActionFinalValidator.validateJson(binding(wire),map(wire.get("factsManifest")).get("typedDeliberation"),
                null,(String)result.get("content"),(Integer)result.get("outcomeContractVersion"),
                CanonicalContextJson.write(result.get("interactionOutcome")),null);
        assertEquals("ACTION_REQUEST",validated.interactionOutcome().kind());
        assertEquals(32,validated.interactionOutcome().action().sourceRefIds().size());
        var child=map(fixture.get("inspection"));var childFacts=map(child.get("factsManifest"));
        assertEquals(validated.interactionOutcome().action().instruction(),map(childFacts.get("actionContinuation")).get("instruction"));
        assertEquals("901",map(childFacts.get("actionContinuation")).get("originalUserMessageId"));
        assertEquals(wire.get("content"),child.get("content"));
        assertEquals(ChatDeliberationService.inspectionContinuationRequestId(ChatActionFinalValidator.actionEventId(validated)),child.get("requestId"));
        assertNull(validated.inspectionInputReceipt());
    }

    @Test void changedMissingOrInventedActualByteReceiptsAreRejectedByServer() throws Exception {
        for(String change:List.of("source","hash","length","manifest","authorization","missing","duplicate","digest")) {
            var fixture=fixture();var f=new BoundInspection(fixture);
            var extra=map(map(fixture.get("final")).get("extra"));var receipt=map(extra.get("inspectionInputReceipt"));
            var sources=list(receipt.get("sources"));var source=map(sources.getFirst());
            switch(change) {
                case "source" -> source.put("sourceRefId","foreign-source");
                case "hash" -> source.put("sha256","0".repeat(64));
                case "length" -> source.put("byteLength","999");
                case "manifest" -> receipt.put("manifestDigest","sha256:"+"0".repeat(64));
                case "authorization" -> receipt.put("authorizationId","inspection_"+"0".repeat(40));
                case "missing" -> sources.removeLast();
                case "duplicate" -> sources.set(1,new LinkedHashMap<>(source));
                case "digest" -> receipt.put("inputDigest","sha256:"+"0".repeat(64));
            }
            assertThrows(ChatDeliberationException.class,()->f.prepare(extra),change);
            verify(f.store,never()).insertOutcome(any());
        }
    }

    static class BoundInspection {
        final Map<String,Object> wire;
        final ChatTypedDeliberationStore store=mock(ChatTypedDeliberationStore.class);
        final ChatTurnEntity turn;
        final ChatContextSnapshotEntity snapshot;
        final ChatActionFinalService service;
        final String content;
        BoundInspection(Map<String,Object> fixture) {
            wire=map(fixture.get("inspection"));var facts=map(wire.get("factsManifest"));var typed=map(facts.get("typedInspection"));
            content=(String)map(fixture.get("final")).get("content");
            assertEquals(wire.get("contextHash"),ChatDeliberationService.digest(Map.of("sourceVector",wire.get("sourceVector"),"facts",facts)));
            var b=binding(wire);
            var scope=new ChatTypedDeliberationStore.Scope((String)b.get("tenantId"),(String)b.get("ownerJiacn"),
                    (String)b.get("clientId"),(String)b.get("conversationId"),Long.parseLong((String)b.get("conversationGeneration")));
            turn=new ChatTurnEntity().setTenantId(scope.tenantId()).setOwnerJiacn(scope.ownerJiacn()).setClientId(scope.clientId())
                    .setConversationId(scope.conversationId()).setConversationGeneration(scope.conversationGeneration())
                    .setRequestId((String)b.get("requestId")).setRequestRevision(Long.parseLong((String)b.get("requestRevision")))
                    .setTurnId((String)b.get("turnId")).setDispatchId((String)b.get("dispatchId"))
                    .setSnapshotId((String)b.get("snapshotId")).setContextDigest((String)b.get("contextDigest"))
                    .setTargetAgentId((String)b.get("targetAgentId")).setRoute("INSPECT");
            snapshot=new ChatContextSnapshotEntity().setTenantId(turn.getTenantId()).setOwnerJiacn(turn.getOwnerJiacn()).setClientId(turn.getClientId())
                    .setConversationId(turn.getConversationId()).setConversationGeneration(turn.getConversationGeneration())
                    .setRequestId(turn.getRequestId()).setRequestRevision(turn.getRequestRevision()).setSnapshotId(turn.getSnapshotId())
                    .setTargetAgentId(turn.getTargetAgentId()).setRoute("INSPECT").setContextDigest(turn.getContextDigest())
                    .setFactsManifestJson(CanonicalContextJson.write(facts));
            String envelope=CanonicalContextJson.write(Map.of("schemaVersion",1,"contract",typed.get("contract"),"purpose","INSPECT","typedInspection",typed));
            when(store.findAdmissionByRequest(scope,turn.getRequestId())).thenReturn(new ChatTypedDeliberationStore.Admission(
                    "admission",scope,"key","sha256:"+"a".repeat(64),"sha256:"+"b".repeat(64),"DISCUSSION",(String)wire.get("taskId"),3,
                    null,null,turn.getRequestId(),turn.getRequestRevision(),901,CanonicalContextJson.write(List.of(turn.getTurnId())),envelope,"ADMITTED",0,1,1));
            var sessions=new TypedInspectionSessionRegistry();var profile=map(map(typed.get("manifest")).get("profile"));
            Map<String,Object> declaration=new LinkedHashMap<>(profile);
            declaration.putAll(Map.of("schemaVersion",1,"contract",typed.get("contract"),"enabled",true,
                    "toolPolicy","STRICT_NO_TOOLS","recovery","durable-inbox-turn-readback-v1",
                    "supportedInputs",ChatMixedMaterialWireTest.supportedInputs()));
            sessions.register("session",scope.tenantId(),scope.ownerJiacn(),scope.clientId(),turn.getTargetAgentId(),declaration,()->true);
            service=new ChatActionFinalService(store,mock(ChatDeliberationDao.class),sessions);
        }
        ChatActionFinalService.Prepared prepare(Map<String,Object> extra) {
            return service.prepare(turn,snapshot,content,(Integer)extra.get("outcomeContractVersion"),
                    CanonicalContextJson.write(extra.get("interactionOutcome")),CanonicalContextJson.write(extra.get("inspectionInputReceipt")));
        }
    }
    private static Map<String,Object> binding(Map<String,Object> wire) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String key:List.of("tenantId","ownerJiacn","clientId","conversationId","conversationGeneration","requestId",
                "requestRevision","turnId","dispatchId","targetAgentId","route","taskId")) result.put(key,wire.get(key));
        result.put("snapshotId",wire.get("contextSnapshotId"));result.put("contextDigest",wire.get("contextHash"));return result;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> fixture() throws Exception {
        try(var stream=ChatMixedMaterialReceiptTest.class.getResourceAsStream("/mixed-material-client-final-v3.json")) {
            assertNotNull(stream);return JsonUtil.getMapper().readValue(stream,Map.class);
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) {return (List<Object>)value;}
}
