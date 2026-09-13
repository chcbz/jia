package cn.jia.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Arrays;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    @Test
    void credentialedCorsAlwaysAllowsSseResumeAndReaderMutationPreflightHeadersForTrustedOrigins() {
        CorsConfig config = configured(new String[]{"https://kit.chaoyoufan.cn", "https://api.chaoyoufan.cn"});
        CorsConfiguration cors = config.buildConfig();
        assertEquals(List.of("https://kit.chaoyoufan.cn", "https://api.chaoyoufan.cn"),
                cors.getAllowedOriginPatterns());
        assertEquals("https://api.chaoyoufan.cn", cors.checkOrigin("https://api.chaoyoufan.cn"));
        assertNull(cors.checkOrigin("https://untrusted.example"));
        assertEquals(List.of("Authorization", "Content-Type", "X-API-Key", "Idempotency-Key", "If-Match", "Last-Event-ID", "X-Request-Id"),
                cors.getAllowedHeaders());
        assertEquals(List.of("authorization", "content-type", "idempotency-key", "if-match", "last-event-id", "x-request-id"),
                cors.checkHeaders(List.of("authorization", "content-type", "idempotency-key", "if-match", "last-event-id", "x-request-id")));
        assertTrue(cors.getAllowedMethods().containsAll(List.of("POST", "OPTIONS")));
        assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        assertEquals(Ordered.HIGHEST_PRECEDENCE,
                config.corsFilterFilterRegistrationBean().getOrder());
    }

    @Test
    void wildcardOriginFailsFastWhenCredentialsAreEnabled() {
        assertThrows(IllegalStateException.class, () -> configured(new String[]{"*"}).buildConfig());
    }

    @Test
    void rumPreflightAllowsEveryRequestedHeaderWithoutReachingAuthentication() throws Exception {
        var filter = configured(new String[]{"https://kit.chaoyoufan.cn"})
                .corsFilterFilterRegistrationBean().getFilter();
        var request = new MockHttpServletRequest("OPTIONS", "/agent/map");
        request.addHeader("Origin", "https://kit.chaoyoufan.cn");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "authorization,content-type,x-request-id");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("preflight must not require authentication"));
        assertEquals(200, response.getStatus());
        assertEquals("https://kit.chaoyoufan.cn", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"));
        var headers = Arrays.stream(response.getHeader("Access-Control-Allow-Headers").split(","))
                .map(String::trim).toList();
        assertTrue(headers.containsAll(List.of("authorization", "content-type", "x-request-id")));
    }

    @Test
    void unauthorizedResponseRemainsReadableByTrustedFrontendButUntrustedOriginIsRejected() throws Exception {
        var filter = configured(new String[]{"https://kit.chaoyoufan.cn"})
                .corsFilterFilterRegistrationBean().getFilter();
        var request = new MockHttpServletRequest("GET", "/agent/map");
        request.addHeader("Origin", "https://kit.chaoyoufan.cn");
        request.addHeader("X-Request-Id", "fixture-request");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                ((jakarta.servlet.http.HttpServletResponse) res).setStatus(401));
        assertEquals(401, response.getStatus());
        assertEquals("https://kit.chaoyoufan.cn", response.getHeader("Access-Control-Allow-Origin"));

        var untrusted = new MockHttpServletRequest("OPTIONS", "/agent/map");
        untrusted.addHeader("Origin", "https://untrusted.example");
        untrusted.addHeader("Access-Control-Request-Method", "GET");
        untrusted.addHeader("Access-Control-Request-Headers", "x-request-id");
        var rejected = new MockHttpServletResponse();
        filter.doFilter(untrusted, rejected, (req, res) -> fail("untrusted origin must not proceed"));
        assertEquals(403, rejected.getStatus());
        assertNull(rejected.getHeader("Access-Control-Allow-Origin"));
    }

    private static CorsConfig configured(String[] origins) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "allowedOriginPatterns", origins);
        ReflectionTestUtils.setField(config, "allowedMethods", new String[]{"GET", "POST", "OPTIONS"});
        ReflectionTestUtils.setField(config, "allowedHeaders", new String[]{"Authorization", "Content-Type", "X-API-Key"});
        return config;
    }
}
