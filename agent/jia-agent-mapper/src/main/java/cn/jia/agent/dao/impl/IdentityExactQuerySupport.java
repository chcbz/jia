package cn.jia.agent.dao.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

final class IdentityExactQuerySupport {
    private IdentityExactQuerySupport() {
    }

    static <T> QueryWrapper<T> exact(QueryWrapper<T> wrapper, String column, String value, int castBytes) {
        return wrapper.eq(column, value)
                .apply("CAST(" + column + " AS BINARY(" + castBytes + ")) = CAST({0} AS BINARY(" + castBytes + "))", value)
                .apply("OCTET_LENGTH(" + column + ") = OCTET_LENGTH({0})", value);
    }
}
