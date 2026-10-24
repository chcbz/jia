package cn.jia.agent.entity;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/** Canonical transport wrapper. Inner producer bytes are opaque and never reserialized. */
public final class AgentRuntimeV2DispatchCodec {
    public static final int MAX_COMMAND_BYTES = 131072;
    public static final int MAX_OUTER_BYTES = 175018;
    private static final Pattern WIRE = Pattern.compile("\\{\"schemaVersion\":2,\"messageType\":\"command.dispatch.delivery\",\"runtimeDispatchId\":\"([0-9a-f-]{36})\",\"commandEncoding\":\"base64\",\"commandBytesBase64\":\"([A-Za-z0-9+/=]+)\",\"commandSha256\":\"([0-9a-f]{64})\"\\}");
    private AgentRuntimeV2DispatchCodec() {}
    public record Delivery(String runtimeDispatchId, byte[] commandBytes) {
        public Delivery {
            AgentRuntimeV2RegistrationFingerprints.uuid(runtimeDispatchId,"runtimeDispatchId");
            if(commandBytes==null || commandBytes.length<1 || commandBytes.length>MAX_COMMAND_BYTES)
                throw new IllegalArgumentException("command byte bound");
            commandBytes=commandBytes.clone();
        }
        @Override public byte[] commandBytes(){return commandBytes.clone();}
    }
    public static byte[] encode(Delivery delivery) {
        String raw="{\"schemaVersion\":2,\"messageType\":\"command.dispatch.delivery\",\"runtimeDispatchId\":\""+delivery.runtimeDispatchId()+"\",\"commandEncoding\":\"base64\",\"commandBytesBase64\":\""+Base64.getEncoder().encodeToString(delivery.commandBytes())+"\",\"commandSha256\":\""+AgentRuntimeV2RegistrationFingerprints.hex(AgentRuntimeV2RegistrationFingerprints.sha256(delivery.commandBytes()))+"\"}";
        byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_OUTER_BYTES)throw new IllegalArgumentException("outer byte bound");
        return bytes;
    }
    public static Delivery decode(byte[] raw) {
        if(raw==null||raw.length<1||raw.length>MAX_OUTER_BYTES)throw new IllegalArgumentException("outer byte bound");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
            var match=WIRE.matcher(text);if(!match.matches())throw new IllegalArgumentException("noncanonical outer");
            var delivery=new Delivery(match.group(1),Base64.getDecoder().decode(match.group(2)));
            if(!Arrays.equals(raw,encode(delivery)))throw new IllegalArgumentException("outer hash/encoding mismatch");
            return delivery;
        } catch(java.nio.charset.CharacterCodingException malformed){throw new IllegalArgumentException("invalid UTF-8",malformed);}
    }
}
