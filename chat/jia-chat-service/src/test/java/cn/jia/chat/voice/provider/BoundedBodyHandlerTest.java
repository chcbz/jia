package cn.jia.chat.voice.provider;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedBodyHandlerTest {
    @Test
    void declaredOverflowCancelsAndCompletesWithPrivacySafeLimitFailure() {
        HttpResponse.BodySubscriber<byte[]> subscriber =
                new BoundedBodyHandler(1).apply(responseInfoWithLength(2));
        AtomicBoolean cancelled = new AtomicBoolean();
        subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long count) {
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });

        CompletionException completion = assertThrows(CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        Throwable failure = completion.getCause();

        assertTrue(cancelled.get());
        assertTrue(BoundedBodyHandler.isLimitFailure(failure));
        assertNull(failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getStackTrace().length);
    }

    private static HttpResponse.ResponseInfo responseInfoWithLength(long length) {
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(
                        Map.of("Content-Length", List.of(Long.toString(length))),
                        (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
