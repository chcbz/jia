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

/** Additive-only initializer that rejects provider-consent catalog drift. */
@org.springframework.context.annotation.Profile("schema-maintenance")
public final class AgentTaskProviderCostConsentSchemaInitializer implements InitializingBean {
    static final String TABLE="agent_task_provider_cost_consent";

    private final JdbcTemplate jdbc;
    public AgentTaskProviderCostConsentSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc=Objects.requireNonNull(jdbc,"jdbc");
    }
    @Override public void afterPropertiesSet() {
        requireMySql8();
        Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name=?",Integer.class,TABLE);
        if (count==null || count==0) jdbc.execute(ddl());
        validate();
    }
    static String ddl() {
        try {
            String source=new ClassPathResource("db/agent-task-provider-cost-consent-v1.sql")
                    .getContentAsString(StandardCharsets.UTF_8).trim();
            String plain=source.replaceAll("(?m)^\\s*--.*$"," ").trim();
            String normalized=plain.toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
            if (!normalized.startsWith("create table if not exists "+TABLE+" ")
                    || normalized.substring(0,normalized.length()-1).contains(";")
                    || normalized.contains(" alter table ") || normalized.contains(" drop ")
                    || normalized.contains(" insert ") || normalized.contains(" update ")
                    || normalized.contains(" delete ") || normalized.contains(" replace ")) {
                throw new IllegalStateException("Unsafe provider-consent DDL");
            }
            return source.endsWith(";")?source.substring(0,source.length()-1):source;
        } catch (Exception failure) { throw new IllegalStateException("Invalid provider-consent DDL",failure); }
    }
    void validate() {
        Map<String,Object> table=jdbc.queryForMap("SELECT engine,table_collation FROM "
                +"information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",TABLE);
        if (!"InnoDB".equalsIgnoreCase(Objects.toString(table.get("engine"),""))
                || !"utf8mb4_0900_bin".equalsIgnoreCase(Objects.toString(table.get("table_collation"),""))) {
            throw new IllegalStateException("Provider-consent table engine/collation drift");
        }
        List<Map<String,Object>> columns=jdbc.queryForList("SELECT column_name,column_type,is_nullable,"
                +"collation_name,column_default FROM information_schema.columns WHERE table_schema=DATABASE() "
                +"AND table_name=? ORDER BY ordinal_position",TABLE);
        List<Map<String,Object>> indexes=jdbc.queryForList("SELECT index_name,non_unique,seq_in_index,"
                +"column_name,sub_part FROM information_schema.statistics WHERE table_schema=DATABASE() "
                +"AND table_name=? ORDER BY index_name,seq_in_index",TABLE);
        validateCatalog(columns,indexes);
    }
    /** Accept exactly the pristine v1 catalog or the complete follow-up-v3 extension. */
    static void validateCatalog(List<Map<String,Object>> columnRows,List<Map<String,Object>> indexRows) {
        List<String> names=columnRows.stream().map(row->text(row,"column_name")).toList();
        Map<String,Column> expected;
        Map<String,Index> expectedIndexes;
        if (names.equals(columns().keySet().stream().toList())) {
            expected=columns();expectedIndexes=indexes(false);
        } else if (names.equals(extendedColumns().keySet().stream().toList())) {
            expected=extendedColumns();expectedIndexes=indexes(true);
        } else {
            throw new IllegalStateException("Provider-consent columns drift");
        }
        for (Map<String,Object> row:columnRows) {
            String name=text(row,"column_name");Column wanted=expected.get(name);
            String actualCollation=value(row,"collation_name")==null?null:
                    text(row,"collation_name").toLowerCase(Locale.ROOT);
            String actualDefault=value(row,"column_default")==null?null:
                    Objects.toString(value(row,"column_default"));
            if (wanted==null || !wanted.type().equalsIgnoreCase(text(row,"column_type"))
                    || !wanted.nullable().equalsIgnoreCase(text(row,"is_nullable"))
                    || !Objects.equals(wanted.collation(),actualCollation)
                    || !Objects.equals(wanted.defaultValue(),actualDefault)) {
                throw new IllegalStateException("Provider-consent column drift: "+name);
            }
        }
        if (!indexRows.stream().map(row->text(row,"index_name")).distinct().collect(
                java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new))
                .equals(expectedIndexes.keySet())) {
            throw new IllegalStateException("Provider-consent index set drift");
        }
        expectedIndexes.forEach((name,index)->index(indexRows,name,index.nonUnique(),index.columns()));
        int expectedRows=expectedIndexes.values().stream().mapToInt(index->index.columns().size()).sum();
        if (indexRows.size()!=expectedRows) throw new IllegalStateException("Provider-consent extra index drift");
    }

    private void requireMySql8() {
        try (var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            var metadata=connection.getMetaData();
            if (!metadata.getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql")
                    || metadata.getDatabaseMajorVersion()<8) throw new IllegalStateException("Provider consent requires MySQL 8");
        } catch (Exception failure) { throw new IllegalStateException("Provider-consent database unavailable",failure); }
    }
    private static void index(List<Map<String,Object>> rows,String name,int nonUnique,List<String> columns) {
        List<Map<String,Object>> matching=rows.stream().filter(row->name.equals(row.get("index_name"))).toList();
        if (matching.size()!=columns.size()) throw new IllegalStateException("Provider-consent index drift: "+name);
        for (int i=0;i<columns.size();i++) { Map<String,Object> row=matching.get(i);
            if (!columns.get(i).equals(row.get("column_name"))
                    || ((Number)row.get("seq_in_index")).intValue()!=i+1
                    || ((Number)row.get("non_unique")).intValue()!=nonUnique || row.get("sub_part")!=null) {
                throw new IllegalStateException("Provider-consent index drift: "+name);
            }
        }
    }
    static Map<String,Column> v1ColumnDefinitions() { return columns(); }
    static Map<String,Column> v3ColumnDefinitions() { return extendedColumns(); }
    static Map<String,Index> v1IndexDefinitions() { return indexes(false); }
    static Map<String,Index> v3IndexDefinitions() { return indexes(true); }

    private static Map<String,Column> columns() {
        Map<String,Column> c=new LinkedHashMap<>();
        c.put("id",n("bigint","NO"));c.put("consent_id",b("varchar(100)","NO"));
        c.put("owner_jiacn",b("varchar(50)","NO"));c.put("task_id",b("varchar(100)","NO"));
        c.put("target_agent_id",b("varchar(100)","NO"));c.put("idempotency_key",b("varchar(100)","NO"));
        c.put("request_digest",a("char(64)","NO"));c.put("assignment_idempotency_key",b("varchar(100)","NO"));
        c.put("assignment_base_hash",a("char(64)","NO"));c.put("task_version",n("bigint","NO"));
        c.put("requirement_revision",n("bigint","NO"));c.put("requirement_sha256",a("char(64)","NO"));
        c.put("input_snapshot_digest",a("char(64)","NO"));c.put("input_snapshot_json",n("json","NO"));
        c.put("provider_lane",b("varchar(50)","NO"));c.put("binding_id",b("varchar(100)","NO"));
        c.put("binding_epoch",n("bigint","NO"));c.put("model_id",b("varchar(100)","NO"));
        c.put("custody",b("varchar(50)","NO"));c.put("operator_issuer",b("varchar(100)","NO"));
        c.put("operator_policy_revision",b("varchar(100)","NO"));c.put("pricing_mode",b("varchar(50)","NO"));
        c.put("max_outbound_request_attempts",n("int","NO"));c.put("expires_at",n("bigint","NO"));
        c.put("state",b("varchar(20)","NO"));c.put("version",n("bigint","NO"));
        c.put("bound_grant_id",b("varchar(100)","YES"));c.put("bound_grant_version",n("bigint","YES"));
        c.put("bound_assignment_revision",n("bigint","YES"));c.put("reserved_execution_id",b("varchar(100)","YES"));
        c.put("reserved_run_id",b("varchar(100)","YES"));c.put("consumed_lease_id",b("varchar(100)","YES"));
        c.put("consumed_at",n("bigint","YES"));c.put("revoke_idempotency_key",b("varchar(100)","YES"));
        c.put("revoke_request_digest",a("char(64)","YES"));c.put("revoked_at",n("bigint","YES"));
        c.put("created_at",n("bigint","NO"));c.put("tenant_id",b("varchar(50)","NO"));
        c.put("client_id",b("varchar(50)","NO"));c.put("create_time",n("bigint","YES"));
        c.put("update_time",n("bigint","YES")); return java.util.Collections.unmodifiableMap(c);
    }
    private static Map<String,Column> extendedColumns() {
        Map<String,Column> c=new LinkedHashMap<>(columns());
        c.put("consent_purpose",b("varchar(30)","NO","INITIAL_ASSIGN_AND_START"));
        c.put("operation_grant_id",b("varchar(100)","YES"));
        c.put("execution_intent_id",b("varchar(100)","YES"));
        c.put("conversation_id",b("varchar(100)","YES"));
        c.put("conversation_generation",n("bigint","YES"));
        c.put("operation",b("varchar(40)","YES"));
        c.put("instruction_sha256",a("char(64)","YES"));
        c.put("source_snapshot_sha256",a("char(64)","YES"));
        c.put("owner_payload_sha256",a("char(64)","YES"));
        c.put("runtime_input_snapshot_sha256",a("char(64)","YES"));
        return java.util.Collections.unmodifiableMap(c);
    }
    private static Map<String,Index> indexes(boolean extended) {
        Map<String,Index> indexes=new LinkedHashMap<>();
        indexes.put("PRIMARY",new Index(0,List.of("id")));
        indexes.put("idx_atpcc_task_state",new Index(1,List.of("tenant_id","client_id","owner_jiacn","task_id","state")));
        indexes.put("uk_atpcc_scope_consent",new Index(0,List.of("tenant_id","client_id","owner_jiacn","task_id","consent_id")));
        if (extended) {
            indexes.put("uk_atpcc_scope_execution_intent",new Index(0,List.of("tenant_id","client_id","owner_jiacn","task_id","execution_intent_id")));
        }
        indexes.put("uk_atpcc_scope_key",new Index(0,List.of("tenant_id","client_id","owner_jiacn","task_id","idempotency_key")));
        if (extended) {
            indexes.put("uk_atpcc_scope_operation_grant",new Index(0,List.of("tenant_id","client_id","owner_jiacn","task_id","operation_grant_id")));
        }
        return java.util.Collections.unmodifiableMap(indexes);
    }
    private static Object value(Map<String,Object> row,String key) {
        return row.entrySet().stream().filter(entry->entry.getKey().equalsIgnoreCase(key))
                .findFirst().map(Map.Entry::getValue).orElse(null);
    }
    private static String text(Map<String,Object> row,String key) {
        Object value=value(row,key);return value==null?"":value.toString();
    }
    private static Column n(String type,String nullable){return new Column(type,nullable,null,null);}
    private static Column b(String type,String nullable){return new Column(type,nullable,"utf8mb4_0900_bin",null);}
    private static Column b(String type,String nullable,String defaultValue){return new Column(type,nullable,"utf8mb4_0900_bin",defaultValue);}
    private static Column a(String type,String nullable){return new Column(type,nullable,"ascii_bin",null);}
    record Column(String type,String nullable,String collation,String defaultValue) { }
    record Index(int nonUnique,List<String> columns) { }
}
