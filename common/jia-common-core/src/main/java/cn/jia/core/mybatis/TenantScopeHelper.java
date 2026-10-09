package cn.jia.core.mybatis;

/**
 * Tenant query predicates. A business area is always matched exactly: tenant
 * {@code 0} is an ordinary area, never a public-data fallback.
 *
 * @since 2026-08-01
 */
public final class TenantScopeHelper {

    /**
     * Historical bootstrap tenant value. It remains only so legacy modules can
     * compile while their explicit migration mode is still LEGACY_CLIENT; it is
     * never appended as a query fallback by this helper.
     */
    @Deprecated(since = "2026-09-23", forRemoval = true)
    public static final String DEFAULT_TENANT = "0";

    /**
     * Generates an exact tenant predicate for handwritten SQL.
     *
     * @param paramName MyBatis 参数名（如 "tenantId"）
     */
    public static String scopeClause(String paramName) {
        return "tenant_id = #{" + paramName + "}";
    }

    /**
     * Generates a binary exact tenant predicate for legacy non-binary collations.
     *
     * @param paramName MyBatis 参数名
     * @param width     BINARY 宽度（如 200）
     */
    public static String scopeClauseWithCast(String paramName, int width) {
        return "CAST(tenant_id AS BINARY(" + width + ")) = CAST(#{" + paramName
                + "} AS BINARY(" + width + "))";
    }

    /**
     * Generates a length-aware exact tenant predicate.
     *
     * @param paramName MyBatis 参数名
     */
    public static String scopeClauseWithOctet(String paramName) {
        return "tenant_id = #{" + paramName + "} AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{" + paramName + "})";
    }

    private TenantScopeHelper() {
        // utility class
    }
}
