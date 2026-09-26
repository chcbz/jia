package cn.jia.oauth.config;

import cn.jia.core.deadline.RequestDeadline;
import cn.jia.core.deadline.RequestDeadlineContext;
import cn.jia.oauth.dto.WeiXinOauthUserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
            return response(HttpStatus.OK, "{}".getBytes(StandardCharsets.UTF_8), null);
        };
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        assertEquals(HttpStatus.OK, client.exchangeBytes(
                "https://provider.example/token", HttpMethod.POST, HttpEntity.EMPTY).getStatusCode());
        assertEquals(HttpStatus.OK, client.exchangeBytes(
                "https://provider.example/user", HttpMethod.GET, HttpEntity.EMPTY).getStatusCode());
        assertEquals(2, calls.get());
    }

    @Test
    void ordinaryApiDeadlineIsNotAppliedOrForwardedToExternalAuthorization() {
        MockClientHttpRequest request = response(
                HttpStatus.OK, "{}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
        OauthExternalHttpClient client = new OauthExternalHttpClient((uri, method) -> request);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            assertEquals(HttpStatus.OK, client.exchangeBytes(
                    "https://provider.example/user", HttpMethod.GET, HttpEntity.EMPTY).getStatusCode());
        }
        assertNull(request.getHeaders().getFirst("X-Request-Deadline-Ms"));
    }

    @Test
    void retainsConfiguredNetworkTimeoutWithoutRetrying() throws Exception {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);
        when(factory.createRequest(any(), any())).thenThrow(new SocketTimeoutException("transport timeout"));
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        assertThrows(ResourceAccessException.class,
                () -> client.exchangeBytes("https://provider.example/token", HttpMethod.POST, HttpEntity.EMPTY));
        verify(factory, times(1)).createRequest(any(), any());
    }

    @Test
    void nonSuccessDoesNotExposeProviderSecrets() {
        OauthExternalHttpClient client = new OauthExternalHttpClient((uri, method) -> response(
                HttpStatus.BAD_GATEWAY, "secret response body".getBytes(StandardCharsets.UTF_8), null));
        var failure = assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchangeBytes("https://provider.example/user?access_token=secret",
                        HttpMethod.GET, HttpEntity.EMPTY));
        assertEquals("OAuth provider returned a non-success status", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void rejectsProviderNetworkCallsInsideTransactionsBeforeConnecting() {
        ClientHttpRequestFactory factory = mock(ClientHttpRequestFactory.class);
        OauthExternalHttpClient client = new OauthExternalHttpClient(factory);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchangeBytes("http://127.0.0.1:1", HttpMethod.GET, HttpEntity.EMPTY));
        verifyNoInteractions(factory);
    }

    @Test
    void decodesUtf8ChineseWithoutCharset() {
        WeiXinOauthUserDTO user = exchangeProfile(
                "{\"nickname\":\"陈惠超\"}".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);

        assertEquals("陈惠超", user.getNickname());
    }

    @Test
    void preservesEmojiFromUtf8Json() {
        WeiXinOauthUserDTO user = exchangeProfile(
                "{\"nickname\":\"陈惠超😀\"}".getBytes(StandardCharsets.UTF_8), new MediaType("application", "json", StandardCharsets.UTF_8));

        assertEquals("陈惠超😀", user.getNickname());
    }

    @Test
    void stripsOnlyLeadingUtf8BomBeforeParsing() {
        byte[] json = "{\"nickname\":\"陈惠超\"}".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[json.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(json, 0, withBom, 3, json.length);

        assertEquals("陈惠超", exchangeProfile(withBom, MediaType.APPLICATION_JSON).getNickname());
    }

    @Test
    void malformedUtf8FailsWithoutReplacementOrSensitiveCause() {
        byte[] prefix = "{\"nickname\":\"".getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(prefix, prefix.length + 2 + suffix.length);
        malformed[prefix.length] = (byte) 0xC3;
        malformed[prefix.length + 1] = 0x28;
        System.arraycopy(suffix, 0, malformed, prefix.length + 2, suffix.length);
        OauthExternalHttpClient client = client(HttpStatus.OK, malformed, MediaType.APPLICATION_JSON);

        var failure = assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> client.exchangeJson("https://provider.example/user?access_token=secret",
                        HttpMethod.GET, HttpEntity.EMPTY, WeiXinOauthUserDTO.class));

        assertEquals("OAuth provider returned invalid UTF-8 JSON", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("secret"));
        assertFalse(failure.toString().contains("�"));
    }

    @Test
    void emptyOrMalformedJsonFailsWithSafeMessages() {
        OauthExternalHttpClient emptyClient = client(HttpStatus.OK, new byte[0], MediaType.APPLICATION_JSON);
        var empty = assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> emptyClient.exchangeJson("https://provider.example/user", HttpMethod.GET,
                        HttpEntity.EMPTY, WeiXinOauthUserDTO.class));
        assertEquals("OAuth provider returned an empty body", empty.getMessage());

        OauthExternalHttpClient malformedJsonClient = client(HttpStatus.OK,
                "{\"nickname\":\"secret-name\"".getBytes(StandardCharsets.UTF_8), MediaType.APPLICATION_JSON);
        var malformed = assertThrows(OauthExternalHttpClient.OauthExternalCallRejectedException.class,
                () -> malformedJsonClient.exchangeJson("https://provider.example/user?token=secret",
                        HttpMethod.GET, HttpEntity.EMPTY, WeiXinOauthUserDTO.class));
        assertEquals("OAuth provider returned invalid JSON", malformed.getMessage());
        assertNull(malformed.getCause());
        assertFalse(malformed.toString().contains("secret-name"));
    }

    private static WeiXinOauthUserDTO exchangeProfile(byte[] body, MediaType contentType) {
        return client(HttpStatus.OK, body, contentType).exchangeJson(
                "https://provider.example/user", HttpMethod.GET, HttpEntity.EMPTY, WeiXinOauthUserDTO.class);
    }

    private static OauthExternalHttpClient client(HttpStatus status, byte[] body, MediaType contentType) {
        return new OauthExternalHttpClient((uri, method) -> response(status, body, contentType));
    }

    private static MockClientHttpRequest response(HttpStatus status, byte[] body, MediaType contentType) {
        MockClientHttpRequest request = new MockClientHttpRequest();
        MockClientHttpResponse response = new MockClientHttpResponse(body, status);
        if (contentType != null) {
            response.getHeaders().setContentType(contentType);
        }
        request.setResponse(response);
        return request;
    }
}
