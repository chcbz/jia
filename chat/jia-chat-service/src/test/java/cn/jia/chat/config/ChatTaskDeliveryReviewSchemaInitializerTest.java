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
        assertEquals(8,ChatTaskDeliveryReviewSchemaInitializer.checks(ddl).size());
        assertTrue(ChatTaskDeliveryReviewSchemaInitializer.checks(ddl).get("chk_ctdro_origin").contains("child_request_idisnotnull"));
    }
    @Test void booleanComparisonAllowsServerFormattingButNeverLosesAndOrGroupingOrLiteralCase() {
        assertEquals(ChatTaskDeliveryReviewSchemaInitializer.expression("a = 'A' AND b > 0 AND c IS NULL"),
                ChatTaskDeliveryReviewSchemaInitializer.expression("((`a` = _utf8mb4'A') and ((`b` > 0) and (`c` is null)))"));
        assertNotEquals(ChatTaskDeliveryReviewSchemaInitializer.expression("(a=1 OR b=2) AND c=3"),
                ChatTaskDeliveryReviewSchemaInitializer.expression("a=1 OR (b=2 AND c=3)"));
        assertNotEquals(ChatTaskDeliveryReviewSchemaInitializer.expression("a='ACCEPT'"), ChatTaskDeliveryReviewSchemaInitializer.expression("a='accept'"));
    }
    @Test void actualMysqlEscapedMetadataPreservesAllCheckSemantics() {
        var expected=ChatTaskDeliveryReviewSchemaInitializer.expression("(state='ACCEPT' OR state='REJECT') AND version>0");
        assertEquals(expected,ChatTaskDeliveryReviewSchemaInitializer.metadataExpression(
                "((`state` = _utf8mb4\\'ACCEPT\\') or (`state` = _utf8mb4\\'REJECT\\')) and (`version` > 0)"));
        for(String changed:java.util.List.of("state='accept' OR state='REJECT' AND version>0",
                "(state='ACCEPT' OR state='REJECT') AND version>=0", "1=1 OR ((state='ACCEPT' OR state='REJECT') AND version>0)"))
            assertNotEquals(expected,ChatTaskDeliveryReviewSchemaInitializer.metadataExpression(changed.replace("'","\\'")));
    }

}
