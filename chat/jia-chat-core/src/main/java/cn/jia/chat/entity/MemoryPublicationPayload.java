package cn.jia.chat.entity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable generated payload bound to one memory publication. */
public final class MemoryPublicationPayload {
    private static final String DIGEST_VERSION = "memory-publication-payload-v1";
    private static final int MAX_SUMMARY_UTF8_BYTES = 4 * 1024 * 1024;
    private static final int MAX_EMBEDDING_DIMENSION = 4096;

    private final String summaryContent;
    private final byte[] embeddingBytes;
    private final String payloadDigest;

    private MemoryPublicationPayload(
            String summaryContent, byte[] embeddingBytes, String payloadDigest) {
        this.summaryContent = requireSummary(summaryContent);
        this.embeddingBytes = requireEncodedEmbedding(embeddingBytes).clone();
        String expectedDigest = digest(this.summaryContent, this.embeddingBytes);
        if (!expectedDigest.equals(payloadDigest)) {
            throw new IllegalStateException("Memory publication payload digest is invalid");
        }
        this.payloadDigest = payloadDigest;
    }

    public static MemoryPublicationPayload generated(String summaryContent, float[] embedding) {
        byte[] encoded = encodeEmbedding(embedding);
        return new MemoryPublicationPayload(
                summaryContent, encoded, digest(summaryContent, encoded));
    }

    public static MemoryPublicationPayload stored(
            String summaryContent, byte[] embeddingBytes, String payloadDigest) {
        return new MemoryPublicationPayload(summaryContent, embeddingBytes, payloadDigest);
    }

    public String summaryContent() {
        return summaryContent;
    }

    public byte[] embeddingBytes() {
        return embeddingBytes.clone();
    }

    public String payloadDigest() {
        return payloadDigest;
    }

    public float[] embedding() {
        ByteBuffer buffer = ByteBuffer.wrap(embeddingBytes);
        int dimension = buffer.getInt();
        float[] values = new float[dimension];
        for (int index = 0; index < dimension; index++) {
            values[index] = Float.intBitsToFloat(buffer.getInt());
        }
        return values;
    }

    private static byte[] encodeEmbedding(float[] embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (embedding.length < 1 || embedding.length > MAX_EMBEDDING_DIMENSION) {
            throw new IllegalStateException("Memory publication embedding dimension is invalid");
        }
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES + Math.multiplyExact(embedding.length, Float.BYTES));
        buffer.putInt(embedding.length);
        for (float value : embedding) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Memory publication embedding is invalid");
            }
            buffer.putInt(Float.floatToIntBits(value));
        }
        return buffer.array();
    }

    private static byte[] requireEncodedEmbedding(byte[] encoded) {
        Objects.requireNonNull(encoded, "embeddingBytes");
        if (encoded.length < Integer.BYTES + Float.BYTES) {
            throw new IllegalStateException("Memory publication embedding payload is truncated");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        int dimension = buffer.getInt();
        if (dimension < 1 || dimension > MAX_EMBEDDING_DIMENSION
                || encoded.length != Integer.BYTES + dimension * Float.BYTES) {
            throw new IllegalStateException("Memory publication embedding payload is invalid");
        }
        for (int index = 0; index < dimension; index++) {
            float value = Float.intBitsToFloat(buffer.getInt());
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("Memory publication embedding payload is invalid");
            }
        }
        return encoded;
    }

    private static String requireSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("Memory publication summary is unavailable");
        }
        int bytes = summary.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_SUMMARY_UTF8_BYTES) {
            throw new IllegalStateException("Memory publication summary is too large");
        }
        return summary;
    }

    private static String digest(String summary, byte[] embedding) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, DIGEST_VERSION.getBytes(StandardCharsets.UTF_8));
            update(digest, requireSummary(summary).getBytes(StandardCharsets.UTF_8));
            update(digest, requireEncodedEmbedding(embedding));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, byte[] value) {
        digest.update((byte) (value.length >>> 24));
        digest.update((byte) (value.length >>> 16));
        digest.update((byte) (value.length >>> 8));
        digest.update((byte) value.length);
        digest.update(value);
    }
}
