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
        var wire=ChatFinalizationWire.receipt(receipt);assertEquals("COMPLETED",wire.get("state"));assertEquals("ACCEPTED",wire.get("deliveryState"));assertEquals(16,wire.size());
        var output=(Map<?,?>)((List<?>)wire.get("selectedOutputs")).getFirst();assertFalse(output.containsKey("stepId"));assertEquals(hash,((Map<?,?>)output.get("messageSource")).get("finalSha256"));assertEquals("sha256:"+hash,source.finalDigest());
    }
    @Test void unknownPersistedStateIsNotCapitalizedIntoSuccess() {
        var r=new ChatSelectedOutputFinalizationService.Receipt("op","t","c","mystery","1","PROMOTING","0","0",List.of(),null,null,"open","0",null,false);
        assertThrows(IllegalStateException.class,()->ChatFinalizationWire.receipt(r));
    }
}
