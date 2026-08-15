package cn.jia.core.mybatis;

/**
 * 租户查询范围统一工具。
 * <p>
 * tenant_id 字段规则：
 * <ul>
 *   <li>默认值为 {@code "0"}，表示公共/无租户划分的数据。</li>
 *   <li>写入时由业务代码显式指定 tenant_id。</li>
 *   <li>查询时，只要 IN（指定 tenantId, "0"），确保公共数据同时可见。</li>
 * </ul>
 * </p>
 *
 * @since 2026-08-01
 */
public final class TenantScopeHelper {

    /** 默认公共租户标识 */
    public static final String DEFAULT_TENANT = "0";

    /**
     * 生成手写 SQL 的 tenant 范围条件。
     * 形如 {@code (tenant_id = #{tenantId} OR tenant_id = '0')}。
     *
     * @param paramName MyBatis 参数名（如 "tenantId"）
     */
    public static String scopeClause(String paramName) {
        return "(tenant_id = #{" + paramName + "} OR tenant_id = '" + DEFAULT_TENANT + "')";
    }

    /**
     * 生成带 CAST(BINARY) 精确匹配的 tenant 范围条件。
     * 形如：
     * {@code (CAST(tenant_id AS BINARY) = CAST(#{paramName} AS BINARY) OR tenant_id = '0')}
     *
     * @param paramName MyBatis 参数名
     * @param width     BINARY 宽度（如 200）
     */
    public static String scopeClauseWithCast(String paramName, int width) {
        return "(CAST(tenant_id AS BINARY(" + width + ")) = CAST(#{" + paramName
                + "} AS BINARY(" + width + ")) OR tenant_id = '" + DEFAULT_TENANT + "')";
    }

    /**
     * 生成带 OCTET_LENGTH 精确匹配的 tenant 范围条件。
     * 形如：
     * {@code (OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{paramName}) OR tenant_id = '0')}
     *
     * @param paramName MyBatis 参数名
     */
    public static String scopeClauseWithOctet(String paramName) {
        return "(OCTET_LENGTH(tenant_id) = OCTET_LENGTH(#{" + paramName + "}) OR tenant_id = '" + DEFAULT_TENANT + "')";
    }

    private TenantScopeHelper() {
        // utility class
    }
}
