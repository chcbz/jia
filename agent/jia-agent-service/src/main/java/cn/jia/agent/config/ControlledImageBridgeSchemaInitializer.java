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
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class ControlledImageBridgeSchemaInitializer implements InitializingBean {
    static final String TABLE="agent_controlled_image_bridge_operation";

    private final JdbcTemplate jdbc;
    public ControlledImageBridgeSchemaInitializer(JdbcTemplate jdbc){this.jdbc=Objects.requireNonNull(jdbc);}
    @Override public void afterPropertiesSet(){
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",Integer.class,TABLE);
        if(count==null||count==0)jdbc.execute(ddl());
        else ensureV3Extension();
        Map<String,Object> table=jdbc.queryForMap("SELECT engine,table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",TABLE);
        if(!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"),""))
                ||!"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"),"")))
            throw new IllegalStateException("Controlled-image bridge table drift");
        validateColumns(jdbc.queryForList("SELECT column_name,column_type,is_nullable,collation_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",TABLE));
        Map<String,Object> protocolColumn=jdbc.queryForMap("SELECT column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='execution_protocol_version'",TABLE);
        Map<String,Object> operationGrantColumn=jdbc.queryForMap("SELECT column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name='operation_grant_id'",TABLE);
        if(!"2".equals(Objects.toString(protocolColumn.get("column_default"),""))
                ||!Objects.toString(protocolColumn.get("extra"),"").isBlank()
                ||operationGrantColumn.get("column_default")!=null
                ||!Objects.toString(operationGrantColumn.get("extra"),"").isBlank())
            throw new IllegalStateException("Controlled-image bridge v3 column default drift");
        var indexes=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? ORDER BY index_name,seq_in_index",TABLE);
        index(indexes,"PRIMARY",0,List.of("id"));
        index(indexes,"uk_acibo_scope_key",0,List.of("tenant_id","client_id","owner_jiacn","task_id","assignment_idempotency_key"));
        index(indexes,"uk_acibo_scope_consent",0,List.of("tenant_id","client_id","owner_jiacn","task_id","consent_id"));
        index(indexes,"uk_acibo_scope_grant",0,List.of("tenant_id","client_id","owner_jiacn","task_id","grant_id"));
        index(indexes,"uk_acibo_scope_operation_grant",0,List.of("tenant_id","client_id","owner_jiacn","task_id","operation_grant_id"));
        if(indexes.size()!=21)throw new IllegalStateException("Controlled-image bridge index drift");
    }

    private void ensureV3Extension() {
        addColumn("execution_protocol_version","INT NOT NULL DEFAULT 2 AFTER authority_locator");
        addColumn("operation_grant_id","VARCHAR(100) COLLATE utf8mb4_0900_bin DEFAULT NULL AFTER execution_protocol_version");
        Integer index=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='uk_acibo_scope_operation_grant'",Integer.class,TABLE);
        if(index==null||index==0)jdbc.execute("ALTER TABLE "+TABLE+" ADD UNIQUE KEY uk_acibo_scope_operation_grant(tenant_id,client_id,owner_jiacn,task_id,operation_grant_id)");
        else if(index!=5)throw new IllegalStateException("Controlled-image bridge operation-grant index drift");
    }
    private void addColumn(String name,String definition) {
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? AND column_name=?",Integer.class,TABLE,name);
        if(count==null||count==0)jdbc.execute("ALTER TABLE "+TABLE+" ADD COLUMN "+name+" "+definition);
        else if(count!=1)throw new IllegalStateException("Controlled-image bridge column ambiguity: "+name);
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
        c.put("authority_locator",b("varchar(100)","NO"));c.put("execution_protocol_version",n("int","NO"));
        c.put("operation_grant_id",b("varchar(100)","YES"));c.put("created_at",n("bigint","NO"));
        c.put("tenant_id",b("varchar(50)","NO"));c.put("client_id",b("varchar(50)","NO"));
        c.put("create_time",n("bigint","YES"));c.put("update_time",n("bigint","YES"));return c;}
    private static Column n(String type,String nullable){return new Column(type,nullable,null);}
    private static Column b(String type,String nullable){return new Column(type,nullable,"utf8mb4_0900_bin");}
    private static Column a(String type,String nullable){return new Column(type,nullable,"ascii_bin");}
    private record Column(String type,String nullable,String collation) { }

    static String ddl(){try{String source=new ClassPathResource("db/agent-controlled-image-bridge-v1.sql").getContentAsString(StandardCharsets.UTF_8).trim();String normalized=source.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");if(!normalized.startsWith("create table if not exists "+TABLE+" ")||normalized.substring(0,normalized.length()-1).contains(";")||normalized.contains(" alter table ")||normalized.contains(" drop ")||normalized.contains(" insert ")||normalized.contains(" update ")||normalized.contains(" delete "))throw new IllegalStateException("Unsafe bridge DDL");return source.endsWith(";")?source.substring(0,source.length()-1):source;}catch(Exception e){throw new IllegalStateException("Invalid bridge DDL",e);}}
}
