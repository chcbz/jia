package cn.jia.chat.ai;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Non-secret configuration for external chat model transport and slow-call observations. */
@ConfigurationProperties(prefix = "jia.chat.ai")
public class AiProviderProperties {
    private boolean enabled;
    private Provider provider = Provider.DISABLED;
    /** Explicit connection-establishment protection enforced by the provider transport. */
    private Duration connectBudget = Duration.ofMillis(500);
    /** Backward-compatible property name; this value is observation-only. */
    private Duration firstTokenBudget = Duration.ofMillis(2500);
    /** Backward-compatible property name; this value is observation-only. */
    private Duration totalBudget = Duration.ofSeconds(25);

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

    public void validate() {
        requirePositive("connect-budget", connectBudget);
        requirePositive("first-token-budget", firstTokenBudget);
        requirePositive("total-budget", totalBudget);
        if (connectBudget.toMillis() > Integer.MAX_VALUE) {
            throw invalid("connect-budget is too large for the provider transport");
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
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(name + " must be positive");
        }
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException("Invalid external AI configuration: " + detail);
    }

    @Override
    public String toString() {
        return "AiProviderProperties[enabled=" + enabled
                + ", provider=" + provider.id
                + ", connectBudget=" + connectBudget
                + ", firstTokenObservationThreshold=" + firstTokenBudget
                + ", totalObservationThreshold=" + totalBudget + "]";
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
