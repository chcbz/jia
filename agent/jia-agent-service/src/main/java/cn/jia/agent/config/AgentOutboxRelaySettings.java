package cn.jia.agent.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Validated immutable D03 relay settings and deterministic retry policy. */
public final class AgentOutboxRelaySettings {
    private final int batchSize;
    private final int overscan;
    private final long pollDelayMillis;
    private final long leaseMillis;
    private final long confirmTimeoutMillis;
    private final int maxInflight;
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final double backoffMultiplier;
    private final long maxBackoffMillis;
    private final int jitterPercent;
    private final long shutdownDrainMillis;
    private final int maxWireBytes;

    public AgentOutboxRelaySettings(AgentRabbitSafetyProperties.RabbitPublish source) {
        if (source == null) {
            throw invalid("rabbit publish settings are required");
        }
        batchSize = range(source.batchSize(), 1, 500, "batch-size");
        overscan = range(source.overscan(), 1, 10, "overscan");
        pollDelayMillis = range(source.pollDelayMillis(), 25L, 60_000L, "poll-delay-millis");
        leaseMillis = range(source.leaseMillis(), 5_000L, 600_000L, "lease-millis");
        confirmTimeoutMillis = range(
                source.confirmTimeoutMillis(), 100L, 60_000L, "confirm-timeout-millis");
        maxInflight = range(source.maxInflight(), 1, 500, "max-inflight");
        maxAttempts = range(source.maxAttempts(), 1, 100, "max-attempts");
        initialBackoffMillis = range(
                source.initialBackoffMillis(), 1L, 300_000L, "initial-backoff-millis");
        backoffMultiplier = source.backoffMultiplier();
        if (!Double.isFinite(backoffMultiplier)
                || backoffMultiplier < 1.0D || backoffMultiplier > 10.0D) {
            throw invalid("backoff-multiplier is outside [1,10]");
        }
        maxBackoffMillis = range(
                source.maxBackoffMillis(), 1L, 3_600_000L, "max-backoff-millis");
        if (maxBackoffMillis < initialBackoffMillis) {
            throw invalid("max-backoff-millis must be >= initial-backoff-millis");
        }
        jitterPercent = range(source.jitterPercent(), 0, 50, "jitter-percent");
        shutdownDrainMillis = range(
                source.shutdownDrainMillis(), 0L, 599_999L, "shutdown-drain-millis");
        maxWireBytes = range(source.maxWireBytes(), 1, 131_072, "max-wire-bytes");
        long minimumLease;
        try {
            minimumLease = Math.addExact(Math.multiplyExact(confirmTimeoutMillis, 2L), 5_000L);
        } catch (ArithmeticException overflow) {
            throw invalid("confirm timeout makes lease invariant overflow");
        }
        if (leaseMillis < minimumLease) {
            throw invalid("lease-millis must be >= 2*confirm-timeout-millis+5000");
        }
        if (shutdownDrainMillis >= leaseMillis) {
            throw invalid("shutdown-drain-millis must be < lease-millis");
        }
    }

    public int batchSize() { return batchSize; }
    public int overscan() { return overscan; }
    public long pollDelayMillis() { return pollDelayMillis; }
    public long leaseMillis() { return leaseMillis; }
    public long confirmTimeoutMillis() { return confirmTimeoutMillis; }
    public int maxInflight() { return maxInflight; }
    public int maxAttempts() { return maxAttempts; }
    public long initialBackoffMillis() { return initialBackoffMillis; }
    public double backoffMultiplier() { return backoffMultiplier; }
    public long maxBackoffMillis() { return maxBackoffMillis; }
    public int jitterPercent() { return jitterPercent; }
    public long shutdownDrainMillis() { return shutdownDrainMillis; }
    public int maxWireBytes() { return maxWireBytes; }

    public int discoveryLimit(int requested) {
        int bounded = Math.max(0, Math.min(requested, batchSize));
        return Math.multiplyExact(bounded, overscan);
    }

    public long nextRetryAt(
            String eventId, int publishAttempt, int attemptCount, long now, long expiresAt) {
        if (eventId == null || eventId.isEmpty() || publishAttempt <= 0 || attemptCount <= 0) {
            throw new IllegalArgumentException("retry identity and attempts are required");
        }
        if (now >= expiresAt) {
            return expiresAt;
        }
        double grown = initialBackoffMillis
                * StrictMath.pow(backoffMultiplier, Math.max(0, attemptCount - 1));
        long base = Math.min(maxBackoffMillis,
                grown >= Long.MAX_VALUE ? maxBackoffMillis : Math.round(grown));
        double unit = deterministicUnit(eventId, publishAttempt);
        double jitter = ((unit * 2.0D) - 1.0D) * (jitterPercent / 100.0D);
        long delay = Math.max(1L, Math.round(base * (1.0D + jitter)));
        long candidate;
        try {
            candidate = Math.addExact(now, delay);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Math.min(candidate, expiresAt);
    }

    private static double deterministicUnit(String eventId, int publishAttempt) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    (eventId + "\n" + publishAttempt).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        long bits = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            bits = (bits << 8) | Byte.toUnsignedLong(digest[index]);
        }
        return (bits >>> 11) * 0x1.0p-53;
    }

    private static int range(Integer value, int min, int max, String name) {
        if (value == null || value < min || value > max) {
            throw invalid(name + " is outside [" + min + ',' + max + ']');
        }
        return value;
    }

    private static long range(Long value, long min, long max, String name) {
        if (value == null || value < min || value > max) {
            throw invalid(name + " is outside [" + min + ',' + max + ']');
        }
        return value;
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("Invalid D03 outbox relay configuration: " + reason);
    }
}
