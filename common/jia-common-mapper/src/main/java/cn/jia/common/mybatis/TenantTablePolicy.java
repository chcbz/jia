package cn.jia.common.mybatis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Explicit table inventory. Unknown and conflicting classifications fail closed. */
public final class TenantTablePolicy {
    public enum Classification { TENANT, GLOBAL }

    private final Map<String, Classification> classifications;

    private TenantTablePolicy(Map<String, Classification> classifications) {
        this.classifications = Map.copyOf(classifications);
    }

    public static TenantTablePolicy of(Collection<String> tenantTables,
            Collection<String> globalTables) {
        Objects.requireNonNull(tenantTables, "tenantTables");
        Objects.requireNonNull(globalTables, "globalTables");
        Map<String, Classification> values = new LinkedHashMap<>();
        tenantTables.forEach(table -> register(values, table, Classification.TENANT));
        globalTables.forEach(table -> register(values, table, Classification.GLOBAL));
        if (values.isEmpty()) {
            throw new TenantIsolationException("Tenant table inventory must not be empty");
        }
        return new TenantTablePolicy(values);
    }

    public Classification requireClassification(String tableName) {
        Classification classification = classifications.get(normalize(tableName));
        if (classification == null) {
            throw new TenantIsolationException("Unclassified SQL table: " + tableName);
        }
        return classification;
    }

    public boolean isTenantTable(String tableName) {
        return requireClassification(tableName) == Classification.TENANT;
    }

    private static void register(Map<String, Classification> values, String tableName,
            Classification classification) {
        String normalized = normalize(tableName);
        Classification previous = values.putIfAbsent(normalized, classification);
        if (previous != null && previous != classification) {
            throw new TenantIsolationException("Conflicting classification for table: " + tableName);
        }
    }

    private static String normalize(String tableName) {
        if (tableName == null) {
            throw new TenantIsolationException("SQL table name is unavailable");
        }
        String normalized = tableName.strip().replace("`", "").replace("\"", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.indexOf('.') >= 0
                || !normalized.matches("[a-z0-9_]+")) {
            throw new TenantIsolationException("Unsupported SQL table name: " + tableName);
        }
        return normalized;
    }
}
