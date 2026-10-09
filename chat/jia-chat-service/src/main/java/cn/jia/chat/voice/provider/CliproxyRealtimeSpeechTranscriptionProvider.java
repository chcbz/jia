package cn.jia.chat.voice.provider;

import cn.jia.chat.voice.SpeechProviderException;
import cn.jia.chat.voice.SpeechTranscriptionResult;
import cn.jia.chat.voice.config.SpringAiOpenAiVoiceFacade;
import cn.jia.chat.voice.config.VoiceSpeechProperties;
import cn.jia.chat.voice.validation.Pcm16Wav;
import cn.jia.chat.voice.validation.VoiceAudioUploadFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

public final class CliproxyRealtimeSpeechTranscriptionProvider
        implements FileChannelSpeechTranscriptionProvider {
    private final VoiceSpeechProperties properties;
    private final SpringAiOpenAiVoiceFacade openAi;
    private final CliproxyRealtimeSessionClient client;

    public CliproxyRealtimeSpeechTranscriptionProvider(
            VoiceSpeechProperties properties,
            SpringAiOpenAiVoiceFacade openAi,
            ObjectMapper objectMapper) {
        this(properties, openAi, new CliproxyRealtimeSessionClient(
                properties, objectMapper, new JdkRealtimeWebSocketTransport()));
    }

    CliproxyRealtimeSpeechTranscriptionProvider(
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
    public SpeechTranscriptionResult transcribe(FileChannelSpeechTranscriptionRequest request)
            throws SpeechProviderException {
        if (request == null || request.audioChannel() == null
                || request.audioBytes() <= 0
                || request.audioBytes() > VoiceAudioUploadFactory.MAX_AUDIO_BYTES
                || !Pcm16Wav.MEDIA_TYPE.equals(request.mediaType())
                || request.language() == null || request.language().isBlank()) {
            throw known();
        }
        try {
            if (!request.audioChannel().isOpen()
                    || request.audioChannel().size() != request.audioBytes()) {
                throw known();
            }
            byte[] pcm = Pcm16Wav.readPcm(
                    request.audioChannel(), (int) VoiceAudioUploadFactory.MAX_AUDIO_BYTES);
            String text = client.transcribe(
                    openAi.transcription(), properties.getTranscription().getModel(), pcm);
            return new SpeechTranscriptionResult(text, languageCode(request.language()));
        } catch (SpeechProviderException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw known();
        }
    }

    private static String languageCode(String language) throws SpeechProviderException {
        if ("zh-CN".equals(language)) {
            return "zh";
        }
        throw known();
    }

    private static SpeechProviderException known() {
        return new SpeechProviderException(SpeechProviderException.FailureKind.KNOWN,
                "realtime transcription provider rejected request");
    }
}
