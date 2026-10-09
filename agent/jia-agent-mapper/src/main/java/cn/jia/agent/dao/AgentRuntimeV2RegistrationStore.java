package cn.jia.agent.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.util.*;

/** Dedicated stable-ID-first, current-read registration repository. No mutable receiver predicates. */
public final class AgentRuntimeV2RegistrationStore {
    private static final java.util.regex.Pattern COLUMN_NAME=java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");
    public static final String BINDING="agent_runtime_v2_binding", FACT="agent_runtime_v2_binding_generation_fact",
        RECEIPT="agent_runtime_v2_registration_receipt", REQUEST="agent_runtime_v2_registration_request",
        CHALLENGE="agent_runtime_v2_reconnect_challenge", DECISION="agent_runtime_v2_challenge_request_decision",
        OPERATION="core_runtime_v2_drain_operation", COMPLETION="core_runtime_v2_drain_completion_authority";
    private static final Set<String> READ = Set.of(BINDING,FACT,RECEIPT,REQUEST,CHALLENGE,DECISION,OPERATION,COMPLETION,
        "core_tenant_control","core_tenant_module_control","oauth_client","oauth_client_tenant_grant",
        "oauth_client_tenant_domain_grant","user_info","oauth_api_key","agent_runtime","agent_identity_registry",
        "agent_persona_binding","agent_runtime_v2_registration_authority","core_deployment_registration",
        "core_deployment_tenant_grant","core_deployment_module_admission","core_tenant_operation");
    private static final Set<String> APPEND = Set.of(BINDING,FACT,RECEIPT,REQUEST,CHALLENGE,DECISION);
    private final JdbcTemplate jdbc;
    public AgentRuntimeV2RegistrationStore(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    public JdbcTemplate jdbc() { return jdbc; }
    public Map<String,Object> hint(String table, Map<String,Object> key) { return select(table,key,false,null); }
    public Map<String,Object> lock(String table, Map<String,Object> key) { transaction(); return select(table,key,true,null); }
    public Map<String,Object> latestFact(String binding) { transaction(); return select(FACT, row("runtime_binding_id",binding),true,"fact_sequence DESC"); }
    public List<Map<String,Object>> factChain(String binding) {
        transaction();
        List<Map<String,Object>> facts=jdbc.queryForList("SELECT * FROM "+FACT+" WHERE runtime_binding_id=? ORDER BY binding_generation_fact_id FOR UPDATE",binding);
        facts.sort(Comparator.comparingLong(f->number(f,"fact_sequence")));
        return facts;
    }
    public void append(String table, Map<String,Object> values) {
        transaction(); if(!APPEND.contains(table)) throw new IllegalArgumentException("not an append table");
        values.keySet().forEach(AgentRuntimeV2RegistrationStore::column);
        String names=String.join(",",values.keySet());
        if(jdbc.update("INSERT INTO "+table+"("+names+") VALUES("+String.join(",",Collections.nCopies(values.size(),"?"))+")", values.values().toArray())!=1) throw new IllegalStateException("append failed");
    }
    /** Current projection CAS includes stable identity plus the complete old socket fence, never historical lease. */
    public void bindingCas(Map<String,Object> before, Map<String,Object> changes) {
        transaction(); long version=number(before,"version"); if(version==Long.MAX_VALUE) throw new IllegalStateException("binding version exhausted");
        Map<String,Object> values=new LinkedHashMap<>(changes); values.put("version",version+1);
        Map<String,Object> where=row("runtime_binding_id",before.get("runtime_binding_id"),"contract_version",2,
            "registration_subject_sha256",before.get("registration_subject_sha256"),"version",version,"status",before.get("status"),
            "binding_generation",before.get("binding_generation"),"runtime_instance_id",before.get("runtime_instance_id"),
            "current_session_fence",before.get("current_session_fence"),"socket_owner_instance_id",before.get("socket_owner_instance_id"),
            "socket_owner_epoch",before.get("socket_owner_epoch"),"socket_owner_lease_until",before.get("socket_owner_lease_until"));
        if(update(BINDING,values,where)!=1) throw new IllegalStateException("binding CAS lost");
    }
    public void consume(Map<String,Object> c,String request,long now) {
        transaction(); if(now<number(c,"issued_at")||now>=number(c,"expires_at")) throw new IllegalStateException("expired challenge");
        if(update(CHALLENGE,row("status","CONSUMED","consumed_reconnect_request_id",request,"consumed_at",now,"update_time",now),
            row("challenge_id",c.get("challenge_id"),"status","ISSUED","consumed_reconnect_request_id",null,"consumed_at",null,
                "request_wire_sha256",c.get("request_wire_sha256"),"registration_subject_sha256",c.get("registration_subject_sha256")))!=1) throw new IllegalStateException("challenge CAS lost");
    }
    public List<Map<String,Object>> expiredHints(long now,int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("reaper limit");
        return jdbc.queryForList("SELECT * FROM "+BINDING+" WHERE contract_version=2 AND evidence_provenance='R2B1_ENFORCED' AND status='ACTIVE' AND socket_owner_lease_until<=? ORDER BY socket_owner_lease_until,runtime_binding_id LIMIT ?",now,limit);
    }
    private Map<String,Object> select(String table,Map<String,Object> key,boolean lock,String order) {
        if(!READ.contains(table)||key.isEmpty()) throw new IllegalArgumentException("unknown registration read");
        List<Object> args=new ArrayList<>(); String predicate=predicate(key,args);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE "+predicate+(order==null?"":" ORDER BY "+order)+(order==null?"":" LIMIT 1")+(lock?" FOR UPDATE":""),args.toArray());
        if(rows.isEmpty()) return null; if(rows.size()!=1) throw new IllegalStateException("ambiguous current identity");
        Map<String,Object> result=rows.getFirst();
        key.forEach((name,expected)->{if(!same(expected,result.get(name)))throw new SecurityException("nonexact database identity");});
        return result;
    }
    private int update(String table,Map<String,Object> values,Map<String,Object> key) {
        List<Object> args=new ArrayList<>(); List<String> set=new ArrayList<>();
        values.forEach((name,value)->{column(name);set.add(name+"=?");args.add(value);});
        String predicate=predicate(key,args);
        return jdbc.update("UPDATE "+table+" SET "+String.join(",",set)+" WHERE "+predicate,args.toArray());
    }
    private static String predicate(Map<String,Object> key,List<Object> args) {
        List<String> where=new ArrayList<>(); key.forEach((name,value)->{column(name);where.add(name+(value==null?" IS NULL":"=?"));if(value!=null)args.add(value);});return String.join(" AND ",where);
    }
    private static void column(String value) { if(value==null)value.matches("[a-z][a-z0-9_]*");if(!COLUMN_NAME.matcher(value).matches())throw new IllegalArgumentException("invalid column"); }
    public void transaction() { if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw new IllegalStateException("current write transaction required"); }
    public static Map<String,Object> row(Object... pairs) { Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return map; }
    public static long number(Map<String,Object> row,String key) { Object value=row.get(key);if(!(value instanceof Number number)||number.longValue()<0)throw new SecurityException("invalid persisted number: "+key);return number.longValue(); }
    public static String text(Map<String,Object> row,String key) { Object value=row.get(key);if(!(value instanceof String string)||string.isBlank())throw new SecurityException("invalid persisted identity: "+key);return string; }
    public static byte[] bytes(Map<String,Object> row,String key) { Object value=row.get(key);if(!(value instanceof byte[] bytes)||bytes.length!=32)throw new SecurityException("invalid persisted digest: "+key);return bytes; }
    public static boolean same(Object left,Object right) { if(left instanceof byte[] l && right instanceof byte[] r)return MessageDigest.isEqual(l,r);if(left instanceof Number l && right instanceof Number r)return l.longValue()==r.longValue();return Objects.equals(left,right); }
}
