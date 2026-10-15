package cn.jia.chat.handler;

import cn.jia.agent.common.AgentMessageWire;
import cn.jia.core.entity.ProtocolMessage;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentMessageWireTest {
    @Test void publicRegistrationIsExplicitAndProofLongIsExact() {
        var privateBody=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"messageType","agent.register","messageId","m",
                "installationId","rti_1","sessionGeneration",9007199254740993L,"runtimeInstanceId","boot"));
        var original=new LinkedHashMap<>(privateBody);
        var wire=AgentMessageWire.event("agent.register",privateBody,null);
        assertEquals("9007199254740993",wire.data().get("sessionGeneration"));
        assertEquals("rti_1",wire.data().get("runtimeInstallationId"));
        assertFalse(wire.data().containsKey("messageId"));assertEquals(original,privateBody);
        var restored=AgentMessageWire.business(wire);
        assertEquals(9007199254740993L,restored.get("sessionGeneration"));assertEquals("rti_1",restored.get("installationId"));
    }
    @Test void replyHasDistinctStableMessageIdentityAndOriginalCorrelation() {
        var original=ProtocolMessage.event("request","agent.register","registration",Map.of());
        var payload=Map.<String,Object>of("messageId","registration","status","online","installationId","rti_1");
        var reply=AgentMessageWire.event("agent_registered",payload,original);
        assertEquals("agent.registered",reply.messageType());assertEquals("request",reply.requestId());
        assertEquals("registration",reply.correlationId());assertEquals("E0",reply.code());
        assertNotEquals("registration",reply.messageId());assertEquals(reply,AgentMessageWire.event("agent_registered",payload,original));
    }
    @Test void legacyRootsAndAmbiguousMetadataAreRejected() {
        assertThrows(IllegalArgumentException.class,()->AgentMessageWire.decode(Map.of("type","agent.register")));
        assertThrows(IllegalArgumentException.class,()->AgentMessageWire.decode(Map.of("protocolVersion",1,"requestId","r","messageType","agent.register","messageId","m","data",Map.of("messageId","other"))));
        assertThrows(IllegalArgumentException.class,()->AgentMessageWire.business(ProtocolMessage.event("r","agent.register","m",Map.of("sessionGeneration",1))));
    }
    @Test void skillExecutionIdentityAndHashPreimageAreRestoredWithoutTransportOverwrite() {
        var data=new LinkedHashMap<String,Object>(Map.of("resultType","SKILL_INSTALL_RESULT","skillInstallationId","si_1",
                "executionRuntimeInstanceId","original-boot","runtimeInstanceId","current-boot","runtimeInstallationId","rti_1",
                "packageSha256","a".repeat(64),"installedAt",1000L,"sessionGeneration","2"));
        var result=AgentMessageWire.business(ProtocolMessage.event("r","work.result","result",data));
        assertEquals("original-boot",result.get("runtimeInstanceId"));assertEquals("si_1",result.get("installationId"));
        assertEquals("rti_1",result.get("runtimeInstallationId"));assertEquals("sha256:"+"a".repeat(64),result.get("packageDigest"));
        assertEquals("1000",result.get("installedAt"));assertFalse(result.containsKey("requestId"));
        assertEquals("current-boot",data.get("runtimeInstanceId"));
    }
}
