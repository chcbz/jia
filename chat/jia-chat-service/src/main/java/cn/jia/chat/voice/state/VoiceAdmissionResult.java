package cn.jia.chat.voice.state;

public record VoiceAdmissionResult(Outcome outcome, VoiceAdmission admission) {
    public enum Outcome {
        ADMITTED,
        RATE_LIMITED,
        CONCURRENCY_LIMITED
    }

    public static VoiceAdmissionResult admitted(VoiceAdmission admission) {
        return new VoiceAdmissionResult(Outcome.ADMITTED, admission);
    }

    public static VoiceAdmissionResult outcome(Outcome outcome) {
        return new VoiceAdmissionResult(outcome, null);
    }
}
