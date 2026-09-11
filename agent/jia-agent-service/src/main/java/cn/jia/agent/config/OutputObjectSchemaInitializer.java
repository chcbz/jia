package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Strict opt-in OD02 schema bootstrap. Existing partial installs fail closed. */
public final class OutputObjectSchemaInitializer implements InitializingBean {
    static final String RESOURCE = "db/output-delivery-002-object-schema.sql";
    static final List<String> TABLES = List.of("output_scope_quota", "output_binding_upload_quota",
            "output_run_upload_quota",
            "output_object", "output_upload_session", "output_object_reference",
            "output_mutation_receipt", "output_storage_cleanup_job");
    private final JdbcTemplate jdbc;

    public OutputObjectSchemaInitializer(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    @Override public void afterPropertiesSet() throws Exception {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) throw new IllegalStateException("OD02 requires a JDBC DataSource");
        try (Connection c = ds.getConnection()) {
            String product = c.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql"))
                throw new IllegalStateException("OD02 object schema requires MySQL");
        }
        List<String> present = presentTables();
        if (!present.isEmpty() && present.size() != TABLES.size())
            throw new IllegalStateException("OD02 partial object schema: " + present);
        if (present.isEmpty()) for (String statement : ddlStatements()) jdbc.execute(statement);
        if (presentTables().size() != TABLES.size())
            throw new IllegalStateException("OD02 object schema is incomplete");
        Map<String,List<String>> columns=Map.of(
                "output_scope_quota",List.of("tenant_id","client_id","reserved_bytes","stored_bytes","max_bytes","active_uploads","max_active_uploads","created_at","updated_at","row_version"),
                "output_binding_upload_quota",List.of("tenant_id","client_id","binding_id","active_uploads","max_active_uploads","created_at","updated_at","row_version"),
                "output_run_upload_quota",List.of("tenant_id","client_id","run_id","upload_requests","max_upload_requests","attempt_bytes","max_attempt_bytes","created_at","updated_at","row_version"),
                "output_object",List.of("tenant_id","client_id","object_id","run_id","bucket","storage_key","storage_version","actual_sha256","actual_size","actual_mime","verification_status","lifecycle_status","scan_engine_version","verified_at","delete_after","deleted_at","delete_attempts","delete_next_at","delete_lease_owner","delete_lease_until","error_code","created_at","updated_at","row_version"),
                "output_upload_session",List.of("tenant_id","client_id","upload_id","run_id","binding_id","object_id","file_name","expected_size","expected_sha256","declared_mime","state","writer_epoch","writer_started_at","writer_until","writer_deadline_at","expires_at","reserved_bytes","slot_released","verification_attempts","verification_next_at","verification_lease_owner","verification_lease_until","error_code","created_at","updated_at","row_version"),
                "output_object_reference",List.of("tenant_id","client_id","reference_key","object_id","source_type","source_id","output_id","output_version","reference_kind","delivery_id","state","retain_until","hold","hold_reason","released_at","created_at","updated_at","row_version"),
                "output_mutation_receipt",List.of("tenant_id","client_id","actor_kind","actor_id","operation","idempotency_key","request_hash","http_status","response_json","retain_until","created_at","updated_at","row_version"),
                "output_storage_cleanup_job",List.of("cleanup_id","tenant_id","client_id","object_id","upload_id","writer_epoch","bucket","storage_key","storage_version","state","quota_charge_kind","quota_charge_bytes","safe_after","attempts","next_attempt_at","lease_owner","lease_until","last_error","created_at","updated_at","row_version"));
        for(var entry:columns.entrySet())requireColumns(entry.getKey(),entry.getValue());
        requirePrimary("output_scope_quota",List.of("tenant_id","client_id"));requirePrimary("output_binding_upload_quota",List.of("tenant_id","client_id","binding_id"));requirePrimary("output_run_upload_quota",List.of("tenant_id","client_id","run_id"));requirePrimary("output_object",List.of("tenant_id","client_id","object_id"));requirePrimary("output_upload_session",List.of("tenant_id","client_id","upload_id"));requirePrimary("output_object_reference",List.of("tenant_id","client_id","reference_key"));requirePrimary("output_mutation_receipt",List.of("tenant_id","client_id","actor_kind","actor_id","operation","idempotency_key"));requirePrimary("output_storage_cleanup_job",List.of("tenant_id","client_id","cleanup_id"));
    }

    private List<String> presentTables() {
        String placeholders = String.join(",", TABLES.stream().map(x -> "?").toList());
        return jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ("
                + placeholders + ") ORDER BY table_name", String.class, TABLES.toArray());
    }

    private void requireColumns(String table, List<String> required) {
        List<String> actual = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position",
                String.class, table);
        String engine=jdbc.queryForObject("SELECT engine FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",String.class,table);
        String collation=jdbc.queryForObject("SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",String.class,table);
        if (!actual.equals(required)||!"InnoDB".equalsIgnoreCase(engine)||!"utf8mb4_0900_bin".equalsIgnoreCase(collation)) throw new IllegalStateException("OD02 schema drift: " + table);
    }

    private void requirePrimary(String table,List<String> expected){List<String> actual=jdbc.queryForList("SELECT column_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND index_name='PRIMARY' ORDER BY seq_in_index",String.class,table);if(!actual.equals(expected))throw new IllegalStateException("OD02 primary key drift: "+table);}

    static List<String> ddlStatements() throws Exception {
        String sql = new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String part : sql.split(";")) {
            String clean = part.replaceAll("(?m)^\\s*--.*$", " ").trim();
            if (!clean.isEmpty()) statements.add(clean);
        }
        if (statements.size() != TABLES.size()) throw new IllegalStateException("OD02 migration statement count drift");
        return statements;
    }
}
