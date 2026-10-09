package cn.jia.chat.voice.validation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/** Strict canonical PCM16 mono 24 kHz RIFF/WAVE codec used by the realtime voice profile. */
public final class Pcm16Wav {
    public static final String MEDIA_TYPE = "audio/wav";
    public static final int SAMPLE_RATE = 24_000;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int BLOCK_ALIGN = 2;
    public static final int BYTE_RATE = SAMPLE_RATE * BLOCK_ALIGN;
    public static final int HEADER_BYTES = 44;
    public static final int MAX_WAV_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PCM_BYTES = MAX_WAV_BYTES - HEADER_BYTES;

    private Pcm16Wav() {
    }

    public static Info inspect(FileChannel channel) throws IOException {
        if (channel == null || !channel.isOpen()) {
            throw new IOException("WAV channel unavailable");
        }
        long size = channel.size();
        if (size < HEADER_BYTES || size > Integer.MAX_VALUE) {
            throw new IOException("invalid WAV size");
        }
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readExact(channel, header, 0);
        header.flip();
        if (header.getInt() != fourCc("RIFF")) {
            throw new IOException("missing RIFF");
        }
        long riffLength = Integer.toUnsignedLong(header.getInt());
        if (riffLength != size - 8 || header.getInt() != fourCc("WAVE")
                || header.getInt() != fourCc("fmt ") || header.getInt() != 16) {
            throw new IOException("invalid canonical WAV header");
        }
        int audioFormat = Short.toUnsignedInt(header.getShort());
        int channels = Short.toUnsignedInt(header.getShort());
        long sampleRate = Integer.toUnsignedLong(header.getInt());
        long byteRate = Integer.toUnsignedLong(header.getInt());
        int blockAlign = Short.toUnsignedInt(header.getShort());
        int bitsPerSample = Short.toUnsignedInt(header.getShort());
        if (audioFormat != 1 || channels != CHANNELS || sampleRate != SAMPLE_RATE
                || byteRate != BYTE_RATE || blockAlign != BLOCK_ALIGN
                || bitsPerSample != BITS_PER_SAMPLE || header.getInt() != fourCc("data")) {
            throw new IOException("unsupported WAV PCM profile");
        }
        long dataBytes = Integer.toUnsignedLong(header.getInt());
        if (dataBytes <= 0 || dataBytes % BLOCK_ALIGN != 0
                || dataBytes != size - HEADER_BYTES) {
            throw new IOException("invalid WAV data length");
        }
        long samples = dataBytes / BLOCK_ALIGN;
        long durationMs = Math.floorDiv(Math.addExact(
                Math.multiplyExact(samples, 1_000L), SAMPLE_RATE - 1L), SAMPLE_RATE);
        if (durationMs <= 0) {
            throw new IOException("empty WAV duration");
        }
        return new Info(HEADER_BYTES, Math.toIntExact(dataBytes), samples, durationMs);
    }

    public static byte[] readPcm(FileChannel channel, int maximumBytes) throws IOException {
        Info info = inspect(channel);
        if (info.dataBytes() > maximumBytes) {
            throw new IOException("WAV PCM exceeds bound");
        }
        ByteBuffer pcm = ByteBuffer.allocate(info.dataBytes());
        readExact(channel, pcm, info.dataOffset());
        return pcm.array();
    }

    public static byte[] wrap(byte[] pcm) throws IOException {
        if (pcm == null || pcm.length == 0 || pcm.length > MAX_PCM_BYTES
                || pcm.length % BLOCK_ALIGN != 0) {
            throw new IOException("invalid PCM output");
        }
        ByteBuffer wav = ByteBuffer.allocate(Math.addExact(HEADER_BYTES, pcm.length))
                .order(ByteOrder.LITTLE_ENDIAN);
        wav.putInt(fourCc("RIFF"));
        wav.putInt(36 + pcm.length);
        wav.putInt(fourCc("WAVE"));
        wav.putInt(fourCc("fmt "));
        wav.putInt(16);
        wav.putShort((short) 1);
        wav.putShort((short) CHANNELS);
        wav.putInt(SAMPLE_RATE);
        wav.putInt(BYTE_RATE);
        wav.putShort((short) BLOCK_ALIGN);
        wav.putShort((short) BITS_PER_SAMPLE);
        wav.putInt(fourCc("data"));
        wav.putInt(pcm.length);
        wav.put(pcm);
        return wav.array();
    }

    private static int fourCc(String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static void readExact(FileChannel channel, ByteBuffer target, long offset)
            throws IOException {
        long position = offset;
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read <= 0) {
                throw new IOException("truncated WAV");
            }
            position = Math.addExact(position, read);
        }
    }

    public record Info(int dataOffset, int dataBytes, long samples, long durationMs) {
    }
}
