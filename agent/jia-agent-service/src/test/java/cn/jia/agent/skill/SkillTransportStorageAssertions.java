package cn.jia.agent.skill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original native MySQL storage invariants, shared verbatim in meaning with the historical suite. */
final class SkillTransportStorageAssertions {
    static void generatedGuards(JdbcTemplate jdbc,boolean mysql,String tenant,String client,String agent) {
        long delivery=0;
        for(String outcome:List.of("PENDING","SUCCEEDED","FAILED")) {
            for(String settlement:List.of("PENDING","SOURCE_ACKED","SOURCE_REQUEUED","NOT_ACQUIRED","UNKNOWN")) {
                long group=++delivery;
                String operation="guard-"+group;
                insertRedriveGuardRow(jdbc,tenant,client,agent,operation,group,outcome,settlement);
                Map<String,Object> row=jdbc.queryForMap(
                        "SELECT disposition_guard,redrive_guard FROM agent_command_redrive_operation WHERE operation_id=?",operation);
                if(outcome.equals("PENDING")) assertEquals(1,((Number)row.get("disposition_guard")).intValue());
                else assertNull(row.get("disposition_guard"));
                boolean released=Set.of("SOURCE_REQUEUED","NOT_ACQUIRED").contains(settlement);
                if(released) {
                    assertNull(row.get("redrive_guard"));
                    insertRedriveGuardRow(jdbc,tenant,client,agent,operation+"-null",group,outcome,settlement);
                    insertRedriveGuardRow(jdbc,tenant,client,agent,operation+"-active",group,"PENDING","PENDING");
                    DataAccessException update=assertThrows(DataAccessException.class,()->jdbc.update(
                            "UPDATE agent_command_redrive_operation SET settlement_state='PENDING' WHERE operation_id=?",operation));
                    assertEquals(mysql?"23000":"23505",((SQLException)update.getMostSpecificCause()).getSQLState());
                    assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_redrive_operation "
                            +"WHERE delivery_id=? AND redrive_guard IS NULL",Integer.class,group));
                } else {
                    assertEquals(1,((Number)row.get("redrive_guard")).intValue());
                    DataAccessException duplicate=assertThrows(DataAccessException.class,
                            ()->insertRedriveGuardRow(jdbc,tenant,client,agent,operation+"-duplicate",group,outcome,settlement));
                    assertEquals(mysql?"23000":"23505",((SQLException)duplicate.getMostSpecificCause()).getSQLState());
                }
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_command_redrive_operation "
                        +"WHERE delivery_id=? AND redrive_guard=1",Integer.class,group));
            }
        }
    }
    private static void insertRedriveGuardRow(JdbcTemplate jdbc,String tenant,String client,String agent,String operation,long delivery,String outcome,String settlement) {
        jdbc.update("""
                INSERT INTO agent_command_redrive_operation
                  (operation_id,delivery_id,task_id,target_agent_id,command_id,source_event_id,
                   source_message_id,source_attempt,wire_hash,requester_id,reason,ticket_reference,
                   outcome_state,settlement_state,requested_at,tenant_id,client_id,create_time,update_time)
                VALUES(?,?,'guard-task',?,'guard-command','guard-event','guard-message',1,?,
                       'requester','fixture guard check','ticket-guard',?,?,1,?,?,1,1)
                """,operation,delivery,agent,new byte[32],outcome,settlement,tenant,client);
    }
    static void immutableAudit(JdbcTemplate jdbc,String tenant,String client,String agent) {
        jdbc.update("""
                INSERT INTO agent_command_operation_audit
                  (operation_id,phase,operation_type,tenant_id,client_id,task_id,target_agent_id,
                   source_message_id,delivery_id,requester_id,reason,ticket_reference,requested_at,
                   outcome,created_by,created_at)
                VALUES('audit-op-1','REQUEST','BROKER_REDRIVE',?,?,?,?,'message-1',1,
                       'requester','fixture audit reason','ticket-1',1,'REQUESTED','creator',1)
                """,tenant,client,"task-1",agent);
        Map<String,Object> before=jdbc.queryForMap(
                "SELECT * FROM agent_command_operation_audit WHERE operation_id='audit-op-1'");

        DataAccessException update=assertThrows(DataAccessException.class,()->jdbc.update(
                "UPDATE agent_command_operation_audit SET outcome='FAILED' WHERE operation_id='audit-op-1'"));
        assertTrue(update.getMostSpecificCause().getMessage().contains(
                "D09: operation audit rows are immutable after insert"));
        DataAccessException delete=assertThrows(DataAccessException.class,()->jdbc.update(
                "DELETE FROM agent_command_operation_audit WHERE operation_id='audit-op-1'"));
        assertTrue(delete.getMostSpecificCause().getMessage().contains(
                "D09: physical delete of operation audit rows is forbidden"));

        assertEquals(before,jdbc.queryForMap(
                "SELECT * FROM agent_command_operation_audit WHERE operation_id='audit-op-1'"));
        assertEquals(1,jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_command_operation_audit WHERE operation_id='audit-op-1'",Integer.class));
    }
}
