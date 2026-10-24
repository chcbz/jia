package cn.jia.agent.mapper;

import cn.jia.agent.dao.AgentRuntimeV2EffectStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import static cn.jia.agent.dao.AgentRuntimeV2EffectStore.*;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.number;
import static cn.jia.agent.dao.AgentRuntimeV2RegistrationStore.same;

/** Exact fixed-table mapper; no SQL supplied by messages, no DDL, and no network/resource operation under locks. */
public final class AgentRuntimeV2EffectMapper implements AgentRuntimeV2EffectStore {
    public record Definition(List<String> identity,List<String> columns,List<String> mutable,String order) {
        public Definition { identity=List.copyOf(identity);columns=List.copyOf(columns);mutable=List.copyOf(mutable); }
    }
    private static Definition definition(String identity,String columns,String mutable,String order){return new Definition(List.of(identity.split(",")),List.of(columns.split(",")),mutable.isEmpty()?List.of():List.of(mutable.split(",")),order);}
    public static final Map<String,Definition> DEFINITIONS=Map.of(
        RUN,definition("runtime_dispatch_id","runtime_dispatch_id,run_token,execution_origin_snapshot_sha256,dispatch_wire_sha256,started_receipt_id,started_receipt_sha256,original_start_lease_fact_id,disposition,retired,version,created_at,updated_at","disposition,retired,updated_at","runtime_dispatch_id"),
        EFFECT,definition("effect_id","effect_id,runtime_dispatch_id,run_token,effect_kind,resource_wire_bytes,resource_sha256,execution_grant_id,execution_grant_sha256,holder_generation_fact_id,current_lease_fact_id,admission_request_id,admitted_at,valid_until,disposition,commit_receipt_id,result_wire_bytes,result_sha256,version,created_at,updated_at","admission_request_id,admitted_at,valid_until,disposition,commit_receipt_id,result_wire_bytes,result_sha256,updated_at","effect_id"),
        REQUEST,definition("request_id","request_id,runtime_dispatch_id,request_family,effect_id,subject_kind,subject_id,request_wire_bytes,request_sha256,response_wire_bytes,response_sha256,actor_credential_reference,actor_registration_fact_id,actor_runtime_binding_id,actor_runtime_instance_id,actor_binding_generation,actor_session_fence,actor_socket_owner_instance_id,actor_socket_owner_epoch,created_at","","request_id"),
        EVENT,definition("effect_id,event_sequence","effect_id,event_sequence,runtime_dispatch_id,run_token,request_id,previous_sha256,event_kind,evidence_wire_bytes,evidence_sha256,event_sha256,created_at","","effect_id,event_sequence"),
        BLOB,definition("effect_id,chunk_offset","effect_id,chunk_offset,chunk_length,total_length,content_sha256,chunk_sha256,chunk_bytes,created_at","","effect_id,chunk_offset")
    );
    private static final int HISTORY_LIMIT=4096;
    private final JdbcTemplate jdbc;
    public AgentRuntimeV2EffectMapper(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc,"jdbc");Objects.requireNonNull(jdbc.getDataSource(),"datasource");}
    @Override public JdbcTemplate jdbc(){return jdbc;}
    @Override public Map<String,Object> hint(String table,Map<String,Object> key){return select(table,key,false);}
    @Override public Map<String,Object> lock(String table,Map<String,Object> key){return select(table,key,true);}
    private Map<String,Object> select(String table,Map<String,Object> key,boolean lock){
        Definition d=required(table);exactKeys(key,d.identity());if(lock)transaction();var args=new ArrayList<Object>();
        var rows=jdbc.query("SELECT "+projection(d,"")+" FROM "+table+" WHERE "+predicate(key,args)+(lock?" FOR UPDATE":""),rowMapper(d),args.toArray());
        if(rows.isEmpty())return null;if(rows.size()!=1)throw new SecurityException("ambiguous effect stable identity");
        var result=rows.getFirst();key.forEach((name,value)->exact(result.get(name),value));return result;
    }
    @Override public List<Map<String,Object>> history(String table,String dispatch,boolean lock){
        Definition d=required(table);if(!Set.of(EFFECT,REQUEST,EVENT).contains(table)||dispatch==null||dispatch.isEmpty())throw new IllegalArgumentException("effect history scope");
        if(lock)transaction();var rows=jdbc.query("SELECT "+projection(d,"")+" FROM "+table+" WHERE runtime_dispatch_id=? ORDER BY "+d.order()+" LIMIT "+(HISTORY_LIMIT+1)+(lock?" FOR UPDATE":""),rowMapper(d),dispatch);
        if(rows.size()>HISTORY_LIMIT)throw new SecurityException("effect history limit");rows.forEach(row->exact(row.get("runtime_dispatch_id"),dispatch));return List.copyOf(rows);
    }
    @Override public List<Map<String,Object>> blobs(String effectId,boolean lock){
        if(effectId==null||effectId.isEmpty())throw new IllegalArgumentException("blob scope");if(lock)transaction();
        var d=required(BLOB);var rows=jdbc.query("SELECT "+projection(d,"")+" FROM "+BLOB+" WHERE effect_id=? ORDER BY chunk_offset LIMIT "+(HISTORY_LIMIT+1)+(lock?" FOR UPDATE":""),rowMapper(d),effectId);
        if(rows.size()>HISTORY_LIMIT)throw new SecurityException("blob history limit");rows.forEach(row->exact(row.get("effect_id"),effectId));return List.copyOf(rows);
    }
    @Override public List<Map<String,Object>> dispatchBlobs(String dispatch,boolean lock){
        if(dispatch==null||dispatch.isEmpty())throw new IllegalArgumentException("blob dispatch scope");if(lock)transaction();
        // Effects have already been completely read/locked in effect_id order.
        // Keep every associated blob (including illegal non-stage blobs), not
        // a selected STAGE subset or 4096 rows per member of an unbounded sum.
        var d=required(BLOB);var rows=jdbc.query("SELECT "+projection(d,"b.")+" FROM "+BLOB+" b JOIN "+EFFECT+" e ON e.effect_id=b.effect_id WHERE e.runtime_dispatch_id=? ORDER BY b.effect_id,b.chunk_offset LIMIT "+(HISTORY_LIMIT+1)+(lock?" FOR UPDATE":""),rowMapper(d),dispatch);
        if(rows.size()>HISTORY_LIMIT)throw new SecurityException("blob history limit");return List.copyOf(rows);
    }
    @Override public void append(String table,Map<String,Object> row){
        Definition d=required(table);exactKeys(row,d.columns());transaction();
        if(REQUEST.equals(table))requestActor(row);
        if(jdbc.update("INSERT INTO "+table+"("+String.join(",",d.columns())+") VALUES("+String.join(",",Collections.nCopies(d.columns().size(),"?"))+")",d.columns().stream().map(row::get).toArray())!=1)throw new IllegalStateException("effect append failed");
    }
    @Override public void cas(String table,Map<String,Object> before,Map<String,Object> changes){
        Definition d=required(table);if(d.mutable().isEmpty()||changes.isEmpty()||!d.mutable().containsAll(changes.keySet()))throw new IllegalArgumentException("immutable effect row");
        exactKeys(before,d.columns());transaction();var args=new ArrayList<Object>();var sets=new ArrayList<String>();
        changes.forEach((name,value)->{sets.add(name+"=?");args.add(value);});sets.add("version=?");args.add(Math.addExact(number(before,"version"),1));
        var key=new LinkedHashMap<String,Object>();d.identity().forEach(name->key.put(name,before.get(name)));key.put("version",before.get("version"));
        for(String name:d.columns())if(!d.mutable().contains(name)&&!key.containsKey(name))key.put(name,before.get(name));
        if(jdbc.update("UPDATE "+table+" SET "+String.join(",",sets)+" WHERE "+predicate(key,args),args.toArray())!=1)throw new IllegalStateException("effect projection CAS lost");
    }
    // Complete fixed schema, including every provenance/actor/blob column.
    // Only the immutable schema is reused; every call still reads the actual
    // database with the same scope, bounds and lock order. No result cache.
    private static String projection(Definition definition,String prefix){return definition.columns().stream().map(name->prefix+name).collect(java.util.stream.Collectors.joining(","));}
    private static RowMapper<Map<String,Object>> rowMapper(Definition definition){
        return (resultSet,rowNumber)->{
            var result=new LinkedHashMap<String,Object>(definition.columns().size());
            // Ordinals exactly match the complete fixed SELECT projection.
            // Preserve Spring's JDBC value handling (including NULL and blobs),
            // without metadata/column-name or case-insensitive-map discovery.
            for(int index=0;index<definition.columns().size();index++)result.put(definition.columns().get(index),JdbcUtils.getResultSetValue(resultSet,index+1));
            return result;
        };
    }
    private static String predicate(Map<String,Object> key,List<Object> args){var terms=new ArrayList<String>();key.forEach((name,value)->{terms.add(name+(value==null?" IS NULL":"=?"));if(value!=null)args.add(value);});return String.join(" AND ",terms);}
    private static Definition required(String table){Definition d=DEFINITIONS.get(table);if(d==null)throw new IllegalArgumentException("effect table");return d;}
    private static void exactKeys(Map<String,Object> row,List<String> names){if(row==null||!row.keySet().equals(new HashSet<>(names)))throw new IllegalArgumentException("effect exact columns");}
    private static void exact(Object found,Object expected){if(!same(found,expected))throw new SecurityException("nonexact effect scope");}
    private static void requestActor(Map<String,Object> row){
        if(!Set.of("CONTROL","EFFECT","RECONCILE").contains(row.get("request_family")))throw new IllegalArgumentException("effect request family");
        for(String name:List.of("actor_credential_reference","actor_session_fence","actor_socket_owner_instance_id")){
            Object value=row.get(name);if(!(value instanceof String text)||text.isBlank()||!text.equals(text.strip())||text.indexOf('\0')>=0||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>100)throw new IllegalArgumentException("mandatory exact actual actor route");
        }
        if(row.get("actor_socket_owner_epoch")==null||number(row,"actor_socket_owner_epoch")<0||row.get("created_at")==null||number(row,"created_at")<=0)throw new IllegalArgumentException("actual route epoch/time");
        var tuple=List.of("actor_registration_fact_id","actor_runtime_binding_id","actor_runtime_instance_id","actor_binding_generation");
        long present=tuple.stream().filter(name->row.get(name)!=null).count();
        if(present==0){if(!"RECONCILE".equals(row.get("request_family")))throw new IllegalArgumentException("committed effect actor required");}
        else if(present!=4||number(row,"actor_binding_generation")<0)throw new IllegalArgumentException("partial committed actor tuple");
    }
    private void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive()||TransactionSynchronizationManager.isCurrentTransactionReadOnly()||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw new IllegalStateException("bound effect write transaction required");}
}
