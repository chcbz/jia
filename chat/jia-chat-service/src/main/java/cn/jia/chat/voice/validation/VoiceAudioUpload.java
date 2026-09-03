package cn.jia.chat.voice.validation;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;

@Slf4j
public final class VoiceAudioUpload implements AutoCloseable {
    private final FileChannel channel;
    private final long size;
    private final String mediaType;
    private final byte[] audioDigest;
    private final long durationMs;

    public VoiceAudioUpload(
            FileChannel channel,
            long size,
            String mediaType,
            byte[] audioDigest,
            long durationMs) {
        this.channel = channel;
        this.size = size;
        this.mediaType = mediaType;
        this.audioDigest = Arrays.copyOf(audioDigest, audioDigest.length);
        this.durationMs = durationMs;
    }

    public FileChannel channel() {
        return channel;
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
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            log.warn("Voice temporary upload handle cleanup failed; payload details suppressed");
        }
    }
}
