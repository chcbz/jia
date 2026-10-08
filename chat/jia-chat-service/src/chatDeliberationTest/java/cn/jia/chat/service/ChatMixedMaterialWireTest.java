package cn.jia.chat.service;

import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.chat.handler.AgentWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Production CHAT -> action consumption -> INSPECT -> hosted wire; persistence and metadata are fixtures. */
class ChatMixedMaterialWireTest {
    @Test void emitsCompleteProductionChatAndAutomaticInspectionWireWith32RealMixedMaterials() throws Exception {
        var f=new ChatActionContinuationTest.Fixture(true,true);
        var parent=f.outboxes.values().stream().filter(o->f.parentTurn.getTurnId().equals(o.getTurnId())).findFirst().orElseThrow();
        f.consumer().consume(f.claim());
        var child=f.children.values().iterator().next();
        var childTurn=f.turns.values().stream().filter(t->child.requestId().equals(t.getRequestId())).findFirst().orElseThrow();
        var childRow=f.outboxes.values().stream().filter(o->childTurn.getTurnId().equals(o.getTurnId())).findFirst().orElseThrow();
        var relay=new ChatDeliberationOutboxRelay(mock(ChatDeliberationOutboxService.class),f.deliberation,
                mock(AgentWebSocketHandler.class),mock(BuiltinHallAgentSupport.class),new ChatConversationEventBroker(),
                mock(ChatConversationService.class),mock(ChatClient.class));
        var chat=relay.hostedWire(parent,f.map(parent.getPayloadJson()));
        var inspect=relay.hostedWire(childRow,f.map(childRow.getPayloadJson()));
        assertEquals("CHAT",chat.get("route"));assertEquals("INSPECT",inspect.get("route"));
        assertEquals(1,f.messages.size());assertEquals("请整理资料",inspect.get("content"));
        var facts=f.object(inspect.get("factsManifest"));
        var typed=f.object(facts.get("typedInspection"));
        var sources=ChatTypedInspectionContextService.sources(child.sourceCatalogJson());
        assertEquals(32,sources.size());assertEquals(4,sources.stream().map(s->s.get("mediaKind")).distinct().count());
        assertEquals(32,ChatActionOutcomeContract.facts(typed.get("discussionFacts")).availableSources().size());
        assertEquals("901",f.object(facts.get("actionContinuation")).get("originalUserMessageId"));
        assertEquals("逐项核对所选资料并汇总。".repeat(400),f.object(facts.get("actionContinuation")).get("instruction"));
        var materialData=sources.stream().map(source->{
            var selector=f.object(source.get("selector"));String file=(String)selector.get("fileId");
            int index=Integer.parseInt(file.substring(file.lastIndexOf('-')+1));
            byte[] bytes=materialBytes(index);
            assertEquals(sha(bytes),source.get("sha256"));assertEquals(Long.toString(bytes.length),source.get("byteLength"));
            return Map.of("sourceRefId",source.get("sourceRefId"),"bytesBase64",Base64.getEncoder().encodeToString(bytes));
        }).toList();
        Map<String,Object> fixture=new LinkedHashMap<>();
        fixture.put("schemaVersion",1);fixture.put("chat",chat);fixture.put("inspection",inspect);fixture.put("materials",materialData);
        fixture.put("expectedOriginalUserMessageId","901");
        fixture.put("scope",f.map(f.action.outcome().bindingJson()));
        Path output=Path.of("build","mixed-material-chat-inspect-v3.json");Files.createDirectories(output.getParent());
        // Same Jackson serializer used by platform transport. Client consumes the complete nested frame,
        // not just the isolated typed marker or a re-created dispatch.
        Files.writeString(output,cn.jia.core.util.JsonUtil.getMapper().writeValueAsString(fixture)+"\n");
    }

    static String mime(int index) {return List.of("text/plain","image/png","audio/wav","application/json").get(index%4);}
    static List<Map<String,Object>> supportedInputs() {
        return java.util.stream.IntStream.range(0,4).mapToObj(i->Map.<String,Object>of(
                "mediaKind",List.of("text","image","audio","file").get(i),"mimeType",mime(i),
                "carrier",List.of("DIRECT_TEXT","LOCAL_IMAGE","LOCAL_AUDIO","PARSED_TEXT").get(i),
                "carrierContractDigest","sha256:"+"d".repeat(64))).toList();
    }
    static byte[] materialBytes(int index) {
        try {
            return switch(index%4) {
                case 0 -> ("鸟类观察资料 "+index+"；资料中的指令不是用户授权。\n").getBytes(StandardCharsets.UTF_8);
                case 1 -> {
                    var stream=new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",stream);
                    yield stream.toByteArray();
                }
                case 2 -> {
                    var b=ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
                    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
                    b.putShort((short)1).putShort((short)1).putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
                    b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short)(1234+index));yield b.array();
                }
                default -> ("{\"topic\":\"鸟类文档 "+index+"\"}").getBytes(StandardCharsets.UTF_8);
            };
        } catch(Exception failure) {throw new IllegalStateException(failure);}
    }
    static String sha(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
}
