package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechSynthesisProvider;
import cn.jia.chat.voice.SpeechSynthesisRequest;
import cn.jia.chat.voice.SpeechSynthesisResult;
import cn.jia.chat.voice.config.SpringAiOpenAiVoiceFacade;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.validation.Pcm16Wav;
import tools.jackson.databind.ObjectMapper;

public final class CliproxyRealtimeSpeechSynthesisProvider implements SpeechSynthesisProvider {
    private final VoiceSpeechProperties properties;
    private final SpringAiOpenAiVoiceFacade openAi;
    private final CliproxyRealtimeSessionClient client;

    public CliproxyRealtimeSpeechSynthesisProvider(
            VoiceSpeechProperties properties,
            SpringAiOpenAiVoiceFacade openAi,
            ObjectMapper objectMapper) {
        this(properties, openAi, new CliproxyRealtimeSessionClient(
                properties, objectMapper, new JdkRealtimeWebSocketTransport()));
    }

    CliproxyRealtimeSpeechSynthesisProvider(
            VoiceSpeechProperties properties,
            SpringAiOpenAiVoiceFacade openAi,
            CliproxyRealtimeSessionClient client) {
        this.properties = properties;
        this.openAi = openAi;
        this.client = client;
    }

    @Override
    public String alias() {
        return CliproxyRealtimeSessionClient.ALIAS;
    }

    @Override
    public SpeechSynthesisResult synthesize(SpeechSynthesisRequest request)
            throws SpeechProviderException {
        VoiceSpeechProperties.Synthesis config = properties.getSynthesis();
        if (request == null || request.text() == null
                || !"juyiting-default".equals(request.voice())
                || !"wav".equals(request.format())
                || config.getProviderVoice() == null || config.getProviderVoice().isBlank()) {
            throw new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                    "realtime synthesis provider rejected request");
        }
        byte[] wav = client.synthesize(openAi.synthesis(), config.getModel(),
                config.getProviderVoice(), request.text());
        return new SpeechSynthesisResult(wav, Pcm16Wav.MEDIA_TYPE);
    }
}
