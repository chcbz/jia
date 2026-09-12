package cn.jia.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Independent D09 safe gates; asynchronous redrive acceptance defaults fail-closed. */
@ConfigurationProperties(prefix = "agent.rabbit-operations")
public record AgentCommandOperationsProperties(
        boolean readEnabled,
        boolean redriveEnabled,
        boolean asyncRedriveEnabled,
        boolean reissueEnabled,
        Integer maxPageSize,
        Integer dlqScanLimit,
        Long confirmTimeoutMillis,
        Long expiryProximityMillis) {
    @ConstructorBinding
    public AgentCommandOperationsProperties {
        maxPageSize = maxPageSize == null ? 100 : maxPageSize;
        dlqScanLimit = dlqScanLimit == null ? 100 : dlqScanLimit;
        confirmTimeoutMillis = confirmTimeoutMillis == null ? 10_000L : confirmTimeoutMillis;
        expiryProximityMillis = expiryProximityMillis == null ? 300_000L : expiryProximityMillis;
        if (maxPageSize < 1 || maxPageSize > 200
                || dlqScanLimit < 1 || dlqScanLimit > 500
                || confirmTimeoutMillis < 100 || confirmTimeoutMillis > 60_000
                || expiryProximityMillis < 1_000 || expiryProximityMillis > 86_400_000) {
            throw new IllegalStateException("Invalid D09 Rabbit operations bounds");
        }
        if ((redriveEnabled || asyncRedriveEnabled || reissueEnabled) && !readEnabled) {
            throw new IllegalStateException("D09 write operations require read-enabled");
        }
    }

    public AgentCommandOperationsProperties(
            boolean readEnabled,
            boolean redriveEnabled,
            boolean reissueEnabled,
            Integer maxPageSize,
            Integer dlqScanLimit,
            Long confirmTimeoutMillis,
            Long expiryProximityMillis) {
        this(readEnabled, redriveEnabled, false, reissueEnabled,
                maxPageSize, dlqScanLimit, confirmTimeoutMillis, expiryProximityMillis);
    }

    public boolean anyEnabled() {
        return readEnabled || redriveEnabled || asyncRedriveEnabled || reissueEnabled;
    }
}
