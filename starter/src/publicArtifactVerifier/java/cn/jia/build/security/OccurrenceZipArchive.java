package cn.jia.build.security;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Strict, occurrence-addressed reader for the non-ZIP64 JAR subset used by the verifier. */
final class OccurrenceZipArchive implements Closeable {
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final int EOCD_FIXED_SIZE = 22;
    private static final int MAX_EOCD_SEARCH = EOCD_FIXED_SIZE + 0xffff;
    private static final int MAX_ENTRIES = 100_000;
    private static final int STORED = 0;
    private static final int DEFLATED = 8;
    private static final int DATA_DESCRIPTOR_FLAG = 1 << 3;
    private static final int UTF8_FLAG = 1 << 11;
    private static final int ENCRYPTION_FLAGS = 1 | (1 << 6) | (1 << 13);
    private static final int ZIP64_EXTRA = 0x0001;
    private static final int UNICODE_PATH_EXTRA = 0x7075;

    record OccurrenceId(int centralDirectoryOrdinal, long localHeaderOffset) { }

    static final class Occurrence {
        private final OccurrenceId id;
        private final String name;
        private final byte[] rawName;
        private final int flags;
        private final int method;
        private final long crc32;
        private final long compressedSize;
        private final long uncompressedSize;
        private final boolean directory;
        private long dataOffset;
        private long dataEnd;

        private Occurrence(
                OccurrenceId id,
                String name,
                byte[] rawName,
                int flags,
                int method,
                long crc32,
                long compressedSize,
                long uncompressedSize,
                boolean directory) {
            this.id = id;
            this.name = name;
            this.rawName = rawName;
            this.flags = flags;
            this.method = method;
            this.crc32 = crc32;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
            this.directory = directory;
        }

        OccurrenceId id() {
            return id;
        }

        String name() {
            return name;
        }

        long compressedSize() {
            return compressedSize;
        }

        long uncompressedSize() {
            return uncompressedSize;
        }

        boolean directory() {
            return directory;
        }
    }

    /** Mutable actual-output ceiling shared across sequential occurrence copies. */
    static final class SharedOutputBudget {
        private long remaining;

        SharedOutputBudget(long maximumBytes) {
            if (maximumBytes < 0) {
                throw new IllegalArgumentException("invalid-shared-output-budget");
            }
            remaining = maximumBytes;
        }

        long remaining() {
            return remaining;
        }
    }

    /** Per-copy ceiling whose charges are never rolled back after payload validation failures. */
    static final class OutputBudget {
        private final SharedOutputBudget shared;
        private long memberRemaining;
        private long produced;
        private boolean sharedLimitExceeded;

        OutputBudget(long memberMaximumBytes, SharedOutputBudget shared) {
            if (memberMaximumBytes < 0 || shared == null) {
                throw new IllegalArgumentException("invalid-output-budget");
            }
            memberRemaining = memberMaximumBytes;
            this.shared = shared;
        }

        long produced() {
            return produced;
        }

        boolean sharedLimitExceeded() {
            return sharedLimitExceeded;
        }

        private boolean declaredSizeFits(long declaredSize) {
            return declaredSize >= 0
                    && declaredSize <= memberRemaining
                    && declaredSize <= shared.remaining();
        }

        private int nextChunk(int requested) throws PublicArtifactVerifier.VerificationException {
            int count = nextInflateChunk(requested);
            if (count == 0) {
                throw limitFailure();
            }
            return count;
        }

        private int nextInflateChunk(int requested) {
            return (int) Math.min(requested, Math.min(memberRemaining, shared.remaining));
        }

        private PublicArtifactVerifier.VerificationException limitFailure() {
            sharedLimitExceeded = shared.remaining == 0;
            return failure(PublicArtifactVerifier.FailureCode.LIMIT_ERROR);
        }

        private void charge(int count) {
            if (count < 0 || count > memberRemaining || count > shared.remaining) {
                throw new IllegalStateException("output-budget-overcharge");
            }
            memberRemaining -= count;
            shared.remaining -= count;
            produced += count;
        }
    }

    private final FileChannel channel;
    private final List<Occurrence> occurrences;

    private OccurrenceZipArchive(FileChannel channel, List<Occurrence> occurrences) {
        this.channel = channel;
        this.occurrences = List.copyOf(occurrences);
    }

    static OccurrenceZipArchive open(Path path) throws IOException, PublicArtifactVerifier.VerificationException {
        return open(FileChannel.open(path, StandardOpenOption.READ));
    }

    /** Takes ownership of an already identity-bound channel and closes it on every terminal path. */
    static OccurrenceZipArchive open(FileChannel channel)
            throws IOException, PublicArtifactVerifier.VerificationException {
        boolean accepted = false;
        try {
            long size = channel.size();
            if (size == 0) {
                throw failure(PublicArtifactVerifier.FailureCode.EOCD_ERROR);
            }
            Eocd eocd = locateEocd(channel, size);
            List<Occurrence> occurrences = parseCentralDirectory(channel, eocd);
            bindLocalRecords(channel, occurrences, eocd.centralOffset);
            OccurrenceZipArchive archive = new OccurrenceZipArchive(channel, occurrences);
            accepted = true;
            return archive;
        } finally {
            if (!accepted) {
                channel.close();
            }
        }
    }

    List<Occurrence> occurrences() {
        return occurrences;
    }

    void copyPayload(Occurrence occurrence, OutputStream output, long maximumUncompressedBytes)
            throws IOException, PublicArtifactVerifier.VerificationException {
        SharedOutputBudget shared = new SharedOutputBudget(maximumUncompressedBytes);
        copyPayload(occurrence, output, new OutputBudget(maximumUncompressedBytes, shared));
    }

    void copyPayload(Occurrence occurrence, OutputStream output, OutputBudget budget)
            throws IOException, PublicArtifactVerifier.VerificationException {
        if (!budget.declaredSizeFits(occurrence.uncompressedSize)) {
            throw failure(PublicArtifactVerifier.FailureCode.LIMIT_ERROR);
        }
        if (occurrence.method == STORED) {
            copyStored(occurrence, output, budget);
        } else if (occurrence.method == DEFLATED) {
            copyDeflated(occurrence, output, budget);
        } else {
            throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void copyStored(Occurrence occurrence, OutputStream output, OutputBudget budget)
            throws IOException, PublicArtifactVerifier.VerificationException {
        if (occurrence.compressedSize != occurrence.uncompressedSize) {
            throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
        }
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[8192];
        long position = occurrence.dataOffset;
        long remaining = occurrence.compressedSize;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int count = budget.nextChunk(requested);
            readFully(channel, position, buffer, 0, count,
                    PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
            position += count;
            remaining -= count;
            budget.charge(count);
            crc.update(buffer, 0, count);
            output.write(buffer, 0, count);
        }
        verifyPayload(occurrence, budget.produced(), crc.getValue(), position);
    }

    private void copyDeflated(Occurrence occurrence, OutputStream output, OutputBudget budget)
            throws IOException, PublicArtifactVerifier.VerificationException {
        Inflater inflater = new Inflater(true);
        CRC32 crc = new CRC32();
        byte[] compressed = new byte[8192];
        byte[] decompressed = new byte[8192];
        long position = occurrence.dataOffset;
        long compressedRemaining = occurrence.compressedSize;
        long compressedFed = 0;
        try {
            while (true) {
                if (inflater.finished()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
                }
                if (inflater.needsInput()) {
                    if (compressedRemaining == 0) {
                        break;
                    }
                    int count = (int) Math.min(compressed.length, compressedRemaining);
                    readFully(channel, position, compressed, 0, count,
                            PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
                    position += count;
                    compressedRemaining -= count;
                    compressedFed += count;
                    inflater.setInput(compressed, 0, count);
                }
                int outputCapacity = budget.nextInflateChunk(decompressed.length);
                int count;
                try {
                    count = inflater.inflate(decompressed, 0, outputCapacity);
                } catch (DataFormatException ignored) {
                    throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
                }
                if (count > 0) {
                    budget.charge(count);
                    crc.update(decompressed, 0, count);
                    output.write(decompressed, 0, count);
                } else if (inflater.finished()) {
                    break;
                } else if (inflater.needsInput() && compressedRemaining > 0) {
                    continue;
                } else if (inflater.needsDictionary() || (inflater.needsInput() && compressedRemaining == 0)) {
                    throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
                } else if (outputCapacity == 0) {
                    // A zero-length inflate may consume a legal zero-output terminator. If it cannot
                    // finish or request more input, output is pending and must be rejected before
                    // granting even one byte of destination capacity.
                    throw budget.limitFailure();
                } else {
                    throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
                }
            }
            long consumed = compressedFed - inflater.getRemaining();
            if (!inflater.finished() || inflater.needsDictionary()
                    || consumed != occurrence.compressedSize || compressedRemaining != 0) {
                throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
            }
            verifyPayload(occurrence, budget.produced(), crc.getValue(), occurrence.dataOffset + consumed);
        } finally {
            inflater.end();
        }
    }

    private static void verifyPayload(Occurrence occurrence, long produced, long crc, long dataEnd)
            throws PublicArtifactVerifier.VerificationException {
        if (produced != occurrence.uncompressedSize
                || crc != occurrence.crc32
                || dataEnd != occurrence.dataEnd) {
            throw failure(PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
        }
    }

    private static Eocd locateEocd(FileChannel channel, long physicalSize)
            throws IOException, PublicArtifactVerifier.VerificationException {
        int length = (int) Math.min(physicalSize, MAX_EOCD_SEARCH);
        long start = physicalSize - length;
        byte[] tail = readBytes(channel, start, length, PublicArtifactVerifier.FailureCode.EOCD_ERROR);
        int match = -1;
        int matches = 0;
        for (int index = tail.length - EOCD_FIXED_SIZE; index >= 0; index--) {
            if (u32(tail, index) == EOCD_SIGNATURE) {
                int commentLength = u16(tail, index + 20);
                if ((long) start + index + EOCD_FIXED_SIZE + commentLength == physicalSize) {
                    match = index;
                    matches++;
                }
            }
        }
        if (matches != 1) {
            throw failure(PublicArtifactVerifier.FailureCode.EOCD_ERROR);
        }
        long eocdOffset = start + match;
        byte[] fixed = Arrays.copyOfRange(tail, match, match + EOCD_FIXED_SIZE);
        int disk = u16(fixed, 4);
        int centralDisk = u16(fixed, 6);
        int entriesOnDisk = u16(fixed, 8);
        int totalEntries = u16(fixed, 10);
        long centralSize = u32(fixed, 12);
        long centralOffset = u32(fixed, 16);
        if (disk != 0 || centralDisk != 0 || entriesOnDisk != totalEntries) {
            throw failure(PublicArtifactVerifier.FailureCode.EOCD_ERROR);
        }
        if (entriesOnDisk == 0xffff || totalEntries == 0xffff
                || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
            throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        if (totalEntries > MAX_ENTRIES) {
            throw failure(PublicArtifactVerifier.FailureCode.LIMIT_ERROR);
        }
        if (eocdOffset >= 20 && readU32(channel, eocdOffset - 20,
                PublicArtifactVerifier.FailureCode.EOCD_ERROR) == ZIP64_LOCATOR_SIGNATURE) {
            throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        if (centralOffset > eocdOffset || centralSize > eocdOffset - centralOffset
                || centralOffset + centralSize != eocdOffset) {
            throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
        }
        return new Eocd(totalEntries, centralOffset, centralSize);
    }

    private static List<Occurrence> parseCentralDirectory(FileChannel channel, Eocd eocd)
            throws IOException, PublicArtifactVerifier.VerificationException {
        List<Occurrence> occurrences = new ArrayList<>(eocd.entries);
        long position = eocd.centralOffset;
        long end = eocd.centralOffset + eocd.centralSize;
        for (int ordinal = 0; ordinal < eocd.entries; ordinal++) {
            if (position > end - 46) {
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
            byte[] fixed = readBytes(channel, position, 46,
                    PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            if (u32(fixed, 0) != CENTRAL_SIGNATURE) {
                if (u32(fixed, 0) == ZIP64_EOCD_SIGNATURE || u32(fixed, 0) == ZIP64_LOCATOR_SIGNATURE) {
                    throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
                }
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
            int flags = u16(fixed, 8);
            int method = u16(fixed, 10);
            long crc = u32(fixed, 16);
            long compressedSize = u32(fixed, 20);
            long uncompressedSize = u32(fixed, 24);
            int nameLength = u16(fixed, 28);
            int extraLength = u16(fixed, 30);
            int commentLength = u16(fixed, 32);
            int diskStart = u16(fixed, 34);
            long localOffset = u32(fixed, 42);
            long variableLength = (long) nameLength + extraLength + commentLength;
            if (position + 46 + variableLength > end || nameLength == 0) {
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
            rejectUnsupported(flags, method, diskStart, compressedSize, uncompressedSize, localOffset);
            byte[] rawName = readBytes(channel, position + 46, nameLength,
                    PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            byte[] extra = readBytes(channel, position + 46 + nameLength, extraLength,
                    PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            validateExtra(extra, PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            String name = decodeName(rawName, flags);
            validateName(name);
            occurrences.add(new Occurrence(
                    new OccurrenceId(ordinal, localOffset),
                    name,
                    rawName,
                    flags,
                    method,
                    crc,
                    compressedSize,
                    uncompressedSize,
                    name.endsWith("/")));
            position += 46 + variableLength;
        }
        if (position != end) {
            throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
        }
        return occurrences;
    }

    private static void bindLocalRecords(FileChannel channel, List<Occurrence> occurrences, long centralOffset)
            throws IOException, PublicArtifactVerifier.VerificationException {
        if (occurrences.isEmpty()) {
            if (centralOffset != 0) {
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
            return;
        }
        List<Occurrence> physicalOrder = new ArrayList<>(occurrences);
        physicalOrder.sort(Comparator.comparingLong(value -> value.id.localHeaderOffset));
        Set<Long> offsets = new HashSet<>();
        for (int index = 0; index < physicalOrder.size(); index++) {
            Occurrence occurrence = physicalOrder.get(index);
            long localOffset = occurrence.id.localHeaderOffset;
            if (!offsets.add(localOffset) || localOffset < 0 || localOffset > centralOffset - 30) {
                throw failure(PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
            }
            long boundary = index + 1 < physicalOrder.size()
                    ? physicalOrder.get(index + 1).id.localHeaderOffset
                    : centralOffset;
            if (boundary <= localOffset) {
                throw failure(PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
            }
            bindOneLocal(channel, occurrence, boundary);
        }
    }

    private static void bindOneLocal(FileChannel channel, Occurrence occurrence, long boundary)
            throws IOException, PublicArtifactVerifier.VerificationException {
        long localOffset = occurrence.id.localHeaderOffset;
        byte[] fixed = readBytes(channel, localOffset, 30,
                PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        if (u32(fixed, 0) != LOCAL_SIGNATURE) {
            throw failure(PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        }
        int flags = u16(fixed, 6);
        int method = u16(fixed, 8);
        long localCrc = u32(fixed, 14);
        long localCompressedSize = u32(fixed, 18);
        long localUncompressedSize = u32(fixed, 22);
        int nameLength = u16(fixed, 26);
        int extraLength = u16(fixed, 28);
        long dataOffset = localOffset + 30L + nameLength + extraLength;
        if (nameLength == 0 || dataOffset > boundary
                || occurrence.compressedSize > boundary - dataOffset) {
            throw failure(PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        }
        byte[] rawName = readBytes(channel, localOffset + 30, nameLength,
                PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        byte[] extra = readBytes(channel, localOffset + 30L + nameLength, extraLength,
                PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        validateExtra(extra, PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        rejectUnsupported(flags, method, 0, localCompressedSize, localUncompressedSize, localOffset);
        if (flags != occurrence.flags || method != occurrence.method
                || !Arrays.equals(rawName, occurrence.rawName)) {
            throw failure(PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
        }
        long dataEnd = dataOffset + occurrence.compressedSize;
        if ((flags & DATA_DESCRIPTOR_FLAG) == 0) {
            if (localCrc != occurrence.crc32
                    || localCompressedSize != occurrence.compressedSize
                    || localUncompressedSize != occurrence.uncompressedSize) {
                throw failure(PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
            }
            if (dataEnd != boundary) {
                throw failure(PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
            }
        } else {
            if (!((localCrc == 0 && localCompressedSize == 0 && localUncompressedSize == 0)
                    || (localCrc == occurrence.crc32
                    && localCompressedSize == occurrence.compressedSize
                    && localUncompressedSize == occurrence.uncompressedSize))) {
                throw failure(PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
            }
            long descriptorLength = boundary - dataEnd;
            if (descriptorLength != 12 && descriptorLength != 16) {
                throw failure(PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
            }
            byte[] descriptor = readBytes(channel, dataEnd, (int) descriptorLength,
                    PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
            int valueOffset = 0;
            if (descriptorLength == 16) {
                if (u32(descriptor, 0) != DATA_DESCRIPTOR_SIGNATURE) {
                    throw failure(PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
                }
                valueOffset = 4;
            }
            if (u32(descriptor, valueOffset) != occurrence.crc32
                    || u32(descriptor, valueOffset + 4) != occurrence.compressedSize
                    || u32(descriptor, valueOffset + 8) != occurrence.uncompressedSize) {
                throw failure(PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
            }
        }
        occurrence.dataOffset = dataOffset;
        occurrence.dataEnd = dataEnd;
    }

    private static void rejectUnsupported(
            int flags,
            int method,
            int diskStart,
            long compressedSize,
            long uncompressedSize,
            long localOffset) throws PublicArtifactVerifier.VerificationException {
        if ((flags & ENCRYPTION_FLAGS) != 0 || (method != STORED && method != DEFLATED)) {
            throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        if (diskStart != 0 || compressedSize == 0xffffffffL
                || uncompressedSize == 0xffffffffL || localOffset == 0xffffffffL) {
            throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
    }

    private static void validateExtra(byte[] extra, PublicArtifactVerifier.FailureCode malformedCode)
            throws PublicArtifactVerifier.VerificationException {
        int index = 0;
        while (index < extra.length) {
            if (extra.length - index < 4) {
                throw failure(malformedCode);
            }
            int id = u16(extra, index);
            int length = u16(extra, index + 2);
            index += 4;
            if (length > extra.length - index) {
                throw failure(malformedCode);
            }
            if (id == ZIP64_EXTRA || id == UNICODE_PATH_EXTRA) {
                throw failure(PublicArtifactVerifier.FailureCode.UNSUPPORTED);
            }
            index += length;
        }
    }

    private static String decodeName(byte[] rawName, int flags)
            throws PublicArtifactVerifier.VerificationException {
        Charset charset = (flags & UTF8_FLAG) != 0 ? StandardCharsets.UTF_8 : Charset.forName("Cp437");
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(rawName))
                    .toString();
        } catch (CharacterCodingException ignored) {
            throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
        }
    }

    private static void validateName(String name) throws PublicArtifactVerifier.VerificationException {
        if (name.isEmpty()) {
            throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
        }
        for (int index = 0; index < name.length();) {
            int codePoint = name.codePointAt(index);
            if (codePoint == 0 || Character.isISOControl(codePoint)) {
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
            index += Character.charCount(codePoint);
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")
                || (normalized.length() >= 2 && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':')) {
            throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw failure(PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
            }
        }
    }

    private static byte[] readBytes(
            FileChannel channel,
            long position,
            int length,
            PublicArtifactVerifier.FailureCode code)
            throws IOException, PublicArtifactVerifier.VerificationException {
        byte[] bytes = new byte[length];
        readFully(channel, position, bytes, 0, length, code);
        return bytes;
    }

    private static void readFully(
            FileChannel channel,
            long position,
            byte[] target,
            int offset,
            int length,
            PublicArtifactVerifier.FailureCode code)
            throws IOException, PublicArtifactVerifier.VerificationException {
        ByteBuffer buffer = ByteBuffer.wrap(target, offset, length);
        long cursor = position;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, cursor);
            if (count <= 0) {
                throw failure(code);
            }
            cursor += count;
        }
    }

    private static long readU32(
            FileChannel channel,
            long position,
            PublicArtifactVerifier.FailureCode code)
            throws IOException, PublicArtifactVerifier.VerificationException {
        return u32(readBytes(channel, position, 4, code), 0);
    }

    private static int u16(byte[] bytes, int offset) {
        return Short.toUnsignedInt(ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort());
    }

    private static long u32(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static PublicArtifactVerifier.VerificationException failure(PublicArtifactVerifier.FailureCode code) {
        return new PublicArtifactVerifier.VerificationException(code);
    }

    private record Eocd(int entries, long centralOffset, long centralSize) { }
}
