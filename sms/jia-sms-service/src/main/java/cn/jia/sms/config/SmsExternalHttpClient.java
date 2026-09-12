package cn.jia.sms.config;

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
 * Single-shot SMS HTTP boundary. Each operation is capped by the smaller of
 * its module budget and the current request deadline, and is rejected while a
 * database transaction is active.
 */
@Component
public class SmsExternalHttpClient {
    private final SmsExternalHttpTimeouts timeouts;

    public SmsExternalHttpClient(SmsExternalHttpTimeouts timeouts) {
        this.timeouts = timeouts;
    }

    public OperationBudget beginOperation() {
        return new OperationBudget(timeouts, System::nanoTime);
    }

    public <T> ResponseEntity<T> exchange(OperationBudget budget, String url, HttpMethod method,
            HttpEntity<?> entity, Class<T> responseType) {
        return requireSuccess(restTemplate(budget).exchange(url, method, entity, responseType));
    }

    public <T> ResponseEntity<T> postForEntity(OperationBudget budget, String url, HttpEntity<?> entity,
            Class<T> responseType) {
        return requireSuccess(restTemplate(budget).postForEntity(url, entity, responseType));
    }

    public <T> T getForObject(OperationBudget budget, String url, Class<T> responseType) {
        return requireSuccess(restTemplate(budget).exchange(url, HttpMethod.GET, HttpEntity.EMPTY, responseType))
                .getBody();
    }

    public <T> T postForObject(OperationBudget budget, String url, HttpEntity<?> entity, Class<T> responseType) {
        return requireSuccess(restTemplate(budget).exchange(url, HttpMethod.POST, entity, responseType)).getBody();
    }

    public CallTimeouts prepareSdkCall(OperationBudget budget) {
        requireOutsideTransaction();
        if (budget == null) {
            throw new IllegalArgumentException("SMS operation budget is required");
        }
        return budget.nextCallTimeouts();
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
        private final long startedNanos;
        private final java.util.function.LongSupplier monotonicClock;

        OperationBudget(SmsExternalHttpTimeouts timeouts, java.util.function.LongSupplier monotonicClock) {
            this.timeouts = timeouts;
            this.monotonicClock = monotonicClock;
            this.startedNanos = monotonicClock.getAsLong();
        }

        long remainingForNewCallMillis() {
            long requestAvailableMillis = RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                    timeouts.getTotalTimeoutMillis(), timeouts.getSafetyMarginMillis(),
                    SafeRequestTimeoutException.Dependency.SMS);
            long elapsedNanos = monotonicClock.getAsLong() - startedNanos;
            long elapsedMillis = elapsedNanos <= 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            long localAvailableMillis = (long) timeouts.getTotalTimeoutMillis() - elapsedMillis
                    - timeouts.getSafetyMarginMillis();
            long availableMillis = Math.min(localAvailableMillis, requestAvailableMillis);
            if (availableMillis <= 1) {
                throw new SmsExternalBudgetExhaustedException();
            }
            return availableMillis;
        }

        CallTimeouts nextCallTimeouts() {
            long availableMillis = remainingForNewCallMillis();
            int connectionRequestTimeoutMillis = (int) Math.min(timeouts.getConnectionRequestTimeoutMillis(),
                    availableMillis - 1);
            long afterPoolMillis = availableMillis - connectionRequestTimeoutMillis;
            int connectTimeoutMillis = (int) Math.min(timeouts.getConnectTimeoutMillis(), afterPoolMillis - 1);
            int readTimeoutMillis = (int) Math.min(timeouts.getReadTimeoutMillis(),
                    afterPoolMillis - connectTimeoutMillis);
            if (connectionRequestTimeoutMillis <= 0 || connectTimeoutMillis <= 0 || readTimeoutMillis <= 0) {
                throw new SmsExternalBudgetExhaustedException();
            }
            return new CallTimeouts(connectionRequestTimeoutMillis, connectTimeoutMillis, readTimeoutMillis);
        }
    }

    public static final class SmsExternalBudgetExhaustedException extends RuntimeException {
        public SmsExternalBudgetExhaustedException() {
            super("SMS external request budget exhausted before work started");
        }
    }

    public static final class SmsExternalCallRejectedException extends RuntimeException {
        public SmsExternalCallRejectedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
