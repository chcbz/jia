package cn.jia.economy.schema;

import cn.jia.economy.mapper.EconomyUsageCostMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyUsageCostSchemaTest {
    @Test void costSchemaIsSeparateRmbAndHasNoBusinessCheckOrWalletReference() throws Exception {
        String ddl = Files.readString(Path.of("src/main/resources/db/economy-usage-cost.sql")).toLowerCase(Locale.ROOT);
        assertEquals(3, ddl.split("create table if not exists", -1).length - 1);
        assertEquals(3, ddl.split("decimal\\(30,6\\)", -1).length - 1);
        assertTrue(ddl.contains("unique key uk_usage_revision_receipt"));
        assertEquals(1, ddl.split("task_id varchar\\(100\\) default null", -1).length - 1);
        assertEquals(1, ddl.split("owner_jiacn varchar\\(100\\) default null", -1).length - 1);
        assertEquals(1, ddl.split("owner_jiacn varchar\\(100\\) not null", -1).length - 1);
        assertTrue(ddl.contains("foreign key (tenant_id, client_id, attempt_id)"));
        for (String forbidden : new String[]{"check (", "create trigger", "double", "float", "amount_wen", "economy_account", "drop table"})
            assertFalse(ddl.contains(forbidden), forbidden);
    }
    @Test void revisionMapperIsAppendOnly() {
        for (var method : EconomyUsageCostMapper.class.getDeclaredMethods())
            if (method.getName().toLowerCase(Locale.ROOT).contains("revision")) assertEquals("insertRevision", method.getName());
    }
}
