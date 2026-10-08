package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
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
        assertFalse(ddl.contains("insert into task_plan"));
        assertFalse(ddl.contains("update agent_task_requirement_snapshot"));
    }

    @Test void partialOwnerIndexWithCorrectNameFailsStructuralCheck() {
        var indexes=List.<Map<String,Object>>of(Map.of("index_name","uk_atrs_scope_revision",
                "non_unique",0,"seq_in_index",1,"column_name","tenant_id","sub_part",0));
        assertThrows(IllegalStateException.class, () -> AgentTaskRequirementSnapshotSchemaInitializer.index(indexes,
                "uk_atrs_scope_revision",0,
                List.of("tenant_id","client_id","owner_jiacn","task_id","revision")));
    }
}
