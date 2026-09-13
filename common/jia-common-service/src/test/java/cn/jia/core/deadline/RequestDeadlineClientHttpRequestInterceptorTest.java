package cn.jia.core.deadline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequestDeadlineClientHttpRequestInterceptorTest {
    private final RequestDeadlineClientHttpRequestInterceptor interceptor =
            new RequestDeadlineClientHttpRequestInterceptor();

    @AfterEach
    void verifyNoDeadlineLeak() {
        assertFalse(RequestDeadlineContext.current().isPresent());
    }

    @Test
    void replacesCallerValueWithRemainingBudgetAndExecutesExactlyOnce() throws Exception {
        MockClientHttpRequest request = request();
        request.getHeaders().add("x-request-deadline-ms", "60000");
        AtomicInteger executions = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.start(2500, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(deadline)) {
            interceptor.intercept(request, new byte[]{1, 2, 3}, (actualRequest, body) -> {
                executions.incrementAndGet();
                assertEquals("2100", actualRequest.getHeaders().getFirst(RequestDeadlinePropagation.HEADER_NAME));
                assertEquals(3, body.length);
                return new MockClientHttpResponse(new byte[0], 200);
            });
        }

        assertEquals(1, executions.get());
        assertEquals(1, request.getHeaders().get(RequestDeadlinePropagation.HEADER_NAME).size());
    }

    @Test
    void removesReservedHeaderWithoutRequestContextAndDoesNotRetryFailures() {
        MockClientHttpRequest request = request();
        request.getHeaders().add(RequestDeadlinePropagation.HEADER_NAME, "60000");
        AtomicInteger executions = new AtomicInteger();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> interceptor.intercept(request, new byte[0], (actualRequest, body) -> {
                    executions.incrementAndGet();
                    assertNull(actualRequest.getHeaders().getFirst(RequestDeadlinePropagation.HEADER_NAME));
                    throw new IllegalStateException("synthetic downstream failure");
                }));

        assertEquals("synthetic downstream failure", failure.getMessage());
        assertEquals(1, executions.get());
    }

    @Test
    void exhaustedDeadlineFailsBeforeNetworkExecutionWithUnifiedSafeContract() {
        MockClientHttpRequest request = request();
        request.getHeaders().add(RequestDeadlinePropagation.HEADER_NAME, "60000");
        AtomicInteger executions = new AtomicInteger();

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(RequestDeadline.start(0))) {
            SafeRequestTimeoutException failure = assertThrows(SafeRequestTimeoutException.class,
                    () -> interceptor.intercept(request, new byte[0], (actualRequest, body) -> {
                        executions.incrementAndGet();
                        return new MockClientHttpResponse(new byte[0], 200);
                    }));

            assertEquals(SafeRequestTimeoutException.Failure.REQUEST_DEADLINE_EXCEEDED, failure.failure());
            assertEquals(SafeRequestTimeoutException.Dependency.HTTP, failure.dependency());
            assertEquals(SafeRequestTimeoutException.WorkState.NOT_STARTED, failure.workState());
            assertFalse(failure.retryable());
        }

        assertEquals(0, executions.get());
        assertNull(request.getHeaders().getFirst(RequestDeadlinePropagation.HEADER_NAME));
    }

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("https://downstream.invalid/write"));
    }
}
