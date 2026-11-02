package cn.jia.chat.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatTaskDeliveryReviewSchemaInitializerTest {
    @Test void freshSchemaIncludesAllImmutableMessageLineageAndScopedUniqueness() {
        String ddl = ChatTaskDeliveryReviewSchemaInitializer.ddl();
        var columns = ChatTaskDeliveryReviewSchemaInitializer.columns(ddl);
        assertEquals(23,columns.size());
        assertEquals("char(64)|NO|ascii_bin", columns.get("request_sha256"));
        assertEquals("char(64)|NO|ascii_bin", columns.get("batch_sha256")); assertEquals("varchar(100)|YES|utf8mb4_0900_bin",columns.get("child_request_id"));
        assertTrue(ddl.contains("uk_ctdro_source (tenant_id,owner_jiacn,client_id,task_id,source_request_id,decision)"));
        assertFalse(ddl.toLowerCase().contains("check ("));
    }

}
