package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Pcm16WavTest {
    private static final String REQUEST_ID = "01JVOICEPCM16WAV001";

    @Test
    void canonicalWavRoundTripsAndDurationUsesActualSamples() throws Exception {
        byte[] pcm = new byte[Pcm16Wav.BYTE_RATE + 2];
        for (int index = 0; index < pcm.length; index++) {
            pcm[index] = (byte) index;
        }
        byte[] wav = Pcm16Wav.wrap(pcm);
        Path path = Files.createTempFile("voice-pcm", ".wav");
        try {
            Files.write(path, wav);
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                Pcm16Wav.Info info = Pcm16Wav.inspect(channel);
                assertEquals(Pcm16Wav.HEADER_BYTES, info.dataOffset());
                assertEquals(pcm.length, info.dataBytes());
                assertEquals(1001, info.durationMs());
                assertArrayEquals(pcm, Pcm16Wav.readPcm(channel, pcm.length));
            }
            assertEquals(1001, new AudioDurationInspector().inspect(
                    path, Pcm16Wav.MEDIA_TYPE, REQUEST_ID));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void uploadProfileAcceptsOnlyExactAudioWavForRealtime() throws Exception {
        byte[] wav = Pcm16Wav.wrap(new byte[48_000]);
        VoiceAudioUploadFactory factory = new VoiceAudioUploadFactory(
                new AudioDurationInspector());
        try (VoiceAudioUpload upload = factory.create(new MockMultipartFile(
                "audio", "private.wav", "audio/wav", wav), REQUEST_ID,
                "cliproxy-realtime")) {
            assertEquals(Pcm16Wav.MEDIA_TYPE, upload.mediaType());
            assertEquals(1000, upload.durationMs());
        }
        for (String mediaType : new String[]{
                "audio/x-wav", "audio/wav;codecs=pcm", "audio/webm;codecs=opus"}) {
            VoiceException error = assertThrows(VoiceException.class,
                    () -> factory.create(new MockMultipartFile(
                            "audio", "private.wav", mediaType, wav), REQUEST_ID,
                            "cliproxy-realtime"));
            assertEquals(VoiceErrorCode.UNSUPPORTED_MEDIA, error.error());
        }
    }

    @Test
    void rejectsContainerLengthProfileAlignmentEmptyTruncationAndActualOverlength()
            throws Exception {
        byte[] valid = Pcm16Wav.wrap(new byte[48_000]);
        assertInvalid(mutate(valid, 4, 1));
        assertInvalid(mutate(valid, 20, 3));
        assertInvalid(mutate(valid, 22, 2));
        assertInvalid(mutate(valid, 24, 1));
        assertInvalid(mutate(valid, 34, 8));
        assertInvalid(Arrays.copyOf(valid, valid.length - 1));

        byte[] empty = Arrays.copyOf(valid, Pcm16Wav.HEADER_BYTES);
        ByteBuffer.wrap(empty).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, 36).putInt(40, 0);
        assertInvalid(empty);

        byte[] odd = Arrays.copyOf(valid, valid.length - 1);
        ByteBuffer.wrap(odd).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, odd.length - 8).putInt(40, odd.length - Pcm16Wav.HEADER_BYTES);
        assertInvalid(odd);

        byte[] overlong = Pcm16Wav.wrap(new byte[
                (int) ((AudioDurationInspector.MAX_DURATION_MS + 1L)
                        * Pcm16Wav.BYTE_RATE / 1_000L)]);
        Path path = Files.createTempFile("voice-overlong", ".wav");
        try {
            Files.write(path, overlong);
            VoiceException error = assertThrows(VoiceException.class,
                    () -> new AudioDurationInspector().inspect(
                            path, Pcm16Wav.MEDIA_TYPE, REQUEST_ID));
            assertEquals(VoiceErrorCode.TOO_LONG, error.error());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static byte[] mutate(byte[] source, int offset, int value) {
        byte[] copy = source.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    private static void assertInvalid(byte[] wav) throws Exception {
        Path path = Files.createTempFile("voice-invalid", ".wav");
        try {
            Files.write(path, wav);
            VoiceException error = assertThrows(VoiceException.class,
                    () -> new AudioDurationInspector().inspect(
                            path, Pcm16Wav.MEDIA_TYPE, REQUEST_ID));
            assertEquals(VoiceErrorCode.INVALID_AUDIO, error.error());
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
