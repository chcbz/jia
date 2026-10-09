package cn.jia.oauth.config;

import cn.jia.core.util.JsonUtil;
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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Single-shot provider calls outside database transactions. External authorization
 * callbacks are not ordinary synchronous APIs: do not turn their latency SLO into
 * a shared token/profile deadline. Reuse the configured transport (including its
 * network timeouts), without forwarding internal deadlines or logging secrets.
 */
@Component
public class OauthExternalHttpClient {
    private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

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

    public ResponseEntity<byte[]> exchangeBytes(String url, HttpMethod method, HttpEntity<?> entity) {
        requireOutsideTransaction();
        return requireSuccess(restTemplate.exchange(url, method, entity, byte[].class));
    }

    public <T> T exchangeJson(String url, HttpMethod method, HttpEntity<?> entity, Class<T> responseType) {
        byte[] body = exchangeBytes(url, method, entity).getBody();
        String json = decodeStrictUtf8(body);
        try {
            return JsonUtil.getMapper().readValue(json, responseType);
        } catch (Exception exception) {
            // Do not attach the parser exception: its message may include provider response content.
            throw new OauthExternalCallRejectedException("OAuth provider returned invalid JSON");
        }
    }

    public <T> T postJson(String url, HttpEntity<?> entity, Class<T> responseType) {
        return exchangeJson(url, HttpMethod.POST, entity, responseType);
    }

    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OauthExternalCallRejectedException("OAuth provider calls are forbidden inside a transaction");
        }
    }

    private ResponseEntity<byte[]> requireSuccess(ResponseEntity<byte[]> response) {
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new OauthExternalCallRejectedException("OAuth provider returned a non-success status");
        }
        return response;
    }

    private String decodeStrictUtf8(byte[] body) {
        if (body == null || body.length == 0) {
            throw new OauthExternalCallRejectedException("OAuth provider returned an empty body");
        }
        int offset = hasUtf8Bom(body) ? UTF_8_BOM.length : 0;
        if (offset == body.length) {
            throw new OauthExternalCallRejectedException("OAuth provider returned an empty body");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body, offset, body.length - offset))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new OauthExternalCallRejectedException("OAuth provider returned invalid UTF-8 JSON");
        }
    }

    private boolean hasUtf8Bom(byte[] body) {
        return body.length >= UTF_8_BOM.length
                && body[0] == UTF_8_BOM[0]
                && body[1] == UTF_8_BOM[1]
                && body[2] == UTF_8_BOM[2];
    }

    public static final class OauthExternalCallRejectedException extends RuntimeException {
        public OauthExternalCallRejectedException(String safeMessage) {
            super(safeMessage);
        }
    }
}
