package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiCompatibleVoiceProviderTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void transcriptionTimeoutDispatchesExactlyOnceAndReturnsAmbiguousTimeout() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server();
        server.createContext("/audio/transcriptions", exchange -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(250);
                byte[] body = "{\"text\":\"late\"}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        VoiceSpeechProperties properties = configured();
        properties.setProviderDeadlineMillis(50);
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1, 2, 3});
        try {
            OpenAiCompatibleSpeechTranscriptionProvider provider =
                    new OpenAiCompatibleSpeechTranscriptionProvider(properties, new ObjectMapper());
            SpeechProviderException error = assertThrows(SpeechProviderException.class,
                    () -> provider.transcribe(new SpeechTranscriptionRequest(
                            audio, 3, "audio/webm", "zh-CN", 1200)));
            assertEquals(SpeechProviderException.FailureKind.TIMEOUT, error.failureKind());
            assertEquals(1, calls.get());
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void synthesisRejectsDeclaredBodyAboveEightMiBWithoutRetryOrCaching() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server = server();
        server.createContext("/audio/speech", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200,
                    OpenAiCompatibleSpeechSynthesisProvider.MAX_AUDIO_BYTES + 1L);
            exchange.close();
        });
        server.start();
        VoiceSpeechProperties properties = configured();
        OpenAiCompatibleSpeechSynthesisProvider provider =
                new OpenAiCompatibleSpeechSynthesisProvider(properties, new ObjectMapper());

        SpeechProviderException error = assertThrows(SpeechProviderException.class,
                () -> provider.synthesize(new SpeechSynthesisRequest(
                        "林冲领命。", "juyiting-default", "mp3")));

        assertEquals(SpeechProviderException.FailureKind.KNOWN, error.failureKind());
        assertEquals(1, calls.get());
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private VoiceSpeechProperties configured() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setConnectTimeoutMillis(500);
        properties.setProviderDeadlineMillis(1000);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.getTranscription().setBaseUrl(baseUrl);
        properties.getTranscription().setApiKey("stub-key");
        properties.getTranscription().setModel("stub-stt");
        properties.getSynthesis().setBaseUrl(baseUrl);
        properties.getSynthesis().setApiKey("stub-key");
        properties.getSynthesis().setModel("stub-tts");
        properties.getSynthesis().setProviderVoice("stub-voice");
        return properties;
    }
}
