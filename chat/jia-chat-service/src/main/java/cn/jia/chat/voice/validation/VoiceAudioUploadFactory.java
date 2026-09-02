package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class VoiceAudioUploadFactory {
    public static final long MAX_AUDIO_BYTES = 5L * 1024 * 1024;
    private static final int BUFFER_BYTES = 16 * 1024;
    private final AudioDurationInspector durationInspector;

    public VoiceAudioUploadFactory(AudioDurationInspector durationInspector) {
        this.durationInspector = durationInspector;
    }

    public VoiceAudioUpload create(MultipartFile file, String requestId) {
        if (file == null || file.isEmpty()) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
        if (file.getSize() > MAX_AUDIO_BYTES) {
            throw VoiceException.of(VoiceErrorCode.TOO_LARGE, requestId);
        }
        String mediaType = canonicalMediaType(file.getContentType(), requestId);
        Path path = null;
        try {
            path = Files.createTempFile("cyf-voice-", ".upload");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream input = file.getInputStream();
                 OutputStream output = Files.newOutputStream(path, StandardOpenOption.TRUNCATE_EXISTING)) {
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
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (total == 0) {
                throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
            }
            long durationMs = durationInspector.inspect(path, mediaType, requestId);
            return new VoiceAudioUpload(path, total, mediaType, digest.digest(), durationMs);
        } catch (VoiceException exception) {
            delete(path);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException | ArithmeticException exception) {
            delete(path);
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
    }

    static String canonicalMediaType(String declared, String requestId) {
        if (declared == null) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        String[] parts = declared.toLowerCase(Locale.ROOT).split(";", -1);
        String base = parts[0].strip();
        if (!base.equals("audio/webm")) {
            throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
        }
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].strip();
            if (parameter.isEmpty()) {
                continue;
            }
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
        }
        return base;
    }

    private static void delete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The path and original filename are deliberately not logged.
        }
    }
}
