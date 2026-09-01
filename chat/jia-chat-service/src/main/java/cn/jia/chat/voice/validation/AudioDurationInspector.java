package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Bounded metadata-only WebM and MP4 duration inspector. */
public final class AudioDurationInspector {
    public static final long MAX_DURATION_MS = 45_000;
    private static final long MAX_METADATA_BYTES = 256L * 1024;
    private static final int MAX_ELEMENTS = 512;
    private static final int MAX_DEPTH = 8;

    public long inspect(Path path, String mediaType, String requestId) {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            Budget budget = new Budget();
            double durationMs = switch (mediaType) {
                case "audio/webm" -> inspectWebm(channel, budget);
                case "audio/mp4" -> inspectMp4(channel, budget);
                default -> throw VoiceException.of(VoiceErrorCode.UNSUPPORTED_MEDIA, requestId);
            };
            if (!Double.isFinite(durationMs) || durationMs <= 0) {
                throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
            }
            long rounded = (long) Math.ceil(durationMs);
            if (rounded > MAX_DURATION_MS) {
                throw VoiceException.of(VoiceErrorCode.TOO_LONG, requestId);
            }
            return rounded;
        } catch (VoiceException exception) {
            throw exception;
        } catch (IOException | ArithmeticException exception) {
            throw VoiceException.of(VoiceErrorCode.INVALID_AUDIO, requestId);
        }
    }

    private double inspectWebm(FileChannel channel, Budget budget) throws IOException {
        Element header = readElement(channel, 0, channel.size(), budget);
        if (header.id != 0x1A45DFA3L || header.unknownSize
                || !hasWebmDocType(channel, header.dataOffset, header.end(), budget)) {
            throw new IOException("missing WebM header");
        }
        long cursor = header.end();
        while (cursor < channel.size()) {
            Element element = readElement(channel, cursor, channel.size(), budget);
            if (element.id == 0x18538067L) {
                long segmentEnd = element.unknownSize ? channel.size() : element.end();
                return findWebmInfo(channel, element.dataOffset, segmentEnd, budget, 1);
            }
            cursor = element.end();
        }
        throw new IOException("missing segment");
    }

    private boolean hasWebmDocType(
            FileChannel channel, long start, long end, Budget budget) throws IOException {
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.unknownSize) {
                throw new IOException("unknown EBML header child size");
            }
            if (element.id == 0x4282L) {
                if (element.size < 1 || element.size > 16) {
                    throw new IOException("invalid doc type");
                }
                String docType = new String(read(channel, element.dataOffset,
                        (int) element.size, budget), java.nio.charset.StandardCharsets.US_ASCII);
                return "webm".equals(docType);
            }
            cursor = element.end();
        }
        return false;
    }

    private double findWebmInfo(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("EBML depth exceeded");
        }
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.id == 0x1549A966L) {
                if (element.unknownSize) {
                    throw new IOException("unknown info size");
                }
                return parseWebmInfo(channel, element.dataOffset, element.end(), budget);
            }
            cursor = element.end();
        }
        throw new IOException("missing info");
    }

    private double parseWebmInfo(FileChannel channel, long start, long end, Budget budget) throws IOException {
        long timecodeScale = 1_000_000L;
        Double duration = null;
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.unknownSize) {
                throw new IOException("unknown info child size");
            }
            if (element.id == 0x2AD7B1L) {
                if (element.size < 1 || element.size > 8) {
                    throw new IOException("invalid timecode scale");
                }
                timecodeScale = readUnsigned(channel, element.dataOffset, (int) element.size, budget);
                if (timecodeScale <= 0) {
                    throw new IOException("invalid timecode scale");
                }
            } else if (element.id == 0x4489L) {
                if (element.size == 4) {
                    duration = (double) Float.intBitsToFloat((int) readUnsigned(
                            channel, element.dataOffset, 4, budget));
                } else if (element.size == 8) {
                    duration = Double.longBitsToDouble(readUnsigned(
                            channel, element.dataOffset, 8, budget));
                } else {
                    throw new IOException("invalid duration size");
                }
            }
            cursor = element.end();
        }
        if (duration == null || !Double.isFinite(duration) || duration <= 0) {
            throw new IOException("missing duration");
        }
        double durationMs = duration * ((double) timecodeScale / 1_000_000D);
        if (!Double.isFinite(durationMs)) {
            throw new IOException("duration overflow");
        }
        return durationMs;
    }

    private double inspectMp4(FileChannel channel, Budget budget) throws IOException {
        long size = channel.size();
        long cursor = 0;
        boolean ftyp = false;
        while (cursor < size) {
            Box box = readBox(channel, cursor, size, budget);
            if (cursor == 0 && box.type != fourCc("ftyp")) {
                throw new IOException("missing ftyp");
            }
            if (box.type == fourCc("ftyp")) {
                ftyp = true;
            } else if (box.type == fourCc("moov")) {
                if (!ftyp) {
                    throw new IOException("moov before ftyp");
                }
                return inspectMoov(channel, box.dataOffset, box.end(), budget, 1);
            }
            cursor = box.end();
        }
        throw new IOException("missing moov");
    }

    private double inspectMoov(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mvhd")) {
                return parseMvhd(channel, box, budget);
            }
            cursor = box.end();
        }
        throw new IOException("missing mvhd");
    }

    private double parseMvhd(FileChannel channel, Box box, Budget budget) throws IOException {
        byte[] versionAndFlags = read(channel, box.dataOffset, 4, budget);
        int version = versionAndFlags[0] & 0xff;
        long cursor = box.dataOffset + 4;
        long timescale;
        long duration;
        if (version == 0) {
            read(channel, cursor, 8, budget);
            cursor += 8;
            timescale = readUnsigned(channel, cursor, 4, budget);
            duration = readUnsigned(channel, cursor + 4, 4, budget);
        } else if (version == 1) {
            read(channel, cursor, 16, budget);
            cursor += 16;
            timescale = readUnsigned(channel, cursor, 4, budget);
            duration = readUnsigned(channel, cursor + 4, 8, budget);
            if (duration < 0) {
                throw new IOException("unsigned duration overflow");
            }
        } else {
            throw new IOException("unsupported mvhd version");
        }
        if (timescale <= 0 || duration <= 0) {
            throw new IOException("invalid mvhd duration");
        }
        double durationMs = ((double) duration * 1_000D) / timescale;
        if (!Double.isFinite(durationMs)) {
            throw new IOException("duration overflow");
        }
        return durationMs;
    }

    private Element readElement(FileChannel channel, long offset, long limit, Budget budget) throws IOException {
        budget.element();
        Vint id = readVint(channel, offset, true, budget);
        Vint size = readVint(channel, Math.addExact(offset, id.width), false, budget);
        long dataOffset = Math.addExact(offset, Math.addExact(id.width, size.width));
        long end = size.unknown ? limit : Math.addExact(dataOffset, size.value);
        if (dataOffset > limit || end > limit || end <= offset) {
            throw new IOException("invalid EBML bounds");
        }
        return new Element(id.value, size.value, dataOffset, end, size.unknown);
    }

    private Vint readVint(FileChannel channel, long offset, boolean id, Budget budget) throws IOException {
        int first = read(channel, offset, 1, budget)[0] & 0xff;
        if (first == 0) {
            throw new IOException("invalid vint");
        }
        int width = Integer.numberOfLeadingZeros(first) - 24 + 1;
        if (width < 1 || width > 8 || id && width > 4) {
            throw new IOException("invalid vint width");
        }
        byte[] bytes = read(channel, offset, width, budget);
        long value = id ? bytes[0] & 0xffL : bytes[0] & (0xffL >>> width);
        for (int index = 1; index < width; index++) {
            value = (value << 8) | (bytes[index] & 0xffL);
        }
        if (id) {
            long marker = 1L << (7 * width);
            value |= marker;
        }
        long unknownValue = (1L << (7 * width)) - 1;
        return new Vint(value, width, !id && value == unknownValue);
    }

    private Box readBox(FileChannel channel, long offset, long limit, Budget budget) throws IOException {
        budget.element();
        byte[] header = read(channel, offset, 8, budget);
        long size32 = Integer.toUnsignedLong(ByteBuffer.wrap(header, 0, 4).getInt());
        int type = ByteBuffer.wrap(header, 4, 4).getInt();
        long headerSize = 8;
        long size;
        if (size32 == 1) {
            size = readUnsigned(channel, offset + 8, 8, budget);
            if (size < 0) {
                throw new IOException("unsigned box overflow");
            }
            headerSize = 16;
        } else if (size32 == 0) {
            size = limit - offset;
        } else {
            size = size32;
        }
        if (size < headerSize) {
            throw new IOException("invalid box size");
        }
        long end = Math.addExact(offset, size);
        if (end > limit || end <= offset) {
            throw new IOException("invalid box bounds");
        }
        return new Box(type, offset + headerSize, end);
    }

    private long readUnsigned(FileChannel channel, long offset, int length, Budget budget) throws IOException {
        byte[] bytes = read(channel, offset, length, budget);
        if (length == 8 && (bytes[0] & 0x80) != 0) {
            return -1;
        }
        long value = 0;
        for (byte item : bytes) {
            value = (value << 8) | (item & 0xffL);
        }
        return value;
    }

    private byte[] read(FileChannel channel, long offset, int length, Budget budget) throws IOException {
        budget.bytes(length);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + total);
            if (read < 0) {
                throw new EOFException();
            }
            if (read == 0) {
                throw new EOFException();
            }
            total += read;
        }
        return buffer.array();
    }

    private static int fourCc(String value) {
        return ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).getInt();
    }

    private record Vint(long value, int width, boolean unknown) {
    }

    private record Element(long id, long size, long dataOffset, long end, boolean unknownSize) {
    }

    private record Box(int type, long dataOffset, long end) {
    }

    private static final class Budget {
        private long bytes;
        private int elements;

        void bytes(int count) throws IOException {
            bytes = Math.addExact(bytes, count);
            if (bytes > MAX_METADATA_BYTES) {
                throw new IOException("metadata budget exceeded");
            }
        }

        void element() throws IOException {
            elements++;
            if (elements > MAX_ELEMENTS) {
                throw new IOException("element budget exceeded");
            }
        }
    }
}
