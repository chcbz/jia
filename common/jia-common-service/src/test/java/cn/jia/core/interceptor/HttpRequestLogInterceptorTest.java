package cn.jia.core.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRequestLogInterceptorTest {
    @Test
    void requestParamsRedactOAuthTransactionMaterial() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addParameter("grant_type", "authorization_code");
        request.addParameter("code", "oauth-code-value");
        request.addParameter("code_verifier", "pkce-verifier-value");
        request.addParameter("state", "oauth-state-value");

        String logged = ReflectionTestUtils.invokeMethod(
                new HttpRequestLogInterceptor(), "requestParams", request);

        assertTrue(logged.contains("authorization_code"));
        assertTrue(logged.contains("\"code\":null"));
        assertTrue(logged.contains("\"code_verifier\":null"));
        assertTrue(logged.contains("\"state\":null"));
        assertFalse(logged.contains("oauth-code-value"));
        assertFalse(logged.contains("pkce-verifier-value"));
        assertFalse(logged.contains("oauth-state-value"));
    }
}
