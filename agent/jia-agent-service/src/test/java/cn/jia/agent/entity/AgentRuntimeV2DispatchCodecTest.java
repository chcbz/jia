package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2DispatchCodecTest {
    static final String ID="01234567-89ab-4cde-8fab-0123456789ab";
    @Test void maxBoundAndRawOwnership(){byte[] inner=new byte[131072];Arrays.fill(inner,(byte)0xff);var value=new AgentRuntimeV2DispatchCodec.Delivery(ID,inner);byte[] outer=AgentRuntimeV2DispatchCodec.encode(value);assertEquals(175018,outer.length);inner[0]=0;assertArrayEquals(value.commandBytes(),AgentRuntimeV2DispatchCodec.decode(outer).commandBytes());assertThrows(IllegalArgumentException.class,()->new AgentRuntimeV2DispatchCodec.Delivery(ID,new byte[131073]));}
    @Test void rejectAliasesDuplicatesUnknownTypeHashBase64WhitespaceAndUtf8(){String raw=new String(AgentRuntimeV2DispatchCodec.encode(new AgentRuntimeV2DispatchCodec.Delivery(ID,new byte[]{1})),StandardCharsets.UTF_8);for(String wrong:List.of(raw+"\n"," "+raw,raw.replace("\"schemaVersion\":2","\"schemaVersion\":\"2\""),raw.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"),raw.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"extra\":{}"),raw.replace("AQ==","AQ"),raw.replace("AQ==","AR=="),raw.replace(ID,ID.toUpperCase(Locale.ROOT))))assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2DispatchCodec.decode(wrong.getBytes(StandardCharsets.UTF_8)));assertThrows(IllegalArgumentException.class,()->AgentRuntimeV2DispatchCodec.decode(new byte[]{(byte)0xc0,(byte)0xaf}));}
}
