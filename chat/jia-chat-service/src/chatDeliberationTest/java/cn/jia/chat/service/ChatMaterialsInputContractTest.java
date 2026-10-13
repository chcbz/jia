package cn.jia.chat.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ChatMaterialsInputContractTest {
    @Test void nativeSourceDirectoryIsExactAndUnsupportedFormatIsNotDelivered() {
        var profile=Map.<String,Object>of("schemaVersion",1,"supportedInputs",List.of(Map.of("mediaKind","image","mimeType","image/png","carrier","LOCAL_IMAGE","carrierContractDigest","sha256:"+"b".repeat(64))));
        var image=Map.<String,Object>of("sourceRefId","source_image","sourceKind","TASK_WORKSPACE_FILE","contentHash","a".repeat(64),"byteLength","3","contentMimeType","image/png");
        var opaque=Map.<String,Object>of("sourceRefId","source_pdf","sourceKind","TASK_WORKSPACE_FILE","contentHash","c".repeat(64),"byteLength","4","contentMimeType","application/pdf");
        var candidates=ChatMaterialsInputContract.candidates(Map.of("materialsInput",profile),List.of(image,opaque));
        var result=ChatMaterialsInputContract.bind(candidates,"0","owner","client","1",1,"task","request","turn","agent");
        assertEquals(List.of("source_image"),ChatMaterialsInputContract.refs(result));
        assertTrue(result.toString().contains("/internal/agent/chat/requests/request/turns/turn/materials/inputs/source_image/content"));
        var other=ChatMaterialsInputContract.bind(candidates,"0","owner","client","1",2,"task","request","turn","agent");assertNotEquals(result.get("manifestDigest"),other.get("manifestDigest"));
        assertNull(ChatMaterialsInputContract.bind(ChatMaterialsInputContract.candidates(Map.of(),List.of(image)),"0","owner","client","1",1,"task","request","turn","agent"));
    }
    @Test void wrongMimeCarrierAndUnsupportedDeclarationFailClosed() {
        var input=new LinkedHashMap<String,Object>(Map.of("mediaKind","image","mimeType","image/png","carrier","DIRECT_TEXT","carrierContractDigest","sha256:"+"a".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->ChatMaterialsInputContract.declaration(Map.of("schemaVersion",1,"supportedInputs",List.of(input))));
        input.put("carrier","LOCAL_IMAGE");input.put("url","/private");assertThrows(IllegalArgumentException.class,()->ChatMaterialsInputContract.declaration(Map.of("schemaVersion",1,"supportedInputs",List.of(input))));
    }
}
