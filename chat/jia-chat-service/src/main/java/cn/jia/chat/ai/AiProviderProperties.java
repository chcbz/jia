package cn.jia.chat.ai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded, non-secret configuration for external chat model calls. */
@ConfigurationProperties(prefix = "jia.chat.ai")
public class AiProviderProperties {
    static final Duration MAX_BUDGET = Duration.ofMinutes(5);

    private boolean enabled;
    private Provider provider = Provider.DISABLED;
    private Duration connectBudget = Duration.ofMillis(500);
    private Duration firstTokenBudget = Duration.ofMillis(2500);
    private Duration totalBudget = Duration.ofSeconds(25);
    private Duration safetyMargin = Duration.ofMillis(100);
    private int synchronousConcurrency = 16;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Duration getConnectBudget() {
        return connectBudget;
    }

    public void setConnectBudget(Duration connectBudget) {
        this.connectBudget = connectBudget;
    }

    public Duration getFirstTokenBudget() {
        return firstTokenBudget;
    }

    public void setFirstTokenBudget(Duration firstTokenBudget) {
        this.firstTokenBudget = firstTokenBudget;
    }

    public Duration getTotalBudget() {
        return totalBudget;
    }

    public void setTotalBudget(Duration totalBudget) {
        this.totalBudget = totalBudget;
    }

    public Duration getSafetyMargin() {
        return safetyMargin;
    }

    public void setSafetyMargin(Duration safetyMargin) {
        this.safetyMargin = safetyMargin;
    }

    public int getSynchronousConcurrency() {
        return synchronousConcurrency;
    }

    public void setSynchronousConcurrency(int synchronousConcurrency) {
        this.synchronousConcurrency = synchronousConcurrency;
    }

    public void validate() {
        requirePositive("connect-budget", connectBudget);
        requirePositive("first-token-budget", firstTokenBudget);
        requirePositive("total-budget", totalBudget);
        requireNonNegative("safety-margin", safetyMargin);
        if (connectBudget.compareTo(firstTokenBudget) > 0) {
            throw invalid("connect-budget must not exceed first-token-budget");
        }
        if (firstTokenBudget.compareTo(totalBudget) >= 0) {
            throw invalid("first-token-budget must be less than total-budget");
        }
        if (safetyMargin.compareTo(totalBudget) >= 0) {
            throw invalid("safety-margin must be less than total-budget");
        }
        if (synchronousConcurrency < 1 || synchronousConcurrency > 256) {
            throw invalid("synchronous-concurrency must be between 1 and 256");
        }
        provider = Objects.requireNonNull(provider, "provider");
        if (!enabled && provider != Provider.DISABLED) {
            throw invalid("provider must remain disabled while AI is disabled");
        }
        if (enabled && provider == Provider.DISABLED) {
            throw invalid("an explicit provider is required while AI is enabled");
        }
    }

    private static void requirePositive(String name, Duration value) {
        requireNonNegative(name, value);
        if (value.isZero()) {
            throw invalid(name + " must be positive");
        }
    }

    private static void requireNonNegative(String name, Duration value) {
        if (value == null || value.isNegative() || value.compareTo(MAX_BUDGET) > 0) {
            throw invalid(name + " must be between zero and five minutes");
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid external AI budget configuration: " + detail);
    }

    @Override
    public String toString() {
        return "AiProviderProperties[enabled=" + enabled
                + ", provider=" + provider.id
                + ", connectBudget=" + connectBudget
                + ", firstTokenBudget=" + firstTokenBudget
                + ", totalBudget=" + totalBudget
                + ", safetyMargin=" + safetyMargin
                + ", synchronousConcurrency=" + synchronousConcurrency + "]";
    }

    public enum Provider {
        DISABLED("disabled"),
        OPENAI("openai");

        private final String id;

        Provider(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }
}
