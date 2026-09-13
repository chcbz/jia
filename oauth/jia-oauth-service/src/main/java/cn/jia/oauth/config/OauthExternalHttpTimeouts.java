package cn.jia.oauth.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bounded, per-callback budgets for OAuth provider HTTP calls. These calls run
 * before durable local login work, so an exhausted budget fails before another
 * third-party request is started.
 */
@Component
public class OauthExternalHttpTimeouts {
    @Value("${oauth.external-http.connect-timeout-ms:500}")
    private int connectTimeoutMillis = 500;

    @Value("${oauth.external-http.read-timeout-ms:1750}")
    private int readTimeoutMillis = 1750;

    @Value("${oauth.external-http.total-timeout-ms:2500}")
    private int totalTimeoutMillis = 2500;

    @Value("${oauth.external-http.safety-margin-ms:100}")
    private int safetyMarginMillis = 100;

    @PostConstruct
    public void validate() {
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0 || totalTimeoutMillis <= 0
                || safetyMarginMillis < 0) {
            throw new IllegalStateException("OAuth external HTTP timeouts must be positive");
        }
        if ((long) connectTimeoutMillis + readTimeoutMillis > totalTimeoutMillis) {
            throw new IllegalStateException("OAuth external HTTP phase timeouts exceed total timeout budget");
        }
        if (safetyMarginMillis >= totalTimeoutMillis) {
            throw new IllegalStateException("OAuth external HTTP safety margin must leave time for a request");
        }
    }

    public int getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public int getTotalTimeoutMillis() {
        return totalTimeoutMillis;
    }

    public int getSafetyMarginMillis() {
        return safetyMarginMillis;
    }
}
