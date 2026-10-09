package cn.jia.agent.entity;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2ExecutionCodec.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRuntimeV2ExecutionCodecTest {
    static final String ID="11111111-1111-4111-8111-111111111111",HASH="1".repeat(64);
    @Test void exactRenew488BytesAndFrozenFingerprint(){var r=new Renew(ID,ID,ID,HASH,ID,HASH);var b=encodeRenew(r);assertEquals(488,b.length);assertEquals("422fc50f81fc46663bf58d1ac9c72cca2f669aeb9c9eec4820eee21f2e397a9f",hex(sha256(b)));assertEquals(r,decodeRenew(b));assertEquals(306,rejected("RENEWAL",ID,ID,ID,"RENEWAL_NOT_AUTHORIZED").length);}
    @Test void strictRenewRejectsUnknownDuplicateTypesOrderWhitespaceEscapesAndInvalidUtf8(){String wire=new String(encodeRenew(new Renew(ID,ID,ID,HASH,ID,HASH)),StandardCharsets.UTF_8);for(String changed:List.of(" "+wire,wire+"\n",wire.replace("\"schemaVersion\":2","\"schemaVersion\":\"2\""),wire.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"schemaVersion\":2"),wire.replace("\"renewalRequestId\"","\"requestId\""),wire.replace("\"schemaVersion\":2","\"schemaVersion\":2,\"nested\":{}"),wire.replace("11111111","\\u00311111111"),wire.replace("\"renewalRequestId\":\""+ID+"\"","\"renewalRequestId\":null"))){assertThrows(IllegalArgumentException.class,()->decodeRenew(changed.getBytes(StandardCharsets.UTF_8)));}byte[] malformed=wire.getBytes(StandardCharsets.UTF_8);malformed[malformed.length-2]=(byte)0xff;assertThrows(IllegalArgumentException.class,()->decodeRenew(malformed));}
    @Test void originalAckFingerprintAndCanonicalDecimalTypeStayUnchanged(){var a=new Ack("中文\"ack",ID,"source\\x","STARTED",12345);byte[] b=encodeAck(a);assertEquals(a,decodeAck(b));assertArrayEquals(AgentRuntimeV2WireCodec.ackRequestFingerprint(a.ackMessageId(),a.runtimeDispatchId(),a.sourceMessageId(),a.ackStatus(),a.ackAt()),ackFingerprint(a));String wire=new String(b,StandardCharsets.UTF_8);for(String wrong:List.of(wire.replace("\"12345\"","12345"),wire.replace("\"12345\"","\"012345\""),wire.replace("\"12345\"","\"1e4\""),wire.replace("\"12345\"","\"9223372036854775808\""))){assertThrows(IllegalArgumentException.class,()->decodeAck(wrong.getBytes(StandardCharsets.UTF_8)));}}
    @Test void ack599ByteMaximumAndContinue332StayExact(){var a=new Ack("\"".repeat(100),ID,"\"".repeat(100),"SUCCEEDED",Long.MAX_VALUE);assertEquals(599,encodeAck(a).length);assertEquals(a,decodeAck(encodeAck(a)));var c=new AgentRuntimeV2WireCodec.ContinuationRequest(ID,ID,ID,HexFormat.of().parseHex(HASH));assertEquals(332,AgentRuntimeV2WireCodec.encodeContinuationRequest(c).length);assertArrayEquals(c.startedReceiptSha256(),decodeContinue(AgentRuntimeV2WireCodec.encodeContinuationRequest(c)).startedReceiptSha256());}
    @Test void rejectionContainsNoAuthorityFields(){for(String reason:List.of("RENEWAL_SUPERSEDED","RENEWAL_NOT_AUTHORIZED","REQUEST_CONFLICT","RENEWAL_UNAVAILABLE")){String b=new String(rejected("RENEWAL",ID,ID,ID,reason),StandardCharsets.UTF_8);for(String forbidden:List.of("leaseUntil","executionAuthorized","grantBytes","generation","factId"))assertFalse(b.contains(forbidden));}assertThrows(IllegalArgumentException.class,()->rejected("RENEWAL",ID,ID,ID,"OK"));}
}
