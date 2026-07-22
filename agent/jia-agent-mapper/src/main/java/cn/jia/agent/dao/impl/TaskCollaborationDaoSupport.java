package cn.jia.agent.dao.impl;

import cn.jia.core.entity.BaseEntity;
import cn.jia.core.util.StringUtil;

final class TaskCollaborationDaoSupport {
    private TaskCollaborationDaoSupport() {
    }

    static void requireScope(String tenantId, String clientId) {
        if (StringUtil.isBlank(tenantId) || StringUtil.isBlank(clientId)) {
            throw new IllegalArgumentException("tenantId and clientId are required");
        }
    }

    static void requireId(String value, String name) {
        if (StringUtil.isBlank(value)) {
            throw new IllegalArgumentException(name + " is required");
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
