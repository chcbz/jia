package cn.jia.chat.voice;

public interface SpeechTranscriptionProvider {
    String alias();

    SpeechTranscriptionResult transcribe(SpeechTranscriptionRequest request)
            throws SpeechProviderException;
}
