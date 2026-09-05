package cn.jia.economy.config.skillseed;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                String skill = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(skill.startsWith("---\nname: " + product.skillKey()
                        + "\nversion: 1.0.0\n---\n"), product.skillKey());
                assertFalse(zip.getNextEntry() != null, product.skillKey());
            }
        }
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
