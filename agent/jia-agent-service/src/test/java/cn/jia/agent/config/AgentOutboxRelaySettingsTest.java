package cn.jia.agent.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOutboxRelaySettingsTest {
    @Test
    void frozenDefaultsAreExact() {
        AgentOutboxRelaySettings settings = settings(publish(null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        assertEquals(50, settings.batchSize());
        assertEquals(4, settings.overscan());
        assertEquals(250, settings.pollDelayMillis());
        assertEquals(30_000, settings.leaseMillis());
        assertEquals(5_000, settings.confirmTimeoutMillis());
        assertEquals(50, settings.maxInflight());
        assertEquals(20, settings.maxAttempts());
        assertEquals(1_000, settings.initialBackoffMillis());
        assertEquals(2.0D, settings.backoffMultiplier());
        assertEquals(300_000, settings.maxBackoffMillis());
        assertEquals(20, settings.jitterPercent());
        assertEquals(7_000, settings.shutdownDrainMillis());
        assertEquals(131_072, settings.maxWireBytes());
        assertEquals(200, settings.discoveryLimit(50));
    }

    @Test
    void configurationRangesAndCrossFieldInvariantsFailFast() {
        assertThrows(IllegalStateException.class, () -> settings(publish(
                0, null, null, null, null, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> settings(publish(
                null, null, null, 14_999L, 5_000L, null, null, null, null, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> settings(publish(
                null, null, null, 30_000L, null, null, null, null, null, null, null, 30_000L, null)));
        assertThrows(IllegalStateException.class, () -> settings(publish(
                null, null, null, null, null, null, null, 10_000L, null, 1_000L, null, null, null)));
        assertThrows(IllegalStateException.class, () -> settings(publish(
                null, null, null, null, null, null, null, null, 0.5D, null, null, null, null)));
        assertThrows(IllegalStateException.class, () -> settings(publish(
                null, null, null, null, null, null, null, null, null, null, null, null, 131_073)));
    }

    @Test
    void jitterIsDeterministicAttemptScopedAndWithinFrozenBand() {
        AgentOutboxRelaySettings settings = settings(publish(null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        long now = 1_000_000L;
        long first = settings.nextRetryAt("event-a", 2, 1, now, Long.MAX_VALUE);
        assertEquals(first, settings.nextRetryAt("event-a", 2, 1, now, Long.MAX_VALUE));
        assertNotEquals(first, settings.nextRetryAt("event-a", 3, 1, now, Long.MAX_VALUE));
        assertTrue(first - now >= 800 && first - now <= 1_200, Long.toString(first - now));
        long capped = settings.nextRetryAt("event-a", 30, 20, now, Long.MAX_VALUE);
        assertTrue(capped - now >= 240_000 && capped - now <= 360_000,
                Long.toString(capped - now));
    }

    @Test
    void backoffCrossingExpiryRemainsRetryAtExpiryUntilExpiryScan() {
        AgentOutboxRelaySettings settings = settings(publish(null, null, null, null, null,
                null, null, null, null, null, null, null, null));
        assertEquals(1_000_100L,
                settings.nextRetryAt("event-a", 2, 1, 1_000_000L, 1_000_100L));
        assertEquals(1_000_000L,
                settings.nextRetryAt("event-a", 2, 1, 1_000_000L, 1_000_000L));
    }

    private static AgentOutboxRelaySettings settings(
            AgentRabbitSafetyProperties.RabbitPublish publish) {
        return new AgentOutboxRelaySettings(publish);
    }

    private static AgentRabbitSafetyProperties.RabbitPublish publish(
            Integer batch, Integer overscan, Long poll, Long lease, Long confirm,
            Integer inflight, Integer attempts, Long initial, Double multiplier,
            Long max, Integer jitter, Long drain, Integer wire) {
        return new AgentRabbitSafetyProperties.RabbitPublish(true, batch, overscan, poll,
                lease, confirm, inflight, attempts, initial, multiplier, max, jitter, drain, wire);
    }
}
