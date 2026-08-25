package cn.jia.base.service.impl;

import cn.jia.base.dao.LogDao;
import cn.jia.base.entity.LogEntity;
import cn.jia.core.common.EsRequestWrapper;
import cn.jia.core.context.EsContextHolder;
import cn.jia.test.BaseMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LogServiceImplTest extends BaseMockTest {
    private static final String REDACTED_CREDENTIAL = "[REDACTED_CREDENTIAL]";
    private static final String RAW_OPENID = "oH2zD1El9hvjnWu-LRmCr-JiTuXI";
    private static final String ORDINARY_28_CHARACTER_IDENTIFIER = "operationreference1234567890";

    @Mock
    private LogDao logDao;
    @InjectMocks
    private LogServiceImpl logService;

    @AfterEach
    void clearContext() {
        EsContextHolder.clearContext();
    }

    @Test
    void omitsSensitiveHeadersCaseInsensitivelyAndPreservesAuditMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("User-Agent", "audit-client/1.0");
        request.addHeader("AUTHORIZATION", "Bearer header-secret-authorization");
        request.addHeader("pRoXy-AuThOrIzAtIoN", "Basic header-secret-proxy");
        request.addHeader("Cookie", "CTX=header-secret-cookie");
        request.addHeader("SET-cookie", "session=header-secret-set-cookie");
        request.addHeader("X_API_KEY", "header-secret-api-key");
        request.addHeader("X-Request-ID", "request-123");
        setIdentity("Jia-A", "alice");

        LogEntity persisted = persist(request);

        String headers = persisted.getHeader();
        String normalizedHeaders = headers.toLowerCase(Locale.ROOT);
        assertFalse(headers.contains("header-secret"));
        assertFalse(normalizedHeaders.contains("authorization"));
        assertFalse(normalizedHeaders.contains("cookie"));
        assertFalse(normalizedHeaders.contains("x-api-key"));
        assertTrue(headers.contains("X-Request-ID"));
        assertTrue(headers.contains("request-123"));
        assertEquals("/api/tasks", persisted.getUri());
        assertEquals("POST", persisted.getMethod());
        assertEquals("192.0.2.10", persisted.getIp());
        assertEquals("audit-client/1.0", persisted.getUserAgent());
        assertEquals("Jia-A", persisted.getJiacn());
        assertEquals("alice", persisted.getUsername());
    }

    @Test
    void sanitizesGetQueryAndStoresPathOnlyUri() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/search");
        request.setQueryString("term=water+margin&PaSsWoRd=query-password-secret"
                + "&code_verifier=query-code-secret&access_token=query-access-secret"
                + "&api%5Fkey=query-api-secret&user%5Bpassword%5D=query-nested-password-secret"
                + "&credentials%5Bpassword%5D%5Braw%5D=query-deep-password-secret");
        setIdentity("Jia-B", "reader");

        LogEntity persisted = persist(request);

        assertEquals("/search", persisted.getUri());
        assertFalse(persisted.getUri().contains("?"));
        assertTrue(persisted.getParam().contains("water margin"));
        assertNoSecret(persisted, "query-password-secret", "query-code-secret", "query-access-secret",
                "query-api-secret", "query-nested-password-secret", "query-deep-password-secret");
        assertFalse(persisted.getParam().toLowerCase(Locale.ROOT).contains("password"));
        assertFalse(persisted.getParam().toLowerCase(Locale.ROOT).contains("code_verifier"));
    }

    @Test
    void sanitizesAuthorizationAndCookieNamesInQueryAndJsonBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.setQueryString("safe=query-kept&Authorization=query-authorization-secret"
                + "&Cookie=query-cookie-secret");
        request.setContentType("application/json");
        request.setContent(("{\"safe\":\"body-kept\","
                + "\"Authorization\":\"json-authorization-secret\","
                + "\"Cookie\":\"json-cookie-secret\","
                + "\"credentials[password][raw]\":\"json-deep-password-secret\"}")
                .getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-B2", "reader");

        LogEntity persisted = persist(request);

        assertTrue(persisted.getParam().contains("query-kept"));
        assertTrue(persisted.getParam().contains("body-kept"));
        assertNoSecret(persisted, "query-authorization-secret", "query-cookie-secret",
                "json-authorization-secret", "json-cookie-secret", "json-deep-password-secret");
    }

    @Test
    void recursivelySanitizesJsonBodyWhileKeepingUsefulFields() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agent/run");
        request.setContentType("application/vnd.jia.audit+json;charset=UTF-8");
        request.setContent(("{\"task\":\"inspect\",\"password\":\"json-password-secret\","
                + "\"openid\":\"" + RAW_OPENID + "\","
                + "\"nested\":{\"CODE_VERIFIER\":\"json-code-secret\",\"safe\":\"kept\"},"
                + "\"items\":[{\"access-token\":\"json-access-secret\",\"name\":\"first\"},"
                + "{\"client_secret\":\"json-client-secret\",\"name\":\"second\"}],"
                + "\"id_token\":\"json-id-secret\"}").getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-C", "alice");

        LogEntity persisted = persist(request);

        assertTrue(persisted.getParam().contains("inspect"));
        assertTrue(persisted.getParam().contains("kept"));
        assertTrue(persisted.getParam().contains("first"));
        assertTrue(persisted.getParam().contains("second"));
        assertFalse(persisted.getParam().toLowerCase(Locale.ROOT).contains("openid"));
        assertNoSecret(persisted, "json-password-secret", "json-code-secret", "json-access-secret",
                "json-client-secret", "json-id-secret", RAW_OPENID);
    }

    @Test
    void sanitizesUrlEncodedFormIncludingEncodedAndMixedCaseNames() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/profile/update");
        request.setContentType("application/x-www-form-urlencoded;charset=UTF-8");
        request.setContent(("display_name=Song+Jiang&CoDe%5FVeRiFiEr=form-code-secret"
                + "&CLIENT_SECRET=form-client-secret&refresh_token=form-refresh-secret"
                + "&api_key=form-api-secret&note=mb-13800138000").getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-D", "alice");

        LogEntity persisted = persist(request);

        assertTrue(persisted.getParam().contains("Song Jiang"));
        assertTrue(persisted.getParam().contains(REDACTED_CREDENTIAL));
        assertNoSecret(persisted, "form-code-secret", "form-client-secret", "form-refresh-secret", "form-api-secret",
                "mb-13800138000", "13800138000");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/login", "/oauth2/token", "/oauth2/authorize", "/oauth/confirm_access",
            "/oauth2/device_authorization", "/oauth2/device_verification", "/oauth2/introspect",
            "/oauth2/revoke", "/connect/logout", "/userinfo"
    })
    void suppressesAllParametersOnExactCredentialEndpoints(String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setQueryString("safe=query-value&code=credential-query-secret");
        request.setContentType("application/json");
        request.setContent("{\"safe\":\"body-value\",\"password\":\"credential-body-secret\"}"
                .getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-E", "alice");

        LogEntity persisted = persist(request);

        assertNull(persisted.getParam());
        assertNoSecret(persisted, "credential-query-secret", "credential-body-secret");
    }

    @Test
    void suppressesCredentialParametersBehindServletContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jia/oauth2/token");
        request.setContextPath("/jia");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("code=context-code-secret&client_secret=context-client-secret"
                .getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-E2", "alice");

        LogEntity persisted = persist(request);

        assertEquals("/jia/oauth2/token", persisted.getUri());
        assertNull(persisted.getParam());
        assertNoSecret(persisted, "context-code-secret", "context-client-secret");
    }

    @Test
    void suppressesTokenEndpointWithMatrixParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/oauth2/token;jsessionid=ABC123");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("code=matrix-code-secret&client_secret=matrix-client-secret"
                .getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-E3", "alice");

        LogEntity persisted = persist(request);

        assertNull(persisted.getParam());
        assertNoSecret(persisted, "matrix-code-secret", "matrix-client-secret");
    }

    @Test
    void suppressesCredentialEndpointBehindContextPathAndMatrixParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/jia;jsessionid=CTX/oauth2/token;route=blue");
        request.setContextPath("/jia");
        request.setContentType("application/x-www-form-urlencoded");
        request.setContent("code=context-matrix-code-secret&client_secret=context-matrix-client-secret"
                .getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-E4", "alice");

        LogEntity persisted = persist(request);

        assertNull(persisted.getParam());
        assertNoSecret(persisted, "context-matrix-code-secret", "context-matrix-client-secret");
    }

    @Test
    void suppressesLogoutIdTokenHint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/connect/logout");
        request.setQueryString("id_token_hint=logout-id-token-secret&post_logout_redirect_uri=https%3A%2F%2Fclient.example%2Fdone");
        setIdentity("Jia-E5", "alice");

        LogEntity persisted = persist(request);

        assertNull(persisted.getParam());
        assertNoSecret(persisted, "logout-id-token-secret");
    }

    @Test
    void preservesPostLogoutRedirectUriAndExactOpenIdShapeCollisionAcrossAuditCarriers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/profile/preferences/" + ORDINARY_28_CHARACTER_IDENTIFIER);
        request.setRemoteAddr(ORDINARY_28_CHARACTER_IDENTIFIER);
        request.setQueryString("post_logout_redirect_uri=https%3A%2F%2Fclient.example%2Fsigned-out"
                + "&reference=" + ORDINARY_28_CHARACTER_IDENTIFIER);
        request.addHeader("User-Agent", "audit-client/1.0 " + ORDINARY_28_CHARACTER_IDENTIFIER);
        request.addHeader("X-Request-ID", ORDINARY_28_CHARACTER_IDENTIFIER);
        setIdentity("Jia-E6", ORDINARY_28_CHARACTER_IDENTIFIER);

        LogEntity persisted = persist(request);

        assertTrue(persisted.getParam().contains("post_logout_redirect_uri"));
        assertTrue(persisted.getParam().contains("client.example"));
        assertTrue(persisted.getParam().contains(ORDINARY_28_CHARACTER_IDENTIFIER));
        assertTrue(persisted.getUri().contains(ORDINARY_28_CHARACTER_IDENTIFIER));
        assertEquals(ORDINARY_28_CHARACTER_IDENTIFIER, persisted.getIp());
        assertTrue(persisted.getUserAgent().contains(ORDINARY_28_CHARACTER_IDENTIFIER));
        assertTrue(persisted.getHeader().contains(ORDINARY_28_CHARACTER_IDENTIFIER));
        assertEquals(ORDINARY_28_CHARACTER_IDENTIFIER, persisted.getUsername());
        assertFalse(persisted.getParam().contains(REDACTED_CREDENTIAL));
    }

    @Test
    void malformedJsonFailsClosedWhileRetainingSanitizedQueryMetadata() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tasks");
        request.setQueryString("operation=preview&access_token=malformed-query-secret");
        request.setContentType("application/json");
        request.setContent("{\"password\":\"malformed-body-secret\"".getBytes(StandardCharsets.UTF_8));
        setIdentity("Jia-F", "alice");

        LogEntity persisted = persist(request);

        assertTrue(persisted.getParam().contains("preview"));
        assertNoSecret(persisted, "malformed-query-secret", "malformed-body-secret");
    }

    @Test
    void redactsContextualCredentialMarkersAcrossAuditCarriersWithoutJiacnFallback() throws Exception {
        String wxLogin = "wx-" + RAW_OPENID;
        String mobileLogin = "mb-13800138000";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/" + wxLogin);
        request.setRemoteAddr("192.0.2.20");
        request.setQueryString("openid=" + RAW_OPENID + "&safe=kept");
        request.addHeader("User-Agent", "audit-client/1.0 openid=" + RAW_OPENID);
        request.addHeader("X-Request-ID", mobileLogin);
        setIdentity(null, wxLogin);

        LogEntity persisted = persist(request);

        assertNull(persisted.getJiacn());
        assertEquals("192.0.2.20", persisted.getIp());
        assertEquals(REDACTED_CREDENTIAL, persisted.getUsername());
        assertTrue(persisted.getUri().contains(REDACTED_CREDENTIAL));
        assertTrue(persisted.getUserAgent().contains(REDACTED_CREDENTIAL));
        assertTrue(persisted.getHeader().contains(REDACTED_CREDENTIAL));
        assertTrue(persisted.getParam().contains("kept"));
        assertFalse(persisted.getParam().toLowerCase(Locale.ROOT).contains("openid"));
        assertNoSecret(persisted, RAW_OPENID, wxLogin, mobileLogin, "13800138000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wx-openid-credential", "WX-OPENID-CREDENTIAL", "mb-13800138000", "Mb-13800138000"})
    void redactsFederatedAndMobileLoginIdentifiersButPreservesJiacn(String username) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/user/my");
        setIdentity("Jia-G", username);

        LogEntity persisted = persist(request);

        assertEquals("Jia-G", persisted.getJiacn());
        assertEquals(REDACTED_CREDENTIAL, persisted.getUsername());
        assertNoSecret(persisted, username);
    }

    private LogEntity persist(MockHttpServletRequest request) throws Exception {
        ReflectionTestUtils.setField(logService, "baseDao", logDao);
        when(logDao.insert(any(LogEntity.class))).thenReturn(1);
        logService.addLog(new EsRequestWrapper(request));
        ArgumentCaptor<LogEntity> captor = ArgumentCaptor.forClass(LogEntity.class);
        verify(logDao).insert(captor.capture());
        return captor.getValue();
    }

    private void setIdentity(String jiacn, String username) {
        EsContextHolder.getContext().setJiacn(jiacn);
        EsContextHolder.getContext().setUsername(username);
    }

    private void assertNoSecret(LogEntity persisted, String... secrets) {
        String allPersistedFields = String.join("|",
                stringValue(persisted.getHeader()), stringValue(persisted.getParam()), stringValue(persisted.getUri()),
                stringValue(persisted.getMethod()), stringValue(persisted.getIp()), stringValue(persisted.getUserAgent()),
                stringValue(persisted.getUsername()), stringValue(persisted.getJiacn()));
        for (String secret : secrets) {
            assertFalse(allPersistedFields.contains(secret), () -> "persisted secret: " + secret);
        }
    }

    private String stringValue(String value) {
        return value == null ? "" : value;
    }
}
