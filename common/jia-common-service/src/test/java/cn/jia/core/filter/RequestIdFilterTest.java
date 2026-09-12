package cn.jia.core.filter;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIdFilterTest {
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{8,128}");

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsLegalClientRequestIdAndReturnsIt() throws Exception {
        String requestId = "request_01-legal.id";
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/secret");
        request.addHeader(RequestIdFilter.HEADER_NAME, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcRequestId = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                mdcRequestId.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)));

        assertEquals(requestId, response.getHeader(RequestIdFilter.HEADER_NAME));
        assertEquals(requestId, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        assertEquals(requestId, mdcRequestId.get());
        assertNull(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void acceptsBoundaryLengthIdsAndRestoresFinalResponseHeader() throws Exception {
        for (String requestId : new String[]{"a".repeat(8), "b".repeat(128)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestIdFilter.HEADER_NAME, requestId);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setHeader(RequestIdFilter.HEADER_NAME, "overwritten"));

            assertEquals(requestId, response.getHeader(RequestIdFilter.HEADER_NAME));
            assertEquals(requestId, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        }
    }

    @Test
    void generatesNewIdForInvalidClientValues() throws Exception {
        for (String invalid : new String[]{null, "short", "has space", "bad/character", "a".repeat(129)}) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (invalid != null) {
                request.addHeader(RequestIdFilter.HEADER_NAME, invalid);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            String generated = response.getHeader(RequestIdFilter.HEADER_NAME);
            assertTrue(VALID_REQUEST_ID.matcher(generated).matches());
            assertNotEquals(invalid, generated);
            assertEquals(generated, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        }
    }

    @Test
    void retainsFinalIdAndMdcCorrelationAcrossAsyncRedispatch() throws Exception {
        String requestId = "async-request-id-01";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, requestId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> initialMdc = new AtomicReference<>();
        AtomicReference<String> redispatchMdc = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                initialMdc.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)));
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                redispatchMdc.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY)));

        assertEquals(requestId, initialMdc.get());
        assertEquals(requestId, redispatchMdc.get());
        assertEquals(requestId, response.getHeader(RequestIdFilter.HEADER_NAME));
        assertEquals(requestId, request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE));
        assertNull(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY));
    }

    @Test
    void correlatesAvailableTraceIdWithoutOverwritingOuterMdc() throws Exception {
        MDC.put(RequestIdFilter.MDC_REQUEST_ID_KEY, "outer-request-id");
        MDC.put("traceId", "otel-trace-id");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "trace-request-id-01");
        AtomicReference<String> requestIdInChain = new AtomicReference<>();
        AtomicReference<String> traceIdInChain = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            requestIdInChain.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY));
            traceIdInChain.set(MDC.get(RequestIdFilter.MDC_TRACE_ID_KEY));
        });

        assertEquals("trace-request-id-01", requestIdInChain.get());
        assertEquals("otel-trace-id", traceIdInChain.get());
        assertEquals("outer-request-id", MDC.get(RequestIdFilter.MDC_REQUEST_ID_KEY));
        assertNull(MDC.get(RequestIdFilter.MDC_TRACE_ID_KEY));
        assertEquals("otel-trace-id", MDC.get("traceId"));
    }

    @Test
    void readsOnlyRequestIdHeaderAndDoesNotInspectSensitiveRequestData() throws Exception {
        NoSensitiveAccessRequest request = new NoSensitiveAccessRequest();
        request.addHeader(RequestIdFilter.HEADER_NAME, "safe-request-id-01");
        request.addHeader("Authorization", "Bearer authorization-secret");
        request.addHeader("Cookie", "cookie-secret");
        request.setQueryString("token=query-secret");
        request.setContent("body-secret".getBytes());
        request.addParameter("token", "parameter-secret");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertFalse(request.sensitiveDataAccessed);
    }

    private static final class NoSensitiveAccessRequest extends MockHttpServletRequest {
        private boolean sensitiveDataAccessed;

        @Override
        public String getHeader(String name) {
            if (RequestIdFilter.HEADER_NAME.equalsIgnoreCase(name)) {
                return super.getHeader(name);
            }
            sensitiveDataAccessed = true;
            throw new AssertionError("sensitive request header must not be accessed: " + name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            sensitiveDataAccessed = true;
            throw new AssertionError("request header names must not be accessed");
        }

        @Override
        public String getQueryString() {
            sensitiveDataAccessed = true;
            throw new AssertionError("request query must not be accessed");
        }

        @Override
        public Enumeration<String> getParameterNames() {
            sensitiveDataAccessed = true;
            throw new AssertionError("request parameters must not be accessed");
        }
    }
}
