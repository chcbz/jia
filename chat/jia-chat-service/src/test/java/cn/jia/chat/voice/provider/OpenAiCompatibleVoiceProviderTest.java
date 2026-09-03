package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenAiCompatibleVoiceProviderTest {
    @Test
    void transcriptionMapsClientZhCnToIsoZhInActualMultipartBodyAndDispatchesOnce()
            throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("timeout"));
        VoiceSpeechProperties properties = configured();
        properties.setProviderDeadlineMillis(50);
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        byte[] audioBytes = {1, 2, 3, 4};
        Files.write(audio, audioBytes);
        try {
            OpenAiCompatibleSpeechTranscriptionProvider provider =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            properties, new ObjectMapper(), client);
            SpeechProviderException error = assertThrows(SpeechProviderException.class,
                    () -> provider.transcribe(new SpeechTranscriptionRequest(
                            audio, audioBytes.length, "audio/webm;codecs=opus", "zh-CN", 1200)));
            assertEquals(SpeechProviderException.FailureKind.TIMEOUT, error.failureKind());

            var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
            verify(client, times(1)).send(
                    request.capture(), any(HttpResponse.BodyHandler.class));
            HttpRequest captured = request.getValue();
            assertEquals("https://api.openai.com/v1/audio/transcriptions",
                    captured.uri().toString());
            assertEquals(50, captured.timeout().orElseThrow().toMillis());
            assertEquals("Bearer sk-test-openai-voice-key",
                    captured.headers().firstValue("Authorization").orElseThrow());

            byte[] bodyBytes = collect(captured.bodyPublisher().orElseThrow());
            String body = new String(bodyBytes, StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"model\"\r\n\r\nwhisper-1\r\n"));
            assertTrue(body.contains("name=\"language\"\r\n\r\nzh\r\n"));
            assertFalse(body.contains("name=\"language\"\r\n\r\nzh-CN\r\n"));
            assertTrue(body.contains("name=\"file\"; filename=\"audio.webm\""));
            assertTrue(body.contains("Content-Type: audio/webm;codecs=opus\r\n\r\n"));
            assertTrue(contains(bodyBytes, audioBytes));
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void arbitraryEndpointAndControlCharacterCredentialFailBeforeDispatch() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1});
        try {
            VoiceSpeechProperties arbitraryHost = configured();
            arbitraryHost.getTranscription().setBaseUrl("https://attacker.example/v1");
            OpenAiCompatibleSpeechTranscriptionProvider hostPinned =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            arbitraryHost, new ObjectMapper(), client);
            assertThrows(SpeechProviderException.class,
                    () -> hostPinned.transcribe(new SpeechTranscriptionRequest(
                            audio, 1, "audio/webm;codecs=opus", "zh-CN", 1200)));

            VoiceSpeechProperties controlKey = configured();
            controlKey.getTranscription().setApiKey("sk-test\u0000key");
            OpenAiCompatibleSpeechTranscriptionProvider credentialPinned =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            controlKey, new ObjectMapper(), client);
            assertThrows(SpeechProviderException.class,
                    () -> credentialPinned.transcribe(new SpeechTranscriptionRequest(
                            audio, 1, "audio/webm;codecs=opus", "zh-CN", 1200)));

            verify(client, never()).send(
                    any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
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
        VoiceSpeechProperties properties = configured();
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

    private static VoiceSpeechProperties configured() {
        VoiceSpeechProperties properties = new VoiceSpeechProperties();
        properties.setConnectTimeoutMillis(500);
        properties.setProviderDeadlineMillis(1000);
        properties.getTranscription().setBaseUrl(
                VoiceActivationConfigurationValidator.OPENAI_BASE_URL);
        properties.getTranscription().setApiKey("sk-test-openai-voice-key");
        properties.getTranscription().setModel(
                VoiceActivationConfigurationValidator.TRANSCRIPTION_MODEL);
        properties.getSynthesis().setBaseUrl(
                VoiceActivationConfigurationValidator.OPENAI_BASE_URL);
        properties.getSynthesis().setApiKey("sk-test-openai-voice-key");
        properties.getSynthesis().setModel(
                VoiceActivationConfigurationValidator.SYNTHESIS_MODEL);
        properties.getSynthesis().setProviderVoice(
                VoiceActivationConfigurationValidator.SYNTHESIS_VOICE);
        return properties;
    }

    private static byte[] collect(HttpRequest.BodyPublisher publisher) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<byte[]> complete = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(output.toByteArray());
            }
        });
        return complete.get(5, TimeUnit.SECONDS);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
