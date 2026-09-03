package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class OpenAiCompatibleSpeechSynthesisProvider implements SpeechSynthesisProvider {
    public static final int MAX_AUDIO_BYTES = 8 * 1024 * 1024;
    private final VoiceSpeechProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public OpenAiCompatibleSpeechSynthesisProvider(
            VoiceSpeechProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(
                        Math.max(1, properties.getConnectTimeoutMillis()), 3_000)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    OpenAiCompatibleSpeechSynthesisProvider(
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
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request)
            throws SpeechProviderException {
        HttpRequest httpRequest = prepareRequest(properties.getSynthesis(), request);
        HttpResponse<byte[]> response;
        try {
            response = client.send(httpRequest, new BoundedBodyHandler(MAX_AUDIO_BYTES));
        } catch (HttpTimeoutException exception) {
            throw new SpeechProviderException(SpeechProviderException.FailureKind.TIMEOUT,
                    "synthesis provider timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unknown("synthesis provider interrupted");
        } catch (IOException exception) {
            if (BoundedBodyHandler.isLimitFailure(exception)) {
                throw known();
            }
            throw unknown("synthesis provider transport failure");
        } catch (RuntimeException exception) {
            throw unknown("synthesis provider transport failure");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !isMpeg(response.headers().firstValue("Content-Type").orElse(null))) {
            throw known();
        }
        byte[] audio = response.body();
        if (audio == null || audio.length == 0) {
            throw known();
        }
        return new SpeechSynthesisResult(audio, "audio/mpeg");
    }

    private HttpRequest prepareRequest(
            VoiceSpeechProperties.Synthesis config, SpeechSynthesisRequest request)
            throws SpeechProviderException {
        URI uri = OpenAiCompatibleSpeechTranscriptionProvider.endpoint(
                config.getBaseUrl(), "/audio/speech");
        OpenAiCompatibleSpeechTranscriptionProvider.requireConfigured(
                uri, config.getApiKey(), config.getModel(),
                VoiceActivationConfigurationValidator.SYNTHESIS_MODEL);
        if (!VoiceActivationConfigurationValidator.SYNTHESIS_VOICE.equals(
                    config.getProviderVoice())
                || request == null || request.text() == null || request.format() == null
                || !"mp3".equals(request.format())) {
            throw known();
        }
        try {
            byte[] json = objectMapper.writeValueAsBytes(Map.of(
                    "model", config.getModel(),
                    "input", request.text(),
                    "voice", config.getProviderVoice(),
                    "response_format", request.format()));
            return HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Math.min(
                            Math.max(1, properties.getProviderDeadlineMillis()), 25_000)))
                    .header("Authorization", bearer(config.getApiKey()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/mpeg")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                    .build();
        } catch (RuntimeException exception) {
            throw known();
        }
    }

    private static String bearer(String apiKey) throws SpeechProviderException {
        if (!VoiceActivationConfigurationValidator.isValidApiKey(apiKey)) {
            throw known();
        }
        return "Bearer " + apiKey;
    }

    private static boolean isMpeg(String contentType) {
        if (contentType == null) {
            return false;
        }
        String base = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return "audio/mpeg".equals(base) || "audio/mp3".equals(base);
    }

    private static SpeechProviderException known() {
        return new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "synthesis provider rejected request");
    }

    private static SpeechProviderException unknown(String safeMessage) {
        return new SpeechProviderException(SpeechProviderException.FailureKind.UNKNOWN, safeMessage);
    }
}
