package cn.jia.chat.voice.service;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.api.VoiceTranscriptionResponse;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import cn.jia.chat.voice.validation.VoiceAudioUpload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public final class SpeechTranscriptionService {
    private final VoiceSpeechProperties properties;
    private final SpeechTranscriptionProvider provider;
    private final VoiceRequestCoordinator coordinator;
    private final VoiceDigests digests;
    private final ObjectMapper objectMapper;

    public SpeechTranscriptionService(
            VoiceSpeechProperties properties,
            SpeechTranscriptionProvider provider,
            VoiceRequestCoordinator coordinator,
            VoiceDigests digests,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.provider = provider;
        this.coordinator = coordinator;
        this.digests = digests;
        this.objectMapper = objectMapper;
    }

    public void requireAvailable(String requestId) {
        VoiceServiceSupport.requireCommonEnabled(properties,
                properties.getTranscription().isEnabled(), properties.getTranscription().getProvider(),
                provider.alias(), requestId);
        if (!digests.available()) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
    }

    public VoiceTranscriptionResponse transcribe(
            VoiceIdentity identity, String requestId, String language, VoiceAudioUpload upload) {
        requireAvailable(requestId);
        String identityScope = digests.identityScope(identity);
        String digest = digests.transcription(language, upload.mediaType(), upload.audioDigest());
        VoiceBeginResult begin;
        try {
            begin = coordinator.begin(VoiceOperation.TRANSCRIPTION, identityScope, requestId, digest);
        } catch (VoiceStateUnavailableException exception) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (begin.outcome() == VoiceBeginResult.Outcome.REPLAY) {
            return decodeReplay(begin.replay(), requestId);
        }
        VoiceReservation reservation = VoiceServiceSupport.reservation(begin, requestId);
        long started = System.nanoTime();
        try {
            SpeechTranscriptionResult result = provider.transcribe(new SpeechTranscriptionRequest(
                    upload.path(), upload.size(), upload.mediaType(), language, upload.durationMs()));
            if (result == null || result.text() == null || result.text().isBlank()) {
                VoiceServiceSupport.transitionFailure(coordinator, reservation,
                        SpeechProviderException.FailureKind.KNOWN, requestId);
                throw VoiceException.of(VoiceErrorCode.NO_SPEECH, requestId);
            }
            VoiceTranscriptionResponse response = new VoiceTranscriptionResponse(
                    requestId, result.text(), result.detectedLanguage(), upload.durationMs());
            byte[] cached = objectMapper.writeValueAsBytes(response);
            try {
                coordinator.succeed(reservation, new VoiceCachedResult(cached, "application/json"));
            } catch (VoiceStateUnavailableException exception) {
                throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
            }
            logSuccess(started, upload.size(), upload.durationMs(), upload.mediaType());
            return response;
        } catch (SpeechProviderException exception) {
            VoiceServiceSupport.transitionFailure(
                    coordinator, reservation, exception.failureKind(), requestId);
            throw VoiceServiceSupport.providerError(exception, requestId);
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException exception) {
            VoiceServiceSupport.transitionFailure(coordinator, reservation,
                    SpeechProviderException.FailureKind.UNKNOWN, requestId);
            throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
        } finally {
            VoiceServiceSupport.release(coordinator, reservation);
        }
    }

    private VoiceTranscriptionResponse decodeReplay(VoiceCachedResult replay, String requestId) {
        try {
            VoiceTranscriptionResponse response = objectMapper.readValue(
                    replay.payload(), VoiceTranscriptionResponse.class);
            if (!requestId.equals(response.requestId()) || response.text() == null
                    || response.durationMs() <= 0) {
                throw new IOException("invalid cached result");
            }
            return response;
        } catch (IOException | RuntimeException exception) {
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
