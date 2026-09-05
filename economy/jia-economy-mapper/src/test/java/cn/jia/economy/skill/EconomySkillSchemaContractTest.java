package cn.jia.economy.skill;

import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySkillSchemaContractTest {
    private static final Set<String> MUTABLE_METHODS = Set.of(
            "publishProductVersion",
            "markOrderInstalling", "markOrderActive", "markOrderRefunded",
            "markInstallationInstalling", "markInstallationSucceeded", "markInstallationFailed",
            "markEntitlementActive", "markEntitlementFailed");

    @Test
    void ddlIsSevenTableAdditiveSkillOnlyFoundationWithoutSeedsOrMoneyEffects() throws Exception {
        String sql = Files.readString(
                Path.of("src/main/resources/db/economy-v0-skill-marketplace.sql"), StandardCharsets.UTF_8);
        String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String table : List.of(
                "economy_skill_product", "economy_skill_product_version",
                "economy_skill_purchase_quote", "economy_skill_order",
                "economy_skill_order_receipt", "economy_skill_installation",
                "economy_skill_entitlement")) {
            assertEquals(1, occurrences(normalized, "create table if not exists " + table + " ("), table);
        }
        assertEquals(7, occurrences(normalized, "create table if not exists economy_skill_"));
        assertFalse(normalized.contains("create table if not exists economy_account"));
        assertFalse(normalized.contains("insert into"));
        assertFalse(normalized.contains("update economy_"));
        assertFalse(normalized.contains("delete from"));
        assertFalse(normalized.contains("drop "));
        assertFalse(normalized.contains("alter table"));
        assertFalse(normalized.contains("create trigger"));
        assertTrue(normalized.contains("price_micro bigint not null"));
        assertTrue(normalized.contains("price_micro >= 0"));
        assertTrue(normalized.contains("seller_type = 'system' and seller_id = 'skill_store'"));
        assertTrue(normalized.contains("skill_key <> 'deploy-runner' or deployment_restriction = 'admin_only'"));
        assertTrue(normalized.contains("target_agent_id varchar(100) not null"));
        assertTrue(normalized.contains("idempotency_key varbinary(36) not null"));
        assertTrue(normalized.contains("request_hash binary(32) not null"));
        assertTrue(normalized.contains("request_id = message_id"));
        assertTrue(normalized.contains("command_type = 'skill_install'"));
        assertTrue(normalized.contains("download_path = concat('/internal/agent/skill-installations/', installation_id, '/package')"));
        assertTrue(normalized.contains("unique key uk_skill_entitlement_skill (tenant_id, client_id, target_agent_id, skill_key, skill_version)"));
        assertTrue(normalized.contains("unique key uk_skill_entitlement_install (tenant_id, client_id, target_agent_id, installation_id)"));
        assertTrue(normalized.contains("key idx_skill_receipt_buyer_order (tenant_id, client_id, actor_type, actor_id, order_id)"));
        assertTrue(normalized.contains("key idx_skill_installation_target_order (tenant_id, client_id, target_agent_id, order_id)"));
        assertTrue(normalized.contains("key idx_skill_installation_package_envelope ( tenant_id, client_id, product_version_id, skill_key, skill_version, package_size, package_sha256 )"));
        assertTrue(normalized.contains("key idx_skill_entitlement_target_order (tenant_id, client_id, target_agent_id, order_id)"));
        assertTrue(normalized.contains("key idx_skill_entitlement_install_ref ( tenant_id, client_id, target_agent_id, installation_id, product_version_id, skill_key, skill_version )"));
    }

    @Test
    void immutableVersionsQuotesAndReceiptsExposeNoMutationOrDeleteMapperMethods() {
        for (Method method : EconomySkillMarketplaceMapper.class.getDeclaredMethods()) {
            assertFalse(method.isAnnotationPresent(Delete.class), method.toString());
            if (method.isAnnotationPresent(Update.class)) {
                assertTrue(MUTABLE_METHODS.contains(method.getName()), method.toString());
            }
            String lower = method.getName().toLowerCase(Locale.ROOT);
            if (lower.contains("productversion") || lower.contains("purchasequote") || lower.contains("receipt")) {
                assertFalse(method.isAnnotationPresent(Update.class), method.toString());
                assertFalse(lower.startsWith("delete"), method.toString());
            }
        }
    }

    @Test
    void everyReadAndCasMutationCarriesExactTenantClientScope() {
        for (Method method : EconomySkillMarketplaceMapper.class.getDeclaredMethods()) {
            String sql = null;
            Select select = method.getAnnotation(Select.class);
            Update update = method.getAnnotation(Update.class);
            if (select != null) sql = String.join(" ", select.value());
            if (update != null) sql = String.join(" ", update.value());
            if (sql == null) continue;
            assertTrue(sql.contains("tenant_id=#{tenantId}"), method.toString());
            assertTrue(sql.contains("client_id=#{clientId}"), method.toString());
            assertTrue(sql.contains("OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"), method.toString());
            assertTrue(sql.contains("OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId})"), method.toString());
            if (update != null && !method.getName().equals("publishProductVersion")) {
                assertTrue(sql.contains("version=#{expectedVersion}"), method.toString());
            }
        }
        Method versionLock = method("selectApprovedProductVersionForUpdate");
        assertNotNull(versionLock.getAnnotation(Select.class));
        assertTrue(String.join(" ", versionLock.getAnnotation(Select.class).value()).contains("FOR UPDATE"));
    }

    private Method method(String name) {
        return java.util.Arrays.stream(EconomySkillMarketplaceMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }
}
