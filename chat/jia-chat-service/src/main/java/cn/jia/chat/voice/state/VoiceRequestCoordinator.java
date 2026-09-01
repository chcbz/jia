package cn.jia.chat.voice.state;

public interface VoiceRequestCoordinator {
    VoiceBeginResult begin(
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest);

    void succeed(VoiceReservation reservation, VoiceCachedResult result);

    void failKnown(VoiceReservation reservation);

    void failUnknown(VoiceReservation reservation);

    void release(VoiceReservation reservation);
}
