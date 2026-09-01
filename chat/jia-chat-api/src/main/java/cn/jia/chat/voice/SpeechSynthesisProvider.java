package cn.jia.chat.voice;

public interface SpeechSynthesisProvider {
    String alias();

    SpeechSynthesisResult synthesize(SpeechSynthesisRequest request)
            throws SpeechProviderException;
}
