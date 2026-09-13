package cn.jia.sms.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Explicit per-transport protections plus a non-enforcing slow-call observation threshold. */
@Component
public class SmsExternalHttpTimeouts {
    @Value("${sms.external-http.connection-request-timeout-ms:250}")
    private int connectionRequestTimeoutMillis = 250;

    @Value("${sms.external-http.connect-timeout-ms:500}")
    private int connectTimeoutMillis = 500;

    @Value("${sms.external-http.read-timeout-ms:1750}")
    private int readTimeoutMillis = 1750;

    /** Backward-compatible property name; this threshold is observation-only. */
    @Value("${sms.external-http.total-timeout-ms:2500}")
    private int totalTimeoutMillis = 2500;

    @PostConstruct
    public void validate() {
        if (connectionRequestTimeoutMillis <= 0 || connectTimeoutMillis <= 0 || readTimeoutMillis <= 0
                || totalTimeoutMillis <= 0) {
            throw new IllegalStateException("SMS external HTTP timeouts and observation threshold must be positive");
        }
    }

    public int getConnectionRequestTimeoutMillis() {
        return connectionRequestTimeoutMillis;
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
}
