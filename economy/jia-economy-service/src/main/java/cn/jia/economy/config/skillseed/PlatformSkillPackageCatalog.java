package cn.jia.economy.config.skillseed;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable V0 platform catalog definitions and the exact bundled package bytes. */
public final class PlatformSkillPackageCatalog {
    public static final String SKILL_VERSION = "1.0.0";
    public static final String APPROVED_PERMISSIONS_MANIFEST = "[]";
    public static final String SELLER_TYPE = "SYSTEM";
    public static final String SELLER_ID = "SKILL_STORE";

    private static final List<PlatformSkillProduct> PRODUCTS = List.of(
            product("sp_repo_inspector", "spv_repo_inspector_1_0_0", "repo-inspector",
                    "Repository Inspector", "Read-only repository inspection for structure and implementation facts.",
                    0L, "NONE", "e4a2bb2215d1a7f885fa483838cff4829bcc9c5e72fc5c0063e24d90c79eaba9", 551L),
            product("sp_code_reviewer", "spv_code_reviewer_1_0_0", "code-reviewer",
                    "Code Reviewer", "Bounded contract and compatibility review with actionable findings.",
                    20_000_000L, "NONE", "ea45ab9231048a0fdcaa294e392fbfb1c0818254c64316297d9975e89e940b02", 550L),
            product("sp_repo_test", "spv_repo_test_1_0_0", "repo-test",
                    "Repository Test", "Focused repository verification with attributable test evidence.",
                    30_000_000L, "NONE", "bf4e3c922f4ca7820e908c75c6fb3576568f9c326a2947e599f8fdff55b74407", 522L),
            product("sp_code_editor", "spv_code_editor_1_0_0", "code-editor",
                    "Code Editor", "Scoped implementation work that preserves the frozen contract.",
                    50_000_000L, "NONE", "15adfd2adf6ec787187957aa19815427cfd2c8598d398468d39710d2dbf33858", 547L),
            product("sp_web_builder", "spv_web_builder_1_0_0", "web-builder",
                    "Web Builder", "Bounded web implementation using established application flows.",
                    60_000_000L, "NONE", "d78884cc1cde2f154aa701b39bcf5b8d810efb20156ace5e17de6df90d4b0b7b", 580L),
            product("sp_deploy_runner", "spv_deploy_runner_1_0_0", "deploy-runner",
                    "Deploy Runner", "Explicitly authorized deployment runbook execution with evidence.",
                    100_000_000L, "ADMIN_ONLY", "71ce8b2387c1c7b1df9f0edb40b18a90ed4680e394c6f4a9749bfd6244be054e", 575L));
    private static final Map<String, PlatformSkillProduct> BY_PRODUCT_ID = indexByProductId();
    private static final Map<String, PlatformSkillProduct> BY_PRODUCT_VERSION_ID = indexByProductVersionId();

    public List<PlatformSkillProduct> products() {
        return PRODUCTS;
    }

    public PlatformSkillProduct requireProduct(String productId) {
        PlatformSkillProduct product = BY_PRODUCT_ID.get(productId);
        if (product == null) throw new IllegalArgumentException("Unknown platform skill product " + productId);
        return product;
    }

    public PlatformSkillProduct requireProductVersion(String productVersionId) {
        PlatformSkillProduct product = BY_PRODUCT_VERSION_ID.get(productVersionId);
        if (product == null) throw new IllegalArgumentException("Unknown platform skill product version " + productVersionId);
        return product;
    }

    /** Loads and verifies the exact bytes W09 must expose for a seeded product version. */
    public PlatformSkillPackage requirePackage(String productVersionId) {
        PlatformSkillProduct product = requireProductVersion(productVersionId);
        byte[] bytes = readResource(product.resourcePath());
        if (bytes.length != product.packageSize() || !Arrays.equals(sha256(bytes), product.packageSha256())) {
            throw new IllegalStateException("Bundled platform skill package does not match frozen catalog metadata: "
                    + product.productVersionId());
        }
        return new PlatformSkillPackage(product, bytes);
    }

    private static PlatformSkillProduct product(
            String productId, String productVersionId, String skillKey, String name, String description,
            long priceMicro, String deploymentRestriction, String packageSha256Hex, long packageSize) {
        return new PlatformSkillProduct(productId, productVersionId, skillKey, SKILL_VERSION, name, description,
                priceMicro, deploymentRestriction, "skill-packages/" + skillKey + "-" + SKILL_VERSION + ".zip",
                hex(packageSha256Hex), packageSize, APPROVED_PERMISSIONS_MANIFEST,
                sha256(APPROVED_PERMISSIONS_MANIFEST.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, PlatformSkillProduct> indexByProductId() {
        Map<String, PlatformSkillProduct> result = new LinkedHashMap<>();
        for (PlatformSkillProduct product : PRODUCTS) {
            if (result.put(product.productId(), product) != null) throw new IllegalStateException("Duplicate product id");
        }
        return Map.copyOf(result);
    }

    private static Map<String, PlatformSkillProduct> indexByProductVersionId() {
        Map<String, PlatformSkillProduct> result = new LinkedHashMap<>();
        for (PlatformSkillProduct product : PRODUCTS) {
            if (result.put(product.productVersionId(), product) != null) throw new IllegalStateException("Duplicate product version id");
        }
        return Map.copyOf(result);
    }

    private static byte[] readResource(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        try (InputStream stream = resource.getInputStream()) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Missing bundled platform skill package " + resourcePath, exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hex(String value) {
        if (value.length() != 64) throw new IllegalArgumentException("Expected SHA-256 hex");
        byte[] result = new byte[32];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IllegalArgumentException("Expected lowercase hexadecimal SHA-256");
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public record PlatformSkillProduct(
            String productId,
            String productVersionId,
            String skillKey,
            String skillVersion,
            String name,
            String description,
            long priceMicro,
            String deploymentRestriction,
            String resourcePath,
            byte[] packageSha256,
            long packageSize,
            String approvedPermissionsManifest,
            byte[] approvedPermissionsSha256) {
        public PlatformSkillProduct {
            packageSha256 = Arrays.copyOf(packageSha256, packageSha256.length);
            approvedPermissionsSha256 = Arrays.copyOf(approvedPermissionsSha256, approvedPermissionsSha256.length);
        }

        @Override
        public byte[] packageSha256() {
            return Arrays.copyOf(packageSha256, packageSha256.length);
        }

        @Override
        public byte[] approvedPermissionsSha256() {
            return Arrays.copyOf(approvedPermissionsSha256, approvedPermissionsSha256.length);
        }
    }

    public record PlatformSkillPackage(PlatformSkillProduct product, byte[] bytes) {
        public PlatformSkillPackage {
            bytes = Arrays.copyOf(bytes, bytes.length);
        }

        @Override
        public byte[] bytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        public String packageDigest() {
            return "sha256:" + java.util.HexFormat.of().formatHex(product.packageSha256());
        }
    }
}
