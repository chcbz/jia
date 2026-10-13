package cn.jia.agent.dao;

import cn.jia.agent.mapper.PersonalWorkspaceExecutionOutputMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;
import cn.jia.agent.dao.impl.PersonalWorkspaceExecutionDaoImpl;
import cn.jia.agent.entity.PersonalWorkspaceExecutionOutputEntity;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionMapper;
import cn.jia.agent.mapper.PersonalWorkspaceExecutionInputMapper;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

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
    @Test void preparedOutputReadIsNonLockingAndByteExactInEveryScopedKey() throws Exception {
        var method=PersonalWorkspaceExecutionOutputMapper.class.getDeclaredMethod("findByOutputId",
                String.class,String.class,String.class,String.class,String.class);
        String sql=String.join(" ",method.getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT);
        assertFalse(sql.contains("for update"));assertFalse(sql.contains("lock in share mode"));
        assertTrue(sql.contains("limit 1"));
        for(String key:new String[]{"tenant_id","client_id","owner_jiacn","execution_id","output_id"}) {
            assertTrue(sql.contains("cast("+key+" as binary)"),key);
            assertTrue(sql.contains("octet_length("+key+")"),key);
        }
    }

    @Test void daoPreparationForwardsFullOwnerScopeAndNeverCallsLockQuery() {
        var outputs=mock(PersonalWorkspaceExecutionOutputMapper.class);
        var dao=new PersonalWorkspaceExecutionDaoImpl(mock(PersonalWorkspaceExecutionMapper.class),
                mock(PersonalWorkspaceExecutionInputMapper.class),outputs);
        var receipt=new PersonalWorkspaceExecutionOutputEntity().setOutputId("output_1");
        when(outputs.findByOutputId("0","client","owner","execution","output_1")).thenReturn(receipt);
        assertSame(receipt,dao.findOutput("0","client","owner","execution","output_1"));
        verify(outputs).findByOutputId("0","client","owner","execution","output_1");
        verifyNoMoreInteractions(outputs);
        assertThrows(IllegalArgumentException.class,()->dao.findOutput("1","client","owner","execution","output_1"));
        assertThrows(IllegalArgumentException.class,()->dao.findOutput("0","client","0","execution","output_1"));
        assertThrows(IllegalArgumentException.class,()->dao.findOutput("0","client","owner"," execution","output_1"));
    }
}
