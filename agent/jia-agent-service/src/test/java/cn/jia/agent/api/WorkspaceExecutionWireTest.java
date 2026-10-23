package cn.jia.agent.api;

import cn.jia.agent.service.PersonalWorkspaceExecutionService.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceExecutionWireTest {
    @Test void explicitNullsAndExactDecimalProjectionDoNotAlterPrivateCommand() throws Exception {
        var input=new RuntimeInput("in","file",1,"input.png","image/png",0,"a".repeat(64));
        var view=new ExecutionView("execution","task","run",null,"agent","QUEUED",null,null,Long.MAX_VALUE,null,List.of(input),null,"CONVERSATION",null,null,null);
        var json=JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(WorkspaceExecutionWire.from(view)));
        assertEquals(16,json.size());assertEquals(Long.toString(Long.MAX_VALUE),json.path("grantRevision").asText());
        assertTrue(json.path("runtimeCommand").isNull());assertEquals("1",json.path("inputs").get(0).path("version").asText());
        assertEquals("0",json.path("inputs").get(0).path("byteLength").asText());assertEquals(1,input.version());
    }
    @Test void queueUsesMessageEnvelopeAndStringLengthsWithoutChangingIdentity() {
        var command=new RuntimeCommand("task","run",List.of(new RuntimeInputCommand("in","inputs/in.png","/input",7,"a".repeat(64))),List.of(new RuntimeOutput("output_1","outputs/result.png","image/png",12,"/output")));
        var original=new RuntimeQueuedCommand(1,"command.dispatch","message","command","tenant","client","owner","task","run","agent","WORKSPACE_FILE_EXECUTE","edit",command);
        var wire=WorkspaceExecutionWire.from(original);
        assertEquals(1,wire.protocolVersion());assertEquals("message",wire.messageId());assertEquals("command.dispatch",wire.messageType());assertFalse(wire.data().containsKey("messageId"));
        var payload=(WorkspaceExecutionWire.Command)wire.data().get("payload");
        assertEquals("7",payload.inputManifest().getFirst().length());assertEquals("12",payload.outputManifest().getFirst().maxLength());assertEquals(7,command.inputManifest().getFirst().length());
    }
}
