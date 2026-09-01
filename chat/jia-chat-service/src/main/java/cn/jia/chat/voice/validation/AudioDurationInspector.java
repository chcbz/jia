package cn.jia.chat.voice.validation;

import cn.jia.chat.voice.api.VoiceErrorCode;
import cn.jia.chat.voice.api.VoiceException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded metadata-only WebM/Opus and MP4/AAC duration and codec inspector. */
public final class AudioDurationInspector {
    public static final long MAX_DURATION_MS = 45_000;
    private static final long MAX_METADATA_BYTES = 256L * 1024;
    private static final int MAX_ELEMENTS = 4_096;
    private static final int MAX_DEPTH = 8;
    private static final double MAX_OPUS_PACKET_DURATION_MS = 120D;

    private static final long EBML = 0x1A45DFA3L;
    private static final long SEGMENT = 0x18538067L;
    private static final long INFO = 0x1549A966L;
    private static final long TIMECODE_SCALE = 0x2AD7B1L;
    private static final long DURATION = 0x4489L;
    private static final long TRACKS = 0x1654AE6BL;
    private static final long TRACK_ENTRY = 0xAEL;
    private static final long TRACK_NUMBER = 0xD7L;
    private static final long TRACK_TYPE = 0x83L;
    private static final long CODEC_ID = 0x86L;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long CLUSTER_TIMECODE = 0xE7L;
    private static final long SIMPLE_BLOCK = 0xA3L;
    private static final long BLOCK_GROUP = 0xA0L;
    private static final long BLOCK = 0xA1L;

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
        if (header.id != EBML || header.unknownSize
                || !hasWebmDocType(channel, header.dataOffset, header.end(), budget)) {
            throw new IOException("missing WebM header");
        }
        long cursor = header.end();
        while (cursor < channel.size()) {
            Element element = readElement(channel, cursor, channel.size(), budget);
            if (element.id == SEGMENT) {
                long segmentEnd = element.unknownSize ? channel.size() : element.end();
                return inspectWebmSegment(channel, element.dataOffset, segmentEnd, budget);
            }
            if (element.unknownSize) {
                throw new IOException("unknown top-level element size");
            }
            cursor = element.end();
        }
        throw new IOException("missing segment");
    }

    private double inspectWebmSegment(
            FileChannel channel, long start, long end, Budget budget) throws IOException {
        WebmInfo info = null;
        Set<Long> audioTracks = null;
        List<Element> clusters = new ArrayList<>();
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.id == INFO) {
                if (info != null || element.unknownSize) {
                    throw new IOException("invalid info");
                }
                info = parseWebmInfo(channel, element.dataOffset, element.end(), budget);
            } else if (element.id == TRACKS) {
                if (audioTracks != null || element.unknownSize) {
                    throw new IOException("invalid tracks");
                }
                audioTracks = parseWebmTracks(channel, element.dataOffset, element.end(), budget);
            } else if (element.id == CLUSTER) {
                clusters.add(element);
            } else if (element.unknownSize) {
                throw new IOException("unknown segment child size");
            }
            cursor = element.end();
        }
        if (info == null || audioTracks == null || audioTracks.isEmpty() || clusters.isEmpty()) {
            throw new IOException("incomplete WebM metadata");
        }
        TimestampBounds timestamps = new TimestampBounds();
        for (Element cluster : clusters) {
            parseWebmCluster(channel, cluster.dataOffset, cluster.end(), budget, audioTracks, timestamps);
        }
        if (timestamps.count == 0) {
            throw new IOException("missing audio blocks");
        }
        double observedEndMs = timestamps.max * ((double) info.timecodeScale / 1_000_000D)
                + MAX_OPUS_PACKET_DURATION_MS;
        if (!Double.isFinite(observedEndMs) || observedEndMs <= 0) {
            throw new IOException("invalid block timestamps");
        }
        if (info.durationMs != null) {
            return Math.max(info.durationMs, observedEndMs);
        }
        if (!timestamps.advanced) {
            throw new IOException("insufficient timestamps for derived duration");
        }
        return observedEndMs;
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
                        (int) element.size, budget), StandardCharsets.US_ASCII);
                return "webm".equals(docType);
            }
            cursor = element.end();
        }
        return false;
    }

    private WebmInfo parseWebmInfo(
            FileChannel channel, long start, long end, Budget budget) throws IOException {
        long timecodeScale = 1_000_000L;
        Double durationUnits = null;
        boolean scaleSeen = false;
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.unknownSize) {
                throw new IOException("unknown info child size");
            }
            if (element.id == TIMECODE_SCALE) {
                if (scaleSeen || element.size < 1 || element.size > 8) {
                    throw new IOException("invalid timecode scale");
                }
                scaleSeen = true;
                timecodeScale = readUnsigned(channel, element.dataOffset, (int) element.size, budget);
                if (timecodeScale <= 0) {
                    throw new IOException("invalid timecode scale");
                }
            } else if (element.id == DURATION) {
                if (durationUnits != null) {
                    throw new IOException("duplicate duration");
                }
                if (element.size == 4) {
                    durationUnits = (double) Float.intBitsToFloat((int) readUnsigned(
                            channel, element.dataOffset, 4, budget));
                } else if (element.size == 8) {
                    durationUnits = Double.longBitsToDouble(readUnsigned(
                            channel, element.dataOffset, 8, budget));
                } else {
                    throw new IOException("invalid duration size");
                }
                if (!Double.isFinite(durationUnits) || durationUnits <= 0) {
                    throw new IOException("invalid duration");
                }
            }
            cursor = element.end();
        }
        Double durationMs = durationUnits == null ? null
                : durationUnits * ((double) timecodeScale / 1_000_000D);
        if (durationMs != null && (!Double.isFinite(durationMs) || durationMs <= 0)) {
            throw new IOException("duration overflow");
        }
        return new WebmInfo(timecodeScale, durationMs);
    }

    private Set<Long> parseWebmTracks(
            FileChannel channel, long start, long end, Budget budget) throws IOException {
        Set<Long> audioTracks = new HashSet<>();
        boolean sawAudio = false;
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.id == TRACK_ENTRY) {
                if (element.unknownSize) {
                    throw new IOException("unknown track entry size");
                }
                WebmTrack track = parseWebmTrackEntry(
                        channel, element.dataOffset, element.end(), budget);
                if (track.type == 2) {
                    sawAudio = true;
                    if (!"A_OPUS".equals(track.codecId) || track.number <= 0
                            || !audioTracks.add(track.number)) {
                        throw new IOException("unsupported or duplicate audio track");
                    }
                }
            } else if (element.unknownSize) {
                throw new IOException("unknown tracks child size");
            }
            cursor = element.end();
        }
        if (!sawAudio || audioTracks.isEmpty()) {
            throw new IOException("missing Opus audio track");
        }
        return Set.copyOf(audioTracks);
    }

    private WebmTrack parseWebmTrackEntry(
            FileChannel channel, long start, long end, Budget budget) throws IOException {
        Long number = null;
        Long type = null;
        String codecId = null;
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.unknownSize) {
                throw new IOException("unknown track child size");
            }
            if (element.id == TRACK_NUMBER) {
                if (number != null || element.size < 1 || element.size > 8) {
                    throw new IOException("invalid track number");
                }
                number = readUnsigned(channel, element.dataOffset, (int) element.size, budget);
            } else if (element.id == TRACK_TYPE) {
                if (type != null || element.size < 1 || element.size > 8) {
                    throw new IOException("invalid track type");
                }
                type = readUnsigned(channel, element.dataOffset, (int) element.size, budget);
            } else if (element.id == CODEC_ID) {
                if (codecId != null || element.size < 1 || element.size > 64) {
                    throw new IOException("invalid codec id");
                }
                codecId = new String(read(channel, element.dataOffset,
                        (int) element.size, budget), StandardCharsets.US_ASCII);
            }
            cursor = element.end();
        }
        if (number == null || type == null || codecId == null) {
            throw new IOException("incomplete track entry");
        }
        return new WebmTrack(number, type, codecId);
    }

    private void parseWebmCluster(
            FileChannel channel, long start, long end, Budget budget,
            Set<Long> audioTracks, TimestampBounds timestamps) throws IOException {
        Long clusterTimecode = null;
        long cursor = start;
        while (cursor < end) {
            Element element = readElement(channel, cursor, end, budget);
            if (element.unknownSize) {
                throw new IOException("unknown cluster child size");
            }
            if (element.id == CLUSTER_TIMECODE) {
                if (clusterTimecode != null || element.size < 1 || element.size > 8) {
                    throw new IOException("invalid cluster timecode");
                }
                clusterTimecode = readUnsigned(
                        channel, element.dataOffset, (int) element.size, budget);
                if (clusterTimecode < 0) {
                    throw new IOException("cluster timecode overflow");
                }
            } else if (element.id == SIMPLE_BLOCK) {
                if (clusterTimecode == null) {
                    throw new IOException("block before cluster timecode");
                }
                parseWebmBlock(channel, element, clusterTimecode, budget, audioTracks, timestamps);
            } else if (element.id == BLOCK_GROUP) {
                if (clusterTimecode == null) {
                    throw new IOException("block group before cluster timecode");
                }
                parseWebmBlockGroup(channel, element, clusterTimecode,
                        budget, audioTracks, timestamps);
            }
            cursor = element.end();
        }
    }

    private void parseWebmBlockGroup(
            FileChannel channel, Element group, long clusterTimecode, Budget budget,
            Set<Long> audioTracks, TimestampBounds timestamps) throws IOException {
        long cursor = group.dataOffset;
        boolean blockSeen = false;
        while (cursor < group.end()) {
            Element child = readElement(channel, cursor, group.end(), budget);
            if (child.unknownSize) {
                throw new IOException("unknown block group child size");
            }
            if (child.id == BLOCK) {
                if (blockSeen) {
                    throw new IOException("duplicate block");
                }
                blockSeen = true;
                parseWebmBlock(channel, child, clusterTimecode, budget, audioTracks, timestamps);
            }
            cursor = child.end();
        }
        if (!blockSeen) {
            throw new IOException("missing block");
        }
    }

    private void parseWebmBlock(
            FileChannel channel, Element block, long clusterTimecode, Budget budget,
            Set<Long> audioTracks, TimestampBounds timestamps) throws IOException {
        Vint track = readVint(channel, block.dataOffset, false, budget);
        long headerBytes = Math.addExact(track.width, 3);
        if (track.unknown || track.value <= 0 || block.size < headerBytes) {
            throw new IOException("invalid block header");
        }
        byte[] timingAndFlags = read(channel, block.dataOffset + track.width, 3, budget);
        int relative = (short) (((timingAndFlags[0] & 0xff) << 8)
                | (timingAndFlags[1] & 0xff));
        if ((timingAndFlags[2] & 0x06) != 0) {
            throw new IOException("laced Opus block is not inspected");
        }
        if (audioTracks.contains(track.value)) {
            long absolute = Math.addExact(clusterTimecode, relative);
            if (absolute < 0) {
                throw new IOException("negative block timestamp");
            }
            timestamps.add(absolute);
        }
    }

    private double inspectMp4(FileChannel channel, Budget budget) throws IOException {
        long size = channel.size();
        long cursor = 0;
        boolean ftyp = false;
        Double durationMs = null;
        while (cursor < size) {
            Box box = readBox(channel, cursor, size, budget);
            if (cursor == 0 && box.type != fourCc("ftyp")) {
                throw new IOException("missing ftyp");
            }
            if (box.type == fourCc("ftyp")) {
                if (ftyp) {
                    throw new IOException("duplicate ftyp");
                }
                ftyp = true;
            } else if (box.type == fourCc("moov")) {
                if (!ftyp || durationMs != null) {
                    throw new IOException("invalid moov");
                }
                durationMs = inspectMoov(channel, box.dataOffset, box.end(), budget, 1);
            }
            cursor = box.end();
        }
        if (durationMs == null) {
            throw new IOException("missing moov");
        }
        return durationMs;
    }

    private double inspectMoov(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        Double durationMs = null;
        int audioTracks = 0;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mvhd")) {
                if (durationMs != null) {
                    throw new IOException("duplicate mvhd");
                }
                durationMs = parseMvhd(channel, box, budget);
            } else if (box.type == fourCc("trak")) {
                if (inspectMp4Track(channel, box.dataOffset, box.end(), budget, depth + 1)) {
                    audioTracks++;
                }
            }
            cursor = box.end();
        }
        if (durationMs == null || audioTracks < 1) {
            throw new IOException("missing AAC audio track or duration");
        }
        return durationMs;
    }

    private boolean inspectMp4Track(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        MdiaInfo mdia = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mdia")) {
                if (mdia != null) {
                    throw new IOException("duplicate mdia");
                }
                mdia = inspectMdia(channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        if (mdia == null || mdia.handler == null) {
            throw new IOException("missing media handler");
        }
        if (mdia.handler == fourCc("soun")) {
            if (!mdia.hasMp4a || mdia.hasNonMp4a) {
                throw new IOException("unsupported audio sample entry");
            }
            return true;
        }
        return false;
    }

    private MdiaInfo inspectMdia(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        Integer handler = null;
        SampleEntries entries = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("hdlr")) {
                if (handler != null) {
                    throw new IOException("duplicate handler");
                }
                handler = parseHandler(channel, box, budget);
            } else if (box.type == fourCc("minf")) {
                if (entries != null) {
                    throw new IOException("duplicate media info");
                }
                entries = inspectMinf(channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        return new MdiaInfo(handler,
                entries != null && entries.hasMp4a,
                entries != null && entries.hasNonMp4a);
    }

    private SampleEntries inspectMinf(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        SampleEntries entries = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("stbl")) {
                if (entries != null) {
                    throw new IOException("duplicate sample table");
                }
                entries = inspectStbl(channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        return entries == null ? new SampleEntries(false, false) : entries;
    }

    private SampleEntries inspectStbl(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
        SampleEntries entries = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("stsd")) {
                if (entries != null) {
                    throw new IOException("duplicate sample description");
                }
                entries = parseStsd(channel, box, budget);
            }
            cursor = box.end();
        }
        return entries == null ? new SampleEntries(false, false) : entries;
    }

    private SampleEntries parseStsd(FileChannel channel, Box box, Budget budget) throws IOException {
        if (box.end() - box.dataOffset < 8) {
            throw new IOException("invalid stsd");
        }
        read(channel, box.dataOffset, 4, budget);
        long entryCount = readUnsigned(channel, box.dataOffset + 4, 4, budget);
        if (entryCount < 1 || entryCount > 16) {
            throw new IOException("invalid sample entry count");
        }
        long cursor = box.dataOffset + 8;
        boolean mp4a = false;
        boolean nonMp4a = false;
        for (long index = 0; index < entryCount; index++) {
            Box entry = readBox(channel, cursor, box.end(), budget);
            if (entry.type == fourCc("mp4a")) {
                mp4a = true;
            } else {
                nonMp4a = true;
            }
            cursor = entry.end();
        }
        if (cursor != box.end()) {
            throw new IOException("trailing sample description data");
        }
        return new SampleEntries(mp4a, nonMp4a);
    }

    private int parseHandler(FileChannel channel, Box box, Budget budget) throws IOException {
        if (box.end() - box.dataOffset < 12) {
            throw new IOException("invalid handler");
        }
        byte[] value = read(channel, box.dataOffset, 12, budget);
        return ByteBuffer.wrap(value, 8, 4).getInt();
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

    private Element readElement(
            FileChannel channel, long offset, long limit, Budget budget) throws IOException {
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

    private Vint readVint(
            FileChannel channel, long offset, boolean id, Budget budget) throws IOException {
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
            value |= 1L << (7 * width);
        }
        long unknownValue = (1L << (7 * width)) - 1;
        return new Vint(value, width, !id && value == unknownValue);
    }

    private Box readBox(
            FileChannel channel, long offset, long limit, Budget budget) throws IOException {
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

    private long readUnsigned(
            FileChannel channel, long offset, int length, Budget budget) throws IOException {
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

    private byte[] read(
            FileChannel channel, long offset, int length, Budget budget) throws IOException {
        budget.bytes(length);
        ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset + total);
            if (read <= 0) {
                throw new EOFException();
            }
            total += read;
        }
        return buffer.array();
    }

    private static int fourCc(String value) {
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.US_ASCII)).getInt();
    }

    private record Vint(long value, int width, boolean unknown) {
    }

    private record Element(long id, long size, long dataOffset, long end, boolean unknownSize) {
    }

    private record Box(int type, long dataOffset, long end) {
    }

    private record WebmInfo(long timecodeScale, Double durationMs) {
    }

    private record WebmTrack(long number, long type, String codecId) {
    }

    private record MdiaInfo(Integer handler, boolean hasMp4a, boolean hasNonMp4a) {
    }

    private record SampleEntries(boolean hasMp4a, boolean hasNonMp4a) {
    }

    private static final class TimestampBounds {
        private long max = -1;
        private long last = -1;
        private int count;
        private boolean advanced;

        void add(long value) throws IOException {
            if (last > value) {
                throw new IOException("non-monotonic audio timestamp");
            }
            if (last >= 0 && value > last) {
                advanced = true;
            }
            last = value;
            max = Math.max(max, value);
            count++;
        }
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
