package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoiceAudioUploadFactoryTest {
    private static final String REQUEST_ID = "01JVOICEUPLOADR4001";

    @TempDir
    Path tempDirectory;

    @Test
    void unlinkedUploadRetainsOnlyHandleAndCloseNeverDeletesReplacementPath() throws Exception {
        byte[] original = fixture();
        byte[] replacement = "foreign-replacement-must-survive"
                .getBytes(StandardCharsets.US_ASCII);
        Path formerPath = tempDirectory.resolve("spool.upload");
        VoiceAudioUploadFactory factory = new VoiceAudioUploadFactory(
                new AudioDurationInspector(), () -> unlinkedChannelWithReplacement(
                        formerPath, replacement));

        FileChannel retained;
        try (VoiceAudioUpload upload = factory.create(new MockMultipartFile(
                "audio", "private.webm", "audio/webm;codecs=opus", original), REQUEST_ID)) {
            retained = upload.channel();
            assertEquals(original.length, upload.size());
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(original),
                    upload.audioDigest());
            assertTrue(Files.exists(formerPath));
            assertArrayEquals(replacement, Files.readAllBytes(formerPath));
            for (var field : VoiceAudioUpload.class.getDeclaredFields()) {
                assertFalse(Path.class.isAssignableFrom(field.getType()), field.getName());
            }
        }

        assertFalse(retained.isOpen());
        assertTrue(Files.exists(formerPath));
        assertArrayEquals(replacement, Files.readAllBytes(formerPath));
    }

    @Test
    void parseFailureAfterUnlinkClosesHandleWithoutDeletingReplacementPath() throws Exception {
        byte[] replacement = "foreign-replacement-after-unlink"
                .getBytes(StandardCharsets.US_ASCII);
        Path formerPath = tempDirectory.resolve("invalid.upload");
        VoiceAudioUploadFactory factory = new VoiceAudioUploadFactory(
                new AudioDurationInspector(), () -> unlinkedChannelWithReplacement(
                        formerPath, replacement));

        VoiceException error = assertThrows(VoiceException.class,
                () -> factory.create(new MockMultipartFile(
                        "audio", "private.webm", "audio/webm;codecs=opus",
                        new byte[]{1, 2, 3, 4}), REQUEST_ID));

        assertEquals(VoiceErrorCode.INVALID_AUDIO, error.error());
        assertTrue(Files.exists(formerPath));
        assertArrayEquals(replacement, Files.readAllBytes(formerPath));
    }

    private FileChannel unlinkedChannelWithReplacement(Path path, byte[] replacement)
            throws java.io.IOException {
        FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
        Files.delete(path);
        Files.write(path, replacement, StandardOpenOption.CREATE_NEW);
        return channel;
    }

    private byte[] fixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/cn/jia/chat/voice/media/mediarecorder-valid.webm")) {
            return input.readAllBytes();
        }
    }
}
