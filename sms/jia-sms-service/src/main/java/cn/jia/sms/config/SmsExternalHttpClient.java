package cn.jia.sms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Single-shot SMS HTTP boundary. Explicit transport timeouts remain enforced,
 * while the former API/operation total budget is retained only as a safe slow-call observation.
 * Calls are rejected while a database transaction is active.
 */
@Component
public class SmsExternalHttpClient {
    private static final Logger log = LoggerFactory.getLogger(SmsExternalHttpClient.class);

    private final SmsExternalHttpTimeouts timeouts;

    public SmsExternalHttpClient(SmsExternalHttpTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    public OperationBudget beginOperation() {
        return new OperationBudget(timeouts, System::nanoTime);
    }

    public <T> ResponseEntity<T> exchange(OperationBudget budget, String url, HttpMethod method,
            HttpEntity<?> entity, Class<T> responseType) {
        return observedHttpCall(budget,
                template -> requireSuccess(template.exchange(url, method, entity, responseType)));
    }

    public <T> ResponseEntity<T> postForEntity(OperationBudget budget, String url, HttpEntity<?> entity,
            Class<T> responseType) {
        return observedHttpCall(budget,
                template -> requireSuccess(template.postForEntity(url, entity, responseType)));
    }

    public <T> T getForObject(OperationBudget budget, String url, Class<T> responseType) {
        return observedHttpCall(budget, template -> requireSuccess(
                template.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, responseType)).getBody());
    }

    public <T> T postForObject(OperationBudget budget, String url, HttpEntity<?> entity, Class<T> responseType) {
        return observedHttpCall(budget, template -> requireSuccess(
                template.exchange(url, HttpMethod.POST, entity, responseType)).getBody());
    }

    public CallTimeouts prepareSdkCall(OperationBudget budget) {
        requireOutsideTransaction();
        if (budget == null) {
            throw new IllegalArgumentException("SMS operation observation is required");
        }
        return budget.nextCallTimeouts();
    }

    private <T> T observedHttpCall(OperationBudget budget, Function<RestTemplate, T> call) {
        RestTemplate template = restTemplate(budget);
        long startedNanos = budget.markCallStarted();
        String outcome = "success";
        try {
            return call.apply(template);
        } catch (RuntimeException | Error failure) {
            outcome = "failure";
            throw failure;
        } finally {
            budget.observeIfSlow("http", startedNanos, outcome);
        }
    }

    private RestTemplate restTemplate(OperationBudget budget) {
        CallTimeouts callTimeouts = prepareSdkCall(budget);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(callTimeouts.connectTimeoutMillis());
        factory.setReadTimeout(callTimeouts.readTimeoutMillis());
        RestTemplate template = new RestTemplate(factory);
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
        return template;
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new SmsExternalCallRejectedException("SMS external calls are forbidden inside a transaction");
        }
    }

    private <T> ResponseEntity<T> requireSuccess(ResponseEntity<T> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new SmsExternalCallRejectedException("SMS dependency returned a non-success status");
        }
        return response;
    }

    public record CallTimeouts(int connectionRequestTimeoutMillis, int connectTimeoutMillis, int readTimeoutMillis) {
    }

    public static final class OperationBudget {
        private final SmsExternalHttpTimeouts timeouts;
        private final LongSupplier monotonicClock;

        OperationBudget(SmsExternalHttpTimeouts timeouts, LongSupplier monotonicClock) {
            this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
            this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        }

        CallTimeouts nextCallTimeouts() {
            return new CallTimeouts(timeouts.getConnectionRequestTimeoutMillis(),
                    timeouts.getConnectTimeoutMillis(), timeouts.getReadTimeoutMillis());
        }

        long markCallStarted() {
            return monotonicClock.getAsLong();
        }

        void observeIfSlow(String transport, long startedNanos, String outcome) {
            long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
            long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            if (elapsedMillis >= timeouts.getTotalTimeoutMillis()) {
                log.warn("Slow SMS external call observed: transport={}, elapsedMs={}, thresholdMs={}, outcome={}",
                        transport, elapsedMillis, timeouts.getTotalTimeoutMillis(), outcome);
            }
        }
    }

    public static final class SmsExternalCallRejectedException extends RuntimeException {
        public SmsExternalCallRejectedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
