package cn.jia.agent.dao;

import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceExecutionOutputMapperContractTest {
    @Test
    void reworkSourceIsExactPublishedTaskOutputAndNeverLatest() throws Exception {
        Method method = PersonalWorkspaceExecutionOutputMapper.class.getDeclaredMethod(
                "findPublishedReworkSource", String.class, String.class, String.class,
                String.class, String.class, String.class, String.class, int.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);

        for (String predicate : new String[] {
                "o.tenant_id=#{tenantid}", "o.client_id=#{clientid}",
                "o.owner_jiacn=#{ownerjiacn}", "e.task_id=#{taskid}",
                "o.formal_delivery_id=#{formaldeliveryid}", "o.output_id=#{outputid}",
                "o.workspace_file_id=#{workspacefileid}",
                "o.workspace_file_version=#{workspacefileversion}",
                "e.execution_mode='task'", "e.execution_state='output_committed'",
                "o.output_state='committed'", "o.publication_state='published'"}) {
            assertTrue(sql.contains(predicate), predicate);
        }
        for (String exact : new String[] {"o.tenant_id", "o.client_id", "o.owner_jiacn",
                "e.task_id", "o.formal_delivery_id", "o.output_id", "o.workspace_file_id"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")"), exact);
        }
        assertFalse(sql.contains("max("));
        assertFalse(sql.contains("order by"));
        assertFalse(sql.contains("latest_version"));
    }
}
