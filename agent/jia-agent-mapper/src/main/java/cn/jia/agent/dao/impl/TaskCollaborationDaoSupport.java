package cn.jia.agent.dao.impl;

import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.util.List;

final class TaskCollaborationDaoSupport {
    private TaskCollaborationDaoSupport() {
    }

    static void requireScope(String tenantId, String clientId) {
        requireId(tenantId, "tenantId");
        requireId(clientId, "clientId");
    }

    static void requireId(String value, String name) {
        if (StringUtil.isBlank(value) || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static <T> LambdaQueryWrapper<T> exact(
            LambdaQueryWrapper<T> wrapper, String column, String value) {
        requireExactColumn(column);
        return wrapper
                .apply("CAST(" + column + " AS BINARY) = CAST({0} AS BINARY)", value)
                .apply("OCTET_LENGTH(" + column + ") = OCTET_LENGTH({0})", value);
    }

    static <T> LambdaQueryWrapper<T> exactAny(
            LambdaQueryWrapper<T> wrapper, String column, List<String> values) {
        requireExactColumn(column);
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("exact predicate values are required");
        }
        return wrapper.and(exact -> {
            boolean first = true;
            for (String value : values) {
                if (!first) {
                    exact.or();
                }
                exact.apply("(CAST(" + column + " AS BINARY) = CAST({0} AS BINARY) "
                        + "AND OCTET_LENGTH(" + column + ") = OCTET_LENGTH({0}))", value);
                first = false;
            }
        });
    }

    private static void requireExactColumn(String column) {
        if (column == null || !column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("exact predicate column is invalid");
        }
    }

    static void requireExpectedVersion(long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("expectedVersion must not be negative");
        }
    }

    static int boundedLimit(int limit) {
        return Math.max(1, Math.min(limit, 500));
    }

    static void applyScope(BaseEntity entity, String tenantId, String clientId) {
        entity.setTenantId(tenantId);
        entity.setClientId(clientId);
    }
}
