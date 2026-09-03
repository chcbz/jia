package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public final class OpenAiCompatibleSpeechTranscriptionProvider implements SpeechTranscriptionProvider {
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final VoiceSpeechProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public OpenAiCompatibleSpeechTranscriptionProvider(
            VoiceSpeechProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(
                        Math.max(1, properties.getConnectTimeoutMillis()), 3_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    OpenAiCompatibleSpeechTranscriptionProvider(
            VoiceSpeechProperties properties, ObjectMapper objectMapper, HttpClient client) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.client = client;
    }

    @Override
    public String alias() {
        return "openai-compatible";
    }

    @Override
    public SpeechTranscriptionResult transcribe(SpeechTranscriptionRequest request)
            throws SpeechProviderException {
        VoiceSpeechProperties.Transcription config = properties.getTranscription();
        HttpRequest httpRequest = prepareRequest(config, request);
        HttpResponse<byte[]> response;
        try {
            response = client.send(httpRequest, new BoundedBodyHandler(MAX_RESPONSE_BYTES));
        } catch (HttpTimeoutException exception) {
            throw new SpeechProviderException(SpeechProviderException.FailureKind.TIMEOUT,
                    "transcription provider timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unknown("transcription provider interrupted");
        } catch (IOException exception) {
            if (BoundedBodyHandler.isLimitFailure(exception)) {
                throw known();
            }
            throw unknown("transcription provider transport failure");
        } catch (RuntimeException exception) {
            throw unknown("transcription provider transport failure");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw known();
        }
        try {
            JsonNode json = objectMapper.readTree(response.body());
            String text = json.path("text").isTextual() ? json.path("text").textValue() : null;
            String language = json.path("language").isTextual()
                    ? json.path("language").textValue() : null;
            if (text == null) {
                throw known();
            }
            return new SpeechTranscriptionResult(text, language);
        } catch (SpeechProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw known();
        }
    }

    private HttpRequest prepareRequest(
            VoiceSpeechProperties.Transcription config, SpeechTranscriptionRequest request)
            throws SpeechProviderException {
        URI uri = endpoint(config.getBaseUrl(), "/audio/transcriptions");
        requireConfigured(uri, config.getApiKey(), config.getModel(),
                VoiceActivationConfigurationValidator.TRANSCRIPTION_MODEL);
        if (request == null || request.audioPath() == null || request.language() == null
                || !"audio/webm;codecs=opus".equals(request.mediaType())) {
            throw known();
        }
        String providerLanguage = providerLanguage(request.language());
        String boundary = "cyf-voice-" + UUID.randomUUID();
        try {
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                    textPart(boundary, "model", config.getModel()),
                    textPart(boundary, "language", providerLanguage),
                    fileHeader(boundary, request.mediaType()),
                    HttpRequest.BodyPublishers.ofFile(request.audioPath()),
                    HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n")
                            .getBytes(StandardCharsets.US_ASCII)));
            return HttpRequest.newBuilder(uri)
                    .timeout(deadline())
                    .header("Authorization", bearer(config.getApiKey()))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(body)
                    .build();
        } catch (IOException | RuntimeException exception) {
            throw known();
        }
    }

    private HttpRequest.BodyPublisher textPart(String boundary, String name, String value) {
        String part = "--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + name + "\"\r\n\r\n" + value + "\r\n";
        return HttpRequest.BodyPublishers.ofByteArray(part.getBytes(StandardCharsets.UTF_8));
    }

    private HttpRequest.BodyPublisher fileHeader(String boundary, String mediaType) {
        String header = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.webm\""
                + "\r\nContent-Type: " + mediaType + "\r\n\r\n";
        return HttpRequest.BodyPublishers.ofByteArray(header.getBytes(StandardCharsets.US_ASCII));
    }

    private Duration deadline() {
        return Duration.ofMillis(Math.min(Math.max(1, properties.getProviderDeadlineMillis()), 25_000));
    }

    static URI endpoint(String baseUrl, String path) throws SpeechProviderException {
        if (!VoiceActivationConfigurationValidator.OPENAI_BASE_URL.equals(baseUrl)
                || (!"/audio/transcriptions".equals(path) && !"/audio/speech".equals(path))) {
            throw known();
        }
        try {
            return URI.create(VoiceActivationConfigurationValidator.OPENAI_BASE_URL + path);
        } catch (IllegalArgumentException exception) {
            throw known();
        }
    }

    static void requireConfigured(
            URI uri, String apiKey, String model, String expectedModel)
            throws SpeechProviderException {
        if (uri == null || !VoiceActivationConfigurationValidator.isValidApiKey(apiKey)
                || !expectedModel.equals(model)) {
            throw known();
        }
    }

    private static String bearer(String apiKey) throws SpeechProviderException {
        if (!VoiceActivationConfigurationValidator.isValidApiKey(apiKey)) {
            throw known();
        }
        return "Bearer " + apiKey;
    }

    private static String providerLanguage(String clientLanguage) throws SpeechProviderException {
        if ("zh-CN".equals(clientLanguage)) {
            return "zh";
        }
        throw known();
    }

    static SpeechProviderException known() {
        return new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "transcription provider rejected request");
    }

    private static SpeechProviderException unknown(String safeMessage) {
        return new SpeechProviderException(SpeechProviderException.FailureKind.UNKNOWN, safeMessage);
    }
}
