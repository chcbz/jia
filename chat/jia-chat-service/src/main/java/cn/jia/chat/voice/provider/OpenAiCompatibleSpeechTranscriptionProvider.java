package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.config.VoiceActivationConfigurationValidator;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

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
        if (response.statusCode() < 200 || response.statusCode() >= 300
                || !isJson(response.headers().firstValue("Content-Type").orElse(null))) {
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
        if (request == null || request.audioChannel() == null || request.language() == null
                || request.audioBytes() <= 0
                || request.audioBytes() > VoiceAudioUploadFactory.MAX_AUDIO_BYTES
                || !VoiceAudioUploadFactory.WEBM_OPUS_MEDIA_TYPE.equals(request.mediaType())) {
            throw known();
        }
        try {
            if (!request.audioChannel().isOpen()
                    || request.audioChannel().size() != request.audioBytes()) {
                throw known();
            }
        } catch (IOException exception) {
            throw known();
        }
        String providerLanguage = providerLanguage(request.language());
        String boundary = "cyf-voice-" + UUID.randomUUID();
        try {
            HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.concat(
                    textPart(boundary, "model", config.getModel()),
                    textPart(boundary, "language", providerLanguage),
                    fileHeader(boundary, request.mediaType()),
                    new ExactFileChannelBodyPublisher(
                            request.audioChannel(), request.audioBytes()),
                    HttpRequest.BodyPublishers.ofByteArray(("\r\n--" + boundary + "--\r\n")
                            .getBytes(StandardCharsets.US_ASCII)));
            return HttpRequest.newBuilder(uri)
                    .timeout(deadline())
                    .header("Authorization", bearer(config.getApiKey()))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .POST(body)
                    .build();
        } catch (RuntimeException exception) {
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

    private static boolean isJson(String contentType) {
        if (contentType == null) {
            return false;
        }
        String[] parts = contentType.split(";", -1);
        if (!"application/json".equals(parts[0].strip().toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        if (parts.length != 2) {
            return false;
        }
        String parameter = parts[1].strip();
        int separator = parameter.indexOf('=');
        if (separator <= 0 || separator != parameter.lastIndexOf('=')) {
            return false;
        }
        if (!"charset".equals(parameter.substring(0, separator).strip()
                .toLowerCase(Locale.ROOT))) {
            return false;
        }
        String charset = parameter.substring(separator + 1).strip();
        if (charset.length() >= 2 && charset.startsWith("\"") && charset.endsWith("\"")) {
            charset = charset.substring(1, charset.length() - 1);
        }
        return "utf-8".equals(charset.toLowerCase(Locale.ROOT));
    }

    static SpeechProviderException known() {
        return new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "transcription provider rejected request");
    }

    private static SpeechProviderException unknown(String safeMessage) {
        return new SpeechProviderException(SpeechProviderException.FailureKind.UNKNOWN, safeMessage);
    }

    private static final class ExactFileChannelBodyPublisher implements HttpRequest.BodyPublisher {
        private final HttpRequest.BodyPublisher delegate;
        private final AtomicBoolean subscribed = new AtomicBoolean();
        private final long length;

        private ExactFileChannelBodyPublisher(FileChannel channel, long length) {
            this.length = length;
            this.delegate = HttpRequest.BodyPublishers.ofInputStream(
                    () -> new ExactFileChannelInputStream(channel, length));
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            if (!subscribed.compareAndSet(false, true)) {
                subscriber.onSubscribe(new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                    }

                    @Override
                    public void cancel() {
                    }
                });
                subscriber.onError(new IllegalStateException("voice body publisher is one-shot"));
                return;
            }
            delegate.subscribe(subscriber);
        }
    }


    private static final class ExactFileChannelInputStream extends InputStream {
        private final FileChannel channel;
        private final long length;
        private long position;
        private boolean closed;

        private ExactFileChannelInputStream(FileChannel channel, long length) {
            this.channel = channel;
            this.length = length;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            int read = read(single, 0, 1);
            return read < 0 ? -1 : single[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int offset, int requested) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            Objects.checkFromIndexSize(offset, requested, bytes.length);
            if (closed) {
                throw new IOException("voice body stream closed");
            }
            if (requested == 0) {
                return 0;
            }
            if (position == length) {
                return -1;
            }
            int allowed = (int) Math.min(requested, length - position);
            int read = channel.read(ByteBuffer.wrap(bytes, offset, allowed), position);
            if (read <= 0) {
                throw new IOException("voice body shorter than declared length");
            }
            position += read;
            return read;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
