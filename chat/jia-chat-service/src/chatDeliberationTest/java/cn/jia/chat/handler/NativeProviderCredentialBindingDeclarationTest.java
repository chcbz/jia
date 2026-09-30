package cn.jia.chat.handler;

import cn.jia.agent.service.NativeProviderCredentialBindingLookup;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeProviderCredentialBindingDeclarationTest {
    @Test void exactDeclarationIsReadyAndDoesNotChangeFastOrNativeV1() {
        var snapshot=NativeProviderCredentialBindingDeclaration.parse(candidate()).snapshot();
        assertEquals(NativeProviderCredentialBindingLookup.State.READY,snapshot.state());
        assertEquals("CONTROLLED_IMAGE_HTTP_V1",snapshot.providerLane());assertEquals("binding-a",snapshot.bindingId());
        assertEquals(7L,snapshot.bindingEpoch());assertEquals("model-a",snapshot.modelId());
        assertEquals(16,snapshot.maxInputItems());assertEquals(1,snapshot.maxOutboundRequestAttempts());
        assertEquals(1,snapshot.precallFenceVersion());
        assertEquals(AgentRuntimeCapabilities.Decision.UNSUPPORTED,
                AgentRuntimeCapabilities.parse(AgentRuntimeCapabilitiesTest.candidate(true,true))
                        .decision(cn.jia.chat.deliberation.InteractionRoute.EXECUTE));
        assertEquals(cn.jia.agent.service.NativeBountyExecutionSessionLookup.State.UNDECLARED,
                NativeBountyExecutionDeclaration.parse(null).snapshot().state());
    }
    @Test void disabledShapeIsExactAndMalformedAuthorityOrEpochFailsClosed() {
        assertEquals(NativeProviderCredentialBindingLookup.State.UNDECLARED,
                NativeProviderCredentialBindingDeclaration.parse(null).snapshot().state());
        assertEquals(NativeProviderCredentialBindingLookup.State.DISABLED,
                NativeProviderCredentialBindingDeclaration.parse(Map.of("schemaVersion",1,"enabled",false)).snapshot().state());
        for(Map<String,Object> malformed:List.<Map<String,Object>>of(changed("bindingEpoch","0"),changed("bindingEpoch","01"),
                changed("bindingEpoch",1),changed("maxInputItems",32),changed("maxOutboundRequestAttempts",2),
                changed("providerLane","PERSONAL_WORKSPACE_CONVERSATION_HTTP_V1"),unknown("custody","OWNER"),
                new LinkedHashMap<>(Map.of("schemaVersion",1,"enabled",false,"bindingId","fake")))) {
            assertEquals(NativeProviderCredentialBindingLookup.State.UNSUPPORTED,
                    NativeProviderCredentialBindingDeclaration.parse(malformed).snapshot().state());
        }
    }
    static Map<String,Object> candidate(){Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion",1);
        value.put("enabled",true);value.put("providerLane","CONTROLLED_IMAGE_HTTP_V1");value.put("bindingId","binding-a");
        value.put("bindingEpoch","7");value.put("modelId","model-a");value.put("maxInputItems",16);
        value.put("maxOutboundRequestAttempts",1);value.put("precallFenceVersion",1);return value;}
    private static Map<String,Object> changed(String key,Object value){Map<String,Object> result=new LinkedHashMap<>(candidate());result.put(key,value);return result;}
    private static Map<String,Object> unknown(String key,Object value){Map<String,Object> result=new LinkedHashMap<>(candidate());result.put(key,value);return result;}
}
