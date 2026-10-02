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
public final class AgentTaskProviderCostConsentSchemaInitializer implements InitializingBean {
    static final String TABLE="agent_task_provider_cost_consent";
    /** Exact MySQL 8 CHECK_CLAUSE rendering for the additive DDL below. Keeping this
     * provider-specific avoids weakening the shared funded-task normalizer around BETWEEN's
     * syntactic AND or MySQL's REGEXP BINARY -> regexp_like/cast catalog rewrite. */
    private static final Map<String,String> CHECK_EXPRESSIONS=Map.ofEntries(
            Map.entry("chk_atpcc_bound", """
                    (((bound_grant_id IS NULL) AND (bound_grant_version IS NULL)
                      AND (bound_assignment_revision IS NULL)) OR
                    ((bound_grant_id IS NOT NULL) AND (bound_grant_version IS NOT NULL)
                      AND (bound_assignment_revision IS NOT NULL)
                      AND (state IN ('BOUND','RESERVED','CONSUMED','REVOKED'))))
                    """),
            Map.entry("chk_atpcc_consumed", """
                    (((state='CONSUMED') AND (consumed_lease_id IS NOT NULL)
                      AND (consumed_at IS NOT NULL) AND (reserved_execution_id IS NOT NULL)) OR
                    ((state<>'CONSUMED') AND (consumed_lease_id IS NULL) AND (consumed_at IS NULL)))
                    """),
            Map.entry("chk_atpcc_hashes", """
                    (regexp_like(request_digest,cast('^[0-9a-f]{64}$' as char charset binary))
                      AND regexp_like(assignment_base_hash,cast('^[0-9a-f]{64}$' as char charset binary))
                      AND regexp_like(requirement_sha256,cast('^[0-9a-f]{64}$' as char charset binary))
                      AND regexp_like(input_snapshot_digest,cast('^[0-9a-f]{64}$' as char charset binary))
                      AND ((revoke_request_digest IS NULL) OR
                        regexp_like(revoke_request_digest,cast('^[0-9a-f]{64}$' as char charset binary))))
                    """),
            Map.entry("chk_atpcc_identity", """
                    ((char_length(client_id) BETWEEN 1 AND 50)
                      AND (char_length(owner_jiacn) BETWEEN 1 AND 50)
                      AND (char_length(consent_id) BETWEEN 1 AND 100)
                      AND (char_length(task_id) BETWEEN 1 AND 100)
                      AND (char_length(target_agent_id) BETWEEN 1 AND 100)
                      AND (char_length(idempotency_key) BETWEEN 1 AND 100)
                      AND (char_length(assignment_idempotency_key) BETWEEN 1 AND 100)
                      AND (char_length(binding_id) BETWEEN 1 AND 100)
                      AND (char_length(model_id) BETWEEN 1 AND 100)
                      AND (char_length(operator_issuer) BETWEEN 1 AND 100)
                      AND (char_length(operator_policy_revision) BETWEEN 1 AND 100))
                    """),
            Map.entry("chk_atpcc_inputs", """
                    ((json_type(input_snapshot_json)='ARRAY')
                      AND (json_length(input_snapshot_json) BETWEEN 0 AND 16))
                    """),
            Map.entry("chk_atpcc_issued", """
                    ((state<>'ISSUED') OR ((bound_grant_id IS NULL)
                      AND (reserved_execution_id IS NULL) AND (consumed_lease_id IS NULL)
                      AND (revoke_idempotency_key IS NULL)))
                    """),
            Map.entry("chk_atpcc_provider", """
                    ((provider_lane='CONTROLLED_IMAGE_HTTP_V1')
                      AND (pricing_mode='UNPRICED_EXTERNAL_ACCOUNT')
                      AND (max_outbound_request_attempts=1) AND (binding_epoch>0) AND (expires_at>0))
                    """),
            Map.entry("chk_atpcc_reserved", """
                    (((reserved_execution_id IS NULL) AND (reserved_run_id IS NULL)) OR
                    ((reserved_execution_id IS NOT NULL) AND (reserved_run_id IS NOT NULL)
                      AND (bound_grant_id IS NOT NULL)
                      AND (state IN ('RESERVED','CONSUMED','REVOKED'))))
                    """),
            Map.entry("chk_atpcc_revoked", """
                    (((state='REVOKED') AND (revoke_idempotency_key IS NOT NULL)
                      AND (revoke_request_digest IS NOT NULL) AND (revoked_at IS NOT NULL)) OR
                    ((state<>'REVOKED') AND (revoke_idempotency_key IS NULL)
                      AND (revoke_request_digest IS NULL) AND (revoked_at IS NULL)))
                    """),
            Map.entry("chk_atpcc_scope", "((tenant_id='0') AND (owner_jiacn<>'0'))"),
            Map.entry("chk_atpcc_state",
                    "(state IN ('ISSUED','BOUND','RESERVED','CONSUMED','REVOKED'))"),
            Map.entry("chk_atpcc_time", """
                    ((created_at>0) AND ((consumed_at IS NULL) OR (consumed_at>=created_at))
                      AND ((revoked_at IS NULL) OR (revoked_at>=created_at)))
                    """),
            Map.entry("chk_atpcc_versions", """
                    ((task_version>=0) AND (requirement_revision>0) AND (version>0)
                      AND ((bound_grant_version IS NULL) OR (bound_grant_version>0))
                      AND ((bound_assignment_revision IS NULL) OR (bound_assignment_revision>=0)))
                    """));
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
        List<Map<String,Object>> checks=jdbc.queryForList("SELECT tc.constraint_name,tc.enforced,"
                +"cc.check_clause FROM information_schema.table_constraints tc JOIN "
                +"information_schema.check_constraints cc ON cc.constraint_catalog=tc.constraint_catalog "
                +"AND cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name "
                +"WHERE tc.constraint_schema=DATABASE() AND tc.table_name=? "
                +"AND tc.constraint_type='CHECK'",TABLE);
        validateCatalog(columns,indexes,checks);
    }
    /** Accept exactly the pristine v1 catalog or the complete follow-up-v3 extension. */
    static void validateCatalog(List<Map<String,Object>> columnRows,List<Map<String,Object>> indexRows,
            List<Map<String,Object>> checkRows) {
        List<String> names=columnRows.stream().map(row->text(row,"column_name")).toList();
        Map<String,Column> expected;
        Map<String,Index> expectedIndexes;
        Map<String,String> expectedChecks;
        if (names.equals(columns().keySet().stream().toList())) {
            expected=columns();expectedIndexes=indexes(false);expectedChecks=CHECK_EXPRESSIONS;
        } else if (names.equals(extendedColumns().keySet().stream().toList())) {
            expected=extendedColumns();expectedIndexes=indexes(true);
            expectedChecks=new LinkedHashMap<>(CHECK_EXPRESSIONS);
            expectedChecks.put("chk_atpcc_purpose_union",
                    ControlledImageFollowupV3SchemaInitializer.consentPurposeCatalogCheckExpression());
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
        validateChecks(checkRows,expectedChecks);
    }

    static void validateChecks(List<Map<String,Object>> rows) {
        validateChecks(rows,CHECK_EXPRESSIONS);
    }
    private static void validateChecks(List<Map<String,Object>> rows,Map<String,String> expectedChecks) {
        record Check(String enforced,String clause) { }
        Map<String,Check> actual=new LinkedHashMap<>();
        for (Map<String,Object> row:rows) {
            String name=text(row,"constraint_name");
            Check found=new Check(text(row,"enforced"),text(row,"check_clause"));
            if (name.isBlank() || actual.put(name,found)!=null) {
                throw new IllegalStateException("Provider-consent ambiguous CHECK catalog");
            }
        }
        if (!actual.keySet().equals(expectedChecks.keySet())) {
            throw new IllegalStateException("Provider-consent CHECK set drift");
        }
        for (Map.Entry<String,String> expected:expectedChecks.entrySet()) {
            Check found=actual.get(expected.getKey());
            if (found==null || !"YES".equalsIgnoreCase(found.enforced())) {
                throw new IllegalStateException("Provider-consent CHECK enforcement drift: "+expected.getKey());
            }
            String wanted;
            String clause;
            try {
                wanted=ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(
                        expected.getValue());
                clause=ControlledImageFollowupV3SchemaInitializer.canonicalControlledCheck(
                        found.clause());
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(
                        "Provider-consent malformed CHECK catalog: "+expected.getKey(),malformed);
            }
            if (!wanted.equals(clause)) {
                throw new IllegalStateException("Provider-consent CHECK definition drift: "+expected.getKey());
            }
        }
    }
    static Map<String,String> checkExpressions() {
        return CHECK_EXPRESSIONS;
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
    static Map<String,String> v3CheckExpressions() {
        Map<String,String> checks=new LinkedHashMap<>(CHECK_EXPRESSIONS);
        checks.put("chk_atpcc_purpose_union",
                ControlledImageFollowupV3SchemaInitializer.consentPurposeCatalogCheckExpression());
        return java.util.Collections.unmodifiableMap(checks);
    }
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
