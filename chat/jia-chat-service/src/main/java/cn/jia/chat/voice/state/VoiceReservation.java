package cn.jia.chat.voice.state;

public record VoiceReservation(
        VoiceOperation operation,
        String identityScope,
        String requestId,
        String digest,
        String leaseToken) {
}
