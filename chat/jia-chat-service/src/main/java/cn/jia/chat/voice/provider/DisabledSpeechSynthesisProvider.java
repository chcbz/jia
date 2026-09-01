package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechSynthesisResult;

public final class DisabledSpeechSynthesisProvider implements SpeechSynthesisProvider {
    @Override
    public String alias() {
        return "disabled";
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request)
            throws SpeechProviderException {
        throw new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "synthesis provider disabled");
    }
}
