package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Additive, binary-scoped private authority schema. Drift fails closed. */
public final class AgentSelectedOutputFinalizationSchemaInitializer implements InitializingBean {
    static final String RESOURCE="db/agent-selected-output-finalization-v1.sql";
    private static final String TABLE="agent_selected_output_finalization";
    private static final Set<String> COLUMNS=Set.of("operation_id","tenant_id","client_id","owner_jiacn","task_id",
            "immutable_digest","source_facts_digest","expected_task_version","expected_assignment_revision",
            "conversation_id","conversation_generation","target_agent_id","grant_id","grant_version","work_item_id",
            "run_id","summary","lease_token","lease_work_item_version","delivery_id","manifest_artifact_id",
            "artifact_facts_json","phase","version","created_at","updated_at");
    private static final Set<String> BINARY=Set.of("operation_id","tenant_id","client_id","owner_jiacn","task_id",
            "conversation_id","target_agent_id","grant_id","work_item_id","run_id","lease_token","delivery_id",
            "manifest_artifact_id","phase");
    private static final Map<String,Index> INDEXES=Map.of(
            "PRIMARY",new Index(true,List.of("tenant_id","client_id","owner_jiacn","operation_id")),
            "uk_asof_delivery",new Index(true,List.of("tenant_id","client_id","owner_jiacn","delivery_id")),
            "idx_asof_task",new Index(false,List.of("tenant_id","client_id","owner_jiacn","task_id","phase")));
    private static final Set<String> CHECKS=Set.of("chk_asof_versions","chk_asof_digest","chk_asof_phase","chk_asof_lease");
    private final JdbcTemplate jdbc;

    public AgentSelectedOutputFinalizationSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}

    @Override public void afterPropertiesSet(){
        jdbc.execute((ConnectionCallback<Void>) connection->{
            String product=connection.getMetaData().getDatabaseProductName();
            if(product==null||!product.toLowerCase(Locale.ROOT).contains("mysql")
                    ||connection.getMetaData().getDatabaseMajorVersion()<8)
                throw new IllegalStateException("Selected-output finalization schema requires MySQL 8");
            JdbcTemplate locked=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            Integer acquired=locked.queryForObject("SELECT GET_LOCK('cyf:agent:selected-output-finalization:v1',10)",Integer.class);
            if(!Integer.valueOf(1).equals(acquired))throw new IllegalStateException("Selected-output finalization schema lock unavailable");
            try{locked.execute(ddl());validate(locked);}
            finally{
                Integer released=locked.queryForObject("SELECT RELEASE_LOCK('cyf:agent:selected-output-finalization:v1')",Integer.class);
                if(!Integer.valueOf(1).equals(released))throw new IllegalStateException("Selected-output finalization schema lock lost");
            }
            return null;
        });
    }

    private static void validate(JdbcTemplate jdbc){
        List<Map<String,Object>> table=jdbc.queryForList("""
                SELECT engine,table_collation FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """,TABLE);
        if(table.size()!=1||!"innodb".equals(lower(table.getFirst(),"engine"))
                ||!"utf8mb4_0900_bin".equals(lower(table.getFirst(),"table_collation")))
            throw new IllegalStateException("Selected-output finalization table drift");
        Set<String> columns=Set.copyOf(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=?
                """,String.class,TABLE).stream().map(String::toLowerCase).toList());
        if(!columns.containsAll(COLUMNS))throw new IllegalStateException("Selected-output finalization columns missing="+difference(COLUMNS,columns));
        Map<String,String> collations=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT column_name,collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND collation_name IS NOT NULL
                """,TABLE))collations.put(text(row,"column_name").toLowerCase(Locale.ROOT),text(row,"collation_name"));
        for(String column:BINARY)if(!"utf8mb4_0900_bin".equalsIgnoreCase(collations.get(column)))
            throw new IllegalStateException("Selected-output finalization binary column drift: "+column);
        for(String column:Set.of("immutable_digest","source_facts_digest"))
            if(!"ascii_bin".equalsIgnoreCase(collations.get(column)))
                throw new IllegalStateException("Selected-output finalization digest collation drift: "+column);
        validateIndexes(jdbc);validateChecks(jdbc);
        Integer foreignKeys=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name=?
                """,Integer.class,TABLE);
        Integer triggers=jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.triggers
                WHERE trigger_schema=DATABASE() AND event_object_table=?
                """,Integer.class,TABLE);
        if(!Integer.valueOf(0).equals(foreignKeys)||!Integer.valueOf(0).equals(triggers))
            throw new IllegalStateException("Selected-output finalization schema has forbidden side effects");
    }

    private static void validateIndexes(JdbcTemplate jdbc){
        record Parts(boolean unique,List<String> columns) { }
        Map<String,Parts> actual=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT index_name,non_unique,seq_in_index,column_name,sub_part
                FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=?
                ORDER BY index_name,seq_in_index
                """,TABLE)){
            if(value(row,"sub_part")!=null)throw new IllegalStateException("Selected-output finalization prefix index drift");
            String name=text(row,"index_name");boolean unique=number(row,"non_unique")==0;
            Parts parts=actual.computeIfAbsent(name,ignored->new Parts(unique,new ArrayList<>()));
            if(parts.unique()!=unique)throw new IllegalStateException("Selected-output finalization index drift: "+name);
            parts.columns().add(text(row,"column_name").toLowerCase(Locale.ROOT));
        }
        for(var expected:INDEXES.entrySet()){
            Parts found=actual.get(expected.getKey());
            if(found==null||found.unique()!=expected.getValue().unique()||!found.columns().equals(expected.getValue().columns()))
                throw new IllegalStateException("Selected-output finalization index drift: "+expected.getKey());
        }
    }

    private static void validateChecks(JdbcTemplate jdbc){
        Map<String,String> actual=new LinkedHashMap<>();
        for(Map<String,Object> row:jdbc.queryForList("""
                SELECT constraint_name,enforced FROM information_schema.table_constraints
                WHERE constraint_schema=DATABASE() AND table_name=? AND constraint_type='CHECK'
                """,TABLE))actual.put(text(row,"constraint_name"),text(row,"enforced"));
        for(String check:CHECKS)if(!"YES".equalsIgnoreCase(actual.get(check)))
            throw new IllegalStateException("Selected-output finalization check drift: "+check);
    }

    static String ddl(){
        try{
            String sql=new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8).trim();
            String normalized=sql.toLowerCase(Locale.ROOT).replaceAll("(?m)^--.*$","").replaceAll("\\s+"," ");
            if(!normalized.contains("create table if not exists "+TABLE)
                    ||normalized.matches("(?s).*\\b(alter|insert|update|delete|replace|truncate|drop|trigger)\\b.*"))
                throw new IllegalStateException("Unsafe selected-output finalization DDL");
            return sql.endsWith(";")?sql.substring(0,sql.length()-1):sql;
        }catch(Exception failure){throw new IllegalStateException("Selected-output finalization DDL unavailable",failure);}
    }

    private static Set<String> difference(Set<String> expected,Set<String> actual){
        var missing=new java.util.LinkedHashSet<>(expected);missing.removeAll(actual);return Set.copyOf(missing);
    }
    private static Object value(Map<String,Object> row,String key){return row.entrySet().stream()
            .filter(entry->entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null);}
    private static String text(Map<String,Object> row,String key){Object value=value(row,key);return value==null?null:String.valueOf(value);}
    private static String lower(Map<String,Object> row,String key){String value=text(row,key);return value==null?null:value.toLowerCase(Locale.ROOT);}
    private static long number(Map<String,Object> row,String key){Object value=value(row,key);return value instanceof Number n?n.longValue():Long.parseLong(String.valueOf(value));}
    private record Index(boolean unique,List<String> columns) { }
}
