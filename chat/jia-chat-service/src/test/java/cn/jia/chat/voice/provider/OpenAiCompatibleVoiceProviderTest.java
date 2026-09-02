package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleVoiceProviderTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void transcriptionTimeoutDispatchesExactlyOnceAndReturnsAmbiguousTimeout() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timeout"));
        VoiceSpeechProperties properties = configured("http://127.0.0.1:1");
        properties.setProviderDeadlineMillis(50);
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1, 2, 3});
        try {
            OpenAiCompatibleSpeechTranscriptionProvider provider =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            properties, new ObjectMapper(), client);
            SpeechProviderException error = assertThrows(SpeechProviderException.class,
                    () -> provider.transcribe(new SpeechTranscriptionRequest(
                            audio, 3, "audio/webm", "zh-CN", 1200)));
            assertEquals(SpeechProviderException.FailureKind.TIMEOUT, error.failureKind());
            var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
            verify(client, times(1)).send(
                    request.capture(), any(HttpResponse.BodyHandler.class));
            assertEquals(50, request.getValue().timeout().orElseThrow().toMillis());
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void synthesisRejectsDeclaredBodyAboveEightMiBWithoutRetryOrCaching() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    calls.incrementAndGet();
                    HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                    HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
                    when(responseInfo.headers()).thenReturn(java.net.http.HttpHeaders.of(
                            java.util.Map.of("Content-Length", java.util.List.of(String.valueOf(
                                    OpenAiCompatibleSpeechSynthesisProvider.MAX_AUDIO_BYTES + 1L))),
                            (name, value) -> true));
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(responseInfo);
                    subscriber.onSubscribe(mock(java.util.concurrent.Flow.Subscription.class));
                    try {
                        subscriber.getBody().toCompletableFuture().join();
                    } catch (java.util.concurrent.CompletionException exception) {
                        throw new java.io.IOException(exception.getCause());
                    }
                    throw new AssertionError("declared oversized body was not rejected");
                });
        VoiceSpeechProperties properties = configured("https://voice-provider.invalid");
        OpenAiCompatibleSpeechSynthesisProvider provider =
                new OpenAiCompatibleSpeechSynthesisProvider(properties, new ObjectMapper(), client);
        SpeechSynthesisRequest request = new SpeechSynthesisRequest(
                "林冲领命。", "juyiting-default", "mp3");

        for (int invocation = 0; invocation < 2; invocation++) {
            SpeechProviderException error = assertThrows(SpeechProviderException.class,
                    () -> provider.synthesize(request));
            assertEquals(SpeechProviderException.FailureKind.KNOWN, error.failureKind());
        }

        assertEquals(2, calls.get());
        verify(client, times(2)).send(
                any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    private HttpServer server() throws Exception {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private VoiceSpeechProperties configured() {
        return configured("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private VoiceSpeechProperties configured(String baseUrl) {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setConnectTimeoutMillis(500);
        properties.setProviderDeadlineMillis(1000);
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
