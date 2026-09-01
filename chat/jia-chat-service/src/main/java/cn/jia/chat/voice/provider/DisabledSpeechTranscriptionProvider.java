package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.SpeechTranscriptionResult;

public final class DisabledSpeechTranscriptionProvider implements SpeechTranscriptionProvider {
    @Override
    public String alias() {
        return "disabled";
    }

    @Override
    public SpeechTranscriptionResult transcribe(SpeechTranscriptionRequest request)
            throws SpeechProviderException {
        throw new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "transcription provider disabled");
    }
}
