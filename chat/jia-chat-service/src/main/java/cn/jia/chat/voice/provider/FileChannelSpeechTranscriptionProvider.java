package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionProvider;
import cn.jia.chat.voice.SpeechTranscriptionRequest;
import cn.jia.chat.voice.SpeechTranscriptionResult;

/** Service-internal extension used to preserve one-handle upload identity without changing core ABI. */
public interface FileChannelSpeechTranscriptionProvider extends SpeechTranscriptionProvider {
    SpeechTranscriptionResult transcribe(FileChannelSpeechTranscriptionRequest request)
            throws SpeechProviderException;

    @Override
    default SpeechTranscriptionResult transcribe(SpeechTranscriptionRequest request)
            throws SpeechProviderException {
        throw new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "path-backed transcription is not accepted");
    }
}
