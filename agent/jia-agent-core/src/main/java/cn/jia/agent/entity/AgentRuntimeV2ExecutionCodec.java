package cn.jia.agent.entity;

import java.io.ByteArrayOutputStream;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints.*;

/** Additive raw execution boundary. Existing immutable wire/fingerprint algorithms are unchanged. */
public final class AgentRuntimeV2ExecutionCodec {
    public static final int ACK_CAP=599, CONTINUE_CAP=332, RENEW_CAP=488;
    private static final HexFormat HEX=HexFormat.of();
    private AgentRuntimeV2ExecutionCodec() {}
    public record Ack(String ackMessageId,String runtimeDispatchId,String sourceMessageId,String ackStatus,long ackAt) {}
    public record Renew(String renewalRequestId,String runtimeDispatchId,String startedReceiptId,String startedReceiptSha256,
                        String previousExecutionGrantId,String previousExecutionGrantSha256) {}
    public static byte[] encodeAck(Ack a){
        uuid(a.runtimeDispatchId(),"runtimeDispatchId");RuntimeV2ExactIdentity.require(a.ackMessageId(),100,"ackMessageId");RuntimeV2ExactIdentity.require(a.sourceMessageId(),100,"sourceMessageId");
        if(!Set.of("RECEIVED","STARTED","SUCCEEDED","FAILED","REJECTED").contains(a.ackStatus())||a.ackAt()<=0)throw invalid();
        return raw("{\"schemaVersion\":2,\"messageType\":\"command.ack\",\"ackMessageId\":"+quote(a.ackMessageId())+",\"runtimeDispatchId\":"+quote(a.runtimeDispatchId())+",\"sourceMessageId\":"+quote(a.sourceMessageId())+",\"ackStatus\":"+quote(a.ackStatus())+",\"ackAt\":\""+a.ackAt()+"\"}");
    }
    public static Ack decodeAck(byte[] wire){limited(wire,ACK_CAP);Parser p=new Parser(wire);p.open();p.number("schemaVersion",2);p.string("messageType","command.ack");var a=new Ack(p.string("ackMessageId"),p.string("runtimeDispatchId"),p.string("sourceMessageId"),p.string("ackStatus"),p.decimal("ackAt",true));p.close();canonical(wire,encodeAck(a));return a;}
    public static byte[] ackFingerprint(Ack a){return AgentRuntimeV2WireCodec.ackRequestFingerprint(a.ackMessageId(),a.runtimeDispatchId(),a.sourceMessageId(),a.ackStatus(),a.ackAt());}
    public static byte[] encodeRenew(Renew r){
        for(String v:List.of(r.renewalRequestId(),r.runtimeDispatchId(),r.startedReceiptId(),r.previousExecutionGrantId()))uuid(v,"renew ID");
        for(String v:List.of(r.startedReceiptSha256(),r.previousExecutionGrantSha256()))if(!v.matches("[0-9a-f]{64}"))throw invalid();
        return raw("{\"schemaVersion\":2,\"messageType\":\"command.execution.renew\",\"renewalRequestId\":"+quote(r.renewalRequestId())+",\"runtimeDispatchId\":"+quote(r.runtimeDispatchId())+",\"startedReceiptId\":"+quote(r.startedReceiptId())+",\"startedReceiptSha256\":"+quote(r.startedReceiptSha256())+",\"previousExecutionGrantId\":"+quote(r.previousExecutionGrantId())+",\"previousExecutionGrantSha256\":"+quote(r.previousExecutionGrantSha256())+"}");
    }
    public static Renew decodeRenew(byte[] wire){limited(wire,RENEW_CAP);Parser p=new Parser(wire);p.open();p.number("schemaVersion",2);p.string("messageType","command.execution.renew");var r=new Renew(p.string("renewalRequestId"),p.string("runtimeDispatchId"),p.string("startedReceiptId"),HEX.formatHex(p.hash("startedReceiptSha256")),p.string("previousExecutionGrantId"),HEX.formatHex(p.hash("previousExecutionGrantSha256")));p.close();canonical(wire,encodeRenew(r));return r;}
    public static AgentRuntimeV2WireCodec.ContinuationRequest decodeContinue(byte[] wire){if(wire==null||wire.length!=CONTINUE_CAP)throw invalid();return AgentRuntimeV2WireCodec.decodeContinuationRequest(wire);}
    public static byte[] rejected(String kind,String id,String dispatch,String receipt,String reason){
        if(!Set.of("CONTINUATION","RENEWAL").contains(kind)||!Set.of(kind+"_SUPERSEDED",kind+"_NOT_AUTHORIZED",kind+"_UNAVAILABLE","REQUEST_CONFLICT").contains(reason))throw invalid();
        for(String v:List.of(id,dispatch,receipt))uuid(v,"rejection ID");
        return raw("{\"schemaVersion\":2,\"messageType\":\"command.execution."+(kind.equals("RENEWAL")?"renew":"continue")+".rejected\",\"correlationType\":"+quote(kind)+",\"correlationId\":"+quote(id)+",\"runtimeDispatchId\":"+quote(dispatch)+",\"startedReceiptId\":"+quote(receipt)+",\"reasonCode\":"+quote(reason)+"}");
    }
    public static byte[] receiptDelivery(byte[] bytes,boolean prior){AgentRuntimeV2WireCodec.decodeReceipt(bytes);return raw("{\"schemaVersion\":2,\"messageType\":\"command.ack.receipt.delivery\",\"deliveryResult\":\""+(prior?"PRIOR":"ADVANCED")+"\",\"receiptEncoding\":\"base64\",\"receiptBytesBase64\":"+quote(Base64.getEncoder().encodeToString(bytes))+",\"receiptSha256\":"+quote(hex(sha256(bytes)))+"}");}
    public static byte[] grantDelivery(byte[] bytes,String kind,String id,boolean prior){AgentRuntimeV2WireCodec.decodeExecutionGrant(bytes);if(!Set.of("STARTED_ACK","CONTINUATION","RENEWAL").contains(kind))throw invalid();RuntimeV2ExactIdentity.require(id,100,"correlationId");return raw("{\"schemaVersion\":2,\"messageType\":\"command.execution.grant.delivery\",\"deliveryResult\":\""+(prior?"PRIOR":"ADVANCED")+"\",\"correlationType\":"+quote(kind)+",\"correlationId\":"+quote(id)+",\"grantEncoding\":\"base64\",\"grantBytesBase64\":"+quote(Base64.getEncoder().encodeToString(bytes))+",\"grantSha256\":"+quote(hex(sha256(bytes)))+"}");}
    private static void limited(byte[] b,int cap){if(b==null||b.length==0||b.length>cap)throw invalid();}
    private static byte[] raw(String v){return v.getBytes(StandardCharsets.UTF_8);}
    private static void canonical(byte[] b,byte[] c){if(!Arrays.equals(b,c))throw invalid();}
    private static String quote(String v){StringBuilder s=new StringBuilder("\"");v.codePoints().forEach(cp->{if(cp=='"')s.append("\\\"");else if(cp=='\\')s.append("\\\\");else if(cp<=31)s.append(String.format("\\u%04x",cp));else s.appendCodePoint(cp);});return s.append('"').toString();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("noncanonical runtime-v2 execution wire");}
    private static final class Parser {
        private final byte[] bytes; private int position; private boolean first = true;
        private Parser(byte[] bytes) { this.bytes = Objects.requireNonNull(bytes, "wire").clone(); if (bytes.length == 0) fail(); }
        void open() { expect('{'); }
        void close() { expect('}'); if (position != bytes.length) fail(); }
        void number(String key, int expected) { key(key); byte[] token = Integer.toString(expected).getBytes(StandardCharsets.US_ASCII); for (byte b : token) expect(b); }
        void string(String key, String expected) { if (!expected.equals(string(key))) fail(); }
        String string(String key) { key(key); return readString(); }
        String nullableString(String key) { key(key); if (peek('n')) { expect('n'); expect('u'); expect('l'); expect('l'); return null; } return readString(); }
        long decimal(String key, boolean positive) {
            String text = string(key); if (!text.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")) fail();
            try { long value = Long.parseLong(text); if (positive && value <= 0) fail(); return value; } catch (NumberFormatException e) { return fail(); }
        }
        byte[] hash(String key) { String text = string(key); if (!text.matches("[0-9a-f]{64}")) fail(); return HEX.parseHex(text); }
        private void key(String name) { if (!first) expect(','); first = false; if (!name.equals(readString())) fail(); expect(':'); }
        private String readString() {
            expect('"'); ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (position < bytes.length) {
                int current = bytes[position++] & 0xff;
                if (current == '"') return decodeUtf8(out.toByteArray());
                if (current == '\\') {
                    if (position >= bytes.length) fail(); int escaped = bytes[position++] & 0xff;
                    if (escaped == '"' || escaped == '\\') out.write(escaped);
                    else if (escaped == 'u') {
                        expect('0'); expect('0'); int high = hex(); int low = hex(); int cp = high * 16 + low;
                        if (cp > 0x1f) fail(); out.write(cp);
                    } else fail();
                } else { if (current < 0x20) fail(); out.write(current); }
            }
            return fail();
        }
        private String decodeUtf8(byte[] value) {
            String decoded = new String(value, StandardCharsets.UTF_8);
            if (!Arrays.equals(value, decoded.getBytes(StandardCharsets.UTF_8))) fail();
            for (int i = 0; i < decoded.length(); i++) {
                char c = decoded.charAt(i);
                if (Character.isHighSurrogate(c)) { if (++i >= decoded.length() || !Character.isLowSurrogate(decoded.charAt(i))) fail(); }
                else if (Character.isLowSurrogate(c)) fail();
            }
            return decoded;
        }
        private int hex() { if (position >= bytes.length) return fail(); int c = bytes[position++] & 0xff; if (c >= '0' && c <= '9') return c - '0'; if (c >= 'a' && c <= 'f') return c - 'a' + 10; return fail(); }
        private boolean peek(int c) { return position < bytes.length && (bytes[position] & 0xff) == c; }
        private void expect(int c) { if (!peek(c)) fail(); position++; }
        private <T> T fail() { throw new IllegalArgumentException("wire is not the exact runtime-v2 canonical form at byte " + position); }
    }
}
