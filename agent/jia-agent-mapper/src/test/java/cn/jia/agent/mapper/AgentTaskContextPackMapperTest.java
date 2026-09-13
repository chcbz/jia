package cn.jia.agent.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskContextPackMapperTest {
    @Test
    void taskDescriptionLookupIsNumericAndExactScopedWithSafeColumnsOnly() throws Exception {
        Method method = AgentTaskContextPackMapper.class.getMethod(
                "findTaskDescription", String.class, String.class, String.class);
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        assertTrue(sql.contains("regexp '^[0-9]+$'"));
        assertTrue(sql.contains("plan.id = cast(#{taskid} as unsigned)"));
        assertTrue(sql.contains("cast(plan.jiacn as binary)"));
        assertTrue(sql.contains("octet_length(plan.client_id)"));
        assertFalse(sql.contains("remind_phone"));
        assertFalse(sql.contains("remind_msg"));
        assertFalse(sql.contains("select *"));
    }

    @Test
    void authoritativeOutcomeContractFiltersAcceptedAndVisibilityBeforeLimit() throws Exception {
        Method method = Arrays.stream(AgentTaskArtifactOutcomeMapper.class.getMethods())
                .filter(candidate -> candidate.getName().equals("selectAuthoritativeAccepted"))
                .findFirst().orElseThrow();
        String sql = String.join(" ", method.getAnnotation(Select.class).value())
                .replaceAll("\\s+", " ").toLowerCase();

        int accepted = sql.indexOf("o.outcome_state = 'accepted'");
        int visibility = sql.indexOf("a.visibility = 'task_members'");
        int limit = sql.lastIndexOf("limit #{limit}");
        assertTrue(accepted >= 0 && accepted < limit, sql);
        assertTrue(visibility >= 0 && visibility < limit, sql);
        assertFalse(sql.contains("storage_uri"));
        assertFalse(sql.contains("content_uri"));
        assertFalse(sql.contains("artifact_content"));
    }
}
