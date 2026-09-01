package cn.jia.chat.voice.validation;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

@Slf4j
public final class VoiceAudioUpload implements AutoCloseable {
    private final Path path;
    private final long size;
    private final String mediaType;
    private final byte[] audioDigest;
    private final long durationMs;

    public VoiceAudioUpload(Path path, long size, String mediaType, byte[] audioDigest, long durationMs) {
        this.path = path;
        this.size = size;
        this.mediaType = mediaType;
        this.audioDigest = Arrays.copyOf(audioDigest, audioDigest.length);
        this.durationMs = durationMs;
    }

    public Path path() {
        return path;
    }

    public long size() {
        return size;
    }

    public String mediaType() {
        return mediaType;
    }

    public byte[] audioDigest() {
        return Arrays.copyOf(audioDigest, audioDigest.length);
    }

    public long durationMs() {
        return durationMs;
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            log.warn("Voice temporary upload cleanup failed; payload details suppressed");
        }
    }
}
