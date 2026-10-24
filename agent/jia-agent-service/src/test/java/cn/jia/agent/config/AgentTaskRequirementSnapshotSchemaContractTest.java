package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentTaskRequirementSnapshotSchemaContractTest {
    @Test void exactScopeRevisionAndFullTextArePersistedWithoutLegacyBackfill() throws Exception {
        String ddl=new ClassPathResource("db/agent-task-requirement-snapshot-v1.sql")
                .getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(ddl.contains("unique key uk_atrs_scope_revision "
                + "(tenant_id,client_id,owner_jiacn,task_id,revision)"));
        assertTrue(ddl.contains("unique key uk_atrs_confirmation "
                + "(tenant_id,client_id,owner_jiacn,task_id,confirmation_id)"));
        assertTrue(ddl.contains("title mediumtext"));
        assertTrue(ddl.contains("description mediumtext"));
        assertTrue(ddl.contains("content_sha256 char(64)"));
        assertTrue(ddl.contains("tenant_id is not null"));
        assertTrue(ddl.contains("tenant_id regexp binary '^(0|[1-9][0-9]{0,49})$'"));
        assertTrue(ddl.contains("tenant_id not regexp binary '[^0-9]'"));
        assertTrue(ddl.contains("instr(tenant_id, char(10)) = 0"));
        assertTrue(ddl.contains("instr(tenant_id, char(13)) = 0"));
        assertTrue(ddl.contains("owner_jiacn<>'0'"));
        assertFalse(ddl.contains("insert into task_plan"));
        assertFalse(ddl.contains("update agent_task_requirement_snapshot"));
    }

    @Test void canonicalScopeCatalogRejectsNullableOrWeakenedTenantCheck() {
        var rows = checkRows();
        AgentTaskRequirementSnapshotSchemaInitializer.validateChecks(rows);

        var nullable = checkRows();
        nullable.getFirst().compute("CHECK_CLAUSE",
                (ignored, clause) -> clause.toString().replace("(`tenant_id` is not null) and ", ""));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskRequirementSnapshotSchemaInitializer.validateChecks(nullable));

        var newlineAllowed = checkRows();
        newlineAllowed.getFirst().compute("CHECK_CLAUSE",
                (ignored, clause) -> clause.toString().replace(
                        " and (instr(`tenant_id`,char(10)) = 0)", ""));
        assertThrows(IllegalStateException.class,
                () -> AgentTaskRequirementSnapshotSchemaInitializer.validateChecks(newlineAllowed));
    }

    @Test void partialOwnerIndexWithCorrectNameFailsStructuralCheck() {
        var indexes=List.<Map<String,Object>>of(Map.of("index_name","uk_atrs_scope_revision",
                "non_unique",0,"seq_in_index",1,"column_name","tenant_id","sub_part",0));
        assertThrows(IllegalStateException.class, () -> AgentTaskRequirementSnapshotSchemaInitializer.index(indexes,
                "uk_atrs_scope_revision",0,
                List.of("tenant_id","client_id","owner_jiacn","task_id","revision")));
    }

    private static List<Map<String, Object>> checkRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(check("chk_atrs_scope", "((`tenant_id` is not null) and "
                + "regexp_like(`tenant_id`,cast(_utf8mb4\\'^(0|[1-9][0-9]{0,49})$\\' "
                + "as char charset binary)) and not(regexp_like(`tenant_id`,"
                + "cast(_utf8mb4\\'[^0-9]\\' as char charset binary))) and "
                + "(instr(`tenant_id`,char(10)) = 0) and "
                + "(instr(`tenant_id`,char(13)) = 0) and (`owner_jiacn` <> _utf8mb4\\'0\\'))"));
        rows.add(check("chk_atrs_content", "((`revision` >= 1) and "
                + "(`task_version_at_confirmation` >= 0) and (char_length(`title`) > 0) "
                + "and regexp_like(`content_sha256`,_utf8mb4\\'^[0-9a-f]{64}$\\') and "
                + "(`source` in (_utf8mb4\\'CREATE\\',_utf8mb4\\'RECONFIRM\\')) and "
                + "(`created_at` > 0))"));
        return rows;
    }

    private static Map<String, Object> check(String name, String clause) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("CONSTRAINT_NAME", name);
        row.put("ENFORCED", "YES");
        row.put("CHECK_CLAUSE", clause);
        return row;
    }
}
