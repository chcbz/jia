package cn.jia.chat.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

class ChatSelectedOutputFinalizationSchemaInitializerTest {
    @Test
    void schemaIsAdditiveBinaryScopedAndTerminalInvariantIsDatabaseChecked(){
        var ddl=ChatSelectedOutputFinalizationSchemaInitializer.ddl();assertEquals(2,ddl.size());
        String sql=String.join("\n",ddl).toLowerCase();assertTrue(sql.contains("utf8mb4_0900_bin"));
        assertTrue(sql.contains("uk_csof_request"));assertTrue(sql.contains("state_version>0"));
        assertTrue(sql.contains("between 8 and 160"));assertTrue(sql.contains("9007199254740991"));
        assertTrue(sql.contains("chk_csof_progress"));assertTrue(sql.contains("chk_csof_outcome"));
        assertTrue(sql.contains("delivery_state='accepted'"));assertTrue(sql.contains("task_state='completed'"));
        assertTrue(sql.contains("changes_requested"));assertFalse(sql.contains("alter table"));
    }

    @Test
    void initializerRequiresExplicitCoreThenDeliberationReadinessBoundary() throws Exception {
        assertNotNull(ChatSelectedOutputFinalizationSchemaInitializer.class.getConstructor(
                JdbcTemplate.class,ChatSchemaReadiness.class));
    }
}
