package cn.jia.agent.dao;

import cn.jia.agent.mapper.AgentCommandTransportMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandMailboxMapperContractTest {
    @Test
    void mailboxProjectionIsExactScopedBoundedAndStable() throws Exception {
        String sql = sql("selectMailboxPage");

        assertTrue(sql.contains("where d.tenant_id=#{tenantid} and d.client_id=#{clientid}"));
        assertExactParameter(sql, "d.tenant_id", "tenantid");
        assertExactParameter(sql, "d.client_id", "clientid");
        assertExactParameter(sql, "d.target_agent_id", "targetagentid");
        assertTrue(sql.contains("d.task_id=#{taskid}"));
        assertTrue(sql.contains("d.create_time<#{beforecreatetime}"));
        assertTrue(sql.contains("d.create_time=#{beforecreatetime} and d.id<#{beforeid}"));
        assertTrue(sql.contains("order by d.create_time desc, d.id desc"));
        assertTrue(sql.contains("limit #{limit}"));
    }

    @Test
    void callerAndTargetMustBothBeCanonicalWritableTaskMembers() throws Exception {
        String sql = sql("selectMailboxPage");

        assertTrue(sql.contains("inner join agent_task_member caller"));
        assertTrue(sql.contains("caller.agent_id=#{calleragentid}"));
        assertTrue(sql.contains("cast(caller.agent_id as binary)=cast(#{calleragentid} as binary)"));
        assertTrue(sql.contains("inner join agent_task_member target"));
        assertTrue(sql.contains("target.agent_id=d.target_agent_id"));
        for (String alias : new String[] {"caller", "target"}) {
            for (String column : new String[] {"member_status", "member_role", "assignment_source"}) {
                assertTrue(sql.contains("cast(" + alias + "." + column + " as binary)"),
                        alias + "." + column);
                assertTrue(sql.contains("octet_length(" + alias + "." + column + ")"),
                        alias + "." + column);
            }
        }
        assertTrue(sql.contains("cast('accepted' as binary)"));
        assertTrue(sql.contains("cast('working' as binary)"));
        assertTrue(sql.contains("cast('blocked' as binary)"));
    }

    @Test
    void defaultExcludesTerminalAndSelectListContainsNoSensitiveInternals() throws Exception {
        String sql = sql("selectMailboxPage");
        String select = sql.substring(0, sql.indexOf(" from agent_command_delivery"));

        assertTrue(sql.contains("#{includeterminal}=true"));
        for (String terminal : new String[] {
                "'succeeded'", "'failed'", "'rejected'", "'expired'", "'dead'"}) {
            assertTrue(sql.contains(terminal), terminal);
        }
        for (String forbidden : new String[] {
                "command_payload", "command_payload_hash", "lease_owner", "lease_until",
                "active_message_id", "active_attempt", "replay_parent_message_id",
                "replay_requester_id", "replay_approver_id", "replay_reason",
                "publisher_confirm_status", "mandatory_return_status", "last_error"}) {
            assertFalse(select.contains(forbidden), forbidden + ": " + select);
        }
    }

    private static String sql(String methodName) throws Exception {
        Method method = null;
        for (Method candidate : AgentCommandTransportMapper.class.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)) method = candidate;
        }
        if (method == null) throw new NoSuchMethodException(methodName);
        Select select = method.getAnnotation(Select.class);
        return String.join(" ", select.value())
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void assertExactParameter(
            String sql, String column, String parameter) {
        assertTrue(sql.contains("cast(" + column + " as binary)=cast(#{"
                + parameter + "} as binary)"), column);
        assertTrue(sql.contains("octet_length(" + column + ")=octet_length(#{"
                + parameter + "})"), column);
    }
}
