package cn.jia.oauth.config;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Creates a request-scoped third-party HTTP client from the remaining callback
 * budget. It never retries, and it does not expose request URLs or response
 * bodies to logs.
 */
@Component
public class OauthExternalHttpClient {
    private final OauthExternalHttpTimeouts timeouts;

    public OauthExternalHttpClient(OauthExternalHttpTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    public CallbackBudget beginCallback() {
        return new CallbackBudget(timeouts, System::nanoTime);
    }

    public ResponseEntity<String> exchange(CallbackBudget budget, String url, HttpMethod method, HttpEntity<?> entity) {
        return restTemplate(budget).exchange(url, method, entity, String.class);
    }

    public ResponseEntity<String> postForEntity(CallbackBudget budget, String url, HttpEntity<?> entity) {
        return restTemplate(budget).postForEntity(url, entity, String.class);
    }

    private RestTemplate restTemplate(CallbackBudget budget) {
        long availableMillis = budget.remainingForNewCallMillis();
        int connectTimeoutMillis = (int) Math.min(timeouts.getConnectTimeoutMillis(), availableMillis - 1);
        int readTimeoutMillis = (int) Math.min(timeouts.getReadTimeoutMillis(), availableMillis - connectTimeoutMillis);
        if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
            throw new OauthExternalBudgetExhaustedException();
        }
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMillis);
        factory.setReadTimeout(readTimeoutMillis);
        RestTemplate restTemplate = new RestTemplate(factory);
        // Keep the shared legacy client behavior: provider status responses are parsed by the existing callback code.
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }

    public static final class CallbackBudget {
        private final OauthExternalHttpTimeouts timeouts;
        private final long startedNanos;
        private final java.util.function.LongSupplier monotonicClock;

        CallbackBudget(OauthExternalHttpTimeouts timeouts, java.util.function.LongSupplier monotonicClock) {
            this.timeouts = timeouts;
            this.monotonicClock = monotonicClock;
            this.startedNanos = monotonicClock.getAsLong();
        }

        long remainingForNewCallMillis() {
            long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
            long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            long remainingMillis = (long) timeouts.getTotalTimeoutMillis() - elapsedMillis;
            long availableMillis = remainingMillis - timeouts.getSafetyMarginMillis();
            if (availableMillis <= 1) {
                throw new OauthExternalBudgetExhaustedException();
            }
            return Math.min(availableMillis, timeouts.getTotalTimeoutMillis());
        }
    }

    public static final class OauthExternalBudgetExhaustedException extends RuntimeException {
        public OauthExternalBudgetExhaustedException() {
            super("OAuth provider request budget exhausted before work started");
        }
    }
}
