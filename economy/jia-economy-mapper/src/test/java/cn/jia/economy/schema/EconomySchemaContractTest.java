package cn.jia.economy.schema;

import cn.jia.economy.mapper.EconomyLedgerMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EconomySchemaContractTest {
    @Test
    void canonicalAndNamedFoundationDdlRemainByteIdentical() throws Exception {
        Path resources = Path.of("src/main/resources/db");
        assertEquals(
                Files.readString(resources.resolve("schema.sql"), StandardCharsets.UTF_8),
                Files.readString(resources.resolve("economy-v0-foundation.sql"), StandardCharsets.UTF_8));
    }

    @Test
    void schemaContainsExactFiveTablesAndNonDestructiveBootstrapConstraints() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/schema.sql"), StandardCharsets.UTF_8);
        String normalized = sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String table : List.of("economy_account", "economy_transaction", "economy_entry",
                "economy_escrow", "economy_escrow_funding_lot")) {
            assertEquals(1, occurrences(normalized, "create table if not exists " + table + " ("), table);
        }
        assertTrue(normalized.contains("idempotency_key varbinary(36) not null"));
        assertTrue(normalized.contains("request_hash binary(32) not null"));
        assertTrue(normalized.contains("signed_amount_micro bigint not null"));
        assertTrue(normalized.contains("captured_micro <= gross_micro - refunded_micro"));
        assertTrue(normalized.contains("owner_type = 'system'"));
        assertTrue(normalized.contains("purpose in ('silver_issuance','provider_variance')"));
        assertTrue(normalized.contains("unique key uk_economy_transaction_idempotency (tenant_id, client_id, principal_type, principal_id, idempotency_key)"));
        assertTrue(normalized.contains("unique key uk_economy_escrow_business (tenant_id, client_id, business_type, business_id)"));
        assertFalse(normalized.contains("drop trigger"));
        assertFalse(normalized.contains("create trigger"));
        assertEquals(0, occurrences(normalized, "signal sqlstate '45000'"));
        assertFalse(normalized.contains("then null"));
        assertFalse(normalized.contains("set new.status"));
        assertFalse(normalized.contains("decimal("));
        assertFalse(normalized.contains("double"));
        assertFalse(normalized.contains("float"));
    }

    @Test
    void curatedMapperExposesNoUpdateOrDeleteForPostedEntriesOrFundingLots() {
        for (Method method : EconomyLedgerMapper.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            boolean immutableTarget = name.contains("entry") || name.contains("fundinglot");
            if (immutableTarget) {
                assertFalse(name.startsWith("update") || name.startsWith("delete"), method.toString());
                assertFalse(method.isAnnotationPresent(Update.class), method.toString());
                assertFalse(method.isAnnotationPresent(Delete.class), method.toString());
            }
        }
        assertTrue(Arrays.stream(EconomyLedgerMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("selectAccountForUpdate"))
                .findFirst().orElseThrow().getAnnotation(org.apache.ibatis.annotations.Select.class)
                .value()[0].contains("FOR UPDATE"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }
}
