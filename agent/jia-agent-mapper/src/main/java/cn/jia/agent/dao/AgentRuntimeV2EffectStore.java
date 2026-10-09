package cn.jia.agent.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;

/** Narrow same-datasource persistence. Stable lookup is descriptive, never an ACL or an admission permit. */
public interface AgentRuntimeV2EffectStore {
    String RUN="agent_runtime_v2_effect_run", EFFECT="agent_runtime_v2_effect", REQUEST="agent_runtime_v2_effect_request", EVENT="agent_runtime_v2_effect_event", BLOB="agent_runtime_v2_effect_blob";
    JdbcTemplate jdbc();
    Map<String,Object> hint(String table,Map<String,Object> stableKey);
    Map<String,Object> lock(String table,Map<String,Object> stableKey);
    /** Complete bounded history; exceeding the limit rejects instead of returning a truncated proof. */
    List<Map<String,Object>> history(String table,String runtimeDispatchId,boolean lock);
    List<Map<String,Object>> blobs(String effectId,boolean lock);
    /** All blobs of this dispatch's complete effect graph, under the same
     * bounded history limit. Non-stage members are not filtered out. */
    List<Map<String,Object>> dispatchBlobs(String runtimeDispatchId,boolean lock);
    void append(String table,Map<String,Object> completeRow);
    /** Only RUN/EFFECT have projections; request/response/event/blob bytes are immutable. */
    void cas(String table,Map<String,Object> before,Map<String,Object> changes);
}
