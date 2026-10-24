package cn.jia.common.mybatis;

import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Validates tenant write values against the authenticated context immediately before the exact
 * BoundSql is bound. It also freezes one write tenant per Executor until transaction completion.
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "commit", args = {boolean.class}),
        @Signature(type = Executor.class, method = "rollback", args = {boolean.class}),
        @Signature(type = Executor.class, method = "close", args = {boolean.class}),
        @Signature(type = Executor.class, method = "flushStatements", args = {}),
        @Signature(type = StatementHandler.class, method = "parameterize",
                args = {java.sql.Statement.class}),
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {java.sql.Connection.class, Integer.class})
})
public final class TenantWriteGuard implements Interceptor {
    private static final Object TRANSACTION_TENANT_RESOURCE = new Object();
    private final TenantTablePolicy tablePolicy;
    private final ThreadLocal<Executor> activeExecutor = new ThreadLocal<>();
    private final Map<Executor, String> writeTenants =
            Collections.synchronizedMap(new WeakHashMap<>());

    public TenantWriteGuard(TenantTablePolicy tablePolicy) {
        this.tablePolicy = tablePolicy;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (invocation.getTarget() instanceof Executor executor) {
            return interceptExecutor(invocation, executor);
        }
        if (invocation.getTarget() instanceof StatementHandler statementHandler) {
            if (mappedStatement(statementHandler).getStatementType() == StatementType.CALLABLE) {
                throw new TenantIsolationException("Callable SQL is not supported by tenant isolation");
            }
            if ("prepare".equals(invocation.getMethod().getName())) {
                validatePreparableStatement(statementHandler.getBoundSql());
                Object prepared = invocation.proceed();
                validateFinalBoundSql(statementHandler);
                return prepared;
            }
            validateFinalBoundSql(statementHandler);
        }
        return invocation.proceed();
    }

    private Object interceptExecutor(Invocation invocation, Executor executor) throws Throwable {
        String method = invocation.getMethod().getName();
        if ("update".equals(method)) {
            Executor previous = activeExecutor.get();
            activeExecutor.set(executor);
            try {
                return invocation.proceed();
            } finally {
                restoreActiveExecutor(previous);
            }
        }
        if ("flushStatements".equals(method) || "commit".equals(method)) {
            requireFrozenExecutorTenant(executor);
        }
        try {
            return invocation.proceed();
        } finally {
            if ("commit".equals(method) || "rollback".equals(method) || "close".equals(method)) {
                writeTenants.remove(executor);
            }
        }
    }

    private void validateFinalBoundSql(StatementHandler handler) {
        BoundSql boundSql = handler.getBoundSql();
        Statement parsed;
        try {
            var statements = CCJSqlParserUtil.parseStatements(boundSql.getSql()).getStatements();
            if (statements.size() != 1) {
                throw new TenantIsolationException("Multiple SQL statements are not supported");
            }
            parsed = statements.get(0);
        } catch (Exception exception) {
            if (exception instanceof TenantIsolationException tenantException) {
                throw tenantException;
            }
            throw new TenantIsolationException("Unverifiable SQL for tenant isolation", exception);
        }
        validateStatementKind(boundSql.getSql(), parsed);

        Table table = writeTable(parsed);
        if (table == null) {
            if (parsed instanceof Select) return;
            throw new TenantIsolationException("Unsupported tenant write statement: "
                    + parsed.getClass().getSimpleName());
        }
        requireUnqualified(table);
        if (!tablePolicy.isTenantTable(table.getName())) {
            return;
        }

        String tenantId = requireCurrentTenant();
        Configuration configuration = mappedStatement(handler).getConfiguration();
        if (parsed instanceof Insert insert) {
            validateInsert(insert, tenantId, configuration, boundSql);
        } else if (parsed instanceof Update update) {
            validateUpdate(update, tenantId, configuration, boundSql);
        } else if (parsed instanceof Delete delete) {
            validateDelete(delete);
        }
        freezeExecutorTenant(tenantId);
    }

    private static void validatePreparableStatement(BoundSql boundSql) {
        try {
            var statements = CCJSqlParserUtil.parseStatements(boundSql.getSql()).getStatements();
            if (statements.size() != 1) {
                throw new TenantIsolationException("Multiple SQL statements are not supported");
            }
            validateStatementKind(boundSql.getSql(), statements.get(0));
        } catch (TenantIsolationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TenantIsolationException("Unverifiable SQL for tenant isolation", exception);
        }
    }

    private static Table writeTable(Statement statement) {
        if (statement instanceof Insert insert) return insert.getTable();
        if (statement instanceof Update update) return update.getTable();
        if (statement instanceof Delete delete) return delete.getTable();
        return null;
    }

    private static void validateInsert(Insert insert, String tenantId, Configuration configuration,
            BoundSql boundSql) {
        require(empty(insert.getDuplicateUpdateSets()) && empty(insert.getSetUpdateSets())
                        && insert.getConflictAction() == null && empty(insert.getWithItemsList())
                        && insert.getSelect() instanceof Values && insert.getColumns() != null,
                "Unsupported tenant INSERT shape");
        int tenantColumnIndex = -1;
        for (int index = 0; index < insert.getColumns().size(); index++) {
            if (isTenantColumn(insert.getColumns().get(index).getColumnName())) {
                require(tenantColumnIndex < 0, "Duplicate tenant_id INSERT column");
                tenantColumnIndex = index;
            }
        }
        require(tenantColumnIndex >= 0,
                "TenantLine interceptor did not supply tenant_id for INSERT");
        ExpressionList<?> values = ((Values) insert.getSelect()).getExpressions();
        if (!values.isEmpty() && values.get(0) instanceof ExpressionList<?>) {
            for (Expression expression : values) {
                require(expression instanceof ExpressionList<?>, "Unsupported tenant VALUES row");
                validateInsertRow((ExpressionList<?>) expression, insert.getColumns().size(),
                        tenantColumnIndex, tenantId, configuration, boundSql);
            }
        } else {
            validateInsertRow(values, insert.getColumns().size(), tenantColumnIndex, tenantId,
                    configuration, boundSql);
        }
    }

    private static void validateInsertRow(ExpressionList<?> row, int columnCount,
            int tenantColumnIndex, String tenantId, Configuration configuration, BoundSql boundSql) {
        require(row.size() == columnCount, "Tenant INSERT column/value arity mismatch");
        requireSameTenant(row.get(tenantColumnIndex), tenantId, configuration, boundSql);
    }

    private static void validateUpdate(Update update, String tenantId, Configuration configuration,
            BoundSql boundSql) {
        require(empty(update.getWithItemsList()) && empty(update.getJoins())
                        && empty(update.getStartJoins()) && update.getFromItem() == null,
                "Unsupported tenant UPDATE shape");
        update.getUpdateSets().forEach(updateSet -> {
            require(updateSet.getColumns().size() == updateSet.getValues().size(),
                    "Unsupported tuple UPDATE");
            for (int index = 0; index < updateSet.getColumns().size(); index++) {
                if (isTenantColumn(updateSet.getColumn(index).getColumnName())) {
                    requireSameTenant(updateSet.getValue(index), tenantId, configuration, boundSql);
                }
            }
        });
    }

    private static void validateDelete(Delete delete) {
        require(empty(delete.getWithItemsList()) && empty(delete.getTables())
                        && empty(delete.getJoins()) && empty(delete.getUsingList()),
                "Unsupported tenant DELETE shape");
    }

    private static void requireSameTenant(Expression expression, String tenantId,
            Configuration configuration, BoundSql boundSql) {
        Object value;
        if (expression instanceof JdbcParameter parameter) {
            Integer index = parameter.getIndex();
            List<ParameterMapping> mappings = boundSql.getParameterMappings();
            require(index != null && index > 0 && index <= mappings.size(),
                    "Invalid tenant bind index");
            ParameterMapping mapping = mappings.get(index - 1);
            require(mapping.getMode() != ParameterMode.OUT, "Unsupported tenant output binding");
            value = resolveBoundValue(mapping.getProperty(), configuration, boundSql);
        } else if (expression instanceof StringValue stringValue) {
            value = stringValue.getValue();
        } else {
            throw new TenantIsolationException(
                    "tenant_id must be a bound value or a server-added string literal");
        }
        require(value instanceof String && tenantId.equals(value),
                "tenant_id does not match the authenticated tenant");
    }

    private static Object resolveBoundValue(String property, Configuration configuration,
            BoundSql boundSql) {
        if (boundSql.hasAdditionalParameter(property)) {
            return boundSql.getAdditionalParameter(property);
        }
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject == null) return null;
        if (configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
            return parameterObject;
        }
        return configuration.newMetaObject(parameterObject).getValue(property);
    }

    private static MappedStatement mappedStatement(StatementHandler handler) {
        MetaObject metaObject = SystemMetaObject.forObject(PluginUtils.realTarget(handler));
        Object value = metaObject.hasGetter("delegate.mappedStatement")
                ? metaObject.getValue("delegate.mappedStatement")
                : metaObject.getValue("mappedStatement");
        if (value instanceof MappedStatement mappedStatement) return mappedStatement;
        throw new TenantIsolationException("MappedStatement is unavailable for tenant write");
    }

    private void freezeExecutorTenant(String tenantId) {
        Executor executor = activeExecutor.get();
        if (executor == null) {
            throw new TenantIsolationException("Tenant write is outside an Executor update boundary");
        }
        synchronized (writeTenants) {
            String previous = writeTenants.putIfAbsent(executor, tenantId);
            if (previous != null && !previous.equals(tenantId)) {
                throw new TenantIsolationException(
                        "A write Executor cannot switch authenticated tenant before completion");
            }
        }
        freezeSpringTransactionTenant(tenantId);
    }

    private void requireFrozenExecutorTenant(Executor executor) {
        String frozen = writeTenants.get(executor);
        if (frozen == null) return;
        String current = requireCurrentTenant();
        require(frozen.equals(current),
                "Tenant write scope changed before flush or commit");
    }

    private static void freezeSpringTransactionTenant(String tenantId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Object frozen = TransactionSynchronizationManager.getResource(TRANSACTION_TENANT_RESOURCE);
        if (frozen == null) {
            TransactionSynchronizationManager.bindResource(TRANSACTION_TENANT_RESOURCE, tenantId);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TRANSACTION_TENANT_RESOURCE)) {
                        TransactionSynchronizationManager.unbindResource(TRANSACTION_TENANT_RESOURCE);
                    }
                }
            });
            return;
        }
        require(tenantId.equals(frozen),
                "A Spring transaction cannot write more than one authenticated tenant");
    }

    private static String requireCurrentTenant() {
        try {
            return TrustedTenantContext.requireTenantId().value();
        } catch (IllegalStateException exception) {
            throw new TenantIsolationException("Trusted tenant context is required for tenant write",
                    exception);
        }
    }

    private void restoreActiveExecutor(Executor previous) {
        if (previous == null) activeExecutor.remove();
        else activeExecutor.set(previous);
    }

    private static void requireUnqualified(Table table) {
        require(table != null && (table.getSchemaName() == null || table.getSchemaName().isBlank()),
                "Qualified tenant write tables are not supported");
    }

    private static boolean isTenantColumn(String columnName) {
        return columnName != null
                && "tenant_id".equalsIgnoreCase(columnName.replace("`", "").replace("\"", ""));
    }

    private static void validateStatementKind(String sql, Statement statement) {
        String keyword = firstKeyword(sql);
        boolean supported = switch (keyword) {
            case "SELECT", "WITH" -> statement instanceof Select;
            case "INSERT" -> statement instanceof Insert;
            case "UPDATE" -> statement instanceof Update;
            case "DELETE" -> statement instanceof Delete;
            default -> false;
        };
        require(supported, "Unsupported or mismatched SQL statement kind: " + keyword);
    }

    private static String firstKeyword(String sql) {
        require(sql != null, "SQL text is unavailable");
        int index = 0;
        while (index < sql.length()) {
            while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
            if (index + 1 < sql.length() && sql.startsWith("--", index)) {
                int lineEnd = sql.indexOf('\n', index + 2);
                index = lineEnd < 0 ? sql.length() : lineEnd + 1;
                continue;
            }
            if (index < sql.length() && sql.charAt(index) == '#') {
                int lineEnd = sql.indexOf('\n', index + 1);
                index = lineEnd < 0 ? sql.length() : lineEnd + 1;
                continue;
            }
            if (index + 1 < sql.length() && sql.startsWith("/*", index)) {
                int commentEnd = sql.indexOf("*/", index + 2);
                require(commentEnd >= 0, "Unterminated SQL comment");
                index = commentEnd + 2;
                continue;
            }
            break;
        }
        int start = index;
        while (index < sql.length() && Character.isLetter(sql.charAt(index))) index++;
        require(index > start, "SQL statement keyword is unavailable");
        return sql.substring(start, index).toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean empty(List<?> values) { return values == null || values.isEmpty(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new TenantIsolationException(message);
    }
}
