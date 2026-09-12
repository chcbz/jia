package cn.jia.core.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HttpRequestLogInterceptorTest {
    @Test
    void neverAccessesRequestValuesOrLeaksThemToCompletionLog() throws Exception {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(0L);
        NoValueAccessRequest request = new NoValueAccessRequest();
        request.setMethod("GET\r\nmethod-secret");
        request.setRequestURI("/orders/path-secret");
        request.setQueryString("token=query-secret");
        request.addHeader("Authorization", "Bearer header-secret");
        request.addParameter("password", "parameter-secret");
        request.setContent("body-secret".getBytes());
        request.setAttribute(HttpRequestLogInterceptor.REQUEST_CORRELATION_ID_ATTRIBUTE, "external-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = captureLogs(() -> {
            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);
        });

        assertEquals(1, events.size());
        String logged = events.get(0).getFormattedMessage();
        assertTrue(logged.contains("method=UNKNOWN"));
        assertTrue(logged.contains("route=unmapped"));
        assertFalse(logged.contains("path-secret"));
        assertFalse(logged.contains("query-secret"));
        assertFalse(logged.contains("header-secret"));
        assertFalse(logged.contains("parameter-secret"));
        assertFalse(logged.contains("body-secret"));
        assertFalse(logged.contains("external-secret"));
    }

    @Test
    void usesTrustedTemplateAndLogsErrorsWithBoundedFields() throws Exception {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(1000L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/accounts/secret-account-id");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/accounts/{accountId}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);
        HandlerMethod handler = new HandlerMethod(new TestHandler(), TestHandler.class.getMethod("handle"));

        List<ILoggingEvent> events = captureLogs(() -> {
            interceptor.preHandle(request, response, handler);
            interceptor.afterCompletion(request, response, handler, new IllegalStateException("exception-secret"));
        });

        assertEquals(1, events.size());
        String logged = events.get(0).getFormattedMessage();
        assertTrue(logged.contains("method=POST"));
        assertTrue(logged.contains("route=/accounts/{accountId}"));
        assertTrue(logged.contains("status=500"));
        assertTrue(logged.contains("outcome=ERROR"));
        assertFalse(logged.contains("secret-account-id"));
        assertFalse(logged.contains("exception-secret"));
    }

    @Test
    void fastSuccessfulRequestProducesNoLog() {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(1000L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/fast-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = captureLogs(() -> {
            interceptor.preHandle(request, response, new Object());
            interceptor.afterCompletion(request, response, new Object(), null);
        });

        assertTrue(events.isEmpty());
    }

    @Test
    void asyncRedispatchRetainsInitialMonotonicStartAndCompletesOnce() {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(1000L);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/raw-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.nowNanos = 1_000_000L;
        interceptor.preHandle(request, response, new Object());

        interceptor.nowNanos = 1_500_000L;
        interceptor.preHandle(request, response, new Object());
        interceptor.nowNanos = 2_500_000L;
        List<ILoggingEvent> events = captureLogs(() -> {
            interceptor.afterCompletion(request, response, new Object(), new RuntimeException("ignored"));
            interceptor.afterCompletion(request, response, new Object(), new RuntimeException("ignored"));
        });

        assertEquals(1, events.size());
        assertTrue(events.get(0).getFormattedMessage().contains("duration_ms=1"));
    }

    @Test
    void sameThreadDistinctRequestsHaveIndependentStateAndCorrelationIds() {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(0L);
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/first-secret");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/second-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        List<ILoggingEvent> events = captureLogs(() -> {
            interceptor.preHandle(first, response, new Object());
            interceptor.afterCompletion(first, response, new Object(), null);
            interceptor.preHandle(second, response, new Object());
            interceptor.afterCompletion(second, response, new Object(), null);
        });

        assertEquals(2, events.size());
        String firstId = (String) first.getAttribute(HttpRequestLogInterceptor.REQUEST_CORRELATION_ID_ATTRIBUTE);
        String secondId = (String) second.getAttribute(HttpRequestLogInterceptor.REQUEST_CORRELATION_ID_ATTRIBUTE);
        assertNotEquals(firstId, secondId);
        assertFalse(events.get(0).getFormattedMessage().contains("first-secret"));
        assertFalse(events.get(1).getFormattedMessage().contains("second-secret"));
    }

    @Test
    void completionWithoutPreHandleDoesNotLog() {
        DeterministicInterceptor interceptor = new DeterministicInterceptor(0L);
        List<ILoggingEvent> events = captureLogs(() -> interceptor.afterCompletion(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), new RuntimeException("ignored")));
        assertTrue(events.isEmpty());
    }

    private List<ILoggingEvent> captureLogs(ThrowingRunnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLogInterceptor.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
            return List.copyOf(appender.list);
        } catch (Exception e) {
            fail(e);
            return List.of();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

    private static final class DeterministicInterceptor extends HttpRequestLogInterceptor {
        private long nowNanos;

        private DeterministicInterceptor(long slowThresholdMillis) {
            super(slowThresholdMillis);
        }

        @Override
        protected long nanoTime() {
            return nowNanos;
        }
    }

    private static final class NoValueAccessRequest extends MockHttpServletRequest {
        @Override
        public Enumeration<String> getParameterNames() {
            throw new AssertionError("request parameters must not be accessed");
        }

        @Override
        public String getParameter(String name) {
            throw new AssertionError("request parameters must not be accessed");
        }

        @Override
        public String getHeader(String name) {
            throw new AssertionError("request headers must not be accessed");
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            throw new AssertionError("request headers must not be accessed");
        }

        @Override
        public String getQueryString() {
            throw new AssertionError("query string must not be accessed");
        }

    }

    private static final class TestHandler {
        @SuppressWarnings("unused")
        public void handle() {
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
