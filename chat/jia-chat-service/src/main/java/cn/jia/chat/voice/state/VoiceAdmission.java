package cn.jia.chat.voice.state;

/** A pre-materialization rate and concurrency admission bound to one identity and request. */
public record VoiceAdmission(
        VoiceOperation operation,
        String identityScope,
        String requestId,
        String leaseToken) {
}
