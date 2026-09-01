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
    // Unknown-sized live Clusters are walked once for boundaries and once for timing.
    private static final int MAX_ELEMENTS = 8_192;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_WEBM_CLUSTERS = 128;
    private static final int MAX_MP4_FRAGMENTS = 256;
    private static final int MAX_MP4_SAMPLES = 10_000;
    private static final int MAX_OPUS_PACKET_BYTES = 1_275;
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
    private static final long SEEK_HEAD = 0x114D9B74L;
    private static final long CUES = 0x1C53BB6BL;
    private static final long ATTACHMENTS = 0x1941A469L;
    private static final long CHAPTERS = 0x1043A770L;
    private static final long TAGS = 0x1254C367L;
    private static final long VOID = 0xECL;
    private static final long CRC32 = 0xBFL;
    private static final long SILENT_TRACKS = 0x5854L;
    private static final long POSITION = 0xA7L;
    private static final long PREV_SIZE = 0xABL;
    private static final long ENCRYPTED_BLOCK = 0xAFL;

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
                long clusterEnd = element.unknownSize
                        ? findUnknownClusterEnd(channel, element.dataOffset, end, budget)
                        : element.end();
                if (clusterEnd <= element.dataOffset || clusters.size() >= MAX_WEBM_CLUSTERS) {
                    throw new IOException("invalid or excessive clusters");
                }
                clusters.add(new Element(
                        element.id, clusterEnd - element.dataOffset,
                        element.dataOffset, clusterEnd, element.unknownSize));
                cursor = clusterEnd;
                continue;
            } else if (element.unknownSize) {
                throw new IOException("unknown segment child size");
            }
            cursor = element.end();
        }
        if (info == null || audioTracks == null || audioTracks.isEmpty() || clusters.isEmpty()) {
            throw new IOException("incomplete WebM metadata");
        }
        TimestampBounds timestamps = new TimestampBounds();
        double scaleMs = (double) info.timecodeScale / 1_000_000D;
        for (Element cluster : clusters) {
            parseWebmCluster(channel, cluster.dataOffset, cluster.end(), budget,
                    audioTracks, timestamps, scaleMs);
        }
        if (timestamps.count == 0 || !Double.isFinite(timestamps.maxEndMs)
                || timestamps.maxEndMs <= 0) {
            throw new IOException("missing or invalid audio blocks");
        }
        if (info.durationMs != null) {
            return Math.max(info.durationMs, timestamps.maxEndMs);
        }
        if (!timestamps.advanced) {
            throw new IOException("insufficient timestamps for derived duration");
        }
        return timestamps.maxEndMs;
    }

    private long findUnknownClusterEnd(
            FileChannel channel, long start, long segmentEnd, Budget budget) throws IOException {
        long cursor = start;
        boolean childSeen = false;
        while (cursor < segmentEnd) {
            Element child = readElement(channel, cursor, segmentEnd, budget);
            if (isSegmentBoundary(child.id)) {
                if (!childSeen) {
                    throw new IOException("empty unknown-sized cluster");
                }
                return cursor;
            }
            if (!isClusterChild(child.id) || child.unknownSize) {
                throw new IOException("invalid unknown-sized cluster child");
            }
            childSeen = true;
            cursor = child.end();
        }
        if (!childSeen) {
            throw new IOException("empty unknown-sized cluster");
        }
        return segmentEnd;
    }

    private boolean isSegmentBoundary(long id) {
        return id == SEEK_HEAD || id == INFO || id == TRACKS || id == CLUSTER
                || id == CUES || id == ATTACHMENTS || id == CHAPTERS || id == TAGS;
    }

    private boolean isClusterChild(long id) {
        return id == CLUSTER_TIMECODE || id == SIMPLE_BLOCK || id == BLOCK_GROUP
                || id == SILENT_TRACKS || id == POSITION || id == PREV_SIZE
                || id == ENCRYPTED_BLOCK || id == VOID || id == CRC32;
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
            Set<Long> audioTracks, TimestampBounds timestamps, double scaleMs) throws IOException {
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
                parseWebmBlock(channel, element, clusterTimecode, budget,
                        audioTracks, timestamps, scaleMs);
            } else if (element.id == BLOCK_GROUP) {
                if (clusterTimecode == null) {
                    throw new IOException("block group before cluster timecode");
                }
                parseWebmBlockGroup(channel, element, clusterTimecode,
                        budget, audioTracks, timestamps, scaleMs);
            } else if (!isClusterChild(element.id)) {
                throw new IOException("unsupported cluster child");
            }
            cursor = element.end();
        }
    }

    private void parseWebmBlockGroup(
            FileChannel channel, Element group, long clusterTimecode, Budget budget,
            Set<Long> audioTracks, TimestampBounds timestamps, double scaleMs) throws IOException {
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
                parseWebmBlock(channel, child, clusterTimecode, budget,
                        audioTracks, timestamps, scaleMs);
            }
            cursor = child.end();
        }
        if (!blockSeen) {
            throw new IOException("missing block");
        }
    }

    private void parseWebmBlock(
            FileChannel channel, Element block, long clusterTimecode, Budget budget,
            Set<Long> audioTracks, TimestampBounds timestamps, double scaleMs) throws IOException {
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
            long payloadOffset = Math.addExact(block.dataOffset, headerBytes);
            long payloadLength = block.end() - payloadOffset;
            double packetDurationMs = opusPacketDurationMs(
                    channel, payloadOffset, payloadLength, budget);
            timestamps.add(absolute, packetDurationMs, scaleMs);
        }
    }

    private double opusPacketDurationMs(
            FileChannel channel, long offset, long length, Budget budget) throws IOException {
        if (length < 2 || length > MAX_OPUS_PACKET_BYTES) {
            throw new IOException("invalid Opus packet length");
        }
        long limit = Math.addExact(offset, length);
        int toc = readWithin(channel, offset, 1, limit, budget)[0] & 0xff;
        int config = toc >>> 3;
        double frameMs;
        if (config < 12) {
            frameMs = new double[] {10D, 20D, 40D, 60D}[config & 3];
        } else if (config < 16) {
            frameMs = (config & 1) == 0 ? 10D : 20D;
        } else {
            frameMs = new double[] {2.5D, 5D, 10D, 20D}[config & 3];
        }
        int code = toc & 3;
        int frames;
        if (code == 0) {
            frames = 1;
        } else if (code == 1) {
            frames = 2;
            if ((length - 1) < 2 || ((length - 1) & 1) != 0) {
                throw new IOException("invalid Opus CBR packet");
            }
        } else if (code == 2) {
            frames = 2;
            long header = offset + 1;
            FrameLength first = readOpusFrameLength(channel, header, limit, budget);
            long remaining = limit - first.nextOffset;
            if (first.length <= 0 || remaining <= first.length) {
                throw new IOException("invalid Opus VBR packet");
            }
        } else {
            int frameCode = readWithin(channel, offset + 1, 1, limit, budget)[0] & 0xff;
            frames = frameCode & 0x3f;
            if (frames <= 0) {
                throw new IOException("invalid Opus frame count");
            }
            long cursor = offset + 2;
            long padding = 0;
            if ((frameCode & 0x40) != 0) {
                int value;
                do {
                    value = readWithin(channel, cursor, 1, limit, budget)[0] & 0xff;
                    cursor++;
                    padding = Math.addExact(padding, value == 255 ? 254 : value);
                } while (value == 255);
            }
            long payloadEnd = Math.subtractExact(limit, padding);
            if (payloadEnd <= cursor) {
                throw new IOException("invalid Opus packet padding");
            }
            if ((frameCode & 0x80) != 0) {
                long declared = 0;
                for (int index = 0; index < frames - 1; index++) {
                    FrameLength frame = readOpusFrameLength(
                            channel, cursor, payloadEnd, budget);
                    cursor = frame.nextOffset;
                    if (frame.length <= 0) {
                        throw new IOException("empty Opus VBR frame");
                    }
                    declared = Math.addExact(declared, frame.length);
                }
                if (Math.addExact(cursor, declared) >= payloadEnd) {
                    throw new IOException("invalid Opus VBR frame extents");
                }
            } else {
                long payload = payloadEnd - cursor;
                if (payload < frames || payload % frames != 0) {
                    throw new IOException("invalid Opus CBR frame extents");
                }
            }
        }
        double durationMs = frameMs * frames;
        if (!Double.isFinite(durationMs) || durationMs <= 0
                || durationMs > MAX_OPUS_PACKET_DURATION_MS) {
            throw new IOException("invalid Opus packet duration");
        }
        return durationMs;
    }

    private FrameLength readOpusFrameLength(
            FileChannel channel, long offset, long limit, Budget budget) throws IOException {
        int first = readWithin(channel, offset, 1, limit, budget)[0] & 0xff;
        if (first < 252) {
            return new FrameLength(first, offset + 1);
        }
        int second = readWithin(channel, offset + 1, 1, limit, budget)[0] & 0xff;
        return new FrameLength(Math.addExact(first, Math.multiplyExact(second, 4)), offset + 2);
    }

    private double inspectMp4(FileChannel channel, Budget budget) throws IOException {
        long size = channel.size();
        long cursor = 0;
        boolean ftyp = false;
        Box moov = null;
        List<Box> topLevel = new ArrayList<>();
        List<Box> moofs = new ArrayList<>();
        List<MediaExtent> allMediaExtents = new ArrayList<>();
        while (cursor < size) {
            Box box = readBox(channel, cursor, size, budget);
            topLevel.add(box);
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
            } else if (box.type == fourCc("moof")) {
                if (moov == null || moofs.size() >= MAX_MP4_FRAGMENTS) {
                    throw new IOException("invalid or excessive movie fragments");
                }
                moofs.add(box);
            } else if (box.type == fourCc("mdat")) {
                if (box.dataOffset >= box.end()) {
                    throw new IOException("empty media data");
                }
                allMediaExtents.add(new MediaExtent(box.dataOffset, box.end()));
            }
            cursor = box.end();
        }
        if (moov == null || allMediaExtents.isEmpty()) {
            throw new IOException("missing moov or media data");
        }
        boolean fragmented = !moofs.isEmpty();
        Mp4Movie movie = inspectMoov(
                channel, moov.dataOffset, moov.end(), budget, 1,
                fragmented, List.copyOf(allMediaExtents));
        if (!fragmented) {
            if (movie.fragmentDefaults != null || movie.audioTrack.classicDurationMs == null) {
                throw new IOException("invalid classic MP4 metadata");
            }
            return Math.max(movie.header.durationMs(), movie.audioTrack.classicDurationMs);
        }
        if (movie.fragmentDefaults == null || !movie.audioTrack.sampleTable.initializationOnly()) {
            throw new IOException("incomplete fragmented MP4 initialization");
        }
        FragmentTimeline timeline = new FragmentTimeline(
                movie.audioTrack.mediaHeader.timescale);
        long previousSequence = -1;
        for (Box moof : moofs) {
            List<MediaExtent> fragmentMedia = fragmentMediaExtents(topLevel, moof);
            FragmentInfo fragment = parseMoof(
                    channel, moof, budget, movie, fragmentMedia);
            if (previousSequence >= 0
                    && fragment.sequenceNumber != Math.addExact(previousSequence, 1)) {
                throw new IOException("inconsistent movie fragment sequence");
            }
            previousSequence = fragment.sequenceNumber;
            timeline.add(fragment);
        }
        if (timeline.sampleCount <= 0 || timeline.sampleCount > MAX_MP4_SAMPLES
                || timeline.durationTicks <= 0) {
            throw new IOException("empty or excessive fragmented AAC media");
        }
        double fragmentDurationMs = ((double) timeline.durationTicks * 1_000D)
                / movie.audioTrack.mediaHeader.timescale;
        if (!Double.isFinite(fragmentDurationMs) || fragmentDurationMs <= 0) {
            throw new IOException("fragment duration overflow");
        }
        validateDeclaredFragmentDuration(movie.header.durationMs(), fragmentDurationMs);
        validateDeclaredFragmentDuration(
                movie.audioTrack.mediaHeader.durationMs(), fragmentDurationMs);
        return fragmentDurationMs;
    }

    private List<MediaExtent> fragmentMediaExtents(List<Box> topLevel, Box moof)
            throws IOException {
        List<MediaExtent> extents = new ArrayList<>();
        boolean afterMoof = false;
        for (Box box : topLevel) {
            if (box.start == moof.start) {
                afterMoof = true;
                continue;
            }
            if (!afterMoof) {
                continue;
            }
            if (box.type == fourCc("moof")) {
                break;
            }
            if (box.type == fourCc("mdat")) {
                extents.add(new MediaExtent(box.dataOffset, box.end()));
            }
        }
        if (extents.isEmpty()) {
            throw new IOException("movie fragment has no media data");
        }
        return List.copyOf(extents);
    }

    private void validateDeclaredFragmentDuration(double declaredMs, double observedMs)
            throws IOException {
        if (declaredMs < 0 || !Double.isFinite(declaredMs)) {
            throw new IOException("invalid declared fragment duration");
        }
        if (declaredMs > 0 && Math.abs(declaredMs - observedMs) > 1D) {
            throw new IOException("inconsistent declared fragment duration");
        }
    }

    private void parseFtyp(FileChannel channel, Box box, Budget budget) throws IOException {
        long length = box.end() - box.dataOffset;
        if (length < 8 || length > 128 || (length & 3) != 0) {
            throw new IOException("invalid ftyp");
        }
        byte[] value = readWithin(
                channel, box.dataOffset, Math.toIntExact(length), box.end(), budget);
        Set<Integer> compatible = Set.of(
                fourCc("isom"), fourCc("iso2"), fourCc("iso5"), fourCc("iso6"),
                fourCc("mp41"), fourCc("mp42"), fourCc("M4A "), fourCc("M4B "));
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
            boolean fragmented, List<MediaExtent> mediaExtents) throws IOException {
        requireDepth(depth);
        MovieHeader movieHeader = null;
        Mp4Track audioTrack = null;
        TrexDefaults fragmentDefaults = null;
        int tracks = 0;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("mvhd")) {
                if (movieHeader != null) {
                    throw new IOException("duplicate mvhd");
                }
                movieHeader = parseMvhd(channel, box, budget);
            } else if (box.type == fourCc("trak")) {
                tracks++;
                Mp4Track track = inspectMp4Track(
                        channel, box.dataOffset, box.end(), budget, depth + 1,
                        fragmented, mediaExtents);
                if (track.handler != fourCc("soun") || audioTrack != null) {
                    throw new IOException("non-audio or multiple MP4 tracks");
                }
                audioTrack = track;
            } else if (box.type == fourCc("mvex")) {
                if (fragmentDefaults != null) {
                    throw new IOException("duplicate movie extends metadata");
                }
                fragmentDefaults = inspectMvex(
                        channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        if (movieHeader == null || audioTrack == null || tracks != 1) {
            throw new IOException("missing AAC audio track or movie header");
        }
        if (!fragmented) {
            if (movieHeader.duration <= 0 || audioTrack.mediaHeader.duration <= 0
                    || fragmentDefaults != null) {
                throw new IOException("invalid classic MP4 duration");
            }
        } else if (fragmentDefaults == null
                || fragmentDefaults.trackId != audioTrack.trackId) {
            throw new IOException("missing matching fragment defaults");
        }
        return new Mp4Movie(movieHeader, audioTrack, fragmentDefaults);
    }

    private Mp4Track inspectMp4Track(
            FileChannel channel, long start, long end, Budget budget, int depth,
            boolean fragmented, List<MediaExtent> mediaExtents) throws IOException {
        requireDepth(depth);
        Long trackId = null;
        MdiaInfo mdia = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("tkhd")) {
                if (trackId != null) {
                    throw new IOException("duplicate track header");
                }
                trackId = parseTkhd(channel, box, budget);
            } else if (box.type == fourCc("mdia")) {
                if (mdia != null) {
                    throw new IOException("duplicate mdia");
                }
                mdia = inspectMdia(channel, box.dataOffset, box.end(), budget, depth + 1);
            }
            cursor = box.end();
        }
        if (trackId == null || mdia == null || mdia.handler != fourCc("soun")
                || mdia.mediaHeader == null || mdia.sampleTable == null) {
            throw new IOException("missing audio track metadata");
        }
        Double classicDurationMs = null;
        if (!fragmented) {
            classicDurationMs = validateAudioSampleTable(
                    mdia.mediaHeader, mdia.sampleTable, mediaExtents);
        }
        return new Mp4Track(
                trackId, mdia.handler, mdia.mediaHeader,
                mdia.sampleTable, classicDurationMs);
    }

    private long parseTkhd(FileChannel channel, Box box, Budget budget) throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        long dataLength = box.end() - box.dataOffset;
        long trackIdOffset;
        if (full.version == 0) {
            if (dataLength < 84) {
                throw new IOException("short version 0 track header");
            }
            trackIdOffset = box.dataOffset + 12;
        } else if (full.version == 1) {
            if (dataLength < 96) {
                throw new IOException("short version 1 track header");
            }
            trackIdOffset = box.dataOffset + 20;
        } else {
            throw new IOException("unsupported track header version");
        }
        long trackId = readUnsignedWithin(channel, trackIdOffset, 4, box.end(), budget);
        if (trackId <= 0) {
            throw new IOException("invalid track id");
        }
        return trackId;
    }

    private MdiaInfo inspectMdia(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
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
            return new MdiaInfo(handler, null, null);
        }
        if (mediaHeader == null || sampleTable == null) {
            throw new IOException("incomplete audio media metadata");
        }
        return new MdiaInfo(handler, mediaHeader, sampleTable);
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

    private TrexDefaults inspectMvex(
            FileChannel channel, long start, long end, Budget budget, int depth) throws IOException {
        requireDepth(depth);
        TrexDefaults defaults = null;
        long cursor = start;
        while (cursor < end) {
            Box box = readBox(channel, cursor, end, budget);
            if (box.type == fourCc("trex")) {
                if (defaults != null) {
                    throw new IOException("duplicate track extends defaults");
                }
                defaults = parseTrex(channel, box, budget);
            }
            cursor = box.end();
        }
        if (defaults == null) {
            throw new IOException("missing track extends defaults");
        }
        return defaults;
    }

    private TrexDefaults parseTrex(FileChannel channel, Box box, Budget budget)
            throws IOException {
        requireDataLength(box, 24, 24, "trex");
        requireFullBoxZero(channel, box, budget);
        long trackId = readUnsignedWithin(channel, box.dataOffset + 4, 4, box.end(), budget);
        long description = readUnsignedWithin(channel, box.dataOffset + 8, 4, box.end(), budget);
        long duration = readUnsignedWithin(channel, box.dataOffset + 12, 4, box.end(), budget);
        long size = readUnsignedWithin(channel, box.dataOffset + 16, 4, box.end(), budget);
        long flags = readUnsignedWithin(channel, box.dataOffset + 20, 4, box.end(), budget);
        if (trackId <= 0 || description != 1 || duration > Integer.MAX_VALUE
                || size > Integer.MAX_VALUE) {
            throw new IOException("invalid track extends defaults");
        }
        return new TrexDefaults(trackId, (int) duration, (int) size, flags);
    }

    private FragmentInfo parseMoof(
            FileChannel channel, Box moof, Budget budget, Mp4Movie movie,
            List<MediaExtent> mediaExtents) throws IOException {
        Long sequence = null;
        Box traf = null;
        long cursor = moof.dataOffset;
        while (cursor < moof.end()) {
            Box box = readBox(channel, cursor, moof.end(), budget);
            if (box.type == fourCc("mfhd")) {
                if (sequence != null) {
                    throw new IOException("duplicate movie fragment header");
                }
                sequence = parseMfhd(channel, box, budget);
            } else if (box.type == fourCc("traf")) {
                if (traf != null) {
                    throw new IOException("multiple track fragments");
                }
                traf = box;
            }
            cursor = box.end();
        }
        if (sequence == null || traf == null) {
            throw new IOException("incomplete movie fragment");
        }
        TrackFragment fragment = parseTraf(
                channel, traf, moof, budget, movie, mediaExtents);
        return new FragmentInfo(
                sequence, fragment.baseDecodeTime, fragment.durationTicks,
                fragment.sampleCount, fragment.firstDataOffset, fragment.lastDataEnd);
    }

    private long parseMfhd(FileChannel channel, Box box, Budget budget) throws IOException {
        requireDataLength(box, 8, 8, "mfhd");
        requireFullBoxZero(channel, box, budget);
        long sequence = readUnsignedWithin(channel, box.dataOffset + 4, 4, box.end(), budget);
        if (sequence <= 0) {
            throw new IOException("invalid movie fragment sequence");
        }
        return sequence;
    }

    private TrackFragment parseTraf(
            FileChannel channel, Box traf, Box moof, Budget budget, Mp4Movie movie,
            List<MediaExtent> mediaExtents) throws IOException {
        Box tfhdBox = null;
        Box tfdtBox = null;
        List<Box> truns = new ArrayList<>();
        long cursor = traf.dataOffset;
        while (cursor < traf.end()) {
            Box box = readBox(channel, cursor, traf.end(), budget);
            if (box.type == fourCc("tfhd")) {
                if (tfhdBox != null) {
                    throw new IOException("duplicate track fragment header");
                }
                tfhdBox = box;
            } else if (box.type == fourCc("tfdt")) {
                if (tfdtBox != null) {
                    throw new IOException("duplicate track fragment decode time");
                }
                tfdtBox = box;
            } else if (box.type == fourCc("trun")) {
                if (truns.size() >= MAX_MP4_SAMPLES) {
                    throw new IOException("excessive track fragment runs");
                }
                truns.add(box);
            }
            cursor = box.end();
        }
        if (tfhdBox == null || tfdtBox == null || truns.isEmpty()) {
            throw new IOException("incomplete track fragment");
        }
        Tfhd tfhd = parseTfhd(channel, tfhdBox, moof, budget, movie);
        long baseDecodeTime = parseTfdt(channel, tfdtBox, budget);
        Long previousRunEnd = null;
        long totalDuration = 0;
        int totalSamples = 0;
        long firstDataOffset = -1;
        for (Box trun : truns) {
            TrunInfo run = parseTrun(
                    channel, trun, budget, tfhd,
                    movie.audioTrack.mediaHeader.timescale,
                    previousRunEnd, mediaExtents);
            if (firstDataOffset < 0) {
                firstDataOffset = run.dataStart;
            }
            previousRunEnd = run.dataEnd;
            totalDuration = Math.addExact(totalDuration, run.durationTicks);
            totalSamples = Math.addExact(totalSamples, run.sampleCount);
            if (totalSamples > MAX_MP4_SAMPLES) {
                throw new IOException("excessive fragmented AAC samples");
            }
        }
        return new TrackFragment(
                baseDecodeTime, totalDuration, totalSamples,
                firstDataOffset, previousRunEnd == null ? -1 : previousRunEnd);
    }

    private Tfhd parseTfhd(
            FileChannel channel, Box box, Box moof, Budget budget, Mp4Movie movie)
            throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        if (full.version != 0) {
            throw new IOException("unsupported tfhd version");
        }
        int allowed = 0x000001 | 0x000002 | 0x000008 | 0x000010 | 0x000020
                | 0x010000 | 0x020000;
        if ((full.flags & ~allowed) != 0
                || (full.flags & 0x000001) != 0 && (full.flags & 0x020000) != 0
                || (full.flags & 0x010000) != 0) {
            throw new IOException("unsupported tfhd flags");
        }
        long cursor = box.dataOffset + 4;
        long trackId = readUnsignedWithin(channel, cursor, 4, box.end(), budget);
        cursor += 4;
        if (trackId != movie.audioTrack.trackId
                || trackId != movie.fragmentDefaults.trackId) {
            throw new IOException("fragment track mismatch");
        }
        long baseDataOffset = moof.start;
        boolean explicitBase = (full.flags & 0x000001) != 0;
        if (explicitBase) {
            baseDataOffset = readUnsignedWithin(channel, cursor, 8, box.end(), budget);
            cursor += 8;
            if (baseDataOffset < 0) {
                throw new IOException("fragment base offset overflow");
            }
        }
        int description = 1;
        if ((full.flags & 0x000002) != 0) {
            description = Math.toIntExact(readUnsignedWithin(
                    channel, cursor, 4, box.end(), budget));
            cursor += 4;
        }
        int defaultDuration = movie.fragmentDefaults.defaultDuration;
        if ((full.flags & 0x000008) != 0) {
            defaultDuration = Math.toIntExact(readUnsignedWithin(
                    channel, cursor, 4, box.end(), budget));
            cursor += 4;
        }
        int defaultSize = movie.fragmentDefaults.defaultSize;
        if ((full.flags & 0x000010) != 0) {
            defaultSize = Math.toIntExact(readUnsignedWithin(
                    channel, cursor, 4, box.end(), budget));
            cursor += 4;
        }
        long defaultFlags = movie.fragmentDefaults.defaultFlags;
        if ((full.flags & 0x000020) != 0) {
            defaultFlags = readUnsignedWithin(channel, cursor, 4, box.end(), budget);
            cursor += 4;
        }
        if (cursor != box.end() || description != 1
                || defaultDuration < 0 || defaultSize < 0) {
            throw new IOException("invalid track fragment defaults");
        }
        return new Tfhd(baseDataOffset, explicitBase, defaultDuration, defaultSize, defaultFlags);
    }

    private long parseTfdt(FileChannel channel, Box box, Budget budget) throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        if (full.flags != 0) {
            throw new IOException("unsupported tfdt flags");
        }
        if (full.version == 0) {
            requireDataLength(box, 8, 8, "tfdt version 0");
            return readUnsignedWithin(channel, box.dataOffset + 4, 4, box.end(), budget);
        }
        if (full.version == 1) {
            requireDataLength(box, 12, 12, "tfdt version 1");
            long value = readUnsignedWithin(
                    channel, box.dataOffset + 4, 8, box.end(), budget);
            if (value < 0) {
                throw new IOException("decode time overflow");
            }
            return value;
        }
        throw new IOException("unsupported tfdt version");
    }

    private TrunInfo parseTrun(
            FileChannel channel, Box box, Budget budget, Tfhd tfhd,
            long mediaTimescale, Long previousRunEnd,
            List<MediaExtent> mediaExtents) throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        if (full.version != 0 && full.version != 1) {
            throw new IOException("unsupported trun version");
        }
        int allowed = 0x000001 | 0x000004 | 0x000100 | 0x000200
                | 0x000400 | 0x000800;
        if ((full.flags & ~allowed) != 0
                || (full.flags & 0x000004) != 0 && (full.flags & 0x000400) != 0) {
            throw new IOException("unsupported trun flags");
        }
        long cursor = box.dataOffset + 4;
        int sampleCount = boundedFragmentSampleCount(readUnsignedWithin(
                channel, cursor, 4, box.end(), budget));
        cursor += 4;
        Integer dataOffset = null;
        if ((full.flags & 0x000001) != 0) {
            dataOffset = readSignedIntWithin(channel, cursor, box.end(), budget);
            cursor += 4;
        }
        if ((full.flags & 0x000004) != 0) {
            readUnsignedWithin(channel, cursor, 4, box.end(), budget);
            cursor += 4;
        }
        int fieldsPerSample = Integer.bitCount(full.flags & 0x000f00);
        long expectedEnd = Math.addExact(cursor, Math.multiplyExact((long) sampleCount,
                Math.multiplyExact(fieldsPerSample, 4L)));
        if (expectedEnd != box.end()) {
            throw new IOException("invalid track run length");
        }
        long dataStart;
        if (dataOffset != null) {
            dataStart = Math.addExact(tfhd.baseDataOffset, dataOffset.longValue());
        } else if (previousRunEnd != null) {
            dataStart = previousRunEnd;
        } else if (tfhd.explicitBaseDataOffset) {
            dataStart = tfhd.baseDataOffset;
        } else {
            throw new IOException("first track run has no data offset");
        }
        if (dataStart < 0 || previousRunEnd != null && dataStart < previousRunEnd) {
            throw new IOException("backward track run data offset");
        }
        long dataCursor = dataStart;
        long durationTicks = 0;
        for (int index = 0; index < sampleCount; index++) {
            long duration = (full.flags & 0x000100) != 0
                    ? readUnsignedWithin(channel, cursor, 4, box.end(), budget)
                    : tfhd.defaultDuration;
            if ((full.flags & 0x000100) != 0) {
                cursor += 4;
            }
            long sampleSize = (full.flags & 0x000200) != 0
                    ? readUnsignedWithin(channel, cursor, 4, box.end(), budget)
                    : tfhd.defaultSize;
            if ((full.flags & 0x000200) != 0) {
                cursor += 4;
            }
            if ((full.flags & 0x000400) != 0) {
                readUnsignedWithin(channel, cursor, 4, box.end(), budget);
                cursor += 4;
            }
            if ((full.flags & 0x000800) != 0) {
                long compositionOffset = full.version == 0
                        ? readUnsignedWithin(channel, cursor, 4, box.end(), budget)
                        : readSignedIntWithin(channel, cursor, box.end(), budget);
                cursor += 4;
                if (compositionOffset != 0) {
                    throw new IOException("unsupported AAC composition offset");
                }
            }
            if (duration <= 0 || duration > mediaTimescale
                    || sampleSize <= 0 || sampleSize > 1_048_576) {
                throw new IOException("invalid fragmented AAC sample");
            }
            long sampleEnd = Math.addExact(dataCursor, sampleSize);
            if (!insideMediaExtent(mediaExtents, dataCursor, sampleEnd)) {
                throw new IOException("fragmented AAC sample is outside media data");
            }
            dataCursor = sampleEnd;
            durationTicks = Math.addExact(durationTicks, duration);
        }
        if (cursor != box.end() || dataCursor <= dataStart) {
            throw new IOException("empty or malformed track run");
        }
        return new TrunInfo(dataStart, dataCursor, durationTicks, sampleCount);
    }

    private boolean insideMediaExtent(
            List<MediaExtent> mediaExtents, long start, long end) {
        for (MediaExtent extent : mediaExtents) {
            if (extent.contains(start, end)) {
                return true;
            }
        }
        return false;
    }

    private Mp4aInfo parseStsd(FileChannel channel, Box box, Budget budget) throws IOException {
        requireDataLength(box, 8, Long.MAX_VALUE, "stsd");
        requireFullBoxZero(channel, box, budget);
        long entryCount = readUnsignedWithin(
                channel, box.dataOffset + 4, 4, box.end(), budget);
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
        byte[] fixed = readWithin(channel, entry.dataOffset, 28, entry.end(), budget);
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
        byte[] bytes = readWithin(
                channel, box.dataOffset, Math.toIntExact(length), box.end(), budget);
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

    private MediaHeader parseMdhd(FileChannel channel, Box box, Budget budget)
            throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        long dataLength = box.end() - box.dataOffset;
        long timescaleOffset;
        int durationWidth;
        if (full.version == 0) {
            if (dataLength < 24) {
                throw new IOException("short version 0 media header");
            }
            timescaleOffset = box.dataOffset + 12;
            durationWidth = 4;
        } else if (full.version == 1) {
            if (dataLength < 36) {
                throw new IOException("short version 1 media header");
            }
            timescaleOffset = box.dataOffset + 20;
            durationWidth = 8;
        } else {
            throw new IOException("unsupported mdhd version");
        }
        if (full.flags != 0) {
            throw new IOException("invalid mdhd flags");
        }
        long timescale = readUnsignedWithin(
                channel, timescaleOffset, 4, box.end(), budget);
        long duration = readUnsignedWithin(
                channel, timescaleOffset + 4, durationWidth, box.end(), budget);
        if (timescale < 8_000 || timescale > 192_000 || duration < 0) {
            throw new IOException("invalid media duration");
        }
        return new MediaHeader(timescale, duration);
    }

    private TimeToSample parseStts(FileChannel channel, Box box, Budget budget)
            throws IOException {
        requireDataLength(box, 8, Long.MAX_VALUE, "stts");
        requireFullBoxZero(channel, box, budget);
        int entries = boundedTableCount(readUnsignedWithin(
                channel, box.dataOffset + 4, 4, box.end(), budget), true);
        long cursor = box.dataOffset + 8;
        long expectedEnd = Math.addExact(cursor, Math.multiplyExact((long) entries, 8L));
        if (expectedEnd != box.end()) {
            throw new IOException("invalid time-to-sample table length");
        }
        long sampleCount = 0;
        long durationTicks = 0;
        for (int index = 0; index < entries; index++) {
            long count = readUnsignedWithin(channel, cursor, 4, box.end(), budget);
            long delta = readUnsignedWithin(channel, cursor + 4, 4, box.end(), budget);
            cursor += 8;
            if (count <= 0 || delta <= 0) {
                throw new IOException("invalid time-to-sample entry");
            }
            sampleCount = Math.addExact(sampleCount, count);
            durationTicks = Math.addExact(durationTicks, Math.multiplyExact(count, delta));
            if (sampleCount > MAX_MP4_SAMPLES) {
                throw new IOException("excessive AAC samples");
            }
        }
        return new TimeToSample(sampleCount, durationTicks);
    }

    private List<SampleToChunk> parseStsc(
            FileChannel channel, Box box, Budget budget) throws IOException {
        requireDataLength(box, 8, Long.MAX_VALUE, "stsc");
        requireFullBoxZero(channel, box, budget);
        int entries = boundedTableCount(readUnsignedWithin(
                channel, box.dataOffset + 4, 4, box.end(), budget), true);
        long cursor = box.dataOffset + 8;
        long expectedEnd = Math.addExact(cursor, Math.multiplyExact((long) entries, 12L));
        if (expectedEnd != box.end()) {
            throw new IOException("invalid sample-to-chunk table length");
        }
        List<SampleToChunk> values = new ArrayList<>(entries);
        long previous = 0;
        for (int index = 0; index < entries; index++) {
            long firstChunk = readUnsignedWithin(channel, cursor, 4, box.end(), budget);
            long samplesPerChunk = readUnsignedWithin(
                    channel, cursor + 4, 4, box.end(), budget);
            long sampleDescription = readUnsignedWithin(
                    channel, cursor + 8, 4, box.end(), budget);
            cursor += 12;
            if (firstChunk <= previous || index == 0 && firstChunk != 1
                    || samplesPerChunk <= 0 || samplesPerChunk > MAX_MP4_SAMPLES
                    || sampleDescription != 1) {
                throw new IOException("invalid sample-to-chunk entry");
            }
            values.add(new SampleToChunk(firstChunk, samplesPerChunk));
            previous = firstChunk;
        }
        return List.copyOf(values);
    }

    private SampleSizes parseStsz(FileChannel channel, Box box, Budget budget)
            throws IOException {
        requireDataLength(box, 12, Long.MAX_VALUE, "stsz");
        requireFullBoxZero(channel, box, budget);
        long defaultSize = readUnsignedWithin(
                channel, box.dataOffset + 4, 4, box.end(), budget);
        int count = boundedSampleCount(readUnsignedWithin(
                channel, box.dataOffset + 8, 4, box.end(), budget), true);
        long cursor = box.dataOffset + 12;
        if (defaultSize > Integer.MAX_VALUE) {
            throw new IOException("sample size overflow");
        }
        int[] sizes = defaultSize == 0 ? new int[count] : null;
        if (sizes != null) {
            long expectedEnd = Math.addExact(cursor, Math.multiplyExact((long) count, 4L));
            if (expectedEnd != box.end()) {
                throw new IOException("invalid sample size table length");
            }
            for (int index = 0; index < count; index++) {
                long size = readUnsignedWithin(channel, cursor, 4, box.end(), budget);
                cursor += 4;
                if (size <= 0 || size > Integer.MAX_VALUE) {
                    throw new IOException("invalid AAC sample size");
                }
                sizes[index] = (int) size;
            }
        } else if (count == 0 || cursor != box.end()) {
            throw new IOException("invalid default sample size");
        }
        if (count == 0 && (defaultSize != 0 || cursor != box.end())) {
            throw new IOException("invalid empty sample size table");
        }
        return new SampleSizes((int) defaultSize, sizes, count);
    }

    private long[] parseChunkOffsets(
            FileChannel channel, Box box, Budget budget, int width) throws IOException {
        requireDataLength(box, 8, Long.MAX_VALUE, "chunk offsets");
        requireFullBoxZero(channel, box, budget);
        int count = boundedTableCount(readUnsignedWithin(
                channel, box.dataOffset + 4, 4, box.end(), budget), true);
        long cursor = box.dataOffset + 8;
        long expectedEnd = Math.addExact(cursor, Math.multiplyExact((long) count, width));
        if (expectedEnd != box.end()) {
            throw new IOException("invalid chunk offset table length");
        }
        long[] offsets = new long[count];
        long previous = -1;
        for (int index = 0; index < count; index++) {
            long offset = readUnsignedWithin(channel, cursor, width, box.end(), budget);
            cursor += width;
            if (offset <= previous) {
                throw new IOException("non-monotonic chunk offsets");
            }
            offsets[index] = offset;
            previous = offset;
        }
        return offsets;
    }

    private double validateAudioSampleTable(
            MediaHeader header, SampleTable table, List<MediaExtent> mediaExtents)
            throws IOException {
        if (table.initializationOnly() || table.timing.sampleCount <= 0
                || table.sampleToChunks.isEmpty() || table.chunkOffsets.length == 0
                || header.timescale != table.format.sampleRate
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
                    || mediaExtents.stream().noneMatch(
                            extent -> extent.contains(chunkStart, chunkEnd))) {
                throw new IOException("AAC sample extent is outside media data");
            }
            previousChunkEnd = chunkEnd;
            totalBytes = Math.addExact(totalBytes, chunkBytes);
            sampleIndex = nextSample;
        }
        if (sampleIndex != table.sampleSizes.count || totalBytes <= 0) {
            throw new IOException("empty or incomplete AAC media");
        }
        double durationMs = header.durationMs();
        if (!Double.isFinite(durationMs) || durationMs <= 0) {
            throw new IOException("AAC duration overflow");
        }
        return durationMs;
    }

    private int parseHandler(FileChannel channel, Box box, Budget budget) throws IOException {
        requireDataLength(box, 24, Long.MAX_VALUE, "handler");
        FullBox full = readFullBox(channel, box, budget);
        if (full.version != 0 || full.flags != 0) {
            throw new IOException("unsupported handler full box");
        }
        byte[] value = readWithin(channel, box.dataOffset, 12, box.end(), budget);
        return ByteBuffer.wrap(value, 8, 4).getInt();
    }

    private MovieHeader parseMvhd(FileChannel channel, Box box, Budget budget)
            throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        long dataLength = box.end() - box.dataOffset;
        long timescaleOffset;
        int durationWidth;
        if (full.version == 0) {
            if (dataLength < 100) {
                throw new IOException("short version 0 movie header");
            }
            timescaleOffset = box.dataOffset + 12;
            durationWidth = 4;
        } else if (full.version == 1) {
            if (dataLength < 112) {
                throw new IOException("short version 1 movie header");
            }
            timescaleOffset = box.dataOffset + 20;
            durationWidth = 8;
        } else {
            throw new IOException("unsupported mvhd version");
        }
        if (full.flags != 0) {
            throw new IOException("invalid mvhd flags");
        }
        long timescale = readUnsignedWithin(
                channel, timescaleOffset, 4, box.end(), budget);
        long duration = readUnsignedWithin(
                channel, timescaleOffset + 4, durationWidth, box.end(), budget);
        if (timescale <= 0 || duration < 0) {
            throw new IOException("invalid mvhd duration");
        }
        return new MovieHeader(timescale, duration);
    }

    private FullBox readFullBox(FileChannel channel, Box box, Budget budget)
            throws IOException {
        requireDataLength(box, 4, Long.MAX_VALUE, "full box");
        byte[] bytes = readWithin(channel, box.dataOffset, 4, box.end(), budget);
        int version = bytes[0] & 0xff;
        int flags = (bytes[1] & 0xff) << 16 | (bytes[2] & 0xff) << 8 | bytes[3] & 0xff;
        return new FullBox(version, flags);
    }

    private void requireFullBoxZero(FileChannel channel, Box box, Budget budget)
            throws IOException {
        FullBox full = readFullBox(channel, box, budget);
        if (full.version != 0 || full.flags != 0) {
            throw new IOException("unsupported full box version or flags");
        }
    }

    private void requireDataLength(Box box, long minimum, long maximum, String name)
            throws IOException {
        long length = box.end() - box.dataOffset;
        if (length < minimum || length > maximum) {
            throw new IOException("invalid " + name + " length");
        }
    }

    private int boundedTableCount(long count, boolean allowZero) throws IOException {
        if (count < (allowZero ? 0 : 1) || count > MAX_MP4_SAMPLES) {
            throw new IOException("invalid MP4 table count");
        }
        return (int) count;
    }

    private int boundedSampleCount(long count, boolean allowZero) throws IOException {
        if (count < (allowZero ? 0 : 1) || count > MAX_MP4_SAMPLES) {
            throw new IOException("invalid MP4 sample count");
        }
        return (int) count;
    }

    private int boundedFragmentSampleCount(long count) throws IOException {
        return boundedSampleCount(count, false);
    }

    private int readSignedIntWithin(
            FileChannel channel, long offset, long limit, Budget budget) throws IOException {
        byte[] bytes = readWithin(channel, offset, 4, limit, budget);
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt();
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
        byte[] header = readWithin(channel, offset, 8, limit, budget);
        long size32 = Integer.toUnsignedLong(ByteBuffer.wrap(header, 0, 4).getInt());
        int type = ByteBuffer.wrap(header, 4, 4).getInt();
        long headerSize = 8;
        long size;
        if (size32 == 1) {
            size = readUnsignedWithin(channel, offset + 8, 8, limit, budget);
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
        return new Box(type, offset, offset + headerSize, end);
    }

    private long readUnsignedWithin(
            FileChannel channel, long offset, int length, long limit, Budget budget)
            throws IOException {
        return unsignedValue(readWithin(channel, offset, length, limit, budget));
    }

    private byte[] readWithin(
            FileChannel channel, long offset, int length, long limit, Budget budget)
            throws IOException {
        if (length < 0 || offset < 0 || Math.addExact(offset, length) > limit) {
            throw new IOException("read exceeds local box bounds");
        }
        return read(channel, offset, length, budget);
    }

    private long readUnsigned(
            FileChannel channel, long offset, int length, Budget budget) throws IOException {
        return unsignedValue(read(channel, offset, length, budget));
    }

    private long unsignedValue(byte[] bytes) {
        if (bytes.length == 8 && (bytes[0] & 0x80) != 0) {
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

    private record Box(int type, long start, long dataOffset, long end) {
    }

    private record WebmInfo(long timecodeScale, Double durationMs) {
    }

    private record WebmTrack(long number, long type, String codecId) {
    }

    private record FullBox(int version, int flags) {
    }

    private record MovieHeader(long timescale, long duration) {
        double durationMs() {
            return (double) duration * 1_000D / timescale;
        }
    }

    private record Mp4Movie(
            MovieHeader header, Mp4Track audioTrack, TrexDefaults fragmentDefaults) {
    }

    private record Mp4Track(
            long trackId, int handler, MediaHeader mediaHeader,
            SampleTable sampleTable, Double classicDurationMs) {
    }

    private record MdiaInfo(
            int handler, MediaHeader mediaHeader, SampleTable sampleTable) {
    }

    private record MediaHeader(long timescale, long duration) {
        double durationMs() {
            return (double) duration * 1_000D / timescale;
        }
    }

    private record Mp4aInfo(int channels, int sampleRate) {
    }

    private record TrexDefaults(
            long trackId, int defaultDuration, int defaultSize, long defaultFlags) {
    }

    private record Tfhd(
            long baseDataOffset, boolean explicitBaseDataOffset,
            int defaultDuration, int defaultSize, long defaultFlags) {
    }

    private record TrunInfo(
            long dataStart, long dataEnd, long durationTicks, int sampleCount) {
    }

    private record TrackFragment(
            long baseDecodeTime, long durationTicks, int sampleCount,
            long firstDataOffset, long lastDataEnd) {
    }

    private record FragmentInfo(
            long sequenceNumber, long baseDecodeTime, long durationTicks, int sampleCount,
            long firstDataOffset, long lastDataEnd) {
    }

    private record TimeToSample(long sampleCount, long durationTicks) {
    }

    private record SampleToChunk(long firstChunk, long samplesPerChunk) {
    }

    private record SampleTable(
            Mp4aInfo format, TimeToSample timing, List<SampleToChunk> sampleToChunks,
            SampleSizes sampleSizes, long[] chunkOffsets) {
        boolean initializationOnly() {
            return timing.sampleCount == 0 && timing.durationTicks == 0
                    && sampleToChunks.isEmpty() && sampleSizes.count == 0
                    && chunkOffsets.length == 0;
        }
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

    private static final class FragmentTimeline {
        private final long timescale;
        private long firstDecodeTime = -1;
        private long expectedDecodeTime = -1;
        private long durationTicks;
        private long previousDataEnd = -1;
        private int sampleCount;

        private FragmentTimeline(long timescale) {
            this.timescale = timescale;
        }

        void add(FragmentInfo fragment) throws IOException {
            if (fragment.durationTicks <= 0 || fragment.sampleCount <= 0
                    || fragment.firstDataOffset < 0
                    || fragment.lastDataEnd <= fragment.firstDataOffset) {
                throw new IOException("empty movie fragment");
            }
            if (firstDecodeTime < 0) {
                firstDecodeTime = fragment.baseDecodeTime;
                expectedDecodeTime = fragment.baseDecodeTime;
            }
            long gap = Math.subtractExact(fragment.baseDecodeTime, expectedDecodeTime);
            if (gap < 0 || gap > timescale
                    || previousDataEnd > fragment.firstDataOffset) {
                throw new IOException("inconsistent fragment timeline or media order");
            }
            expectedDecodeTime = Math.addExact(
                    fragment.baseDecodeTime, fragment.durationTicks);
            durationTicks = Math.subtractExact(expectedDecodeTime, firstDecodeTime);
            sampleCount = Math.addExact(sampleCount, fragment.sampleCount);
            previousDataEnd = fragment.lastDataEnd;
            if (sampleCount > MAX_MP4_SAMPLES) {
                throw new IOException("excessive fragmented AAC samples");
            }
        }
    }

    private record FrameLength(long length, long nextOffset) {
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
        private long last = -1;
        private int count;
        private boolean advanced;
        private double maxEndMs = -1D;

        void add(long value, double packetDurationMs, double scaleMs) throws IOException {
            if (last > value) {
                throw new IOException("non-monotonic audio timestamp");
            }
            if (last >= 0 && value > last) {
                advanced = true;
            }
            double endMs = value * scaleMs + packetDurationMs;
            if (!Double.isFinite(endMs) || endMs <= 0) {
                throw new IOException("invalid audio block end");
            }
            last = value;
            maxEndMs = Math.max(maxEndMs, endMs);
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
