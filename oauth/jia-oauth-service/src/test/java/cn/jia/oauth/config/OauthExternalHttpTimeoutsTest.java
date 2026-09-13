package cn.jia.oauth.config;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Keep the existing Flow selector; replace the disproven budget with incident regressions. */
class OauthExternalHttpTimeoutsTest {
    @AfterEach
    void clearTransactionFlag() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void slowTokenDoesNotPreventTheFollowingProfileRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ClientHttpRequestFactory factory = (uri, method) -> {
            if (calls.getAndIncrement() == 0) {
                // Reproduce token latency exceeding the removed 2.5s total budget.
                try {
                    Thread.sleep(2600);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
            }
            return response(HttpStatus.OK);
        };
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        assertEquals(HttpStatus.OK, client.postForEntity("https://provider.example/token", HttpEntity.EMPTY).getStatusCode());
        assertEquals(HttpStatus.OK, client.exchange("https://provider.example/user", HttpMethod.GET, HttpEntity.EMPTY).getStatusCode());
        assertEquals(2, calls.get());
    }

    @Test
    void ordinaryApiDeadlineIsNotAppliedOrForwardedToExternalAuthorization() {
        MockClientHttpRequest request = response(HttpStatus.OK);
        OauthExternalHttpClient client = new OauthExternalHttpClient((uri, method) -> request);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            assertEquals(HttpStatus.OK, client.exchange("https://provider.example/user", HttpMethod.GET,
                    HttpEntity.EMPTY).getStatusCode());
        }
        assertFalse(request.getHeaders().containsKey("X-Request-Deadline-Ms"));
    }

    @Test
    void retainsConfiguredNetworkTimeoutWithoutRetrying() throws Exception {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);
        when(factory.createRequest(any(), any())).thenThrow(new SocketTimeoutException("transport timeout"));
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        assertThrows(ResourceAccessException.class,
                () -> client.postForEntity("https://provider.example/token", HttpEntity.EMPTY));
        verify(factory, times(1)).createRequest(any(), any());
    }

    @Test
    void nonSuccessDoesNotExposeProviderSecrets() {
        OauthExternalHttpClient client = new OauthExternalHttpClient((uri, method) -> response(HttpStatus.BAD_GATEWAY));
        var failure = assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchange("https://provider.example/user?access_token=secret", HttpMethod.GET,
                        HttpEntity.EMPTY));
        assertEquals("OAuth provider returned a non-success status", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void rejectsProviderNetworkCallsInsideTransactionsBeforeConnecting() {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchange("http://127.0.0.1:1", HttpMethod.GET, HttpEntity.EMPTY));
        verifyNoInteractions(factory);
    }

    private static MockClientHttpRequest response(HttpStatus status) {
        MockClientHttpRequest request = new MockClientHttpRequest();
        request.setResponse(new MockClientHttpResponse("{}".getBytes(StandardCharsets.UTF_8), status));
        return request;
    }
}
