package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Additive bridge-operation catalog. Runtime flags remain disabled by default. */
public final class ControlledImageBridgeSchemaInitializer implements InitializingBean {
    static final String TABLE="agent_controlled_image_bridge_operation";
    private static final Map<String,String> CHECKS=checks();
    private final JdbcTemplate jdbc;
    public ControlledImageBridgeSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,TABLE);
        if(count==null||count==0)jdbc.execute(ddl());
        Map<String,Object> table=jdbc.queryForMap("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",TABLE);
        if(!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"),""))
                ||!"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"),"")))
            throw new IllegalStateException("Controlled-image bridge table drift");
        validateColumns(jdbc.queryForList("SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",TABLE));
        var indexes=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",TABLE);
        index(indexes,"PRIMARY",0,List.of("id"));
        index(indexes,"uk_acibo_scope_key",0,List.of("tenant_id","client_id","owner_jiacn","task_id","assignment_idempotency_key"));
        index(indexes,"uk_acibo_scope_consent",0,List.of("tenant_id","client_id","owner_jiacn","task_id","consent_id"));
        index(indexes,"uk_acibo_scope_grant",0,List.of("tenant_id","client_id","owner_jiacn","task_id","grant_id"));
        if(indexes.size()!=16)throw new IllegalStateException("Controlled-image bridge index drift");
        validateChecks(jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,cc.check_clause FROM information_schema.table_constraints tc JOIN information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? AND tc.constraint_type='CHECK'",TABLE));
    }

    static Map<String,String> checkExpressions(){return CHECKS;}
    static void validateChecks(List<Map<String,Object>> rows){
        Map<String,String> actual=new LinkedHashMap<>();
        for(var row:rows){String name=Objects.toString(row.get("constraint_name"),"");
            if(name.isBlank()||!"YES".equalsIgnoreCase(Objects.toString(row.get("enforced"),""))
                    ||actual.put(name,canonicalCheck(Objects.toString(row.get("check_clause"),"")))!=null)
                throw new IllegalStateException("Controlled-image bridge CHECK catalog drift");}
        if(!actual.keySet().equals(CHECKS.keySet()))throw new IllegalStateException("Controlled-image bridge CHECK set drift");
        CHECKS.forEach((name,clause)->{if(!Objects.equals(canonicalCheck(clause),actual.get(name)))
            throw new IllegalStateException("Controlled-image bridge CHECK definition drift: "+name);});
    }
    static void validateColumns(List<Map<String,Object>> rows) {
        Map<String,Column> expected=columns();
        if(!rows.stream().map(row->Objects.toString(row.get("column_name"),"")).toList()
                .equals(expected.keySet().stream().toList()))
            throw new IllegalStateException("Controlled-image bridge column set drift");
        for(var row:rows) {
            String name=Objects.toString(row.get("column_name"),"");
            Column wanted=expected.get(name);
            String collation=row.get("collation_name")==null?null:
                    Objects.toString(row.get("collation_name"),"").toLowerCase(Locale.ROOT);
            if(wanted==null||!wanted.type().equalsIgnoreCase(Objects.toString(row.get("column_type"),""))
                    ||!wanted.nullable().equalsIgnoreCase(Objects.toString(row.get("is_nullable"),""))
                    ||!Objects.equals(wanted.collation(),collation))
                throw new IllegalStateException("Controlled-image bridge column drift: "+name);
        }
    }
    private static String canonicalCheck(String value) {
        try { return AgentTaskCreationOperationSchemaInitializer.canonicalCheckExpression(value); }
        catch (IllegalArgumentException malformed) {
            throw new IllegalStateException("Controlled-image bridge malformed CHECK catalog",malformed);
        }
    }
    private static void index(List<Map<String,Object>> rows,String name,int nonUnique,List<String> columns){
        var found=rows.stream().filter(row->name.equals(row.get("index_name"))).toList();
        if(found.size()!=columns.size())throw new IllegalStateException("Controlled-image bridge index drift: "+name);
        for(int i=0;i<columns.size();i++){var row=found.get(i);if(!columns.get(i).equals(row.get("column_name"))
                ||((Number)row.get("non_unique")).intValue()!=nonUnique
                ||((Number)row.get("seq_in_index")).intValue()!=i+1||row.get("sub_part")!=null)
            throw new IllegalStateException("Controlled-image bridge index drift: "+name);}
    }
    private static Map<String,Column> columns(){Map<String,Column> c=new LinkedHashMap<>();
        c.put("id",n("bigint","NO"));c.put("owner_jiacn",b("varchar(50)","NO"));
        c.put("task_id",b("varchar(100)","NO"));c.put("assignment_idempotency_key",b("varchar(100)","NO"));
        c.put("wrapper_digest",a("char(64)","NO"));c.put("consent_id",b("varchar(100)","NO"));
        c.put("expected_consent_version",n("bigint","NO"));c.put("grant_id",b("varchar(100)","NO"));
        c.put("grant_version",n("bigint","NO"));c.put("assignment_revision",n("bigint","NO"));
        c.put("authority_locator",b("varchar(100)","NO"));c.put("created_at",n("bigint","NO"));
        c.put("tenant_id",b("varchar(50)","NO"));c.put("client_id",b("varchar(50)","NO"));
        c.put("create_time",n("bigint","YES"));c.put("update_time",n("bigint","YES"));return c;}
    private static Column n(String type,String nullable){return new Column(type,nullable,null);}
    private static Column b(String type,String nullable){return new Column(type,nullable,"utf8mb4_0900_bin");}
    private static Column a(String type,String nullable){return new Column(type,nullable,"ascii_bin");}
    private record Column(String type,String nullable,String collation) { }
    /** Exact MySQL 8 CHECK_CLAUSE rendering for the additive DDL. This deliberately mirrors
     * the provider-consent catalog validation instead of weakening predicates through a generic
     * parenthesis or REGEXP normalizer. */
    private static Map<String,String> checks(){Map<String,String> c=new LinkedHashMap<>();
        c.put("chk_acibo_scope","((tenant_id='0') and (owner_jiacn<>'0'))");
        c.put("chk_acibo_hash","regexp_like(wrapper_digest,cast('^[0-9a-f]{64}$' as char charset binary))");
        c.put("chk_acibo_consent","regexp_like(consent_id,cast('^consent_[0-9a-f]{32}$' as char charset binary))");
        c.put("chk_acibo_locator","(authority_locator=concat('mmd-ci-v1:',consent_id))");
        c.put("chk_acibo_versions","((expected_consent_version>0) and (grant_version>0) and (assignment_revision>=0))");
        c.put("chk_acibo_time","(created_at>0)");return Map.copyOf(c);}
    static String ddl(){try{String source=new ClassPathResource("db/agent-controlled-image-bridge-v1.sql").getContentAsString(StandardCharsets.UTF_8).trim();String normalized=source.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");if(!normalized.startsWith("create table if not exists "+TABLE+" ")||normalized.substring(0,normalized.length()-1).contains(";")||normalized.contains(" alter table ")||normalized.contains(" drop ")||normalized.contains(" insert ")||normalized.contains(" update ")||normalized.contains(" delete "))throw new IllegalStateException("Unsafe bridge DDL");return source.endsWith(";")?source.substring(0,source.length()-1):source;}catch(Exception e){throw new IllegalStateException("Invalid bridge DDL",e);}}
}
