package cn.jia.core.deadline;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.filter.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlineExceptionHandlerTest {
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");
    private final RequestDeadlineExceptionHandler handler = new RequestDeadlineExceptionHandler();

    @Test
    void mapsSafeDeadlineFailureToExact504JsonResultContract() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-deadline-01");

        var response = handler.handle(
                SafeRequestTimeoutException.deadlineBeforeWork(SafeRequestTimeoutException.Dependency.DATABASE),
                request);

        assertEquals(504, response.getStatusCode().value());
        assertEquals("request-deadline-01", response.getHeaders().getFirst(RequestIdFilter.HEADER_NAME));
        JsonResult<TimeoutErrorContract> body = response.getBody();
        assertNotNull(body);
        assertEquals(504, body.getStatus());
        assertEquals("E504", body.getCode());
        assertEquals("请求处理超时", body.getMsg());
        assertEquals(new TimeoutErrorContract(
                "request-deadline-01", "REQUEST_DEADLINE_EXCEEDED", "DATABASE", false), body.getData());
    }

    @Test
    void mapsDependencyUnavailableTo503WithoutProviderPayload() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-dependency-01");
        SafeRequestTimeoutException exception = SafeRequestTimeoutException.dependencyUnavailableBeforeWork(
                SafeRequestTimeoutException.Dependency.HTTP);

        var response = handler.handle(exception, request);
        JsonResult<TimeoutErrorContract> body = response.getBody();

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(body);
        assertEquals(503, body.getStatus());
        assertEquals("E503_DEPENDENCY", body.getCode());
        assertEquals("依赖服务不可用", body.getMsg());
        assertEquals("DEPENDENCY_UNAVAILABLE", body.getData().failure());
        assertEquals("HTTP", body.getData().dependency());
        assertFalse(body.getData().retryable());
        assertFalse(body.getData().requestId().contains("secret"));
    }

    @Test
    void timeoutDetailRejectsUnboundedOrUnknownFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutErrorContract("short", "REQUEST_DEADLINE_EXCEEDED", "HTTP", true));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutErrorContract("request-valid-01", "INTERNAL_STACK", "HTTP", true));
        assertThrows(IllegalArgumentException.class,
                () -> new TimeoutErrorContract("request-valid-01", "DEPENDENCY_TIMEOUT", "provider-secret", true));
    }

    @Test
    void replacesUntrustedRequestIdWithBoundedGeneratedCorrelation() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String malicious = "secret token with spaces and /path";
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, malicious);

        var response = handler.handle(
                SafeRequestTimeoutException.dependencyTimedOutSafely(
                        SafeRequestTimeoutException.Dependency.ELASTICSEARCH),
                request);
        String generated = response.getHeaders().getFirst(RequestIdFilter.HEADER_NAME);

        assertNotNull(generated);
        assertNotEquals(malicious, generated);
        assertTrue(VALID_REQUEST_ID.matcher(generated).matches());
        assertEquals(generated, response.getBody().getData().requestId());
        assertEquals(generated, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
    }
}
