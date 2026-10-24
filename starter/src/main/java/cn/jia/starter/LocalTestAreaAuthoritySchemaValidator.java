package cn.jia.starter;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcOperations;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Read-only catalog validation for the LOCAL_TEST control-plane authority tables. */
final class LocalTestAreaAuthoritySchemaValidator {
    private static final List<TableSpec> REQUIRED_TABLES = List.of(
            table("core_tenant", pk("tenant_id"),
                    required("tenant_id"), required("tenant_code"), required("tenant_name"),
                    required("tenant_kind"), required("policy_revision"), required("created_at")),
            table("core_tenant_control", pk("tenant_id"),
                    required("tenant_id"), required("state"), required("control_revision"),
                    required("tenant_session_epoch"), required("admission_epoch"), required("drain_epoch"),
                    nullable("drain_started_at"), nullable("drain_deadline")),
            table("user_info", pk("id"),
                    required("id"), nullable("jiacn"), required("account_state"),
                    required("auth_epoch")),
            table("oauth_client", pk("id"),
                    required("id"), required("client_id")),
            table("core_tenant_membership", pk("tenant_id", "uid"),
                    required("tenant_id"), required("uid"), required("status"),
                    required("membership_revision"), required("created_at")),
            table("oauth_client_tenant_grant", pk("client_id", "tenant_id"),
                    required("client_id"), required("tenant_id"), required("status"),
                    required("grant_revision"), required("created_at")),
            table("oauth_client_tenant_domain_grant", pk("client_id", "tenant_id", "data_domain_id"),
                    required("client_id"), required("tenant_id"), required("data_domain_id"),
                    required("status"), required("domain_revision"), required("created_at")),
            table("core_tenant_operation", pk("tenant_id", "operator_id", "idempotency_key"),
                    required("tenant_id"), required("operator_id"), required("idempotency_key"),
                    required("request_hash"), required("expected_revision"), required("from_state"),
                    required("to_state"), required("result_revision"), required("result_snapshot"),
                    required("created_at")),
            table("core_tenant_module_control", pk("tenant_id", "module_id"),
                    required("tenant_id"), required("module_id"), required("mode_state"),
                    required("mode_revision"), required("migration_epoch"), required("drain_sealed"),
                    required("schema_version"), nullable("mapping_digest"), nullable("drain_cutoff"),
                    nullable("drain_deadline")),
            table("core_deployment_registration", pk("deployment_id"),
                    required("deployment_id"), required("stage"), required("artifact_digest"),
                    required("credential_digest"), required("status"), required("worker_generation"),
                    required("registration_revision")),
            table("core_deployment_tenant_grant", pk("deployment_id", "tenant_id", "data_domain_id"),
                    required("deployment_id"), required("tenant_id"), required("data_domain_id"),
                    required("execution_pool_id"), required("status"), required("grant_revision"),
                    required("allow_read"), required("allow_write")),
            table("core_deployment_module_admission",
                    pk("deployment_id", "tenant_id", "data_domain_id", "module_id"),
                    required("deployment_id"), required("tenant_id"), required("data_domain_id"),
                    required("module_id"), required("mode_state"), required("mode_revision"),
                    required("migration_epoch"), required("admission_epoch"), required("schema_version"),
                    required("registration_revision"), required("grant_revision"), required("allow_read"),
                    required("allow_write"), required("status")));

    private LocalTestAreaAuthoritySchemaValidator() {
    }

    static void verify(JdbcOperations jdbc) {
        try {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                validate(connection);
                return null;
            });
        } catch (RuntimeException failure) {
            throw denied();
        }
    }

    private static void validate(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = connection.getCatalog();
        if (catalog == null || catalog.isBlank()) throw denied();
        String schema = connection.getSchema();
        Map<String, TableRef> tables = tables(metadata, catalog, schema);
        for (TableSpec expected : REQUIRED_TABLES) {
            TableRef actual = tables.get(expected.name());
            if (actual == null) throw denied();
            validateColumns(metadata, actual, expected);
            validatePrimaryKey(metadata, actual, expected);
        }
    }

    private static Map<String, TableRef> tables(DatabaseMetaData metadata, String catalog, String schema)
            throws SQLException {
        Map<String, TableRef> tables = new HashMap<>();
        try (ResultSet rows = metadata.getTables(catalog, null, "%", null)) {
            while (rows.next()) {
                String type = rows.getString("TABLE_TYPE");
                if (!"TABLE".equalsIgnoreCase(type) && !"BASE TABLE".equalsIgnoreCase(type)) continue;
                if (!sameIdentifier(catalog, rows.getString("TABLE_CAT"))
                        || !sameIdentifier(schema, rows.getString("TABLE_SCHEM"))) {
                    continue;
                }
                TableRef table = new TableRef(rows.getString("TABLE_CAT"), rows.getString("TABLE_SCHEM"),
                        rows.getString("TABLE_NAME"));
                String normalized = normalized(table.name());
                if (tables.putIfAbsent(normalized, table) != null) throw denied();
            }
        }
        return tables;
    }

    private static void validateColumns(DatabaseMetaData metadata, TableRef table, TableSpec expected)
            throws SQLException {
        Map<String, Boolean> columns = new HashMap<>();
        try (ResultSet rows = metadata.getColumns(table.catalog(), null, "%", "%")) {
            while (rows.next()) {
                if (!sameIdentifier(table.catalog(), rows.getString("TABLE_CAT"))
                        || !sameIdentifier(table.schema(), rows.getString("TABLE_SCHEM"))
                        || !sameIdentifier(table.name(), rows.getString("TABLE_NAME"))) {
                    continue;
                }
                String name = normalized(rows.getString("COLUMN_NAME"));
                int nullable = rows.getInt("NULLABLE");
                if (nullable != DatabaseMetaData.columnNoNulls && nullable != DatabaseMetaData.columnNullable) {
                    throw denied();
                }
                if (columns.putIfAbsent(name, nullable == DatabaseMetaData.columnNullable) != null) {
                    throw denied();
                }
            }
        }
        for (ColumnSpec column : expected.columns().values()) {
            Boolean nullable = columns.get(column.name());
            if (nullable == null || nullable != column.nullable()) throw denied();
        }
    }

    private static void validatePrimaryKey(DatabaseMetaData metadata, TableRef table, TableSpec expected)
            throws SQLException {
        List<PrimaryKeyColumn> primaryKey = new ArrayList<>();
        try (ResultSet rows = metadata.getPrimaryKeys(table.catalog(), table.schema(), table.name())) {
            while (rows.next()) {
                primaryKey.add(new PrimaryKeyColumn(rows.getShort("KEY_SEQ"),
                        normalized(rows.getString("COLUMN_NAME"))));
            }
        }
        primaryKey.sort(Comparator.comparingInt(PrimaryKeyColumn::sequence));
        if (primaryKey.size() != expected.primaryKey().size()) throw denied();
        for (int index = 0; index < primaryKey.size(); index++) {
            PrimaryKeyColumn actual = primaryKey.get(index);
            if (actual.sequence() != index + 1 || !actual.name().equals(expected.primaryKey().get(index))) {
                throw denied();
            }
        }
    }

    private static TableSpec table(String name, List<String> primaryKey, ColumnSpec... columns) {
        Map<String, ColumnSpec> byName = new LinkedHashMap<>();
        for (ColumnSpec column : columns) {
            if (byName.putIfAbsent(column.name(), column) != null) throw new IllegalArgumentException();
        }
        return new TableSpec(normalized(name), List.copyOf(primaryKey), Map.copyOf(byName));
    }

    private static List<String> pk(String... columns) {
        return java.util.Arrays.stream(columns).map(LocalTestAreaAuthoritySchemaValidator::normalized).toList();
    }

    private static ColumnSpec required(String name) {
        return new ColumnSpec(normalized(name), false);
    }

    private static ColumnSpec nullable(String name) {
        return new ColumnSpec(normalized(name), true);
    }

    private static boolean sameIdentifier(String expected, String actual) {
        if (expected == null || actual == null) return expected == actual;
        return normalized(expected).equals(normalized(actual));
    }

    private static String normalized(String identifier) {
        if (identifier == null || identifier.isBlank()) throw denied();
        return identifier.toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("Local TEST deployment registration is unavailable");
    }

    private record TableSpec(String name, List<String> primaryKey, Map<String, ColumnSpec> columns) {
    }

    private record ColumnSpec(String name, boolean nullable) {
    }

    private record TableRef(String catalog, String schema, String name) {
    }

    private record PrimaryKeyColumn(int sequence, String name) {
    }
}
