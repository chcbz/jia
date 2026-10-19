package cn.jia.chat.api;
import cn.jia.chat.service.ChatSelectedOutputFinalizationService;
import cn.jia.agent.service.SelectedOutputFinalizationDigest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatFinalizationWireTest {
    @Test void completedReceiptHasPublicStateAndExactSelectionWithoutChangingOriginalDigest() {
        String hash="b".repeat(64);var source=new SelectedOutputFinalizationDigest.MessageSource("turn","1","snapshot","sha256:"+hash);
        var selection=new ChatSelectedOutputFinalizationService.Selection("r",null,null,hash,"标题","成果",source);
        var receipt=new ChatSelectedOutputFinalizationService.Receipt("op","t","c","completed","2","TASK_COMPLETED","0","0",List.of(selection),"d","accepted","completed","3",null,false);
        var wire=ChatFinalizationWire.receipt(receipt);assertEquals("COMPLETED",wire.get("state"));assertEquals("ACCEPTED",wire.get("deliveryState"));assertEquals(Set.of("operationId","taskId","conversationId","state","stateVersion","stage","expectedTaskVersion","expectedAssignmentRevision","selectedOutputs","deliveryId","deliveryState","taskState","taskVersion","errorCode","retryable"),wire.keySet());
        var output=(Map<?,?>)((List<?>)wire.get("selectedOutputs")).getFirst();assertFalse(output.containsKey("stepId"));assertEquals(hash,((Map<?,?>)output.get("messageSource")).get("finalSha256"));assertEquals("sha256:"+hash,source.finalDigest());
    }
    @Test void formalSourceReadHasFixedStringVersionAndNoFabricatedConversationAuthority() {
        var source=new SelectedOutputFinalizationDigest.FormalArtifactSource("old-delivery","old-artifact",2);
        var selection=new ChatSelectedOutputFinalizationService.Selection(null,null,null,"a".repeat(64),"保留项","final",null,source);
        var receipt=new ChatSelectedOutputFinalizationService.Receipt("op","t","c","completed","2","SUBMITTED","7","1",List.of(selection),"d","submitted","reviewing","9",null,false);
        var wire=ChatFinalizationWire.receipt(receipt);var output=(Map<?,?>)((List<?>)wire.get("selectedOutputs")).getFirst();
        assertEquals(Set.of("formalArtifactSource","sha256","title","purpose"),output.keySet());
        assertEquals(Map.of("sourceDeliveryId","old-delivery","sourceArtifactId","old-artifact","sourceArtifactVersion","2"),output.get("formalArtifactSource"));
        var hybrid=new ChatSelectedOutputFinalizationService.Selection("forged",null,null,"a".repeat(64),"保留项","final",null,source);
        var invalid=new ChatSelectedOutputFinalizationService.Receipt("op","t","c","completed","2","SUBMITTED","7","1",List.of(hybrid),"d","submitted","reviewing","9",null,false);
        assertThrows(IllegalStateException.class,()->ChatFinalizationWire.receipt(invalid));
    }
    @Test void unknownPersistedStateIsNotCapitalizedIntoSuccess() {
        var r=new ChatSelectedOutputFinalizationService.Receipt("op","t","c","mystery","1","PROMOTING","0","0",List.of(),null,null,"open","0",null,false);
        assertThrows(IllegalStateException.class,()->ChatFinalizationWire.receipt(r));
    }
}
