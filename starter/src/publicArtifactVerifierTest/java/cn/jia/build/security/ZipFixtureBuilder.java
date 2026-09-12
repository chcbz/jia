package cn.jia.build.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

final class ZipFixtureBuilder {
    static final String COLLIDING_UNSAFE_PROPERTIES =
            "api-key=live-secret\n# collision:      T51RKAE5\n";
    static final String COLLIDING_SAFE_PROPERTIES =
            "api-key=${PUBLIC_API_KEY}\n# collision:Xi4KtUrL\n";
    static final long COLLIDING_CRC32 = 0x3fce2d7cL;
    static final int DEFLATE_INPUT_CHUNK_SIZE = 8192;
    static final int NON_FINAL_STORED_BLOCK_DATA_SIZE = DEFLATE_INPUT_CHUNK_SIZE - 5;

    enum Descriptor {
        NONE,
        SIGNED,
        UNSIGNED
    }

    static final class EntrySpec {
        final String name;
        final byte[] data;
        int method;
        int extraFlags;
        byte[] localExtra = new byte[0];
        byte[] centralExtra = new byte[0];
        boolean redundantLocalSizes;
        long declaredUncompressedSize = -1;
        boolean finalEmptyBlockAfterInputBoundary;
        Descriptor descriptor = Descriptor.NONE;

        EntrySpec(String name, byte[] data) {
            this.name = name;
            this.data = data.clone();
        }

        EntrySpec deflated() {
            method = 8;
            return this;
        }

        EntrySpec stored() {
            method = 0;
            return this;
        }

        EntrySpec deflatedWithFinalEmptyBlockAfterInputBoundary() {
            method = 8;
            finalEmptyBlockAfterInputBoundary = true;
            return this;
        }

        EntrySpec descriptor(Descriptor value) {
            descriptor = value;
            return this;
        }

        EntrySpec extraFlags(int value) {
            extraFlags = value;
            return this;
        }

        EntrySpec localExtra(byte[] value) {
            localExtra = value.clone();
            return this;
        }

        EntrySpec centralExtra(byte[] value) {
            centralExtra = value.clone();
            return this;
        }

        EntrySpec redundantLocalSizes() {
            redundantLocalSizes = true;
            return this;
        }

        EntrySpec declaredUncompressedSize(long value) {
            if (value < 0 || value > 0xffffffffL) {
                throw new IllegalArgumentException("invalid-declared-uncompressed-size");
            }
            declaredUncompressedSize = value;
            return this;
        }
    }

    record Layout(
            int localOffset,
            int localFlagsOffset,
            int localMethodOffset,
            int localCrcOffset,
            int localCompressedSizeOffset,
            int localUncompressedSizeOffset,
            int localNameOffset,
            int payloadOffset,
            int descriptorOffset,
            int centralOffset,
            int centralFlagsOffset,
            int centralMethodOffset,
            int centralCrcOffset,
            int centralCompressedSizeOffset,
            int centralUncompressedSizeOffset,
            int centralLocalOffsetField,
            int compressedSize,
            int uncompressedSize) { }

    record BuiltZip(byte[] bytes, List<Layout> layouts, int centralOffset, int eocdOffset) {
        BuiltZip {
            bytes = bytes.clone();
            layouts = List.copyOf(layouts);
        }

        byte[] copy() {
            return bytes.clone();
        }

        Path write(Path path) throws IOException {
            Files.write(path, bytes);
            return path;
        }
    }

    private final List<EntrySpec> entries = new ArrayList<>();
    private int gapAfterLocals;

    ZipFixtureBuilder add(String name, String data) {
        return add(new EntrySpec(name, data.getBytes(StandardCharsets.UTF_8)).stored());
    }

    ZipFixtureBuilder add(EntrySpec entry) {
        entries.add(entry);
        return this;
    }

    ZipFixtureBuilder gapAfterLocals(int bytes) {
        gapAfterLocals = bytes;
        return this;
    }

    BuiltZip build() {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            List<Pending> pending = new ArrayList<>();
            for (EntrySpec entry : entries) {
                byte[] name = entry.name.getBytes(StandardCharsets.UTF_8);
                byte[] compressed;
                if (entry.method == 8) {
                    compressed = entry.finalEmptyBlockAfterInputBoundary
                            ? deflateWithFinalEmptyBlockAfterInputBoundary(entry.data)
                            : deflate(entry.data);
                } else {
                    compressed = entry.data.clone();
                }
                long declaredUncompressedSize = entry.declaredUncompressedSize >= 0
                        ? entry.declaredUncompressedSize
                        : entry.data.length;
                byte[] localExtra = entry.localExtra;
                if (entry.redundantLocalSizes) {
                    localExtra = new byte[20];
                    putU16(localExtra, 0, 1);
                    putU16(localExtra, 2, 16);
                    putU32(localExtra, 4, declaredUncompressedSize);
                    putU32(localExtra, 12, compressed.length);
                }
                CRC32 crc = new CRC32();
                crc.update(entry.data);
                int flags = (1 << 11) | entry.extraFlags;
                if (entry.descriptor != Descriptor.NONE) {
                    flags |= 1 << 3;
                }
                int localOffset = output.size();
                le32(output, 0x04034b50L);
                le16(output, 20);
                int localFlagsOffset = output.size();
                le16(output, flags);
                int localMethodOffset = output.size();
                le16(output, entry.method);
                le16(output, 0);
                le16(output, 0);
                int localCrcOffset = output.size();
                le32(output, entry.descriptor == Descriptor.NONE ? crc.getValue() : 0);
                int localCompressedOffset = output.size();
                le32(output, entry.descriptor == Descriptor.NONE ? compressed.length : 0);
                int localUncompressedOffset = output.size();
                le32(output, entry.descriptor == Descriptor.NONE ? declaredUncompressedSize : 0);
                le16(output, name.length);
                le16(output, localExtra.length);
                int localNameOffset = output.size();
                output.write(name);
                output.write(localExtra);
                int payloadOffset = output.size();
                output.write(compressed);
                int descriptorOffset = -1;
                if (entry.descriptor != Descriptor.NONE) {
                    descriptorOffset = output.size();
                    if (entry.descriptor == Descriptor.SIGNED) {
                        le32(output, 0x08074b50L);
                    }
                    le32(output, crc.getValue());
                    le32(output, compressed.length);
                    le32(output, declaredUncompressedSize);
                }
                pending.add(new Pending(entry, name, compressed, crc.getValue(), localOffset,
                        localFlagsOffset, localMethodOffset, localCrcOffset, localCompressedOffset,
                        localUncompressedOffset, localNameOffset, payloadOffset, descriptorOffset, flags,
                        declaredUncompressedSize));
            }
            for (int i = 0; i < gapAfterLocals; i++) {
                output.write(0x5a);
            }
            int centralOffset = output.size();
            List<Layout> layouts = new ArrayList<>();
            for (Pending entry : pending) {
                int central = output.size();
                le32(output, 0x02014b50L);
                le16(output, 20);
                le16(output, 20);
                int centralFlags = output.size();
                le16(output, entry.flags);
                int centralMethod = output.size();
                le16(output, entry.spec.method);
                le16(output, 0);
                le16(output, 0);
                int centralCrc = output.size();
                le32(output, entry.crc);
                int centralCompressed = output.size();
                le32(output, entry.compressed.length);
                int centralUncompressed = output.size();
                le32(output, entry.declaredUncompressedSize);
                le16(output, entry.name.length);
                le16(output, entry.spec.centralExtra.length);
                le16(output, 0);
                le16(output, 0);
                le16(output, 0);
                le32(output, 0);
                int centralLocalOffset = output.size();
                le32(output, entry.localOffset);
                output.write(entry.name);
                output.write(entry.spec.centralExtra);
                layouts.add(new Layout(entry.localOffset, entry.localFlagsOffset, entry.localMethodOffset,
                        entry.localCrcOffset, entry.localCompressedOffset, entry.localUncompressedOffset,
                        entry.localNameOffset, entry.payloadOffset, entry.descriptorOffset, central,
                        centralFlags, centralMethod, centralCrc, centralCompressed, centralUncompressed,
                        centralLocalOffset, entry.compressed.length, entry.spec.data.length));
            }
            int centralSize = output.size() - centralOffset;
            int eocdOffset = output.size();
            le32(output, 0x06054b50L);
            le16(output, 0);
            le16(output, 0);
            le16(output, entries.size());
            le16(output, entries.size());
            le32(output, centralSize);
            le32(output, centralOffset);
            le16(output, 0);
            return new BuiltZip(output.toByteArray(), layouts, centralOffset, eocdOffset);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static byte[] withByte(byte[] bytes, int offset, int value) {
        byte[] copy = bytes.clone();
        copy[offset] = (byte) value;
        return copy;
    }

    static byte[] withU16(byte[] bytes, int offset, int value) {
        byte[] copy = bytes.clone();
        putU16(copy, offset, value);
        return copy;
    }

    static byte[] withU32(byte[] bytes, int offset, long value) {
        byte[] copy = bytes.clone();
        putU32(copy, offset, value);
        return copy;
    }

    static byte[] truncated(byte[] bytes, int newLength) {
        return Arrays.copyOf(bytes, newLength);
    }

    static int u16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | (bytes[offset + 1] & 0xff) << 8;
    }

    static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | (bytes[offset + 1] & 0xffL) << 8
                | (bytes[offset + 2] & 0xffL) << 16
                | (bytes[offset + 3] & 0xffL) << 24;
    }

    static void putU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    static void putU32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static byte[] deflateWithFinalEmptyBlockAfterInputBoundary(byte[] data) throws IOException {
        if (data.length != NON_FINAL_STORED_BLOCK_DATA_SIZE) {
            throw new IllegalArgumentException("invalid-input-boundary-deflate-size");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(DEFLATE_INPUT_CHUNK_SIZE + 5);
        output.write(0x00); // non-final stored block, already byte-aligned
        le16(output, data.length);
        le16(output, ~data.length);
        output.write(data);
        if (output.size() != DEFLATE_INPUT_CHUNK_SIZE) {
            throw new IllegalStateException("deflate-input-boundary-mismatch");
        }
        output.write(0x01); // final empty stored block starts in the next verifier input chunk
        le16(output, 0);
        le16(output, 0xffff);
        return output.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(6, true);
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[256];
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            if (count <= 0) {
                throw new IllegalStateException("deflate-stalled");
            }
            output.write(buffer, 0, count);
        }
        deflater.end();
        return output.toByteArray();
    }

    private static void le16(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value);
        output.write(value >>> 8);
    }

    private static void le32(ByteArrayOutputStream output, long value) throws IOException {
        output.write((int) value);
        output.write((int) (value >>> 8));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 24));
    }

    private record Pending(
            EntrySpec spec,
            byte[] name,
            byte[] compressed,
            long crc,
            int localOffset,
            int localFlagsOffset,
            int localMethodOffset,
            int localCrcOffset,
            int localCompressedOffset,
            int localUncompressedOffset,
            int localNameOffset,
            int payloadOffset,
            int descriptorOffset,
            int flags,
            long declaredUncompressedSize) { }
}
