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
    void removesDeadlineHeaderAndExecutesExactlyOnceWithoutShorteningWork() throws Exception {
        MockClientHttpRequest request = request();
        request.getHeaders().add("x-request-deadline-ms", "60000");
        AtomicInteger executions = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        RequestDeadline deadline = RequestDeadline.start(2500, clock::get);
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(400));

        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(deadline)) {
            interceptor.intercept(request, new byte[]{1, 2, 3}, (actualRequest, body) -> {
                executions.incrementAndGet();
                assertNull(actualRequest.getHeaders().getFirst(RequestDeadlinePropagation.HEADER_NAME));
                assertEquals(3, body.length);
                return new MockClientHttpResponse(new byte[0], 200);
            });
        }

        assertEquals(1, executions.get());
        assertNull(request.getHeaders().get(RequestDeadlinePropagation.HEADER_NAME));
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
    void exhaustedObservationExecutesNetworkWriteOnceAndKeepsTheRealResponse() throws Exception {
        MockClientHttpRequest request = request();
        request.getHeaders().add(RequestDeadlinePropagation.HEADER_NAME, "0");
        AtomicInteger executions = new AtomicInteger();
        RequestDeadline expired = RequestDeadline.start(0);
        try (RequestDeadlineContext.Scope ignored = RequestDeadlineContext.open(expired)) {
            var response = interceptor.intercept(request, new byte[0], (actualRequest, body) -> {
                executions.incrementAndGet();
                assertNull(actualRequest.getHeaders().getFirst(RequestDeadlinePropagation.HEADER_NAME));
                return new MockClientHttpResponse(new byte[0], 201);
            });
            assertEquals(201, response.getStatusCode().value());
            org.junit.jupiter.api.Assertions.assertTrue(expired.isExpired(), "overrun remains observable");
        }
        assertEquals(1, executions.get());
    }

    private static MockClientHttpRequest request() {
        return new MockClientHttpRequest(HttpMethod.POST, URI.create("https://downstream.invalid/write"));
    }
}
