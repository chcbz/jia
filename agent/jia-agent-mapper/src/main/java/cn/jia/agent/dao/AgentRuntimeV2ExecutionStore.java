package cn.jia.agent.dao;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stable-ID-first reads; a lookup key is not an ACL. Writes require an active bound write transaction. */
public interface AgentRuntimeV2ExecutionStore {
    String RECEIPT="agent_command_v2_ack_receipt",FACT="agent_command_v2_execution_lease_fact",ANCHOR="agent_command_v2_execution_continuation_anchor",REQUEST="agent_command_v2_execution_request",GRANT="agent_command_v2_execution_grant",INTERNAL="agent_runtime_v2_internal_outbox",DELIVERY="agent_command_delivery",OUTBOX="agent_outbox_event",INBOX="agent_consumer_inbox";
    JdbcTemplate jdbc();
    Map<String,Object> hint(String table,Map<String,Object> key);
    Map<String,Object> lock(String table,Map<String,Object> key);
    List<Map<String,Object>> rows(String table,String dispatch,boolean lock);
    void append(String table,Map<String,Object> values);
    void cas(String table,Map<String,Object> before,Map<String,Object> changes);
}
