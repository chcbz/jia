package cn.jia.chat.voice.service;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.state.VoiceBeginResult;
import cn.jia.chat.voice.state.VoiceRequestCoordinator;
import cn.jia.chat.voice.state.VoiceReservation;
import cn.jia.chat.voice.state.VoiceStateUnavailableException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class VoiceServiceSupport {
    private VoiceServiceSupport() {
    }

    static void requireCommonEnabled(
            VoiceSpeechProperties properties, boolean operationEnabled,
            String configuredProvider, String providerAlias, String requestId) {
        if (!properties.isEnabled() || !operationEnabled
                || configuredProvider == null || "disabled".equals(configuredProvider)) {
            throw VoiceException.of(VoiceErrorCode.DISABLED, requestId);
        }
        if ("disabled".equals(providerAlias) || !configuredProvider.equals(providerAlias)) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
        if (properties.getPerMinute() < 1 || properties.getPerHour() < properties.getPerMinute()
                || properties.getGlobalConcurrency() < 1
                || properties.getProviderDeadlineMillis() < 1
                || properties.getProviderDeadlineMillis() > 25_000
                || properties.getConnectTimeoutMillis() < 1
                || properties.getConnectTimeoutMillis() > 3_000) {
            throw VoiceException.of(VoiceErrorCode.UNAVAILABLE, requestId);
        }
    }

    static VoiceReservation reservation(VoiceBeginResult begin, String requestId) {
        return switch (begin.outcome()) {
            case RESERVED -> begin.reservation();
            case IN_PROGRESS, CONCURRENCY_LIMITED -> throw VoiceException.of(
                    VoiceErrorCode.IN_PROGRESS, requestId);
            case IDEMPOTENCY_CONFLICT, FAILED_KNOWN -> throw VoiceException.of(
                    VoiceErrorCode.IDEMPOTENCY_CONFLICT, requestId);
            case RESULT_UNKNOWN -> throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
            case RATE_LIMITED -> throw VoiceException.of(VoiceErrorCode.RATE_LIMITED, requestId);
            case REPLAY -> throw new IllegalArgumentException("replay has no reservation");
        };
    }

    static void transitionFailure(
            VoiceRequestCoordinator coordinator, VoiceReservation reservation,
            SpeechProviderException.FailureKind kind, String requestId) {
        try {
            if (kind == SpeechProviderException.FailureKind.KNOWN) {
                coordinator.failKnown(reservation);
            } else {
                coordinator.failUnknown(reservation);
            }
        } catch (VoiceStateUnavailableException exception) {
            throw VoiceException.of(VoiceErrorCode.RESULT_UNKNOWN, requestId);
        }
    }

    static void release(VoiceRequestCoordinator coordinator, VoiceReservation reservation) {
        if (reservation == null) {
            return;
        }
        try {
            coordinator.release(reservation);
        } catch (VoiceStateUnavailableException exception) {
            log.warn("Voice concurrency lease release failed; lease will expire by deadline");
        }
    }

    static VoiceException providerError(SpeechProviderException exception, String requestId) {
        return VoiceException.of(
                exception.failureKind() == SpeechProviderException.FailureKind.TIMEOUT
                        ? VoiceErrorCode.PROVIDER_TIMEOUT : VoiceErrorCode.PROVIDER_ERROR,
                requestId);
    }
}
