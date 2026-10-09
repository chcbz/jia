package cn.jia.agent.entity;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Strict canonical codec for immutable runtime-v2 receipt, continuation, and grant wires. */
public final class AgentRuntimeV2WireCodec {
    public static final int CONTRACT_VERSION = 2;
    private static final HexFormat HEX = HexFormat.of();
    private static final String UUID4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private AgentRuntimeV2WireCodec() {}

    public record Receipt(String receiptId, String runtimeDispatchId, String ackMessageId,
            String sourceMessageId, String ackStatus, long ackAt, long committedAt,
            long postcommitDeliveryVersion, long postcommitInboxVersion, String leaseFactId) {}

    public record ContinuationRequest(String continuationRequestId, String runtimeDispatchId,
            String startedReceiptId, byte[] startedReceiptSha256) {
        public ContinuationRequest { startedReceiptSha256 = copy32(startedReceiptSha256, "startedReceiptSha256"); }
        @Override public byte[] startedReceiptSha256() { return startedReceiptSha256.clone(); }
    }

    public record ExecutionGrant(String executionGrantId, String grantKind, String runtimeDispatchId,
            String startedReceiptId, byte[] startedReceiptSha256, String runtimeBindingId,
            String runtimeInstanceId, long dispatchBindingGeneration, long holderBindingGeneration,
            String originalStartLeaseFactId, String currentLeaseFactId, long leaseRevision,
            long leaseUntil, String previousExecutionGrantId, long issuedAt) {
        public ExecutionGrant { startedReceiptSha256 = copy32(startedReceiptSha256, "startedReceiptSha256"); }
        @Override public byte[] startedReceiptSha256() { return startedReceiptSha256.clone(); }
    }

    public static byte[] encodeReceipt(Receipt value) {
        validateReceipt(value);
        String json = "{\"schemaVersion\":2,\"messageType\":\"command.ack.receipt\""
                + ",\"receiptId\":" + quote(value.receiptId())
                + ",\"runtimeDispatchId\":" + quote(value.runtimeDispatchId())
                + ",\"ackMessageId\":" + quote(value.ackMessageId())
                + ",\"sourceMessageId\":" + quote(value.sourceMessageId())
                + ",\"ackStatus\":" + quote(value.ackStatus())
                + ",\"ackAt\":" + quote(decimal(value.ackAt(), true, "ackAt"))
                + ",\"committedAt\":" + quote(decimal(value.committedAt(), true, "committedAt"))
                + ",\"postcommitDeliveryVersion\":" + quote(decimal(value.postcommitDeliveryVersion(), false, "postcommitDeliveryVersion"))
                + ",\"postcommitInboxVersion\":" + quote(decimal(value.postcommitInboxVersion(), false, "postcommitInboxVersion"))
                + ",\"leaseFactId\":" + nullableUuid(value.leaseFactId(), "leaseFactId")
                + ",\"commitResult\":\"ADVANCED\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static Receipt decodeReceipt(byte[] wire) {
        Parser p = new Parser(wire);
        p.open(); p.number("schemaVersion", 2); p.string("messageType", "command.ack.receipt");
        Receipt value = new Receipt(p.string("receiptId"), p.string("runtimeDispatchId"),
                p.string("ackMessageId"), p.string("sourceMessageId"), p.string("ackStatus"),
                p.decimal("ackAt", true), p.decimal("committedAt", true),
                p.decimal("postcommitDeliveryVersion", false), p.decimal("postcommitInboxVersion", false),
                p.nullableString("leaseFactId"));
        p.string("commitResult", "ADVANCED"); p.close(); validateReceipt(value);
        requireCanonical(wire, encodeReceipt(value)); return value;
    }

    public static byte[] encodeContinuationRequest(ContinuationRequest value) {
        Objects.requireNonNull(value, "value"); uuid(value.continuationRequestId(), "continuationRequestId");
        uuid(value.runtimeDispatchId(), "runtimeDispatchId"); uuid(value.startedReceiptId(), "startedReceiptId");
        String json = "{\"schemaVersion\":2,\"messageType\":\"command.execution.continue\""
                + ",\"continuationRequestId\":" + quote(value.continuationRequestId())
                + ",\"runtimeDispatchId\":" + quote(value.runtimeDispatchId())
                + ",\"startedReceiptId\":" + quote(value.startedReceiptId())
                + ",\"startedReceiptSha256\":" + quote(HEX.formatHex(value.startedReceiptSha256())) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static ContinuationRequest decodeContinuationRequest(byte[] wire) {
        Parser p = new Parser(wire); p.open(); p.number("schemaVersion", 2);
        p.string("messageType", "command.execution.continue");
        ContinuationRequest value = new ContinuationRequest(p.string("continuationRequestId"),
                p.string("runtimeDispatchId"), p.string("startedReceiptId"), p.hash("startedReceiptSha256"));
        p.close(); requireCanonical(wire, encodeContinuationRequest(value)); return value;
    }

    public static byte[] encodeExecutionGrant(ExecutionGrant value) {
        validateGrant(value);
        String json = "{\"schemaVersion\":2,\"messageType\":\"command.execution.grant\""
                + ",\"executionGrantId\":" + quote(value.executionGrantId())
                + ",\"grantKind\":" + quote(value.grantKind())
                + ",\"runtimeDispatchId\":" + quote(value.runtimeDispatchId())
                + ",\"startedReceiptId\":" + quote(value.startedReceiptId())
                + ",\"startedReceiptSha256\":" + quote(HEX.formatHex(value.startedReceiptSha256()))
                + ",\"runtimeBindingId\":" + quote(value.runtimeBindingId())
                + ",\"runtimeInstanceId\":" + quote(value.runtimeInstanceId())
                + ",\"dispatchBindingGeneration\":" + quote(decimal(value.dispatchBindingGeneration(), false, "dispatchBindingGeneration"))
                + ",\"holderBindingGeneration\":" + quote(decimal(value.holderBindingGeneration(), false, "holderBindingGeneration"))
                + ",\"originalStartLeaseFactId\":" + quote(value.originalStartLeaseFactId())
                + ",\"currentLeaseFactId\":" + quote(value.currentLeaseFactId())
                + ",\"leaseRevision\":" + quote(decimal(value.leaseRevision(), false, "leaseRevision"))
                + ",\"leaseUntil\":" + quote(decimal(value.leaseUntil(), true, "leaseUntil"))
                + ",\"previousExecutionGrantId\":" + nullableUuid(value.previousExecutionGrantId(), "previousExecutionGrantId")
                + ",\"issuedAt\":" + quote(decimal(value.issuedAt(), true, "issuedAt")) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static ExecutionGrant decodeExecutionGrant(byte[] wire) {
        Parser p = new Parser(wire); p.open(); p.number("schemaVersion", 2);
        p.string("messageType", "command.execution.grant");
        ExecutionGrant value = new ExecutionGrant(p.string("executionGrantId"), p.string("grantKind"),
                p.string("runtimeDispatchId"), p.string("startedReceiptId"), p.hash("startedReceiptSha256"),
                p.string("runtimeBindingId"), p.string("runtimeInstanceId"),
                p.decimal("dispatchBindingGeneration", false), p.decimal("holderBindingGeneration", false),
                p.string("originalStartLeaseFactId"), p.string("currentLeaseFactId"),
                p.decimal("leaseRevision", false), p.decimal("leaseUntil", true),
                p.nullableString("previousExecutionGrantId"), p.decimal("issuedAt", true));
        p.close(); validateGrant(value); requireCanonical(wire, encodeExecutionGrant(value)); return value;
    }

    public static byte[] sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Original unprefixed seven-field ACK fingerprint; each UTF-8 field is LP/u32-big-endian. */
    public static byte[] ackRequestFingerprint(String ackMessageId, String runtimeDispatchId,
            String sourceMessageId, String ackStatus, long ackAt) {
        exact(ackMessageId, 100, "ackMessageId"); uuid(runtimeDispatchId, "runtimeDispatchId");
        exact(sourceMessageId, 100, "sourceMessageId");
        if (!java.util.Set.of("RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED").contains(ackStatus))
            throw new IllegalArgumentException("ackStatus is not canonical");
        positive(ackAt, "ackAt");
        return sha256(lp("2", "command.ack", ackMessageId, runtimeDispatchId,
                sourceMessageId, ackStatus, Long.toString(ackAt)));
    }

    public static byte[] continuationRequestFingerprint(String continuationRequestId,
            String runtimeDispatchId, String startedReceiptId, byte[] startedReceiptSha256) {
        return sha256(encodeContinuationRequest(new ContinuationRequest(continuationRequestId,
                runtimeDispatchId, startedReceiptId, startedReceiptSha256)));
    }

    public static void verifyReceiptRow(AgentCommandV2AckReceiptEntity row) {
        if (row == null || row.getReceiptWireContractVersion() == null
                || row.getReceiptWireContractVersion() != CONTRACT_VERSION
                || row.getReceiptWireBytes() == null || row.getReceiptWireSha256() == null
                || !MessageDigest.isEqual(sha256(row.getReceiptWireBytes()), row.getReceiptWireSha256())) {
            throw new IllegalStateException("receipt wire evidence is absent or corrupt");
        }
        Receipt value = decodeReceipt(row.getReceiptWireBytes());
        boolean same = Objects.equals(value.receiptId(), row.getReceiptId())
                && Objects.equals(value.runtimeDispatchId(), row.getRuntimeDispatchId())
                && Objects.equals(value.ackMessageId(), row.getAckMessageId())
                && Objects.equals(value.sourceMessageId(), row.getSourceMessageId())
                && Objects.equals(value.ackStatus(), row.getStatus()) && value.ackAt() == row.getAckAt()
                && value.committedAt() == row.getCommittedAt()
                && value.postcommitDeliveryVersion() == row.getPostcommitDeliveryVersion()
                && value.postcommitInboxVersion() == row.getPostcommitInboxVersion()
                && Objects.equals(value.leaseFactId(), row.getLeaseFactId());
        if (!same) throw new IllegalStateException("receipt wire does not match its persisted row");
    }

    public static void verifyGrantRow(AgentCommandV2ExecutionGrantEntity row) {
        if (row == null || row.getGrantWireContractVersion() == null
                || row.getGrantWireContractVersion() != CONTRACT_VERSION
                || row.getGrantWireBytes() == null || row.getGrantWireSha256() == null
                || !MessageDigest.isEqual(sha256(row.getGrantWireBytes()), row.getGrantWireSha256())) {
            throw new IllegalStateException("grant wire evidence is absent or corrupt");
        }
        ExecutionGrant value = decodeExecutionGrant(row.getGrantWireBytes());
        boolean same = Objects.equals(value.executionGrantId(), row.getExecutionGrantId())
                && Objects.equals(value.grantKind(), row.getGrantKind())
                && Objects.equals(value.runtimeDispatchId(), row.getRuntimeDispatchId())
                && Objects.equals(value.startedReceiptId(), row.getStartedReceiptId())
                && MessageDigest.isEqual(value.startedReceiptSha256(), row.getStartedReceiptSha256())
                && Objects.equals(value.runtimeBindingId(), row.getRuntimeBindingId())
                && Objects.equals(value.runtimeInstanceId(), row.getRuntimeInstanceId())
                && value.dispatchBindingGeneration() == row.getDispatchBindingGeneration()
                && value.holderBindingGeneration() == row.getHolderBindingGeneration()
                && Objects.equals(value.originalStartLeaseFactId(), row.getOriginalStartLeaseFactId())
                && Objects.equals(value.currentLeaseFactId(), row.getCurrentLeaseFactId())
                && value.leaseRevision() == row.getLeaseRevision() && value.leaseUntil() == row.getLeaseUntil()
                && Objects.equals(value.previousExecutionGrantId(), row.getPreviousExecutionGrantId())
                && value.issuedAt() == row.getIssuedAt();
        if (!same) throw new IllegalStateException("grant wire does not match its persisted row");
    }

    private static void validateReceipt(Receipt value) {
        Objects.requireNonNull(value, "value"); uuid(value.receiptId(), "receiptId");
        uuid(value.runtimeDispatchId(), "runtimeDispatchId"); exact(value.ackMessageId(), 100, "ackMessageId");
        exact(value.sourceMessageId(), 100, "sourceMessageId");
        if (!java.util.Set.of("RECEIVED", "STARTED", "SUCCEEDED", "FAILED", "REJECTED").contains(value.ackStatus()))
            throw new IllegalArgumentException("ackStatus is not canonical");
        positive(value.ackAt(), "ackAt"); positive(value.committedAt(), "committedAt");
        nonnegative(value.postcommitDeliveryVersion(), "postcommitDeliveryVersion");
        nonnegative(value.postcommitInboxVersion(), "postcommitInboxVersion");
        if (value.leaseFactId() != null) uuid(value.leaseFactId(), "leaseFactId");
    }

    private static void validateGrant(ExecutionGrant value) {
        Objects.requireNonNull(value, "value"); uuid(value.executionGrantId(), "executionGrantId");
        if (!java.util.Set.of("START", "CONTINUATION", "RENEWAL").contains(value.grantKind()))
            throw new IllegalArgumentException("grantKind is not canonical");
        uuid(value.runtimeDispatchId(), "runtimeDispatchId"); uuid(value.startedReceiptId(), "startedReceiptId");
        uuid(value.runtimeBindingId(), "runtimeBindingId"); exact(value.runtimeInstanceId(), 100, "runtimeInstanceId");
        nonnegative(value.dispatchBindingGeneration(), "dispatchBindingGeneration");
        nonnegative(value.holderBindingGeneration(), "holderBindingGeneration");
        uuid(value.originalStartLeaseFactId(), "originalStartLeaseFactId");
        uuid(value.currentLeaseFactId(), "currentLeaseFactId"); nonnegative(value.leaseRevision(), "leaseRevision");
        positive(value.leaseUntil(), "leaseUntil"); positive(value.issuedAt(), "issuedAt");
        if (value.previousExecutionGrantId() != null) uuid(value.previousExecutionGrantId(), "previousExecutionGrantId");
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        value.codePoints().forEach(cp -> {
            if (cp == '"') out.append("\\\"");
            else if (cp == '\\') out.append("\\\\");
            else if (cp <= 0x1f) out.append(String.format("\\u%04x", cp));
            else out.appendCodePoint(cp);
        });
        return out.append('"').toString();
    }

    private static String nullableUuid(String value, String name) {
        if (value == null) return "null"; uuid(value, name); return quote(value);
    }
    private static String decimal(long value, boolean positive, String name) {
        if (positive) positive(value, name); else nonnegative(value, name); return Long.toString(value);
    }
    private static void positive(long value, String name) { if (value <= 0) throw new IllegalArgumentException(name + " must be positive"); }
    private static void nonnegative(long value, String name) { if (value < 0) throw new IllegalArgumentException(name + " must be non-negative"); }
    private static void uuid(String value, String name) { if (value == null || !value.matches(UUID4)) throw new IllegalArgumentException(name + " is not UUIDv4"); }
    private static void exact(String value, int maximum, String name) { RuntimeV2ExactIdentity.require(value, maximum, name); }
    private static byte[] copy32(byte[] value, String name) { if (value == null || value.length != 32) throw new IllegalArgumentException(name + " must be SHA-256"); return value.clone(); }
    private static void requireCanonical(byte[] actual, byte[] canonical) { if (!Arrays.equals(actual, canonical)) throw new IllegalArgumentException("wire is not canonical"); }
    private static byte[] lp(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String field : fields) {
                byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length); out.write(encoded);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

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
