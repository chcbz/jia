package cn.jia.chat.voice.provider;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;

interface RealtimeWebSocketTransport {
    Connection connect(URI uri, String authorization, Duration connectTimeout, Listener listener)
            throws IOException, InterruptedException, TimeoutException;

    interface Connection {
        CompletionStage<Void> sendText(String text);

        CompletionStage<Void> close(int statusCode, String reason);

        void abort();
    }

    interface Listener {
        void onOpen(Connection connection);

        void onText(Connection connection, CharSequence data, boolean last);

        void onBinary(Connection connection);

        void onActivity();

        void onClose(int statusCode);

        void onError(Throwable failure);
    }
}
