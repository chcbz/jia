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
    void acceptsClassicAndBrowserContainerVariants() throws Exception {
        assertEquals(1200, inspector.inspect(fixture("mediarecorder-valid.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(1180, inspector.inspect(fixture("mediarecorder-chromium-unmodified.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(32969, inspector.inspect(
                fixture("mediarecorder-chromium-timesliced-multicluster.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(720, inspector.inspect(fixture("mediarecorder-webm-lacing-short.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(240, inspector.inspect(fixture("mediarecorder-webm-opus-delay.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(120, inspector.inspect(fixture("mediarecorder-webm-opushead-v2.webm"),
                "audio/webm", REQUEST_ID));
        assertEquals(1222, inspector.inspect(fixture("mediarecorder-valid.mp4"),
                "audio/mp4", REQUEST_ID));
        assertEquals(3222, inspector.inspect(fixture("mediarecorder-fragmented-valid.mp4"),
                "audio/mp4", REQUEST_ID));
        assertEquals(1122, inspector.inspect(
                fixture("mediarecorder-fragmented-explicit-base.mp4"),
                "audio/mp4", REQUEST_ID));
        assertEquals(3222, inspector.inspect(
                fixture("mediarecorder-fragmented-init-placeholders.mp4"),
                "audio/mp4", REQUEST_ID));
    }

    @Test
    void rejectsUnknownMalformedAndOverlongDurationsForBothContainers() throws Exception {
        assertError("mediarecorder-missing-duration.webm", "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-malformed.webm", "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-overlong.webm", "audio/webm", VoiceErrorCode.TOO_LONG);
        assertError("mediarecorder-derived-overlong.webm", "audio/webm", VoiceErrorCode.TOO_LONG);
        assertError("mediarecorder-webm-equal-timestamps-overlong.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-webm-1ms-timestamps-overlong.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-webm-fixed-laced-overlong.webm",
                "audio/webm", VoiceErrorCode.TOO_LONG);
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
        assertError("mediarecorder-chromium-fragmented-opus.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-forged-mp4a.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-no-esds.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-empty-media.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-invalid-media-extent.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        for (String fixture : new String[] {
                "mediarecorder-webm-missing-opushead.webm",
                "mediarecorder-webm-short-opushead.webm",
                "mediarecorder-webm-bad-opushead-magic.webm",
                "mediarecorder-webm-bad-opushead-version.webm",
                "mediarecorder-webm-bad-opushead-channels.webm",
                "mediarecorder-webm-channel-mismatch.webm",
                "mediarecorder-webm-bad-opushead-mapping.webm",
                "mediarecorder-webm-multiple-tracks.webm",
                "mediarecorder-webm-video-track.webm",
                "mediarecorder-webm-zero-track-number.webm",
                "mediarecorder-webm-bad-codec-delay.webm",
                "mediarecorder-webm-bad-seek-preroll.webm"}) {
            assertError(fixture, "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        }
    }


    @Test
    void rejectsMalformedUnknownSizedAndOverlongChromiumClusters() throws Exception {
        assertError("mediarecorder-chromium-multicluster-nested-id.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-chromium-multicluster-unknown-child.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-chromium-multicluster-truncated.webm",
                "audio/webm", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-chromium-multicluster-overlong.webm",
                "audio/webm", VoiceErrorCode.TOO_LONG);
    }

    @Test
    void rejectsMalformedOrUnboundedFragmentedMp4State() throws Exception {
        assertError("mediarecorder-fragmented-missing-default.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-invalid-offset.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-negative-offset.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-backward-timeline.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-unsupported-flags.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-unsupported-version.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-excessive-samples.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-excessive-fragments.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-excessive-boxes.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-empty-media.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-no-esds.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-truncated.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-overlong.mp4",
                "audio/mp4", VoiceErrorCode.TOO_LONG);
        assertError("mediarecorder-fragmented-init-duration-garbage.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
    }

    @Test
    void rejectsLeafBoxesThatBorrowBytesFromSiblings() throws Exception {
        assertError("mediarecorder-fragmented-short-mdhd.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-fragmented-short-mvhd.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-chromium-fragmented-short-v1-mdhd.mp4",
                "audio/mp4", VoiceErrorCode.INVALID_AUDIO);
        assertError("mediarecorder-chromium-fragmented-short-v1-mvhd.mp4",
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
