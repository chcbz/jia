package cn.jia.economy.config.skillseed;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformSkillPackageCatalogTest {
    private final PlatformSkillPackageCatalog catalog = new PlatformSkillPackageCatalog();

    @Test
    void sixFrozenProductsExposeExactPricesRestrictionsAndDeterministicPackages() throws Exception {
        List<PlatformSkillPackageCatalog.PlatformSkillProduct> products = catalog.products();
        assertEquals(List.of("repo-inspector", "code-reviewer", "repo-test", "code-editor", "web-builder", "deploy-runner"),
                products.stream().map(PlatformSkillPackageCatalog.PlatformSkillProduct::skillKey).toList());
        assertEquals(List.of(0L, 20_000_000L, 30_000_000L, 50_000_000L, 60_000_000L, 100_000_000L),
                products.stream().map(PlatformSkillPackageCatalog.PlatformSkillProduct::priceMicro).toList());
        assertEquals(List.of("NONE", "NONE", "NONE", "NONE", "NONE", "ADMIN_ONLY"),
                products.stream().map(PlatformSkillPackageCatalog.PlatformSkillProduct::deploymentRestriction).toList());

        for (PlatformSkillPackageCatalog.PlatformSkillProduct product : products) {
            PlatformSkillPackageCatalog.PlatformSkillPackage first = catalog.requirePackage(product.productVersionId());
            PlatformSkillPackageCatalog.PlatformSkillPackage second = catalog.requirePackage(product.productVersionId());
            assertEquals(product.packageSize(), first.bytes().length, product.skillKey());
            assertArrayEquals(first.bytes(), second.bytes(), product.skillKey());
            assertEquals("sha256:" + java.util.HexFormat.of().formatHex(product.packageSha256()),
                    first.packageDigest(), product.skillKey());
            assertEquals("[]", product.approvedPermissionsManifest(), product.skillKey());

            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first.bytes()))) {
                assertEquals("SKILL.md", zip.getNextEntry().getName(), product.skillKey());
                byte[] skillBytes = zip.readAllBytes();
                String skill = new String(skillBytes, StandardCharsets.UTF_8);
                assertRequiredMetadata(skill, product);
                assertNull(zip.getNextEntry(), product.skillKey());
                // Reconstruct headers/CRC/offsets from the entry, not a second resource read.
                assertArrayEquals(first.bytes(), deterministicPackage(skillBytes), product.skillKey());
            }
        }
    }

    @Test
    void missingDescriptionIsRejectedEvenWhenTheBodyDescribesTheSkill() throws Exception {
        for (PlatformSkillPackageCatalog.PlatformSkillProduct product : catalog.products()) {
            try (ZipInputStream zip = new ZipInputStream(
                    new ByteArrayInputStream(catalog.requirePackage(product.productVersionId()).bytes()))) {
                zip.getNextEntry();
                String skill = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                String missingDescription = skill.replaceFirst("(?m)^description:.*\\n", "");
                assertThrows(AssertionError.class, () -> assertRequiredMetadata(missingDescription, product));
            }
        }
    }

    @Test
    void unsupportedTopLevelVersionAndMissingNestedVersionAreRejected() throws Exception {
        for (PlatformSkillPackageCatalog.PlatformSkillProduct product : catalog.products()) {
            try (ZipInputStream zip = new ZipInputStream(
                    new ByteArrayInputStream(catalog.requirePackage(product.productVersionId()).bytes()))) {
                zip.getNextEntry();
                String skill = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                String topLevelVersion = skill.replace("metadata:\n  version:", "version:");
                assertThrows(AssertionError.class, () -> assertRequiredMetadata(topLevelVersion, product));
                String missingVersion = skill.replace("  version: \"1.0.0\"\n", "");
                assertThrows(AssertionError.class, () -> assertRequiredMetadata(missingVersion, product));
            }
        }
    }

    private static void assertRequiredMetadata(
            String skill, PlatformSkillPackageCatalog.PlatformSkillProduct product) {
        Matcher frontmatter = Pattern.compile("\\A---\\n(.*?)\\n---(?:\\n|\\z)", Pattern.DOTALL).matcher(skill);
        assertTrue(frontmatter.find(), product.skillKey());
        // Deliberately restricted generated YAML schema, not a general YAML parser.
        Matcher metadata = Pattern.compile(
                "\\Aname: ([a-z0-9-]+)\\ndescription: \"([A-Za-z][A-Za-z0-9 ,.-]*)\""
                        + "\\nmetadata:\\n  version: \"([0-9A-Za-z.+-]+)\"\\z")
                .matcher(frontmatter.group(1));
        assertTrue(metadata.matches(), "Required name/description/metadata.version schema: " + product.skillKey());
        assertEquals(product.skillKey(), metadata.group(1));
        assertEquals(product.skillVersion(), metadata.group(3));
        String description = metadata.group(2);
        assertFalse(description.isBlank(), product.skillKey());
        assertTrue(description.length() <= 1024, product.skillKey());
    }

    /** Original W08 stored ZIP recipe: fixed DOS epoch, UTF-8, regular 0644 file, no extras/comments. */
    private static byte[] deterministicPackage(byte[] skillBytes) {
        byte[] name = "SKILL.md".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(skillBytes);
        int localSize = 30 + name.length + skillBytes.length;
        int centralSize = 46 + name.length;
        ByteBuffer zip = ByteBuffer.allocate(localSize + centralSize + 22).order(ByteOrder.LITTLE_ENDIAN);
        zip.putInt(0x04034b50);
        shorts(zip, 20, 0x0800, 0, 0, 0x0021);
        zip.putInt((int) crc.getValue()).putInt(skillBytes.length).putInt(skillBytes.length);
        shorts(zip, name.length, 0);
        zip.put(name).put(skillBytes);
        zip.putInt(0x02014b50);
        shorts(zip, 0x0314, 20, 0x0800, 0, 0, 0x0021);
        zip.putInt((int) crc.getValue()).putInt(skillBytes.length).putInt(skillBytes.length);
        shorts(zip, name.length, 0, 0, 0, 0);
        zip.putInt(0100644 << 16).putInt(0).put(name);
        zip.putInt(0x06054b50);
        shorts(zip, 0, 0, 1, 1);
        zip.putInt(centralSize).putInt(localSize);
        shorts(zip, 0);
        return zip.array();
    }

    private static void shorts(ByteBuffer target, int... values) {
        for (int value : values) target.putShort((short) value);
    }

    @Test
    void productAndVersionLookupAreExactAndUnknownIdsFailClosed() {
        PlatformSkillPackageCatalog.PlatformSkillProduct product = catalog.requireProduct("sp_repo_test");
        assertEquals("spv_repo_test_1_0_0", product.productVersionId());
        assertEquals(product, catalog.requireProductVersion(product.productVersionId()));
        assertThrows(IllegalArgumentException.class, () -> catalog.requireProduct("sp_unknown"));
        assertThrows(IllegalArgumentException.class, () -> catalog.requirePackage("spv_unknown"));
    }
}
