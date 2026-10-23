package cn.jia.chat.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatMaterialsImageContractTest {
    private static Map<String,Object> source(String mime) {
        return Map.of("sourceRefId","source_1","sourceKind","TASK_WORKSPACE_FILE",
                "contentHash","a".repeat(64),"byteLength","123","contentMimeType",mime);
    }
    private static Map<String,Object> registration(String mime) {
        return Map.of("materialsInput",Map.of("schemaVersion",1,"supportedInputs",List.of(
                Map.of("mediaKind","image","mimeType",mime,"carrier","LOCAL_IMAGE",
                        "carrierContractDigest","sha256:"+"b".repeat(64)))));
    }
    @Test void supportedImagesKeepOriginalHashAndMime() {
        for (String mime:List.of("image/jpeg","image/png","image/webp","image/gif","image/bmp",
                "image/tiff","image/heic","image/heif","image/avif")) {
            var result=ChatMaterialsInputContract.candidates(registration(mime),List.of(source(mime)));
            var inputs=(List<?>)result.get("sources");assertEquals(1,inputs.size());
            var input=(Map<?,?>)inputs.getFirst();assertEquals(mime,input.get("mimeType"));
            assertEquals("a".repeat(64),input.get("sha256"));
        }
    }
    @Test void unsupportedImageCannotSilentlyBecomeAnEmptyTextTurn() {
        for (var registration:List.of(Map.<String,Object>of(),registration("image/png"))) {
            var error=assertThrows(ChatDeliberationException.class,()->
                    ChatMaterialsInputContract.candidates(registration,List.of(source("image/jpeg"))));
            assertTrue(error.getMessage().contains("已收到图片资料"));
        }
    }
    @Test void noMaterialsDoesNotNeedAnImageCapability() {
        assertEquals(List.of(),ChatMaterialsInputContract.candidates(Map.of(),List.of()).get("sources"));
    }
}
