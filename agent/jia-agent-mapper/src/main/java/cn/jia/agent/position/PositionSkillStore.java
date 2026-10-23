package cn.jia.agent.position;

import cn.jia.agent.service.PositionAuthority.Scope;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.inject.Named;
import java.util.*;

/** Persistence only. Scope root serializes grants, revocation, defaults and business commits across API instances. */
@Named
public class PositionSkillStore {
    public record Stored(long revision, String json) { }
    public record Operation(String id, String digest, String resultId) { }
    private final JdbcTemplate jdbc;
    public PositionSkillStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Stored read(Scope s) {
        var rows = jdbc.query("SELECT revision,state_json FROM agent_position_state WHERE tenant_id=? AND client_id=? AND owner_jiacn=?",
                (rs,n) -> new Stored(rs.getLong(1),rs.getString(2)), s.tenantId(),s.clientId(),s.ownerJiacn());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public Stored lock(Scope s, String emptyJson) {
        jdbc.update("INSERT INTO agent_position_state(tenant_id,client_id,owner_jiacn,revision,state_json) VALUES(?,?,?,0,?) ON DUPLICATE KEY UPDATE revision=revision",
                s.tenantId(),s.clientId(),s.ownerJiacn(),emptyJson);
        return jdbc.queryForObject("SELECT revision,state_json FROM agent_position_state WHERE tenant_id=? AND client_id=? AND owner_jiacn=? FOR UPDATE",
                (rs,n) -> new Stored(rs.getLong(1),rs.getString(2)),s.tenantId(),s.clientId(),s.ownerJiacn());
    }
    public int save(Scope s, long revision, String json) {
        return jdbc.update("UPDATE agent_position_state SET state_json=?,revision=revision+1 WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND revision=?",
                json,s.tenantId(),s.clientId(),s.ownerJiacn(),revision);
    }
    public boolean authorized(Scope s, String permission, String resource) {
        return !jdbc.query("SELECT permission_key FROM agent_position_authority WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND permission_key=? AND resource_key=? FOR UPDATE",
                (rs,n)->rs.getString(1),s.tenantId(),s.clientId(),s.ownerJiacn(),permission,resource).isEmpty();
    }
    public Operation operation(Scope s, String key) {
        var rows = jdbc.query("SELECT operation_id,request_sha256,result_id FROM agent_position_operation WHERE tenant_id=? AND client_id=? AND owner_jiacn=? AND idempotency_key=?",
                (rs,n)->new Operation(rs.getString(1),rs.getString(2),rs.getString(3)),s.tenantId(),s.clientId(),s.ownerJiacn(),key);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void operation(Scope s, String key, Operation op, String action) {
        jdbc.update("INSERT INTO agent_position_operation(tenant_id,client_id,owner_jiacn,idempotency_key,operation_id,request_sha256,result_id,action_key) VALUES(?,?,?,?,?,?,?,?)",
                s.tenantId(),s.clientId(),s.ownerJiacn(),key,op.id(),op.digest(),op.resultId(),action);
    }
}
