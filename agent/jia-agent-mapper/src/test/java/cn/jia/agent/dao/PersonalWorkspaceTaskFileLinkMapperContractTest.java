package cn.jia.agent.dao;

import cn.jia.agent.mapper.PersonalWorkspaceTaskFileLinkMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalWorkspaceTaskFileLinkMapperContractTest {
    @Test
    void executionAuthorizationIsExactActiveInputOrReferenceAndNeverOutput() throws Exception {
        Method method = PersonalWorkspaceTaskFileLinkMapper.class.getDeclaredMethod(
                "selectActiveExecutionInputRelation", String.class, String.class, String.class,
                String.class, String.class, int.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ")
                .trim().toLowerCase(Locale.ROOT);

        for (String exact : new String[] {"tenant_id", "client_id", "owner_jiacn", "task_id", "file_id"}) {
            assertTrue(sql.contains("cast(" + exact + " as binary)"), exact);
            assertTrue(sql.contains("octet_length(" + exact + ")"), exact);
        }
        assertTrue(sql.contains("file_version=#{version}"));
        assertTrue(sql.contains("link_state='active'"));
        assertTrue(sql.contains("cast(link_state as binary)=cast('active' as binary)"));
        assertTrue(sql.contains("link_role='input'"));
        assertTrue(sql.contains("cast(link_role as binary)=cast('input' as binary)"));
        assertTrue(sql.contains("link_role='reference'"));
        assertTrue(sql.contains("cast(link_role as binary)=cast('reference' as binary)"));
        assertFalse(sql.contains("link_role='output'"));
        assertFalse(sql.contains("max(file_version)"));
        assertFalse(sql.contains("latest_version"));
    }
}
