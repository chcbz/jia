package cn.jia.task.config;

import cn.jia.core.security.FixedTenantAdmissionPolicy;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import java.sql.Connection;
import java.sql.SQLException;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Set;

/** Process partition for legacy task CRUD and jobs, including tenant-zero production readers. */
@Configuration(proxyBeanMethods = false)
public class TaskTenantConfiguration {
    @Bean
    public MybatisPlusInterceptor taskPlanTenantInterceptor(
            FixedTenantAdmissionPolicy policy) {
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            private final Set<String> tables = Set.of("task_plan", "task_item", "v_task_item");

            @Override
            public Expression getTenantId() {
                // Independent of HTTP, user claims and thread-local state, including background jobs.
                return new StringValue(policy.businessTenantId());
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return !tables.contains(tableName.replace("`", "").toLowerCase(Locale.ROOT));
            }
        }) {
            private boolean taskStatement(MappedStatement statement) {
                return statement.getId().startsWith("cn.jia.task.mapper.");
            }

            @Override
            public void beforeQuery(Executor executor, MappedStatement statement, Object parameter,
                    RowBounds bounds, ResultHandler handler, BoundSql sql) throws SQLException {
                if (taskStatement(statement))
                    super.beforeQuery(executor, statement, parameter, bounds, handler, sql);
            }

            @Override
            public void beforePrepare(StatementHandler statement, Connection connection, Integer timeout) {
                if (taskStatement(PluginUtils.mpStatementHandler(statement).mappedStatement()))
                    super.beforePrepare(statement, connection, timeout);
            }
        });
        return interceptor;
    }
}
