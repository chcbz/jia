package cn.jia.chat.voice.state;

public final class UnavailableVoiceRequestCoordinator implements VoiceRequestCoordinator {
    @Override
    public VoiceBeginResult begin(VoiceOperation operation, String identityScope, String requestId, String digest) {
        throw new VoiceStateUnavailableException();
    }

    @Override
    public void succeed(VoiceReservation reservation, VoiceCachedResult result) {
        throw new VoiceStateUnavailableException();
    }

    @Override
    public void failKnown(VoiceReservation reservation) {
        throw new VoiceStateUnavailableException();
    }

    @Override
    public void failUnknown(VoiceReservation reservation) {
        throw new VoiceStateUnavailableException();
    }

    @Override
    public void release(VoiceReservation reservation) {
        throw new VoiceStateUnavailableException();
    }
}
