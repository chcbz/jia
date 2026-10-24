package cn.jia.common.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import net.sf.jsqlparser.expression.ExpressionVisitor;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ForClause;
import net.sf.jsqlparser.statement.select.ForMode;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.Wait;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

/**
 * Keeps MySQL locking clauses after ORDER BY/LIMIT when JSQLParser 4.9 serializes a tenant-mutated
 * SELECT. JSQLParser 4.9 otherwise moves the lock before those clauses and emits invalid MySQL.
 */
final class MysqlLockingTenantLineInnerInterceptor extends TenantLineInnerInterceptor {
    MysqlLockingTenantLineInnerInterceptor(TenantLineHandler tenantLineHandler) {
        super(tenantLineHandler);
    }

    @Override
    protected String processParser(Statement statement, int index, String sql, Object obj) {
        super.processParser(statement, index, sql, obj);
        if (!(statement instanceof Select)) {
            return statement.toString();
        }
        try {
            return MysqlSelectDeParser.deparse(statement);
        } catch (TenantIsolationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new TenantIsolationException(
                    "Unable to safely serialize tenant SELECT locking clauses", exception);
        }
    }

    private static final class MysqlSelectDeParser extends SelectDeParser {
        private MysqlSelectDeParser(ExpressionVisitor expressionVisitor, StringBuilder buffer) {
            super(expressionVisitor, buffer);
        }

        static String deparse(Statement statement) {
            StringBuilder buffer = new StringBuilder();
            MysqlSelectDeParser selectDeParser = new MysqlSelectDeParser(null, buffer);
            ExpressionDeParser expressionDeParser = new ExpressionDeParser(selectDeParser, buffer);
            selectDeParser.setExpressionVisitor(expressionDeParser);
            statement.accept(new StatementDeParser(expressionDeParser, selectDeParser, buffer));
            return buffer.toString();
        }

        @Override
        public void visit(PlainSelect select) {
            LockingClause lockingClause = LockingClause.detach(select);
            try {
                super.visit(select);
            } finally {
                lockingClause.restore(select);
            }
            lockingClause.appendTo(getBuffer());
        }

        @Override
        public void visit(ParenthesedSelect select) {
            var withItems = select.getWithItemsList();
            if (withItems == null || withItems.size() < 2) {
                super.visit(select);
                return;
            }
            select.setWithItemsList(null);
            try {
                getBuffer().append("WITH ");
                for (int index = 0; index < withItems.size(); index++) {
                    WithItem withItem = withItems.get(index);
                    withItem.accept((SelectVisitor) this);
                    getBuffer().append(index + 1 < withItems.size() ? ", " : " ");
                }
                super.visit(select);
            } finally {
                select.setWithItemsList(withItems);
            }
        }
    }

    private record LockingClause(ForMode mode, Table table, Wait waitClause, boolean noWait,
            boolean skipLocked, ForClause forClause) {
        static LockingClause detach(PlainSelect select) {
            LockingClause clause = new LockingClause(select.getForMode(),
                    select.getForUpdateTable(), select.getWait(), select.isNoWait(),
                    select.isSkipLocked(), select.getForClause());
            clause.validate();
            select.setForMode(null);
            select.setForUpdateTable(null);
            select.setWait(null);
            select.setNoWait(false);
            select.setSkipLocked(false);
            select.setForClause(null);
            return clause;
        }

        private void validate() {
            boolean hasModifier = table != null || waitClause != null || noWait || skipLocked;
            if (mode == null) {
                if (hasModifier || forClause != null) {
                    throw new TenantIsolationException(
                            "Unsupported SELECT locking clause without FOR UPDATE/SHARE");
                }
                return;
            }
            if (mode != ForMode.UPDATE && mode != ForMode.SHARE) {
                throw new TenantIsolationException(
                        "Unsupported MySQL SELECT locking mode: " + mode.getValue());
            }
            if (waitClause != null || forClause != null || (noWait && skipLocked)) {
                throw new TenantIsolationException(
                        "Unsupported or ambiguous MySQL SELECT locking clause");
            }
        }

        private void restore(PlainSelect select) {
            select.setForMode(mode);
            select.setForUpdateTable(table);
            select.setWait(waitClause);
            select.setNoWait(noWait);
            select.setSkipLocked(skipLocked);
            select.setForClause(forClause);
        }

        private void appendTo(StringBuilder buffer) {
            if (mode == null) return;
            buffer.append(" FOR ").append(mode.getValue());
            if (table != null) buffer.append(" OF ").append(table);
            if (noWait) {
                buffer.append(" NOWAIT");
            } else if (skipLocked) {
                buffer.append(" SKIP LOCKED");
            }
        }
    }
}
