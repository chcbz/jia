package cn.jia.common.mybatis;

import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;

final class TrustedTenantLineHandler implements TenantLineHandler {
    private final TenantTablePolicy tablePolicy;

    TrustedTenantLineHandler(TenantTablePolicy tablePolicy) {
        this.tablePolicy = tablePolicy;
    }

    @Override
    public Expression getTenantId() {
        try {
            return new StringValue(TrustedTenantContext.requireTenantId().value());
        } catch (IllegalStateException exception) {
            throw new TenantIsolationException("Trusted tenant context is required for tenant SQL",
                    exception);
        }
    }

    @Override
    public boolean ignoreTable(String tableName) {
        return !tablePolicy.isTenantTable(tableName);
    }
}
