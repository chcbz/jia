package cn.jia.core.deadline;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestDeadlineFilterTest {
    @AfterEach
    void verifyNoThreadLeak() {
        assertFalse(RequestDeadlineContext.current().isPresent());
    }

    @Test
    void createsShadowContextWithoutTerminatingAnExpiredRequest() throws Exception {
        AtomicLong clock = new AtomicLong();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, clock::get);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/write/unchanged");
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "1200");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<RequestDeadline> seen = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            seen.set(RequestDeadlineContext.current().orElseThrow());
            assertEquals(1200, seen.get().remainingMillis());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1300));
        });

        assertEquals(200, response.getStatus());
        assertEquals(RequestDeadlineFilter.SHADOW_MODE, request.getAttribute(RequestDeadlineFilter.MODE_ATTRIBUTE));
        assertEquals(Boolean.TRUE, request.getAttribute(RequestDeadlineFilter.EXHAUSTED_ATTRIBUTE));
        assertSame(seen.get(), request.getAttribute(RequestDeadlineFilter.DEADLINE_ATTRIBUTE));
    }

    @Test
    void elapsedPerformanceTargetStillAllowsFollowingDependencyAndRecordsTheOverrun() throws Exception {
        AtomicLong clock = new AtomicLong();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, clock::get);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fixture/write");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(5000));
            assertEquals(60_000, RequestDeadlinePropagation.requireBudgetBeforeNewWork(
                    60_000, 100, SafeRequestTimeoutException.Dependency.HTTP));
            response.setStatus(201);
        });
        assertEquals(201, response.getStatus());
        assertEquals(Boolean.TRUE, request.getAttribute(RequestDeadlineFilter.EXHAUSTED_ATTRIBUTE));
    }

    @Test
    void malformedOrOversizedHeadersFallBackAndLargeValidValueIsClamped() throws Exception {
        for (String invalid : new String[]{"", "-1", "+1", " 1", "1 ", "1.0", "abc", "99999999999"}) {
            AtomicLong clock = new AtomicLong();
            RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, clock::get);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(RequestDeadlinePropagation.HEADER_NAME, invalid);
            AtomicReference<Long> remaining = new AtomicReference<>();

            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                    remaining.set(RequestDeadlineContext.current().orElseThrow().remainingMillis()));

            assertEquals(3000L, remaining.get(), "invalid header: " + invalid);
        }

        AtomicLong clock = new AtomicLong();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, clock::get);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "9999999999");
        AtomicReference<Long> remaining = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                remaining.set(RequestDeadlineContext.current().orElseThrow().remainingMillis()));
        assertEquals(3000L, remaining.get());
    }

    @Test
    void zeroIsAnExpiredShadowContextButStillPassesTheChain() throws Exception {
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, () -> 1L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "0");
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            chainCalled.set(true);
            assertTrue(RequestDeadlineContext.current().orElseThrow().isExpired());
        });

        assertTrue(chainCalled.get());
        assertEquals(Boolean.TRUE, request.getAttribute(RequestDeadlineFilter.EXHAUSTED_ATTRIBUTE));
    }

    @Test
    void asyncRedispatchReusesTheOriginalAbsoluteBudgetAndIgnoresReplacementHeader() throws Exception {
        AtomicLong clock = new AtomicLong();
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, clock::get);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "2000");
        AtomicReference<RequestDeadline> initial = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                initial.set(RequestDeadlineContext.current().orElseThrow()));

        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        request.removeHeader(RequestDeadlinePropagation.HEADER_NAME);
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "3000");
        request.setDispatcherType(DispatcherType.ASYNC);
        AtomicReference<RequestDeadline> redispatched = new AtomicReference<>();
        AtomicReference<Long> remaining = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            redispatched.set(RequestDeadlineContext.current().orElseThrow());
            remaining.set(redispatched.get().remainingMillis());
        });

        assertSame(initial.get(), redispatched.get());
        assertEquals(1500L, remaining.get());
    }

    @Test
    void authenticationBrowserFlowsAreNotBoundByTheGenericShadowDeadline() throws Exception {
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, () -> 0L);
        for (String path : new String[]{
                "/login", "/login/index.html", "/oauth/third-party/github",
                "/oauth/confirm_access", "/oauth2/authorize"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
                chainCalled.set(true);
                assertFalse(RequestDeadlineContext.current().isPresent(), path);
            });

            assertTrue(chainCalled.get(), path);
            assertEquals(null, request.getAttribute(RequestDeadlineFilter.DEADLINE_ATTRIBUTE), path);
        }
    }

    @Test
    void authenticationPathIsResolvedAfterTheServletContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/jia/login");
        request.setContextPath("/jia");
        AtomicReference<Boolean> sawDeadline = new AtomicReference<>(true);

        new RequestDeadlineFilter(3000, () -> 0L)
                .doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                        sawDeadline.set(RequestDeadlineContext.current().isPresent()));

        assertFalse(sawDeadline.get());
    }

    @Test
    void unrelatedOauthRoutesKeepTheGenericShadowDeadline() throws Exception {
        RequestDeadlineFilter filter = new RequestDeadlineFilter(3000, () -> 0L);
        for (String path : new String[]{"/oauth/clientid", "/oauth2/token", "/oauth2/jwks"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            AtomicReference<Boolean> sawDeadline = new AtomicReference<>(false);

            filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) ->
                    sawDeadline.set(RequestDeadlineContext.current().isPresent()));

            assertTrue(sawDeadline.get(), path);
        }
    }

    @Test
    void readsOnlyTheAllowlistedDeadlineHeader() throws Exception {
        NoSensitiveAccessRequest request = new NoSensitiveAccessRequest();
        request.addHeader(RequestDeadlinePropagation.HEADER_NAME, "2500");
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "secret-cookie");
        request.setQueryString("token=secret");
        request.addParameter("token", "secret");
        request.setContent("secret-body".getBytes());

        new RequestDeadlineFilter(3000, () -> 0L)
                .doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> { });

        assertFalse(request.sensitiveDataAccessed);
    }

    private static final class NoSensitiveAccessRequest extends MockHttpServletRequest {
        private boolean sensitiveDataAccessed;

        @Override
        public String getHeader(String name) {
            if (RequestDeadlinePropagation.HEADER_NAME.equalsIgnoreCase(name)) {
                return super.getHeader(name);
            }
            sensitiveDataAccessed = true;
            throw new AssertionError("sensitive header must not be read: " + name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            sensitiveDataAccessed = true;
            throw new AssertionError("header enumeration is forbidden");
        }

        @Override
        public String getQueryString() {
            sensitiveDataAccessed = true;
            throw new AssertionError("query access is forbidden");
        }

        @Override
        public Enumeration<String> getParameterNames() {
            sensitiveDataAccessed = true;
            throw new AssertionError("parameter access is forbidden");
        }
    }
}
