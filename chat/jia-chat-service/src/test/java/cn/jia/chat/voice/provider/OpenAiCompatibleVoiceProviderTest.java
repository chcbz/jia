package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import cn.jia.chat.voice.state.VoiceAdmission;
import cn.jia.chat.voice.state.VoiceAdmissionResult;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    private static final String REQUEST_ID = "01JVOICEPROVIDER001";

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
        try (FileChannel channel = FileChannel.open(audio, StandardOpenOption.READ)) {
            OpenAiCompatibleSpeechTranscriptionProvider provider =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            properties, new ObjectMapper(), client);
            SpeechProviderException error = assertThrows(SpeechProviderException.class,
                    () -> provider.transcribe(new FileChannelSpeechTranscriptionRequest(
                            channel, audioBytes.length, "audio/webm;codecs=opus", "zh-CN", 1200)));
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

            HttpRequest.BodyPublisher publisher = captured.bodyPublisher().orElseThrow();
            byte[] bodyBytes = collect(publisher);
            assertEquals(bodyBytes.length, publisher.contentLength());
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
    void serviceAndProviderSendTheRetainedHandleAndNeverDeleteAReplacementPath()
            throws Exception {
        byte[] original;
        try (InputStream input = getClass().getResourceAsStream(
                "/cn/jia/chat/voice/media/mediarecorder-valid.webm")) {
            original = input.readAllBytes();
        }
        byte[] replacement = "R4-PATH-REPLACEMENT-MUST-SURVIVE"
                .getBytes(StandardCharsets.US_ASCII);
        Path formerPath = Files.createTempFile("voice-provider-retained", ".webm");
        Files.write(formerPath, original);
        FileChannel retainedChannel = FileChannel.open(formerPath, StandardOpenOption.READ);
        Files.delete(formerPath);
        Files.write(formerPath, replacement);
        VoiceAudioUpload upload = new VoiceAudioUpload(
                retainedChannel, original.length, "audio/webm;codecs=opus",
                MessageDigest.getInstance("SHA-256").digest(original), 1200);
        VoiceAudioUploadFactory factory = mock(VoiceAudioUploadFactory.class);
        when(factory.create(any(), org.mockito.ArgumentMatchers.eq(REQUEST_ID))).thenReturn(upload);
        AtomicReference<byte[]> dispatchedBody = new AtomicReference<>();
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    dispatchedBody.set(collect(request.bodyPublisher().orElseThrow()));
                    return jsonResponse(List.of("application/json; charset=UTF-8"));
                });
        VoiceSpeechProperties properties = configured();
        properties.setEnabled(true);
        properties.setIdentityHmacSecret("01234567890123456789012345678901");
        properties.getTranscription().setEnabled(true);
        properties.getTranscription().setProvider("openai-compatible");
        OpenAiCompatibleSpeechTranscriptionProvider provider =
                new OpenAiCompatibleSpeechTranscriptionProvider(
                        properties, new ObjectMapper(), client);
        SpeechTranscriptionService service = new SpeechTranscriptionService(
                properties, provider, new PassingCoordinator(),
                new VoiceDigests(properties), new ObjectMapper(), factory);

        try {
            var result = service.transcribe(
                    new VoiceIdentity("tenant", "client", "subject"),
                    REQUEST_ID, "zh-CN", mock(org.springframework.web.multipart.MultipartFile.class));

            assertEquals("林冲领命", result.text());
            assertTrue(contains(dispatchedBody.get(), original));
            assertFalse(contains(dispatchedBody.get(), replacement));
            assertFalse(retainedChannel.isOpen());
            assertTrue(Files.exists(formerPath));
            assertArrayEquals(replacement, Files.readAllBytes(formerPath));
        } finally {
            Files.deleteIfExists(formerPath);
        }
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void sharedCorePathRequestKeepsSourceAndBinaryShapeButOpenAiNeverReopensIt()
            throws Exception {
        Path path = Path.of("legacy-audio.webm");
        SpeechTranscriptionRequest request = new SpeechTranscriptionRequest(
                path, 1, "audio/webm;codecs=opus", "zh-CN", 1200);
        assertEquals(path, request.audioPath());
        assertEquals(Path.class, SpeechTranscriptionRequest.class
                .getDeclaredMethod("audioPath").getReturnType());
        SpeechTranscriptionRequest.class.getDeclaredConstructor(
                Path.class, long.class, String.class, String.class, long.class);

        HttpClient client = mock(HttpClient.class);
        OpenAiCompatibleSpeechTranscriptionProvider provider =
                new OpenAiCompatibleSpeechTranscriptionProvider(
                        configured(), new ObjectMapper(), client);
        SpeechProviderException error = assertThrows(
                SpeechProviderException.class, () -> provider.transcribe(request));
        assertEquals(SpeechProviderException.FailureKind.KNOWN, error.failureKind());
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void transcriptionAcceptsOnlyJsonWithOptionalUtf8CharsetOnSuccess() throws Exception {
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1});
        try (FileChannel channel = FileChannel.open(audio, StandardOpenOption.READ)) {
            for (String contentType : List.of(
                    "application/json",
                    "Application/JSON; Charset=UTF-8",
                    "application/json;charset=\"utf-8\"")) {
                HttpClient client = mock(HttpClient.class);
                when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                        .thenAnswer(invocation -> jsonResponse(contentType == null ? List.of() : List.of(contentType)));
                var provider = new OpenAiCompatibleSpeechTranscriptionProvider(
                        configured(), new ObjectMapper(), client);

                assertEquals("林冲领命", provider.transcribe(new FileChannelSpeechTranscriptionRequest(
                        channel, 1, "audio/webm;codecs=opus", "zh-CN", 1200)).text(),
                        contentType);
            }
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void transcriptionRejectsMissingWrongOrUnsafeJsonContentTypeOnSuccess() throws Exception {
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1});
        try (FileChannel channel = FileChannel.open(audio, StandardOpenOption.READ)) {
            for (String contentType : java.util.Arrays.asList(
                    null,
                    "text/plain",
                    "application/problem+json",
                    "application/json; charset=utf-16",
                    "application/json; charset=utf-8; profile=x")) {
                HttpClient client = mock(HttpClient.class);
                when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                        .thenAnswer(invocation -> jsonResponse(contentType == null ? List.of() : List.of(contentType)));
                var provider = new OpenAiCompatibleSpeechTranscriptionProvider(
                        configured(), new ObjectMapper(), client);

                SpeechProviderException error = assertThrows(SpeechProviderException.class,
                        () -> provider.transcribe(new FileChannelSpeechTranscriptionRequest(
                                channel, 1, "audio/webm;codecs=opus", "zh-CN", 1200)),
                        String.valueOf(contentType));
                assertEquals(SpeechProviderException.FailureKind.KNOWN,
                        error.failureKind(), String.valueOf(contentType));
            }
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void transcriptionRejectsDuplicateConflictingAndCombinedContentTypeValues() throws Exception {
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1});
        try (FileChannel channel = FileChannel.open(audio, StandardOpenOption.READ)) {
            for (List<String> contentTypes : List.of(
                    List.of(),
                    List.of("application/json", "application/json"),
                    List.of("application/json", "text/plain"),
                    List.of("application/json, application/json"),
                    List.of("application/json, text/plain"))) {
                HttpClient client = mock(HttpClient.class);
                when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                        .thenAnswer(invocation -> jsonResponse(contentTypes));
                var provider = new OpenAiCompatibleSpeechTranscriptionProvider(
                        configured(), new ObjectMapper(), client);

                SpeechProviderException error = assertThrows(SpeechProviderException.class,
                        () -> provider.transcribe(new FileChannelSpeechTranscriptionRequest(
                                channel, 1, "audio/webm;codecs=opus", "zh-CN", 1200)),
                        contentTypes.toString());
                assertEquals(SpeechProviderException.FailureKind.KNOWN,
                        error.failureKind(), contentTypes.toString());
            }
        } finally {
            Files.deleteIfExists(audio);
        }
    }

    @Test
    void arbitraryEndpointAndControlCharacterCredentialFailBeforeDispatch() throws Exception {
        HttpClient client = mock(HttpClient.class);
        Path audio = Files.createTempFile("voice-provider-test", ".webm");
        Files.write(audio, new byte[]{1});
        try (FileChannel channel = FileChannel.open(audio, StandardOpenOption.READ)) {
            VoiceSpeechProperties arbitraryHost = configured();
            arbitraryHost.getTranscription().setBaseUrl("https://attacker.example/v1");
            OpenAiCompatibleSpeechTranscriptionProvider hostPinned =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            arbitraryHost, new ObjectMapper(), client);
            assertThrows(SpeechProviderException.class,
                    () -> hostPinned.transcribe(new FileChannelSpeechTranscriptionRequest(
                            channel, 1, "audio/webm;codecs=opus", "zh-CN", 1200)));

            VoiceSpeechProperties controlKey = configured();
            controlKey.getTranscription().setApiKey("sk-test\u0000key");
            OpenAiCompatibleSpeechTranscriptionProvider credentialPinned =
                    new OpenAiCompatibleSpeechTranscriptionProvider(
                            controlKey, new ObjectMapper(), client);
            assertThrows(SpeechProviderException.class,
                    () -> credentialPinned.transcribe(new FileChannelSpeechTranscriptionRequest(
                            channel, 1, "audio/webm;codecs=opus", "zh-CN", 1200)));

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
                    when(responseInfo.headers()).thenReturn(HttpHeaders.of(
                            Map.of("Content-Length", List.of(String.valueOf(
                                    OpenAiCompatibleSpeechSynthesisProvider.MAX_AUDIO_BYTES + 1L))),
                            (name, value) -> true));
                    HttpResponse.BodySubscriber<byte[]> subscriber = handler.apply(responseInfo);
                    subscriber.onSubscribe(mock(Flow.Subscription.class));
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

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> jsonResponse(List<String> contentTypes) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        Map<String, List<String>> headers = contentTypes.isEmpty()
                ? Map.of() : Map.of("Content-Type", contentTypes);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        when(response.body()).thenReturn(
                "{\"text\":\"林冲领命\",\"language\":\"zh\"}"
                        .getBytes(StandardCharsets.UTF_8));
        return response;
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
        if (haystack == null || needle == null) {
            return false;
        }
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

    private static final class PassingCoordinator implements VoiceRequestCoordinator {
        @Override
        public VoiceAdmissionResult admit(
                VoiceOperation operation, String scope, String requestId) {
            return VoiceAdmissionResult.admitted(
                    new VoiceAdmission(operation, scope, requestId, "lease"));
        }

        @Override
        public VoiceBeginResult begin(VoiceAdmission admission, String digest) {
            return VoiceBeginResult.reserved(new VoiceReservation(
                    admission.operation(), admission.identityScope(), admission.requestId(),
                    digest, admission.leaseToken()));
        }

        @Override
        public VoiceBeginResult begin(
                VoiceOperation operation, String scope, String requestId, String digest) {
            return VoiceBeginResult.reserved(new VoiceReservation(
                    operation, scope, requestId, digest, "lease"));
        }

        @Override
        public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
        }

        @Override
        public void failKnown(VoiceReservation reservation) {
        }

        @Override
        public void failUnknown(VoiceReservation reservation) {
        }

        @Override
        public void release(VoiceAdmission admission) {
        }

        @Override
        public void release(VoiceReservation reservation) {
        }
    }
}
