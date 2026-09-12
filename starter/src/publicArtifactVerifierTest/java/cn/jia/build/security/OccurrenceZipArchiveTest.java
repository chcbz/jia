package cn.jia.build.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OccurrenceZipArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void zip01ZeroByteStopsAtEocd() throws Exception {
        assertOpenFailure(new byte[0], PublicArtifactVerifier.FailureCode.EOCD_ERROR);
    }

    @Test
    void zip02TruncatedTerminalEocdStopsAtEocd() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        assertOpenFailure(ZipFixtureBuilder.truncated(valid.copy(), valid.eocdOffset() + 10),
                PublicArtifactVerifier.FailureCode.EOCD_ERROR);
    }

    @Test
    void zip03CentralSignatureMutationIsCentralDirectoryError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        byte[] mutated = ZipFixtureBuilder.withByte(valid.copy(), valid.centralOffset(), 0x51);
        assertOpenFailure(mutated, PublicArtifactVerifier.FailureCode.CENTRAL_DIRECTORY_ERROR);
    }

    @Test
    void zip04LocalSignatureMutationIsLocalHeaderError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        byte[] mutated = ZipFixtureBuilder.withByte(valid.copy(), valid.layouts().get(0).localOffset(), 0x51);
        assertOpenFailure(mutated, PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
    }

    @Test
    void zip05LocalMethodMutationIsOccurrenceMismatch() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        byte[] mutated = ZipFixtureBuilder.withU16(valid.copy(), layout.localMethodOffset(), 8);
        assertOpenFailure(mutated, PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
    }

    @Test
    void zip06StoredPayloadMutationIsPayloadIntegrityError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        byte[] mutated = ZipFixtureBuilder.withByte(valid.copy(), layout.payloadOffset(), 'V');
        assertPayloadFailure(mutated, PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
    }

    @Test
    void zip07CentralCrcMutationIsOccurrenceMismatch() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        long changed = ZipFixtureBuilder.u32(valid.copy(), layout.centralCrcOffset()) ^ 1;
        assertOpenFailure(ZipFixtureBuilder.withU32(valid.copy(), layout.centralCrcOffset(), changed),
                PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
    }

    @Test
    void zip08EqualWrongLocalAndCentralCrcIsPayloadIntegrityError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        long wrong = ZipFixtureBuilder.u32(valid.copy(), layout.centralCrcOffset()) ^ 1;
        byte[] mutated = ZipFixtureBuilder.withU32(valid.copy(), layout.localCrcOffset(), wrong);
        mutated = ZipFixtureBuilder.withU32(mutated, layout.centralCrcOffset(), wrong);
        assertPayloadFailure(mutated, PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
    }

    @Test
    void zip09SignedDescriptorMutationIsDescriptorError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", "valid".getBytes()).stored()
                        .descriptor(ZipFixtureBuilder.Descriptor.SIGNED))
                .build();
        int crcOffset = valid.layouts().get(0).descriptorOffset() + 4;
        assertOpenFailure(ZipFixtureBuilder.withU32(valid.copy(), crcOffset,
                        ZipFixtureBuilder.u32(valid.copy(), crcOffset) ^ 1),
                PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
    }

    @Test
    void zip10UnsignedDescriptorMutationIsDescriptorError() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", "valid".getBytes()).deflated()
                        .descriptor(ZipFixtureBuilder.Descriptor.UNSIGNED))
                .build();
        int sizeOffset = valid.layouts().get(0).descriptorOffset() + 4;
        assertOpenFailure(ZipFixtureBuilder.withU32(valid.copy(), sizeOffset,
                        ZipFixtureBuilder.u32(valid.copy(), sizeOffset) + 1),
                PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
    }

    @Test
    void zip11DescriptorGapIsDescriptorError() throws Exception {
        byte[] mutated = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", "valid".getBytes()).stored()
                        .descriptor(ZipFixtureBuilder.Descriptor.SIGNED))
                .gapAfterLocals(1)
                .build()
                .copy();
        assertOpenFailure(mutated, PublicArtifactVerifier.FailureCode.DATA_DESCRIPTOR_ERROR);
    }

    @Test
    void zip12DuplicateNamesAreReadByExactOccurrence() throws Exception {
        ZipFixtureBuilder.BuiltZip duplicate = new ZipFixtureBuilder()
                .add("config/application.yaml", "api-key: live-secret\n")
                .add("config/application.yaml", "api-key: ${PUBLIC_API_KEY}\n")
                .build();
        Path path = write(duplicate.copy());
        try (OccurrenceZipArchive archive = OccurrenceZipArchive.open(path)) {
            List<OccurrenceZipArchive.Occurrence> occurrences = archive.occurrences();
            assertEquals(2, occurrences.size());
            assertEquals(List.of("api-key"), classify(archive, occurrences.get(0)));
            assertEquals(List.of(), classify(archive, occurrences.get(1)));
            assertEquals(0, occurrences.get(0).id().centralDirectoryOrdinal());
            assertEquals(1, occurrences.get(1).id().centralDirectoryOrdinal());
            assertTrue(occurrences.get(0).id().localHeaderOffset()
                    < occurrences.get(1).id().localHeaderOffset());
        }
    }

    @Test
    void zip13CollidingCrcAndSizeDoNotBecomeOccurrenceIdentity() throws Exception {
        CRC32 unsafeCrc = new CRC32();
        unsafeCrc.update(ZipFixtureBuilder.COLLIDING_UNSAFE_PROPERTIES.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CRC32 safeCrc = new CRC32();
        safeCrc.update(ZipFixtureBuilder.COLLIDING_SAFE_PROPERTIES.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(ZipFixtureBuilder.COLLIDING_CRC32, unsafeCrc.getValue());
        assertEquals(unsafeCrc.getValue(), safeCrc.getValue());
        assertEquals(ZipFixtureBuilder.COLLIDING_UNSAFE_PROPERTIES.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                ZipFixtureBuilder.COLLIDING_SAFE_PROPERTIES.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

        ZipFixtureBuilder.BuiltZip duplicate = new ZipFixtureBuilder()
                .add("config/application.properties", ZipFixtureBuilder.COLLIDING_UNSAFE_PROPERTIES)
                .add("config/application.properties", ZipFixtureBuilder.COLLIDING_SAFE_PROPERTIES)
                .build();
        try (OccurrenceZipArchive archive = OccurrenceZipArchive.open(write(duplicate.copy()))) {
            assertEquals(List.of("api-key"), classify(archive, archive.occurrences().get(0)));
            assertEquals(List.of(), classify(archive, archive.occurrences().get(1)));
        }
    }

    @Test
    void zip14Zip64SentinelIsUnsupported() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        byte[] sentinel = ZipFixtureBuilder.withU32(valid.copy(), valid.eocdOffset() + 12, 0xffffffffL);
        assertOpenFailure(sentinel, PublicArtifactVerifier.FailureCode.UNSUPPORTED);

        byte[] locator = new byte[valid.bytes().length + 20];
        System.arraycopy(valid.bytes(), 0, locator, 0, valid.eocdOffset());
        ZipFixtureBuilder.putU32(locator, valid.eocdOffset(), 0x07064b50L);
        System.arraycopy(valid.bytes(), valid.eocdOffset(), locator, valid.eocdOffset() + 20,
                valid.bytes().length - valid.eocdOffset());
        assertOpenFailure(locator, PublicArtifactVerifier.FailureCode.UNSUPPORTED);
    }

    @Test
    void zip15EncryptionAndUnknownMethodsAreUnsupported() throws Exception {
        ZipFixtureBuilder.BuiltZip encrypted = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", "valid".getBytes()).stored().extraFlags(1))
                .build();
        assertOpenFailure(encrypted.copy(), PublicArtifactVerifier.FailureCode.UNSUPPORTED);

        ZipFixtureBuilder.BuiltZip valid = singleStored("payload.bin", "valid");
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        byte[] method = ZipFixtureBuilder.withU16(valid.copy(), layout.centralMethodOffset(), 99);
        assertOpenFailure(method, PublicArtifactVerifier.FailureCode.UNSUPPORTED);
    }

    @Test
    void redundantLocalZip64SizesAcceptStoredAndDeflatedPayloads() throws Exception {
        for (boolean deflated : List.of(false, true)) {
            ZipFixtureBuilder.EntrySpec entry = new ZipFixtureBuilder.EntrySpec(
                    "config.properties", "api-key=zip-secret-canary\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .redundantLocalSizes();
            if (deflated) {
                entry.deflated();
            }
            try (OccurrenceZipArchive archive = OccurrenceZipArchive.open(write(
                    new ZipFixtureBuilder().add(entry).build().copy()))) {
                assertEquals(List.of("api-key"), classify(archive, archive.occurrences().get(0)));
            }
        }
    }

    @Test
    void redundantLocalZip64RejectsConflictsHighBitsAndSentinels() throws Exception {
        ZipFixtureBuilder.BuiltZip valid = new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", new byte[]{1, 2, 3})
                        .redundantLocalSizes()).build();
        ZipFixtureBuilder.Layout layout = valid.layouts().get(0);
        int extra = layout.payloadOffset() - 20;
        for (int offset : List.of(extra + 4, extra + 12, extra + 11, extra + 19)) {
            assertOpenFailure(ZipFixtureBuilder.withByte(valid.copy(), offset, 0x80),
                    PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        for (int offset : List.of(layout.localCompressedSizeOffset(), layout.localUncompressedSizeOffset(),
                layout.centralCompressedSizeOffset(), layout.centralUncompressedSizeOffset())) {
            assertOpenFailure(ZipFixtureBuilder.withU32(valid.copy(), offset, 0xffffffffL),
                    PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        // Matching local64 and local32 is insufficient when central32 disagrees.
        byte[] inconsistent = ZipFixtureBuilder.withU32(valid.copy(), layout.localUncompressedSizeOffset(), 2);
        ZipFixtureBuilder.putU32(inconsistent, extra + 4, 2);
        assertOpenFailure(inconsistent, PublicArtifactVerifier.FailureCode.OCCURRENCE_MISMATCH);
        assertPayloadFailure(ZipFixtureBuilder.withByte(valid.copy(), layout.payloadOffset(), 0x7f),
                PublicArtifactVerifier.FailureCode.PAYLOAD_INTEGRITY_ERROR);
    }

    @Test
    void redundantLocalZip64RejectsDuplicatesWrongLengthsTruncationCentralAndDescriptors() throws Exception {
        byte[] validExtra = new byte[20];
        ZipFixtureBuilder.putU16(validExtra, 0, 1);
        ZipFixtureBuilder.putU16(validExtra, 2, 16);
        ZipFixtureBuilder.putU32(validExtra, 4, 3);
        ZipFixtureBuilder.putU32(validExtra, 12, 3);
        byte[] duplicate = new byte[40];
        System.arraycopy(validExtra, 0, duplicate, 0, 20);
        System.arraycopy(validExtra, 0, duplicate, 20, 20);
        byte[] shortExtra = java.util.Arrays.copyOf(validExtra, 12);
        ZipFixtureBuilder.putU16(shortExtra, 2, 8);
        byte[] longExtra = java.util.Arrays.copyOf(validExtra, 28);
        ZipFixtureBuilder.putU16(longExtra, 2, 24);
        for (byte[] extra : List.of(duplicate, shortExtra, longExtra)) {
            assertOpenFailure(new ZipFixtureBuilder()
                    .add(new ZipFixtureBuilder.EntrySpec("payload.bin", new byte[]{1, 2, 3}).localExtra(extra))
                    .build().copy(), PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
        assertOpenFailure(new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", new byte[]{1, 2, 3})
                        .localExtra(java.util.Arrays.copyOf(validExtra, 19)))
                .build().copy(), PublicArtifactVerifier.FailureCode.LOCAL_HEADER_ERROR);
        assertOpenFailure(new ZipFixtureBuilder()
                .add(new ZipFixtureBuilder.EntrySpec("payload.bin", new byte[]{1, 2, 3}).centralExtra(validExtra))
                .build().copy(), PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        for (ZipFixtureBuilder.Descriptor descriptor : List.of(
                ZipFixtureBuilder.Descriptor.SIGNED, ZipFixtureBuilder.Descriptor.UNSIGNED)) {
            assertOpenFailure(new ZipFixtureBuilder()
                    .add(new ZipFixtureBuilder.EntrySpec("payload.bin", new byte[]{1, 2, 3})
                            .redundantLocalSizes().descriptor(descriptor))
                    .build().copy(), PublicArtifactVerifier.FailureCode.UNSUPPORTED);
        }
    }

    @Test
    void structurallyValidEmptyArchiveIsAccepted() throws Exception {
        ZipFixtureBuilder.BuiltZip empty = new ZipFixtureBuilder().build();
        try (OccurrenceZipArchive archive = OccurrenceZipArchive.open(write(empty.copy()))) {
            assertTrue(archive.occurrences().isEmpty());
        }
    }

    private ZipFixtureBuilder.BuiltZip singleStored(String name, String value) {
        return new ZipFixtureBuilder().add(name, value).build();
    }

    private List<String> classify(
            OccurrenceZipArchive archive,
            OccurrenceZipArchive.Occurrence occurrence) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        archive.copyPayload(occurrence, output, PublicArtifactVerifier.CONFIGURATION_LIMIT);
        List<ConfigurationSecurityClassifier.Match> matches =
                ConfigurationSecurityClassifier.classify(occurrence.name(), output.toByteArray());
        for (ConfigurationSecurityClassifier.Match match : matches) {
            assertEquals(PublicArtifactVerifier.FailureCode.API_KEY_VIOLATION, match.code());
        }
        return matches.stream().map(ConfigurationSecurityClassifier.Match::key).toList();
    }

    private void assertOpenFailure(byte[] bytes, PublicArtifactVerifier.FailureCode expected) throws Exception {
        try (OccurrenceZipArchive ignored = OccurrenceZipArchive.open(write(bytes))) {
            fail("archive unexpectedly opened");
        } catch (PublicArtifactVerifier.VerificationException failure) {
            assertEquals(expected, failure.code());
        }
    }

    private void assertPayloadFailure(byte[] bytes, PublicArtifactVerifier.FailureCode expected) throws Exception {
        try (OccurrenceZipArchive archive = OccurrenceZipArchive.open(write(bytes))) {
            try {
                archive.copyPayload(archive.occurrences().get(0), new ByteArrayOutputStream(), 1024);
                fail("payload unexpectedly passed");
            } catch (PublicArtifactVerifier.VerificationException failure) {
                assertEquals(expected, failure.code());
            }
        }
    }

    private Path write(byte[] bytes) throws IOException {
        Path path = Files.createTempFile(temporaryDirectory, "fixture-", ".zip");
        Files.write(path, bytes);
        return path;
    }
}
