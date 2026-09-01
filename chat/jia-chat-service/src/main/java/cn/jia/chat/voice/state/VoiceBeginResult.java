package cn.jia.chat.voice.state;

public record VoiceBeginResult(Outcome outcome, VoiceReservation reservation, VoiceCachedResult replay) {
    public enum Outcome {
        RESERVED,
        REPLAY,
        IN_PROGRESS,
        IDEMPOTENCY_CONFLICT,
        RESULT_UNKNOWN,
        FAILED_KNOWN,
        RATE_LIMITED,
        CONCURRENCY_LIMITED
    }

    public static VoiceBeginResult reserved(VoiceReservation reservation) {
        return new VoiceBeginResult(Outcome.RESERVED, reservation, null);
    }

    public static VoiceBeginResult replay(VoiceCachedResult replay) {
        return new VoiceBeginResult(Outcome.REPLAY, null, replay);
    }

    public static VoiceBeginResult outcome(Outcome outcome) {
        return new VoiceBeginResult(outcome, null, null);
    }
}
