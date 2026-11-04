package cn.jia.chat.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMaterialsLocalFileContractTest {
    private static Map<String,Object> format(String mime,String carrier,String media) {
        return Map.of("mimeType",mime,"carrier",carrier,"mediaKind",media,
                "carrierContractDigest","sha256:"+"b".repeat(64));
    }
    private static Map<String,Object> source(String mime) {
        return Map.of("sourceRefId","source_1","sourceKind","TASK_WORKSPACE_FILE",
                "contentHash","a".repeat(64),"byteLength","123","contentMimeType",mime);
    }
    @Test void genericFilesRetainActualMimeAndAuthorizedHash() {
        var declaration=Map.of("schemaVersion",1,"supportedInputs",List.of(format("*/*","LOCAL_FILE","file")));
        for(String mime:List.of("text/plain","application/pdf","application/x-custom")) {
            var result=ChatMaterialsInputContract.candidates(Map.of("materialsInput",declaration),List.of(source(mime)));
            var input=(Map<?,?>)((List<?>)result.get("sources")).getFirst();
            assertEquals(mime,input.get("mimeType"));assertEquals("LOCAL_FILE",input.get("carrier"));
            assertEquals("a".repeat(64),input.get("sha256"));
            var bound=ChatMaterialsInputContract.bind(result,"tenant","owner","client","conversation",1,"task","request","turn","agent");
            assertEquals(List.of("source_1"),ChatMaterialsInputContract.refs(bound));
        }
    }
    @Test void exactImageTransportWinsRegardlessOfDeclarationOrder() {
        var generic=format("*/*","LOCAL_FILE","file");var image=format("image/png","LOCAL_IMAGE","image");
        for(var formats:List.of(List.of(generic,image),List.of(image,generic))) {
            var registration=Map.<String,Object>of("materialsInput",Map.of("schemaVersion",1,"supportedInputs",formats));
            var items=(List<?>)ChatMaterialsInputContract.candidates(registration,List.of(source("image/png"))).get("sources");
            assertEquals(1,items.size());assertEquals("LOCAL_IMAGE",((Map<?,?>)items.getFirst()).get("carrier"));
        }
    }
    @Test void wildcardIsFileDeliveryNotAClaimOfUniversalImageOrTextDecoding() {
        for(var item:List.of(format("*/*","LOCAL_IMAGE","image"),format("*/*","DIRECT_TEXT","text"))) {
            assertThrows(IllegalArgumentException.class,()->ChatMaterialsInputContract.declaration(
                    Map.of("schemaVersion",1,"supportedInputs",List.of(item))));
        }
        assertEquals(List.of(),ChatMaterialsInputContract.candidates(Map.of(),List.of()).get("sources"));
    }
}
