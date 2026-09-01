package cn.jia.chat.voice.api;

import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.config.VoiceSecurityConfiguration;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.service.SpeechSynthesisService;
import cn.jia.chat.voice.service.SpeechTranscriptionService;
import cn.jia.chat.voice.validation.AudioDurationInspector;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import cn.jia.chat.voice.validation.VoiceIdentityResolver;
import cn.jia.chat.voice.validation.VoiceRequestValidator;
import cn.jia.core.config.ExceptionHandlerAdvice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.servlet.autoconfigure.MultipartAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = VoiceHttpContractIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.main.web-application-type=servlet",
                "spring.servlet.multipart.max-file-size=5242880B",
                "spring.servlet.multipart.max-request-size=6291456B",
                "server.tomcat.max-swallow-size=6291456B",
                "logging.level.root=INFO"
        })
@ExtendWith(OutputCaptureExtension.class)
class VoiceHttpContractIntegrationTest {
    private static final String REQUEST_ID = "01JVOICEHTTPAPP0001";
    private static final String TOKEN = "voice-token-do-not-log";
    private static final String TRANSCRIPT_SECRET = "voice-transcript-do-not-log";
    private static final String CLAIM_SECRET = "voice-claim-do-not-log";
    private static final String FILENAME_SECRET = "voice-filename-do-not-log.webm";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void realSecurityChainAndVoiceAdviceReturnFrozenErrorsWithoutSensitiveLogs(
            CapturedOutput output) throws Exception {
        HttpResult unauthorized = send("/chat/speech/synthesis", "application/json",
                synthesisJson(TRANSCRIPT_SECRET), false);
        assertError(unauthorized, 401, "VOICE_UNAUTHORIZED");

        HttpResult unsupported = send("/chat/speech/synthesis", "text/plain",
                TRANSCRIPT_SECRET.getBytes(StandardCharsets.UTF_8), true);
        assertError(unsupported, 415, "VOICE_UNSUPPORTED_MEDIA");

        HttpResult malformed = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=broken",
                "--broken\r\nnot-a-part".getBytes(StandardCharsets.US_ASCII), true);
        assertError(malformed, 400, "VOICE_INVALID_REQUEST");

        String validBoundary = "voice-valid-boundary";
        HttpResult valid = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=" + validBoundary,
                multipart(validBoundary, baseParts(List.of(new Part(
                        "audio", FILENAME_SECRET, "audio/webm;codecs=opus",
                        fixture("mediarecorder-chromium-unmodified.webm"))))), true);
        assertEquals(200, valid.status());
        assertEquals("E0", json(valid).path("code").asText());

        String unknownBoundary = "voice-unknown-file-boundary";
        HttpResult unknownFile = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=" + unknownBoundary,
                multipart(unknownBoundary, baseParts(List.of(
                        new Part("audio", FILENAME_SECRET, "audio/webm",
                                fixture("mediarecorder-chromium-unmodified.webm")),
                        new Part("attachment", "unknown.bin", "application/octet-stream",
                                new byte[]{1})))), true);
        assertError(unknownFile, 400, "VOICE_INVALID_REQUEST");

        String multipleBoundary = "voice-multiple-file-boundary";
        HttpResult multipleFiles = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=" + multipleBoundary,
                multipart(multipleBoundary, baseParts(List.of(
                        new Part("audio", "one.webm", "audio/webm",
                                fixture("mediarecorder-chromium-unmodified.webm")),
                        new Part("audio", "two.webm", "audio/webm",
                                fixture("mediarecorder-chromium-unmodified.webm"))))), true);
        assertError(multipleFiles, 400, "VOICE_INVALID_REQUEST");

        String fileLimitBoundary = "voice-file-limit-boundary";
        HttpResult fileLimit = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=" + fileLimitBoundary,
                multipart(fileLimitBoundary, baseParts(List.of(new Part(
                        "audio", FILENAME_SECRET, "audio/webm",
                        new byte[(int) VoiceAudioUploadFactory.MAX_AUDIO_BYTES + 1])))), true);
        assertError(fileLimit, 413, "VOICE_TOO_LARGE");

        String requestLimitBoundary = "voice-request-limit-boundary";
        List<Part> requestLimitParts = new ArrayList<>();
        requestLimitParts.add(new Part("requestId", null, null,
                REQUEST_ID.getBytes(StandardCharsets.US_ASCII)));
        requestLimitParts.add(new Part("padding", null, null, new byte[6 * 1024 * 1024]));
        HttpResult requestLimit = send("/chat/speech/transcriptions",
                "multipart/form-data; boundary=" + requestLimitBoundary,
                multipart(requestLimitBoundary, requestLimitParts), true);
        assertError(requestLimit, 413, "VOICE_TOO_LARGE");

        String logs = output.getAll();
        assertFalse(logs.contains(TRANSCRIPT_SECRET));
        assertFalse(logs.contains(CLAIM_SECRET));
        assertFalse(logs.contains(FILENAME_SECRET));
        assertFalse(logs.contains(TOKEN));
    }

    @Test
    void higherPriorityVoiceAdviceDoesNotChangeNonVoiceGlobalErrors() throws Exception {
        HttpResult response = send("/non-voice/error", null, new byte[0], false);
        assertEquals(200, response.status());
        assertFalse(json(response).path("code").asText().startsWith("VOICE_"));
    }

    private List<Part> baseParts(List<Part> files) {
        List<Part> parts = new ArrayList<>();
        parts.add(new Part("requestId", null, null,
                REQUEST_ID.getBytes(StandardCharsets.US_ASCII)));
        parts.add(new Part("language", null, null, "zh-CN".getBytes(StandardCharsets.US_ASCII)));
        parts.add(new Part("durationMs", null, null, "1200".getBytes(StandardCharsets.US_ASCII)));
        parts.addAll(files);
        return parts;
    }

    private byte[] multipart(String boundary, List<Part> parts) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (Part part : parts) {
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String disposition = "Content-Disposition: form-data; name=\"" + part.name + "\"";
            if (part.filename != null) {
                disposition += "; filename=\"" + part.filename + "\"";
            }
            output.write((disposition + "\r\n").getBytes(StandardCharsets.US_ASCII));
            if (part.contentType != null) {
                output.write(("Content-Type: " + part.contentType + "\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.write(part.body, 0, part.body.length);
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private HttpResult send(String path, String contentType, byte[] body, boolean authenticated)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) {
            request.header(HttpHeaders.CONTENT_TYPE, contentType);
        }
        if (authenticated) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        }
        HttpResponse<String> response = client.send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new HttpResult(response.statusCode(), response.body());
    }

    private void assertError(HttpResult response, int status, String code) throws Exception {
        JsonNode json = json(response);
        assertEquals(status, response.status(), response.body());
        assertEquals(status, json.path("status").asInt(), response.body());
        assertEquals(code, json.path("code").asText(), response.body());
        assertTrue(json.has("data"), response.body());
    }

    private JsonNode json(HttpResult response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private byte[] synthesisJson(String text) {
        return ("{\"requestId\":\"" + REQUEST_ID + "\",\"text\":\"" + text
                + "\",\"voice\":\"juyiting-default\",\"format\":\"mp3\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/cn/jia/chat/voice/media/" + name)) {
            if (input == null) {
                throw new IOException("missing fixture");
            }
            return input.readAllBytes();
        }
    }

    private record Part(String name, String filename, String contentType, byte[] body) {
    }

    private record HttpResult(int status, String body) {
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            MultipartAutoConfiguration.class
    })
    @Import({
            SpeechTranscriptionController.class,
            SpeechSynthesisController.class,
            VoiceExceptionHandler.class,
            VoiceSecurityConfiguration.class,
            ExceptionHandlerAdvice.class,
            NonVoiceController.class
    })
    static class TestApplication {
        @Bean
        VoiceSpeechProperties voiceSpeechProperties() {
            VoiceSpeechProperties properties = new VoiceSpeechProperties();
            properties.setEnabled(true);
            properties.getTranscription().setEnabled(true);
            properties.getTranscription().setProvider("openai-compatible");
            properties.getSynthesis().setEnabled(true);
            properties.getSynthesis().setProvider("openai-compatible");
            return properties;
        }

        @Bean
        VoiceIdentityResolver voiceIdentityResolver() {
            return new VoiceIdentityResolver();
        }

        @Bean
        VoiceRequestValidator voiceRequestValidator() {
            return new VoiceRequestValidator();
        }

        @Bean
        VoiceAudioUploadFactory voiceAudioUploadFactory() {
            return new VoiceAudioUploadFactory(new AudioDurationInspector());
        }

        @Bean
        SpeechTranscriptionService speechTranscriptionService() {
            SpeechTranscriptionService service = mock(SpeechTranscriptionService.class);
            when(service.transcribe(any(), any(), any(), any())).thenAnswer(invocation ->
                    new VoiceTranscriptionResponse(invocation.getArgument(1),
                            "bounded transcript", "zh", 1240));
            return service;
        }

        @Bean
        SpeechSynthesisService speechSynthesisService() {
            SpeechSynthesisService service = mock(SpeechSynthesisService.class);
            when(service.synthesize(any(), any())).thenReturn(
                    new SpeechSynthesisResult(new byte[]{1}, "audio/mpeg"));
            return service;
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("jiacn", CLAIM_SECRET)
                    .claim("client_id", "voice-client")
                    .claim("sub", "voice-subject")
                    .issuedAt(Instant.parse("2026-09-01T00:00:00Z"))
                    .expiresAt(Instant.parse("2030-09-01T00:00:00Z"))
                    .build();
        }
    }

    @RestController
    static class NonVoiceController {
        @PostMapping("/non-voice/error")
        void fail() {
            throw new IllegalStateException("non-voice-probe");
        }
    }
}
