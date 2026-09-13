package cn.jia.oauth.config;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

/**
 * Single-shot provider calls outside database transactions. External authorization
 * callbacks are not ordinary synchronous APIs: do not turn their latency SLO into
 * a shared token/profile deadline. Reuse the configured transport (including its
 * network timeouts), without forwarding internal deadlines or logging secrets.
 */
@Component
public class OauthExternalHttpClient {
    private final RestTemplate restTemplate;

    public OauthExternalHttpClient(ClientHttpRequestFactory requestFactory) {
        restTemplate = new RestTemplate(requestFactory);
        // Reject non-2xx below without including provider URLs or response bodies.
        restTemplate.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) throws IOException {
                return false;
            }
        });
    }

    public ResponseEntity<String> exchange(String url, HttpMethod method, HttpEntity<?> entity) {
        requireOutsideTransaction();
        return requireSuccess(restTemplate.exchange(url, method, entity, String.class));
    }

    public ResponseEntity<String> postForEntity(String url, HttpEntity<?> entity) {
        return exchange(url, HttpMethod.POST, entity);
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OauthExternalCallRejectedException("OAuth provider calls are forbidden inside a transaction");
        }
    }

    private ResponseEntity<String> requireSuccess(ResponseEntity<String> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new OauthExternalCallRejectedException("OAuth provider returned a non-success status");
        }
        return response;
    }

    public static final class OauthExternalCallRejectedException extends RuntimeException {
        public OauthExternalCallRejectedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
