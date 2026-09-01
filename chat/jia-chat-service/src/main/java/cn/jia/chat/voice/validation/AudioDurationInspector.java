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
        Box moov = null;
        List<MediaExtent> mediaExtents = new ArrayList<>();
        while (cursor < size) {
            Box box = readBox(channel, cursor, size, budget);
            if (cursor == 0 && box.type != fourCc("ftyp")) {
                throw new IOException("missing ftyp");
            }
            if (box.type == fourCc("ftyp")) {
                if (ftyp) {
                    throw new IOException("duplicate ftyp");
                }
                parseFtyp(channel, box, budget);
                ftyp = true;
            } else if (box.type == fourCc("moov")) {
                if (!ftyp || moov != null) {
                    throw new IOException("invalid moov");
                }
                moov = box;
            } else if (box.type == fourCc("mdat")) {
                if (box.dataOffset >= box.end()) {
                    throw new IOException("empty media data");
                }
                mediaExtents.add(new MediaExtent(box.dataOffset, box.end()));
            }
            cursor = box.end();
        }
        if (moov == null || mediaExtents.isEmpty()) {
            throw new IOException("missing moov or media data");
        }
        Mp4Movie movie = inspectMoov(
                channel, moov.dataOffset, moov.end(), budget, 1, List.copyOf(mediaExtents));
        return Math.max(movie.movieDurationMs, movie.audioDurationMs);
    }

    private void parseFtyp(FileChannel channel, Box box, Budget budget) throws IOException {
        long length = box.end() - box.dataOffset;
        if (length < 8 || length > 128 || (length & 3) != 0) {
            throw new IOException("invalid ftyp");
        }
        byte[] value = read(channel, box.dataOffset, Math.toIntExact(length), budget);
        Set<Integer> compatible = Set.of(
                fourCc("isom"), fourCc("iso2"), fourCc("mp41"), fourCc("mp42"),
                fourCc("M4A "), fourCc("M4B "));
        int major = ByteBuffer.wrap(value, 0, 4).getInt();
        boolean supported = compatible.contains(major);
        for (int offset = 8; offset < value.length; offset += 4) {
            supported |= compatible.contains(ByteBuffer.wrap(value, offset, 4).getInt());
        }
        if (!supported) {
            throw new IOException("unsupported MP4 brand");
        }
    }

    private Mp4Movie inspectMoov(
            FileChannel channel, long start, long end, Budget budget, int depth,
            List<MediaExtent> mediaExtents) throws IOException {
        requireDepth(depth);
        Double movieDurationMs = null;
        Double audioDurationMs = null;
        int tracks = 0;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mvhd")) {
                if (movieDurationMs != null) {
                    throw new IOException("duplicate mvhd");
                }
                movieDurationMs = parseMvhd(channel, box, budget);
            } else if (box.type == fourCc("trak")) {
                tracks++;
                MdiaInfo track = inspectMp4Track(
                        channel, box.dataOffset, box.end(), budget, depth + 1, mediaExtents);
                if (track.handler != fourCc("soun") || track.durationMs == null) {
                    throw new IOException("non-audio or invalid MP4 track");
                }
                if (audioDurationMs != null) {
                    throw new IOException("multiple audio tracks");
                }
                audioDurationMs = track.durationMs;
            }
            cursor = box.end();
        }
        if (movieDurationMs == null || audioDurationMs == null || tracks != 1) {
            throw new IOException("missing AAC audio track or duration");
        }
        return new Mp4Movie(movieDurationMs, audioDurationMs);
    }

    private MdiaInfo inspectMp4Track(
            FileChannel channel, long start, long end, Budget budget, int depth,
            List<MediaExtent> mediaExtents) throws IOException {
        requireDepth(depth);
        MdiaInfo mdia = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mdia")) {
                if (mdia != null) {
                    throw new IOException("duplicate mdia");
                }
                mdia = inspectMdia(channel, box.dataOffset, box.end(), budget,
                        depth + 1, mediaExtents);
            }
            cursor = box.end();
        }
        if (mdia == null) {
            throw new IOException("missing media handler");
        }
        return mdia;
    }

    private MdiaInfo inspectMdia(
            FileChannel channel, long start, long end, Budget budget, int depth,
            List<MediaExtent> mediaExtents) throws IOException {
        requireDepth(depth);
        Integer handler = null;
        MediaHeader mediaHeader = null;
        SampleTable sampleTable = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mdhd")) {
                if (mediaHeader != null) {
                    throw new IOException("duplicate media header");
                }
                mediaHeader = parseMdhd(channel, box, budget);
            } else if (box.type == fourCc("hdlr")) {
                if (handler != null) {
                    throw new IOException("duplicate handler");
                }
                handler = parseHandler(channel, box, budget);
            } else if (box.type == fourCc("minf")) {
                if (sampleTable != null) {
                    throw new IOException("duplicate media info");
                }
                sampleTable = inspectMinf(
                        channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        if (handler == null) {
            throw new IOException("missing media handler");
        }
        if (handler != fourCc("soun")) {
            return new MdiaInfo(handler, null);
        }
        if (mediaHeader == null || sampleTable == null) {
            throw new IOException("incomplete audio media metadata");
        }
        return new MdiaInfo(handler,
                validateAudioSampleTable(mediaHeader, sampleTable, mediaExtents));
    }

    private SampleTable inspectMinf(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        requireDepth(depth);
        SampleTable table = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("stbl")) {
                if (table != null) {
                    throw new IOException("duplicate sample table");
                }
                table = inspectStbl(channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        if (table == null) {
            throw new IOException("missing sample table");
        }
        return table;
    }

    private SampleTable inspectStbl(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        requireDepth(depth);
        Mp4aInfo format = null;
        TimeToSample timing = null;
        List<SampleToChunk> sampleToChunks = null;
        SampleSizes sampleSizes = null;
        long[] chunkOffsets = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("stsd")) {
                if (format != null) {
                    throw new IOException("duplicate sample description");
                }
                format = parseStsd(channel, box, budget);
            } else if (box.type == fourCc("stts")) {
                if (timing != null) {
                    throw new IOException("duplicate time-to-sample table");
                }
                timing = parseStts(channel, box, budget);
            } else if (box.type == fourCc("stsc")) {
                if (sampleToChunks != null) {
                    throw new IOException("duplicate sample-to-chunk table");
                }
                sampleToChunks = parseStsc(channel, box, budget);
            } else if (box.type == fourCc("stsz")) {
                if (sampleSizes != null) {
                    throw new IOException("duplicate sample size table");
                }
                sampleSizes = parseStsz(channel, box, budget);
            } else if (box.type == fourCc("stco") || box.type == fourCc("co64")) {
                if (chunkOffsets != null) {
                    throw new IOException("duplicate chunk offset table");
                }
                chunkOffsets = parseChunkOffsets(
                        channel, box, budget, box.type == fourCc("co64") ? 8 : 4);
            }
            cursor = box.end();
        }
        if (format == null || timing == null || sampleToChunks == null
                || sampleSizes == null || chunkOffsets == null) {
            throw new IOException("incomplete AAC sample table");
        }
        return new SampleTable(format, timing, sampleToChunks, sampleSizes, chunkOffsets);
    }

    private Mp4aInfo parseStsd(FileChannel channel, Box box, Budget budget) throws IOException {
        if (box.end() - box.dataOffset < 8) {
            throw new IOException("invalid stsd");
        }
        requireFullBoxZero(channel, box.dataOffset, budget);
        long entryCount = readUnsigned(channel, box.dataOffset + 4, 4, budget);
        if (entryCount != 1) {
            throw new IOException("invalid audio sample entry count");
        }
        long cursor = box.dataOffset + 8;
        Box entry = readBox(channel, cursor, box.end(), budget);
        if (entry.type != fourCc("mp4a") || entry.end() != box.end()) {
            throw new IOException("unsupported audio sample entry");
        }
        return parseMp4a(channel, entry, budget);
    }

    private Mp4aInfo parseMp4a(FileChannel channel, Box entry, Budget budget) throws IOException {
        if (entry.end() - entry.dataOffset < 28) {
            throw new IOException("short mp4a sample entry");
        }
        byte[] fixed = read(channel, entry.dataOffset, 28, budget);
        for (int index = 0; index < 6; index++) {
            if (fixed[index] != 0) {
                throw new IOException("invalid mp4a reserved bytes");
            }
        }
        ByteBuffer value = ByteBuffer.wrap(fixed).order(ByteOrder.BIG_ENDIAN);
        int dataReference = Short.toUnsignedInt(value.getShort(6));
        int version = Short.toUnsignedInt(value.getShort(8));
        int channels = Short.toUnsignedInt(value.getShort(16));
        int sampleSize = Short.toUnsignedInt(value.getShort(18));
        int compressionId = Short.toUnsignedInt(value.getShort(20));
        int packetSize = Short.toUnsignedInt(value.getShort(22));
        long fixedSampleRate = Integer.toUnsignedLong(value.getInt(24));
        int sampleRate = (int) (fixedSampleRate >>> 16);
        if (dataReference == 0 || version != 0 || channels < 1 || channels > 2
                || sampleSize != 16 || compressionId != 0 || packetSize != 0
                || (fixedSampleRate & 0xffffL) != 0
                || sampleRate < 8_000 || sampleRate > 192_000) {
            throw new IOException("invalid mp4a audio parameters");
        }
        boolean esds = false;
        long cursor = entry.dataOffset + 28;
        while (cursor < entry.end()) {
            Box child = readBox(channel, cursor, entry.end(), budget);
            if (child.type == fourCc("esds")) {
                if (esds) {
                    throw new IOException("duplicate esds");
                }
                parseEsds(channel, child, budget, sampleRate, channels);
                esds = true;
            }
            cursor = child.end();
        }
        if (!esds) {
            throw new IOException("missing AAC esds");
        }
        return new Mp4aInfo(channels, sampleRate);
    }

    private void parseEsds(
            FileChannel channel, Box box, Budget budget, int sampleRate, int channels)
            throws IOException {
        long length = box.end() - box.dataOffset;
        if (length < 9 || length > 4_096) {
            throw new IOException("invalid esds size");
        }
        byte[] bytes = read(channel, box.dataOffset, Math.toIntExact(length), budget);
        if (bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 0 || bytes[3] != 0) {
            throw new IOException("unsupported esds full box");
        }
        Descriptor es = readDescriptor(bytes, 4, bytes.length);
        if (es.tag != 0x03 || es.nextOffset != bytes.length || es.payloadEnd - es.payloadStart < 3) {
            throw new IOException("missing ES descriptor");
        }
        int cursor = es.payloadStart + 2;
        int flags = bytes[cursor++] & 0xff;
        if ((flags & 0x80) != 0) {
            cursor = checkedAdvance(cursor, 2, es.payloadEnd);
        }
        if ((flags & 0x40) != 0) {
            cursor = checkedAdvance(cursor, 1, es.payloadEnd);
            int urlLength = bytes[cursor - 1] & 0xff;
            cursor = checkedAdvance(cursor, urlLength, es.payloadEnd);
        }
        if ((flags & 0x20) != 0) {
            cursor = checkedAdvance(cursor, 2, es.payloadEnd);
        }
        Descriptor decoder = null;
        while (cursor < es.payloadEnd) {
            Descriptor child = readDescriptor(bytes, cursor, es.payloadEnd);
            if (child.tag == 0x04) {
                if (decoder != null) {
                    throw new IOException("duplicate decoder config");
                }
                decoder = child;
            }
            cursor = child.nextOffset;
        }
        if (decoder == null || decoder.payloadEnd - decoder.payloadStart < 13) {
            throw new IOException("missing AAC decoder config");
        }
        int decoderCursor = decoder.payloadStart;
        int objectType = bytes[decoderCursor++] & 0xff;
        int streamType = bytes[decoderCursor++] & 0xff;
        if (objectType != 0x40 || ((streamType >>> 2) & 0x3f) != 5
                || (streamType & 0x03) != 1) {
            throw new IOException("esds is not MPEG-4 audio");
        }
        decoderCursor = checkedAdvance(decoderCursor, 11, decoder.payloadEnd);
        Descriptor specific = null;
        while (decoderCursor < decoder.payloadEnd) {
            Descriptor child = readDescriptor(bytes, decoderCursor, decoder.payloadEnd);
            if (child.tag == 0x05) {
                if (specific != null) {
                    throw new IOException("duplicate AudioSpecificConfig");
                }
                specific = child;
            }
            decoderCursor = child.nextOffset;
        }
        if (specific == null || specific.payloadEnd - specific.payloadStart > 64) {
            throw new IOException("missing AudioSpecificConfig");
        }
        parseAudioSpecificConfig(
                bytes, specific.payloadStart, specific.payloadEnd, sampleRate, channels);
    }

    private Descriptor readDescriptor(byte[] bytes, int offset, int limit) throws IOException {
        if (offset < 0 || offset >= limit) {
            throw new IOException("missing descriptor");
        }
        int tag = bytes[offset++] & 0xff;
        int length = 0;
        boolean complete = false;
        for (int index = 0; index < 4; index++) {
            if (offset >= limit) {
                throw new IOException("truncated descriptor length");
            }
            int item = bytes[offset++] & 0xff;
            length = Math.addExact(Math.multiplyExact(length, 128), item & 0x7f);
            if ((item & 0x80) == 0) {
                complete = true;
                break;
            }
        }
        if (!complete) {
            throw new IOException("descriptor length exceeds four bytes");
        }
        int end = Math.addExact(offset, length);
        if (end > limit) {
            throw new IOException("descriptor exceeds parent");
        }
        return new Descriptor(tag, offset, end, end);
    }

    private int checkedAdvance(int cursor, int count, int limit) throws IOException {
        int next = Math.addExact(cursor, count);
        if (count < 0 || next > limit) {
            throw new IOException("descriptor field exceeds parent");
        }
        return next;
    }

    private void parseAudioSpecificConfig(
            byte[] bytes, int start, int end, int sampleRate, int channels) throws IOException {
        BitReader bits = new BitReader(bytes, start, end);
        int audioObjectType = bits.read(5);
        if (audioObjectType == 31) {
            audioObjectType = 32 + bits.read(6);
        }
        int frequencyIndex = bits.read(4);
        int declaredSampleRate;
        if (frequencyIndex == 15) {
            declaredSampleRate = bits.read(24);
        } else {
            int[] frequencies = {
                    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
                    22_050, 16_000, 12_000, 11_025, 8_000, 7_350
            };
            if (frequencyIndex >= frequencies.length) {
                throw new IOException("reserved AAC frequency index");
            }
            declaredSampleRate = frequencies[frequencyIndex];
        }
        int channelConfiguration = bits.read(4);
        int frameLengthFlag = bits.read(1);
        int dependsOnCoreCoder = bits.read(1);
        int extensionFlag = bits.read(1);
        if (audioObjectType != 2 || declaredSampleRate != sampleRate
                || channelConfiguration != channels || channelConfiguration < 1
                || channelConfiguration > 2 || frameLengthFlag != 0
                || dependsOnCoreCoder != 0 || extensionFlag != 0) {
            throw new IOException("unsupported AAC AudioSpecificConfig");
        }
    }

    private MediaHeader parseMdhd(FileChannel channel, Box box, Budget budget) throws IOException {
        byte[] versionAndFlags = read(channel, box.dataOffset, 4, budget);
        int version = versionAndFlags[0] & 0xff;
        if (versionAndFlags[1] != 0 || versionAndFlags[2] != 0 || versionAndFlags[3] != 0) {
            throw new IOException("invalid mdhd flags");
        }
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
        } else {
            throw new IOException("unsupported mdhd version");
        }
        if (timescale < 8_000 || timescale > 192_000 || duration <= 0) {
            throw new IOException("invalid media duration");
        }
        return new MediaHeader(timescale, duration);
    }

    private TimeToSample parseStts(FileChannel channel, Box box, Budget budget) throws IOException {
        requireFullBoxZero(channel, box.dataOffset, budget);
        int entries = boundedTableCount(
                readUnsigned(channel, box.dataOffset + 4, 4, budget));
        long cursor = box.dataOffset + 8;
        long sampleCount = 0;
        long durationTicks = 0;
        for (int index = 0; index < entries; index++) {
            long count = readUnsigned(channel, cursor, 4, budget);
            long delta = readUnsigned(channel, cursor + 4, 4, budget);
            cursor += 8;
            if (count <= 0 || delta <= 0) {
                throw new IOException("invalid time-to-sample entry");
            }
            sampleCount = Math.addExact(sampleCount, count);
            durationTicks = Math.addExact(durationTicks, Math.multiplyExact(count, delta));
        }
        if (cursor != box.end() || sampleCount <= 0 || sampleCount > 100_000) {
            throw new IOException("invalid time-to-sample table");
        }
        return new TimeToSample(sampleCount, durationTicks);
    }

    private List<SampleToChunk> parseStsc(
            FileChannel channel, Box box, Budget budget) throws IOException {
        requireFullBoxZero(channel, box.dataOffset, budget);
        int entries = boundedTableCount(
                readUnsigned(channel, box.dataOffset + 4, 4, budget));
        List<SampleToChunk> values = new ArrayList<>(entries);
        long cursor = box.dataOffset + 8;
        long previous = 0;
        for (int index = 0; index < entries; index++) {
            long firstChunk = readUnsigned(channel, cursor, 4, budget);
            long samplesPerChunk = readUnsigned(channel, cursor + 4, 4, budget);
            long sampleDescription = readUnsigned(channel, cursor + 8, 4, budget);
            cursor += 12;
            if (firstChunk <= previous || index == 0 && firstChunk != 1
                    || samplesPerChunk <= 0 || samplesPerChunk > 100_000
                    || sampleDescription != 1) {
                throw new IOException("invalid sample-to-chunk entry");
            }
            values.add(new SampleToChunk(firstChunk, samplesPerChunk));
            previous = firstChunk;
        }
        if (cursor != box.end()) {
            throw new IOException("invalid sample-to-chunk table");
        }
        return List.copyOf(values);
    }

    private SampleSizes parseStsz(FileChannel channel, Box box, Budget budget) throws IOException {
        requireFullBoxZero(channel, box.dataOffset, budget);
        long defaultSize = readUnsigned(channel, box.dataOffset + 4, 4, budget);
        int count = boundedSampleCount(
                readUnsigned(channel, box.dataOffset + 8, 4, budget));
        long cursor = box.dataOffset + 12;
        if (defaultSize > Integer.MAX_VALUE) {
            throw new IOException("sample size overflow");
        }
        int[] sizes = defaultSize == 0 ? new int[count] : null;
        if (sizes != null) {
            for (int index = 0; index < count; index++) {
                long size = readUnsigned(channel, cursor, 4, budget);
                cursor += 4;
                if (size <= 0 || size > Integer.MAX_VALUE) {
                    throw new IOException("invalid AAC sample size");
                }
                sizes[index] = (int) size;
            }
        } else if (defaultSize <= 0) {
            throw new IOException("invalid default sample size");
        }
        if (cursor != box.end()) {
            throw new IOException("invalid sample size table");
        }
        return new SampleSizes((int) defaultSize, sizes, count);
    }

    private long[] parseChunkOffsets(
            FileChannel channel, Box box, Budget budget, int width) throws IOException {
        requireFullBoxZero(channel, box.dataOffset, budget);
        int count = boundedTableCount(
                readUnsigned(channel, box.dataOffset + 4, 4, budget));
        long[] offsets = new long[count];
        long cursor = box.dataOffset + 8;
        long previous = -1;
        for (int index = 0; index < count; index++) {
            long offset = readUnsigned(channel, cursor, width, budget);
            cursor += width;
            if (offset <= previous) {
                throw new IOException("non-monotonic chunk offsets");
            }
            offsets[index] = offset;
            previous = offset;
        }
        if (cursor != box.end()) {
            throw new IOException("invalid chunk offset table");
        }
        return offsets;
    }

    private double validateAudioSampleTable(
            MediaHeader header, SampleTable table, List<MediaExtent> mediaExtents)
            throws IOException {
        if (header.timescale != table.format.sampleRate
                || header.duration != table.timing.durationTicks
                || table.timing.sampleCount != table.sampleSizes.count) {
            throw new IOException("inconsistent AAC timing metadata");
        }
        if (table.sampleToChunks.get(table.sampleToChunks.size() - 1).firstChunk
                > table.chunkOffsets.length) {
            throw new IOException("sample-to-chunk exceeds chunk table");
        }
        int sampleIndex = 0;
        int mappingIndex = 0;
        long previousChunkEnd = -1;
        long totalBytes = 0;
        for (int chunkIndex = 1; chunkIndex <= table.chunkOffsets.length; chunkIndex++) {
            while (mappingIndex + 1 < table.sampleToChunks.size()
                    && table.sampleToChunks.get(mappingIndex + 1).firstChunk <= chunkIndex) {
                mappingIndex++;
            }
            SampleToChunk mapping = table.sampleToChunks.get(mappingIndex);
            int nextSample = Math.toIntExact(Math.addExact(
                    sampleIndex, mapping.samplesPerChunk));
            if (nextSample > table.sampleSizes.count) {
                throw new IOException("chunk references missing samples");
            }
            long chunkBytes = 0;
            for (int index = sampleIndex; index < nextSample; index++) {
                chunkBytes = Math.addExact(chunkBytes, table.sampleSizes.sizeAt(index));
            }
            long chunkStart = table.chunkOffsets[chunkIndex - 1];
            long chunkEnd = Math.addExact(chunkStart, chunkBytes);
            if (chunkBytes <= 0 || chunkStart < previousChunkEnd
                    || mediaExtents.stream().noneMatch(extent -> extent.contains(chunkStart, chunkEnd))) {
                throw new IOException("AAC sample extent is outside media data");
            }
            previousChunkEnd = chunkEnd;
            totalBytes = Math.addExact(totalBytes, chunkBytes);
            sampleIndex = nextSample;
        }
        if (sampleIndex != table.sampleSizes.count || totalBytes <= 0) {
            throw new IOException("empty or incomplete AAC media");
        }
        double durationMs = ((double) table.timing.durationTicks * 1_000D) / header.timescale;
        if (!Double.isFinite(durationMs) || durationMs <= 0) {
            throw new IOException("AAC duration overflow");
        }
        return durationMs;
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
        if (versionAndFlags[1] != 0 || versionAndFlags[2] != 0 || versionAndFlags[3] != 0) {
            throw new IOException("invalid mvhd flags");
        }
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

    private void requireFullBoxZero(
            FileChannel channel, long offset, Budget budget) throws IOException {
        byte[] fullBox = read(channel, offset, 4, budget);
        if (fullBox[0] != 0 || fullBox[1] != 0 || fullBox[2] != 0 || fullBox[3] != 0) {
            throw new IOException("unsupported full box version or flags");
        }
    }

    private int boundedTableCount(long count) throws IOException {
        if (count < 1 || count > 100_000) {
            throw new IOException("invalid MP4 table count");
        }
        return (int) count;
    }

    private int boundedSampleCount(long count) throws IOException {
        if (count < 1 || count > 100_000) {
            throw new IOException("invalid MP4 sample count");
        }
        return (int) count;
    }

    private void requireDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("MP4 depth exceeded");
        }
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

    private record Mp4Movie(double movieDurationMs, double audioDurationMs) {
    }

    private record MdiaInfo(int handler, Double durationMs) {
    }

    private record MediaHeader(long timescale, long duration) {
    }

    private record Mp4aInfo(int channels, int sampleRate) {
    }

    private record TimeToSample(long sampleCount, long durationTicks) {
    }

    private record SampleToChunk(long firstChunk, long samplesPerChunk) {
    }

    private record SampleTable(
            Mp4aInfo format, TimeToSample timing, List<SampleToChunk> sampleToChunks,
            SampleSizes sampleSizes, long[] chunkOffsets) {
    }

    private record SampleSizes(int defaultSize, int[] sizes, int count) {
        long sizeAt(int index) {
            return sizes == null ? defaultSize : sizes[index];
        }
    }

    private record MediaExtent(long start, long end) {
        boolean contains(long candidateStart, long candidateEnd) {
            return candidateStart >= start && candidateEnd <= end && candidateEnd > candidateStart;
        }
    }

    private record Descriptor(int tag, int payloadStart, int payloadEnd, int nextOffset) {
    }

    private static final class BitReader {
        private final byte[] bytes;
        private final int endBit;
        private int bit;

        private BitReader(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.bit = Math.multiplyExact(start, 8);
            this.endBit = Math.multiplyExact(end, 8);
        }

        int read(int count) throws IOException {
            if (count < 1 || count > 24 || bit + count > endBit) {
                throw new IOException("truncated AudioSpecificConfig");
            }
            int value = 0;
            for (int index = 0; index < count; index++) {
                value = (value << 1) | ((bytes[bit >>> 3] >>> (7 - (bit & 7))) & 1);
                bit++;
            }
            return value;
        }
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
