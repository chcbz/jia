package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.junit.jupiter.api.Test;

import java.net.URISyntaxException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AudioDurationInspectorTest {
    private final AudioDurationInspector inspector = new AudioDurationInspector();
    private static final String REQUEST_ID = "01JVOICEFIXTURE0001";

    @Test
    void acceptsFinalizedWebmOpusMp4AacAndUnmodifiedChromiumWebm() throws Exception {
        assertEquals(1200, inspector.inspect(fixture("mediarecorder-valid.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(1240, inspector.inspect(fixture("mediarecorder-chromium-unmodified.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(1200, inspector.inspect(fixture("mediarecorder-valid.mp4"),
                "audio/mp4", REQUEST_ID));
    }

    @Test
    void rejectsUnknownMalformedAndOverlongDurationsForBothContainers() throws Exception {
        assertError("mediarecorder-missing-duration.webm", "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-malformed.webm", "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-overlong.webm", "audio/webm", VoiceErrorCode.TOO_LONG);
        assertError("mediarecorder-derived-overlong.webm", "audio/webm", VoiceErrorCode.TOO_LONG);
        assertError("mediarecorder-missing-duration.mp4", "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-malformed.mp4", "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-overlong.mp4", "audio/mp4", VoiceErrorCode.TOO_LONG);
    }

    @Test
    void rejectsForgedTrackMetadataAndWrongContainerCodecs() throws Exception {
        assertError("mediarecorder-forged-metadata.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-wrong-codec.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-forged-metadata.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-wrong-codec.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
    }

    @Test
    void declaredMimeCodecParametersAreStrictAndBrowserCompatible() {
        assertEquals("audio/webm", VoiceAudioUploadFactory.canonicalMediaType(
                "audio/webm;codecs=opus", REQUEST_ID));
        assertEquals("audio/webm", VoiceAudioUploadFactory.canonicalMediaType(
                "audio/webm; codecs=\"opus\"", REQUEST_ID));
        assertEquals("audio/mp4", VoiceAudioUploadFactory.canonicalMediaType(
                "audio/mp4;codecs=mp4a.40.2", REQUEST_ID));
        VoiceException mismatch = assertThrows(VoiceException.class,
                () -> VoiceAudioUploadFactory.canonicalMediaType(
                        "audio/mp4;codecs=opus", REQUEST_ID));
        assertEquals(VoiceErrorCode.UNSUPPORTED_MEDIA, mismatch.error());
    }

    private void assertError(String name, String mediaType, VoiceErrorCode expected) throws Exception {
        VoiceException error = assertThrows(VoiceException.class,
                () -> inspector.inspect(fixture(name), mediaType, REQUEST_ID));
        assertEquals(expected, error.error());
        assertEquals(REQUEST_ID, error.requestId());
    }

    private Path fixture(String name) throws URISyntaxException {
        return Path.of(getClass().getResource("/cn/jia/chat/voice/media/" + name).toURI());
    }
}
