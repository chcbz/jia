package cn.jia.sms.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Optional SMS transport overrides plus a non-enforcing slow-call observation threshold.
 * Transport defaults inherit existing clients; the threshold only controls warning visibility.
 */
@Component
public class SmsExternalHttpTimeouts {
    @Value("${sms.external-http.connection-request-timeout-ms:#{null}}")
    private Integer connectionRequestTimeoutMillis;

    @Value("${sms.external-http.connect-timeout-ms:#{null}}")
    private Integer connectTimeoutMillis;

    @Value("${sms.external-http.read-timeout-ms:#{null}}")
    private Integer readTimeoutMillis;

    /** Backward-compatible property name; this default is observation-only and never cancels work. */
    @Value("${sms.external-http.total-timeout-ms:2500}")
    private Integer totalTimeoutMillis = 2500;

    @PostConstruct
    public void validate() {
        requirePositiveIfConfigured("connection-request-timeout-ms", connectionRequestTimeoutMillis);
        requirePositiveIfConfigured("connect-timeout-ms", connectTimeoutMillis);
        requirePositiveIfConfigured("read-timeout-ms", readTimeoutMillis);
        requirePositiveIfConfigured("total-timeout-ms", totalTimeoutMillis);
    }

    private void requirePositiveIfConfigured(String property, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalStateException("sms.external-http." + property + " must be positive when configured");
        }
    }

    public Integer getConnectionRequestTimeoutMillis() {
        return connectionRequestTimeoutMillis;
    }

    public Integer getConnectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public Integer getReadTimeoutMillis() {
        return readTimeoutMillis;
    }

    public Integer getTotalTimeoutMillis() {
        return totalTimeoutMillis;
    }
}
