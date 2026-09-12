package cn.jia.oauth.config;

import cn.jia.core.deadline.RequestDeadlinePropagation;
import cn.jia.core.deadline.SafeRequestTimeoutException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Creates a callback-scoped third-party HTTP client from both the local OAuth
 * budget and the request's remaining deadline. Provider calls are single-shot,
 * run outside database transactions, and never expose URLs or bodies in errors.
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
        return requireSuccess(restTemplate(budget).exchange(url, method, entity, String.class));
    }

    public ResponseEntity<String> postForEntity(CallbackBudget budget, String url, HttpEntity<?> entity) {
        return requireSuccess(restTemplate(budget).postForEntity(url, entity, String.class));
    }

    private RestTemplate restTemplate(CallbackBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("OAuth callback budget is required");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OauthExternalCallRejectedException("OAuth provider calls are forbidden inside a transaction");
        }
        CallTimeouts callTimeouts = budget.nextCallTimeouts();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(callTimeouts.connectTimeoutMillis());
        factory.setReadTimeout(callTimeouts.readTimeoutMillis());
        RestTemplate restTemplate = new RestTemplate(factory);
        // Preserve body parsing for provider-specific 2xx responses. Non-2xx is rejected below without exposing it.
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return restTemplate;
    }

    private ResponseEntity<String> requireSuccess(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new OauthExternalCallRejectedException("OAuth provider returned a non-success status");
        }
        return response;
    }

    record CallTimeouts(int connectTimeoutMillis, int readTimeoutMillis) {
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
            long requestAvailableMillis = RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                    timeouts.getTotalTimeoutMillis(), timeouts.getSafetyMarginMillis(),
                    SafeRequestTimeoutException.Dependency.HTTP);
            long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
            long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            long localAvailableMillis = (long) timeouts.getTotalTimeoutMillis() - elapsedMillis
                    - timeouts.getSafetyMarginMillis();
            long availableMillis = Math.min(localAvailableMillis, requestAvailableMillis);
            if (availableMillis <= 1) {
                throw new OauthExternalBudgetExhaustedException();
            }
            return availableMillis;
        }

        CallTimeouts nextCallTimeouts() {
            long availableMillis = remainingForNewCallMillis();
            int connectTimeoutMillis = (int) Math.min(timeouts.getConnectTimeoutMillis(), availableMillis - 1);
            int readTimeoutMillis = (int) Math.min(timeouts.getReadTimeoutMillis(),
                    availableMillis - connectTimeoutMillis);
            if (connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
                throw new OauthExternalBudgetExhaustedException();
            }
            return new CallTimeouts(connectTimeoutMillis, readTimeoutMillis);
        }
    }

    public static final class OauthExternalBudgetExhaustedException extends RuntimeException {
        public OauthExternalBudgetExhaustedException() {
            super("OAuth provider request budget exhausted before work started");
        }
    }

    public static final class OauthExternalCallRejectedException extends RuntimeException {
        public OauthExternalCallRejectedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
