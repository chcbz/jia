package cn.jia.chat.voice.provider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Cancels the HTTP body subscription as soon as the declared or accumulated bound is exceeded. */
final class BoundedBodyHandler implements HttpResponse.BodyHandler<byte[]> {
    private final int maximum;

    BoundedBodyHandler(int maximum) {
        this.maximum = maximum;
    }

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo responseInfo) {
        long declared = responseInfo.headers().firstValueAsLong("Content-Length").orElse(-1);
        return new Subscriber(maximum, declared > maximum);
    }

    static boolean isLimitFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof BodyLimitException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class Subscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final boolean rejectDeclared;
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private int total;

        private Subscriber(int maximum, boolean rejectDeclared) {
            this.maximum = maximum;
            this.rejectDeclared = rejectDeclared;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (rejectDeclared) {
                subscription.cancel();
                body.completeExceptionally(new BodyLimitException());
            } else {
                subscription.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            try {
                for (ByteBuffer buffer : buffers) {
                    int remaining = buffer.remaining();
                    total = Math.addExact(total, remaining);
                    if (total > maximum) {
                        subscription.cancel();
                        body.completeExceptionally(new BodyLimitException());
                        return;
                    }
                    byte[] chunk = new byte[remaining];
                    buffer.get(chunk);
                    output.write(chunk);
                }
            } catch (RuntimeException exception) {
                subscription.cancel();
                body.completeExceptionally(new BodyLimitException());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) {
                body.complete(output.toByteArray());
            }
        }
    }

    private static final class BodyLimitException extends IOException {
        private static final long serialVersionUID = 1L;

        private BodyLimitException() {
            super((String) null);
            initCause(null);
        }

        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
