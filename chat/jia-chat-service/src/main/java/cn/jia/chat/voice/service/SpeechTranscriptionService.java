package cn.jia.chat.voice.service;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.api.VoiceTranscriptionResponse;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.provider.FileChannelSpeechTranscriptionProvider;
import cn.jia.chat.voice.provider.FileChannelSpeechTranscriptionRequest;
import cn.jia.chat.voice.state.VoiceAdmission;
import cn.jia.chat.voice.state.VoiceAdmissionResult;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import cn.jia.core.entity.JsonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.Objects;

@Slf4j
public final class SpeechTranscriptionService {
    public static final int MAX_CLIENT_JSON_BYTES = 256 * 1024;
    private final VoiceSpeechProperties properties;
    private final SpeechTranscriptionProvider provider;
    private final VoiceRequestCoordinator coordinator;
    private final VoiceDigests digests;
    private final ObjectMapper objectMapper;
    private final VoiceAudioUploadFactory uploadFactory;

    public SpeechTranscriptionService(
            VoiceSpeechProperties properties,
            SpeechTranscriptionProvider provider,
            VoiceRequestCoordinator coordinator,
            VoiceDigests digests,
            ObjectMapper objectMapper,
            VoiceAudioUploadFactory uploadFactory) {
        this.properties = properties;
        this.provider = provider;
        this.coordinator = coordinator;
        this.digests = digests;
        this.objectMapper = objectMapper;
        this.uploadFactory = uploadFactory;
    }

    public void requireAvailable(String requestId) {
        VoiceServiceSupport.requireCommonEnabled(properties,
                properties.getTranscription().isEnabled(), properties.getTranscription().getProvider(),
                provider.alias(), requestId);
        if (!digests.available()
                || !(provider instanceof FileChannelSpeechTranscriptionProvider)) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
    }

    public VoiceTranscriptionResponse transcribe(
            VoiceIdentity identity, String requestId, String language, MultipartFile audio) {
        requireAvailable(requestId);
        String identityScope = digests.identityScope(identity);
        VoiceAdmission admission = admit(identityScope, requestId);
        try (VoiceAudioUpload upload = uploadFactory.create(audio, requestId)) {
            String digest = digests.transcription(
                    language, upload.mediaType(), upload.audioDigest());
            VoiceBeginResult begin;
            try {
                begin = coordinator.begin(admission, digest);
            } catch (VoiceStateUnavailableException exception) {
                throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
            }
            if (begin == null || begin.outcome() == null) {
                throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
            }
            if (begin.outcome() == VoiceBeginResult.Outcome.REPLAY) {
                return decodeReplay(begin.replay(), requestId);
            }
            VoiceReservation reservation = VoiceServiceSupport.reservation(begin, requestId);
            if (reservation == null
                    || reservation.operation() != VoiceOperation.TRANSCRIPTION
                    || !Objects.equals(admission.identityScope(), reservation.identityScope())
                    || !Objects.equals(requestId, reservation.requestId())
                    || !Objects.equals(digest, reservation.digest())
                    || !Objects.equals(admission.leaseToken(), reservation.leaseToken())) {
                throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
            }
            return dispatch(requestId, language, upload, reservation);
        } finally {
            VoiceServiceSupport.release(coordinator, admission);
        }
    }

    private VoiceAdmission admit(String identityScope, String requestId) {
        VoiceAdmissionResult result;
        try {
            result = coordinator.admit(VoiceOperation.TRANSCRIPTION, identityScope, requestId);
        } catch (VoiceStateUnavailableException exception) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (result == null) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (result.outcome() == null) {
            releaseUnexpectedAdmission(result.admission());
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        return switch (result.outcome()) {
            case ADMITTED -> {
                VoiceAdmission admission = result.admission();
                if (admission == null
                        || admission.operation() != VoiceOperation.TRANSCRIPTION
                        || !Objects.equals(identityScope, admission.identityScope())
                        || !Objects.equals(requestId, admission.requestId())
                        || admission.leaseToken() == null
                        || admission.leaseToken().isBlank()) {
                    releaseUnexpectedAdmission(admission);
                    throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
                }
                yield admission;
            }
            case RATE_LIMITED -> {
                releaseUnexpectedAdmission(result.admission());
                throw VoiceException.of(VoiceErrorCode.RATE_LIMITED, requestId);
            }
            case CONCURRENCY_LIMITED -> {
                releaseUnexpectedAdmission(result.admission());
                throw VoiceException.of(VoiceErrorCode.IN_PROGRESS, requestId);
            }
        };
    }

    private void releaseUnexpectedAdmission(VoiceAdmission admission) {
        if (admission == null || admission.operation() == null
                || admission.identityScope() == null || admission.identityScope().isBlank()
                || admission.requestId() == null || admission.requestId().isBlank()
                || admission.leaseToken() == null || admission.leaseToken().isBlank()) {
            return;
        }
        VoiceServiceSupport.release(coordinator, admission);
    }

    private VoiceTranscriptionResponse dispatch(
            String requestId,
            String language,
            VoiceAudioUpload upload,
            VoiceReservation reservation) {
        long started = System.nanoTime();
        try {
            FileChannelSpeechTranscriptionProvider handleProvider =
                    (FileChannelSpeechTranscriptionProvider) provider;
            SpeechTranscriptionResult result = handleProvider.transcribe(
                    new FileChannelSpeechTranscriptionRequest(
                            upload.channel(), upload.size(), upload.mediaType(),
                            language, upload.durationMs()));
            if (result == null || result.text() == null || result.text().isBlank()) {
                VoiceServiceSupport.transitionFailure(coordinator, reservation,
                        SpeechProviderException.FailureKind.KNOWN, requestId);
                throw VoiceException.of(VoiceErrorCode.NO_SPEECH, requestId);
            }
            VoiceTranscriptionResponse response = new VoiceTranscriptionResponse(
                    requestId, result.text(), result.detectedLanguage(), upload.durationMs());
            byte[] cached = objectMapper.writeValueAsBytes(response);
            byte[] clientJson = objectMapper.writeValueAsBytes(JsonResult.success(response));
            if (cached.length > MAX_CLIENT_JSON_BYTES
                    || clientJson.length > MAX_CLIENT_JSON_BYTES) {
                VoiceServiceSupport.transitionFailure(coordinator, reservation,
                        SpeechProviderException.FailureKind.KNOWN, requestId);
                throw VoiceException.of(VoiceErrorCode.PROVIDER_ERROR, requestId);
            }
            VoiceServiceSupport.completeSuccess(coordinator, reservation,
                    new VoiceCachedResult(cached, "application/json"), requestId);
            logSuccess(started, upload.size(), upload.durationMs(), upload.mediaType());
            return response;
        } catch (SpeechProviderException exception) {
            VoiceServiceSupport.transitionFailure(
                    coordinator, reservation, exception.failureKind(), requestId);
            throw VoiceServiceSupport.providerError(exception, requestId);
        } catch (VoiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            VoiceServiceSupport.transitionFailure(coordinator, reservation,
                    SpeechProviderException.FailureKind.UNKNOWN, requestId);
            throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
        }
    }

    private VoiceTranscriptionResponse decodeReplay(VoiceCachedResult replay, String requestId) {
        byte[] payload = replay == null ? null : replay.payload();
        if (payload == null || payload.length == 0 || payload.length > MAX_CLIENT_JSON_BYTES
                || !"application/json".equals(replay.contentType())) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        try {
            VoiceTranscriptionResponse response = objectMapper.readValue(
                    payload, VoiceTranscriptionResponse.class);
            if (!requestId.equals(response.requestId()) || response.text() == null
                    || response.durationMs() <= 0) {
                throw new IllegalArgumentException("invalid cached result");
            }
            return response;
        } catch (RuntimeException exception) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
    }

    private void logSuccess(long started, long bytes, long durationMs, String mediaType) {
        long latencyMs = (System.nanoTime() - started) / 1_000_000L;
        log.info("Voice STT completed code=E0 latencyMs={} byteBucket={} durationBucket={} provider={} mime={}",
                latencyMs, bucketBytes(bytes), bucketDuration(durationMs), provider.alias(), mediaType);
    }

    private static String bucketBytes(long bytes) {
        if (bytes <= 256 * 1024L) return "lte256k";
        if (bytes <= 1024 * 1024L) return "lte1m";
        return "lte5m";
    }

    private static String bucketDuration(long durationMs) {
        if (durationMs <= 10_000) return "lte10s";
        if (durationMs <= 30_000) return "lte30s";
        return "lte45s";
    }
}
