package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class VoiceAudioUploadFactory {
    public static final long MAX_AUDIO_BYTES = 5L * 1024 * 1024;
    public static final String WEBM_OPUS_MEDIA_TYPE = "audio/webm;codecs=opus";
    private static final int BUFFER_BYTES = 16 * 1024;
    private final AudioDurationInspector durationInspector;
    private final UnlinkedFileCreator unlinkedFileCreator;

    public VoiceAudioUploadFactory(AudioDurationInspector durationInspector) {
        this(durationInspector, VoiceAudioUploadFactory::createUnlinkedTempFile);
    }

    VoiceAudioUploadFactory(
            AudioDurationInspector durationInspector, UnlinkedFileCreator unlinkedFileCreator) {
        this.durationInspector = durationInspector;
        this.unlinkedFileCreator = unlinkedFileCreator;
    }

    public VoiceAudioUpload create(MultipartFile file, String requestId) {
        if (file == null || file.isEmpty()) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
        if (file.getSize() > MAX_AUDIO_BYTES) {
            throw VoiceException.of(VoiceErrorCode.TOO_LARGE, requestId);
        }
        String mediaType = canonicalMediaType(file.getContentType(), requestId);
        FileChannel channel = null;
        try {
            channel = unlinkedFileCreator.create();
            long total = spool(file, channel, requestId);
            long durationMs = durationInspector.inspect(channel, mediaType, requestId);
            byte[] digest = digest(channel, total, requestId);
            return new VoiceAudioUpload(channel, total, mediaType, digest, durationMs);
        } catch (VoiceException exception) {
            close(channel);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException exception) {
            close(channel);
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
    }

    static String canonicalMediaType(String declared, String requestId) {
        if (declared == null) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        String[] parts = declared.toLowerCase(Locale.ROOT).split(";", -1);
        String base = parts[0].strip();
        if (!base.equals("audio/webm") || parts.length != 2) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        String parameter = parts[1].strip();
        int separator = parameter.indexOf('=');
        if (separator <= 0 || !"codecs".equals(parameter.substring(0, separator).strip())) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        String codec = parameter.substring(separator + 1).strip();
        if (codec.length() >= 2 && codec.startsWith("\"") && codec.endsWith("\"")) {
            codec = codec.substring(1, codec.length() - 1);
        }
        if (!codec.equals("opus")) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        return WEBM_OPUS_MEDIA_TYPE;
    }

    private static long spool(
            MultipartFile file, FileChannel channel, String requestId) throws IOException {
        long total = 0;
        channel.truncate(0);
        channel.position(0);
        try (InputStream input = file.getInputStream()) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total = Math.addExact(total, read);
                if (total > MAX_AUDIO_BYTES) {
                    throw VoiceException.of(VoiceErrorCode.TOO_LARGE, requestId);
                }
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    if (channel.write(bytes) <= 0) {
                        throw new IOException("voice spool write made no progress");
                    }
                }
            }
        }
        if (total == 0 || channel.size() != total) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
        return total;
    }

    private static byte[] digest(FileChannel channel, long length, String requestId)
            throws IOException, NoSuchAlgorithmException {
        if (!channel.isOpen() || channel.size() != length) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES);
        long offset = 0;
        while (offset < length) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), length - offset));
            int read = channel.read(buffer, offset);
            if (read <= 0) {
                throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
            }
            offset += read;
            buffer.flip();
            digest.update(buffer);
        }
        if (channel.size() != length) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
        return digest.digest();
    }

    private static void close(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // Payload details are deliberately not logged.
        }
    }

    private static FileChannel createUnlinkedTempFile() throws IOException {
        Path path = null;
        FileChannel channel = null;
        try {
            path = Files.createTempFile("cyf-voice-", ".upload");
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            Files.delete(path);
            path = null;
            return channel;
        } catch (IOException exception) {
            close(channel);
            if (path != null) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Only a still-linked factory-created pathname is eligible for cleanup.
                }
            }
            throw exception;
        }
    }

    @FunctionalInterface
    interface UnlinkedFileCreator {
        FileChannel create() throws IOException;
    }
}
