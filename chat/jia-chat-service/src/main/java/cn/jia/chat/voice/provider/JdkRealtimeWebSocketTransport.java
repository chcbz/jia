package cn.jia.chat.voice.provider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class JdkRealtimeWebSocketTransport implements RealtimeWebSocketTransport {
    @Override
    public Connection connect(
            URI uri, String authorization, Duration connectTimeout, Listener listener)
            throws IOException, InterruptedException, TimeoutException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        AdapterListener adapter = new AdapterListener(listener);
        CompletableFuture<WebSocket> opening = client.newWebSocketBuilder()
                .connectTimeout(connectTimeout)
                .header("Authorization", authorization)
                .buildAsync(uri, adapter);
        WebSocket socket = awaitOpening(opening, adapter);
        return adapter.connection(socket);
    }

    static WebSocket awaitOpening(
            CompletableFuture<WebSocket> opening, AdapterListener adapter)
            throws IOException, InterruptedException, TimeoutException {
        try {
            return opening.get();
        } catch (InterruptedException exception) {
            adapter.cancel();
            opening.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            adapter.cancel();
            opening.cancel(true);
            Throwable cause = exception.getCause();
            if (cause instanceof HttpTimeoutException timeoutCause) {
                TimeoutException timeout = new TimeoutException(
                        "realtime websocket opening handshake timed out");
                timeout.initCause(timeoutCause);
                throw timeout;
            }
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("realtime websocket connection failed", cause);
        }
    }

    static final class AdapterListener implements WebSocket.Listener {
        private final Listener delegate;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile AdapterConnection connection;

        AdapterListener(Listener delegate) {
            this.delegate = delegate;
        }

        void cancel() {
            cancelled.set(true);
            AdapterConnection current = connection;
            if (current != null) {
                current.abort();
            }
        }

        private AdapterConnection connection(WebSocket socket) {
            AdapterConnection existing = connection;
            if (existing != null) {
                return existing;
            }
            synchronized (this) {
                if (connection == null) {
                    connection = new AdapterConnection(socket);
                }
                return connection;
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            AdapterConnection current = connection(webSocket);
            if (cancelled.get()) {
                current.abort();
                return;
            }
            delegate.onOpen(current);
            if (cancelled.get()) {
                current.abort();
            } else {
                webSocket.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (cancelled.get()) {
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            delegate.onText(connection(webSocket), data, last);
            if (cancelled.get()) {
                webSocket.abort();
            } else {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            if (cancelled.get()) {
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            delegate.onBinary(connection(webSocket));
            if (cancelled.get()) {
                webSocket.abort();
            } else {
                webSocket.request(1);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            if (cancelled.get()) {
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            delegate.onActivity();
            webSocket.request(1);
            return WebSocket.Listener.super.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            if (cancelled.get()) {
                webSocket.abort();
                return CompletableFuture.completedFuture(null);
            }
            delegate.onActivity();
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (cancelled.get()) {
                return CompletableFuture.completedFuture(null);
            }
            delegate.onClose(statusCode);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (!cancelled.get()) {
                delegate.onError(error);
            }
        }
    }

    static final class AdapterConnection implements Connection {
        private final WebSocket socket;
        private CompletableFuture<Void> sendTail = CompletableFuture.completedFuture(null);

        AdapterConnection(WebSocket socket) {
            this.socket = socket;
        }

        @Override
        public synchronized CompletionStage<Void> sendText(String text) {
            sendTail = sendTail.thenCompose(ignored -> socket.sendText(text, true))
                    .thenApply(ignored -> null);
            return sendTail;
        }

        @Override
        public CompletionStage<Void> close(int statusCode, String reason) {
            return socket.sendClose(statusCode, reason).thenApply(ignored -> null);
        }

        @Override
        public void abort() {
            socket.abort();
        }
    }
}
