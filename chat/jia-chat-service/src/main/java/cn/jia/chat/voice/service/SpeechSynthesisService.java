package cn.jia.chat.voice.service;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.VoiceIdentity;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.api.VoiceSynthesisRequest;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.provider.OpenAiCompatibleSpeechSynthesisProvider;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceCachedResult;
import cn.jia.chat.voice.state.VoiceDigests;
import cn.jia.chat.voice.state.VoiceOperation;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import cn.jia.chat.voice.validation.Pcm16Wav;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

@Slf4j
public final class SpeechSynthesisService {
    private final VoiceSpeechProperties properties;
    private final SpeechSynthesisProvider provider;
    private final VoiceRequestCoordinator coordinator;
    private final VoiceDigests digests;

    public SpeechSynthesisService(
            VoiceSpeechProperties properties,
            SpeechSynthesisProvider provider,
            VoiceRequestCoordinator coordinator,
            VoiceDigests digests) {
        this.properties = properties;
        this.provider = provider;
        this.coordinator = coordinator;
        this.digests = digests;
    }

    public void requireAvailable(String requestId) {
        VoiceServiceSupport.requireCommonEnabled(properties,
                properties.getSynthesis().isEnabled(), properties.getSynthesis().getProvider(),
                provider.alias(), requestId);
        if (!digests.available()) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
    }

    public SpeechSynthesisResult synthesize(VoiceIdentity identity, VoiceSynthesisRequest request) {
        String requestId = request.requestId();
        requireAvailable(requestId);
        VoiceSpeechProperties.Synthesis config = properties.getSynthesis();
        String expectedMediaType = mediaType(request.format(), requestId);
        String identityScope = digests.identityScope(identity);
        String digest = digests.synthesis(provider.alias(), config.getModel(),
                config.getProviderVoice(), request.text(), request.voice(), request.format());
        VoiceBeginResult begin;
        try {
            begin = coordinator.begin(VoiceOperation.SYNTHESIS, identityScope, requestId, digest);
        } catch (VoiceStateUnavailableException exception) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (begin == null || begin.outcome() == null) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (begin.outcome() == VoiceBeginResult.Outcome.REPLAY) {
            VoiceCachedResult replay = begin.replay();
            byte[] payload = replay == null ? null : replay.payload();
            if (payload == null || payload.length == 0
                    || payload.length > maximumBytes(request.format())
                    || !expectedMediaType.equals(replay.contentType())) {
                throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
            }
            return new SpeechSynthesisResult(payload, expectedMediaType);
        }
        VoiceReservation reservation = VoiceServiceSupport.reservation(begin, requestId);
        if (reservation == null
                || reservation.operation() != VoiceOperation.SYNTHESIS
                || !Objects.equals(identityScope, reservation.identityScope())
                || !Objects.equals(requestId, reservation.requestId())
                || !Objects.equals(digest, reservation.digest())
                || reservation.leaseToken() == null
                || reservation.leaseToken().isBlank()) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        long started = System.nanoTime();
        try {
            SpeechSynthesisResult result;
            try (VoiceLeaseGuard lease = VoiceLeaseGuard.startIfRealtime(
                    provider.alias(), properties, coordinator, reservation)) {
                result = provider.synthesize(new SpeechSynthesisRequest(
                        request.text(), request.voice(), request.format()));
                lease.requireOwned(requestId);
            }
            byte[] audio = result == null ? null : result.audio();
            if (audio == null || audio.length == 0
                    || audio.length > maximumBytes(request.format())
                    || !expectedMediaType.equals(result.mediaType())) {
                VoiceServiceSupport.transitionFailure(coordinator, reservation,
                        SpeechProviderException.FailureKind.KNOWN, requestId);
                throw VoiceException.of(VoiceErrorCode.PROVIDER_ERROR, requestId);
            }
            VoiceServiceSupport.completeSuccess(coordinator, reservation,
                    new VoiceCachedResult(audio, expectedMediaType), requestId);
            long latencyMs = (System.nanoTime() - started) / 1_000_000L;
            log.info("Voice TTS completed code=E0 latencyMs={} byteBucket={} provider={} mime={}",
                    latencyMs, audio.length <= 1024 * 1024 ? "lte1m" : "lte8m",
                    provider.alias(), expectedMediaType);
            return new SpeechSynthesisResult(audio, expectedMediaType);
        } catch (SpeechProviderException exception) {
            VoiceServiceSupport.transitionFailure(
                    coordinator, reservation, exception.failureKind(), requestId);
            throw VoiceServiceSupport.providerError(exception, requestId);
        } catch (VoiceException exception) {
            if (exception.error() == VoiceErrorCode.RESULT_UNKNOWN) {
                VoiceServiceSupport.transitionFailure(coordinator, reservation,
                        SpeechProviderException.FailureKind.UNKNOWN, requestId);
            }
            throw exception;
        } finally {
            VoiceServiceSupport.release(coordinator, reservation);
        }
    }

    private static String mediaType(String format, String requestId) {
        return switch (format) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> Pcm16Wav.MEDIA_TYPE;
            default -> throw VoiceException.of(VoiceErrorCode.INVALID_REQUEST, requestId);
        };
    }

    private static int maximumBytes(String format) {
        return "wav".equals(format)
                ? Pcm16Wav.MAX_WAV_BYTES
                : OpenAiCompatibleSpeechSynthesisProvider.MAX_AUDIO_BYTES;
    }
}
