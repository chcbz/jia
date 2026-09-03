package cn.jia.chat.voice.state;

public interface VoiceRequestCoordinator {
    VoiceAdmissionResult admit(
            VoiceOperation operation,
            String identityScope,
            String requestId);

    VoiceBeginResult begin(VoiceAdmission admission, String digest);

    VoiceBeginResult begin(
            VoiceOperation operation,
            String identityScope,
            String requestId,
            String digest);

    void succeed(VoiceReservation reservation, VoiceCachedResult result);

    void failKnown(VoiceReservation reservation);

    void failUnknown(VoiceReservation reservation);

    void release(VoiceAdmission admission);

    void release(VoiceReservation reservation);
}
