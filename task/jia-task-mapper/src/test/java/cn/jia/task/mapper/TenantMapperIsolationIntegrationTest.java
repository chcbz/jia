package cn.jia.task.mapper;

import cn.jia.JiaTestApplication;
import cn.jia.common.mybatis.TenantIsolationException;
import cn.jia.common.mybatis.TenantWriteGuard;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import cn.jia.task.entity.TaskItemEntity;
import cn.jia.task.entity.TaskPlanEntity;
import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.parser.JsqlParserSupport;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("test")
@SpringBootTest(classes = JiaTestApplication.class, properties = {
        "cyf.tenant.mybatis.enabled=true",
        "cyf.tenant.mybatis.tenant-tables[0]=task_plan",
        "cyf.tenant.mybatis.tenant-tables[1]=task_item"
})
@Transactional
class TenantMapperIsolationIntegrationTest {
    @Autowired
    private TaskPlanMapper taskPlanMapper;

    @Autowired
    private TaskItemMapper taskItemMapper;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @BeforeEach
    void registerAdversarialMapper() {
        if (!sqlSessionFactory.getConfiguration().hasMapper(AdversarialWriteMapper.class)) {
            sqlSessionFactory.getConfiguration().addMapper(AdversarialWriteMapper.class);
        }
        if (!sqlSessionFactory.getConfiguration().hasMapper(LockingReadMapper.class)) {
            sqlSessionFactory.getConfiguration().addMapper(LockingReadMapper.class);
        }
        if (sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .noneMatch(FinalSqlCapture.class::isInstance)) {
            sqlSessionFactory.getConfiguration().addInterceptor(new FinalSqlCapture());
        }
    }

    @AfterEach
    void clearContext() {
        TrustedTenantContext.clear();
        PageHelper.clearPage();
        FinalSqlCapture.clear();
    }

    @Test
    void springFactoryRegistersOneTenantQueryInterceptorAndOneWriteGuard() {
        List<Interceptor> interceptors = sqlSessionFactory.getConfiguration().getInterceptors();
        assertEquals(1, interceptors.stream()
                .filter(MybatisPlusInterceptor.class::isInstance).count());
        assertEquals(1, interceptors.stream().filter(TenantWriteGuard.class::isInstance).count());
    }

    @Test
    void actualSpringTenantInterceptorRejectsUnknownTables() {
        withTenant("1", () -> assertTenantDenied(() -> tenantParser().parserSingle(
                "SELECT id FROM secret_table", null)));
    }

    @Test
    void planAndItemReadsAreScopedAndPaginationCountsOnlyCurrentTenant() {
        withTenant("1", () -> {
            assertNull(taskPlanMapper.selectById(20L));
            taskPlanMapper.insert(plan(900L, null, "tenant-one-a"));
            taskPlanMapper.insert(plan(901L, null, "tenant-one-b"));
            taskItemMapper.insert(item(910L, 900L, null));

            assertEquals("tenant-one-a", taskPlanMapper.selectById(900L).getName());
            assertEquals(1, taskItemMapper.selectList(null).stream()
                    .filter(row -> row.getId().equals(910L)).count());

            PageHelper.startPage(1, 1);
            PageInfo<TaskPlanEntity> page = new PageInfo<>(taskPlanMapper.selectList(null));
            assertEquals(2, page.getTotal());
            assertEquals(1, page.getList().size());
        });
    }

    @Test
    void writesFillMissingTenantAndRejectForeignInsertOrReassignment() {
        withTenant("1", () -> {
            TaskPlanEntity inserted = plan(920L, null, "safe");
            assertEquals(1, taskPlanMapper.insert(inserted));
            assertEquals("1", taskPlanMapper.selectById(920L).getTenantId());

            assertTenantDenied(() -> taskPlanMapper.insert(plan(921L, "0", "foreign")));

            inserted.setTenantId("0");
            inserted.setName("moved");
            assertTenantDenied(() -> taskPlanMapper.updateById(inserted));

            inserted.setTenantId("1");
            inserted.setName("updated");
            assertEquals(1, taskPlanMapper.updateById(inserted));
            assertEquals("updated", taskPlanMapper.selectById(920L).getName());
        });
    }

    @Test
    void noTrustedContextFailsClosedForReadAndWrite() {
        assertThrows(RuntimeException.class, () -> taskPlanMapper.selectById(20L));
        assertTenantDenied(() -> taskPlanMapper.insert(plan(930L, "0", "untrusted")));
    }

    @Test
    void batchValidatesEveryParameterAndRejectsForeignSecondRow() {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            TaskPlanMapper mapper = session.getMapper(TaskPlanMapper.class);
            withTenant("1", () -> {
                mapper.insert(plan(940L, "1", "first"));
                assertTenantDenied(() -> mapper.insert(plan(941L, "0", "second")));
            });
            session.rollback();
        }
    }

    @Test
    void oneExecutorCannotSwitchWriteTenantBeforeRollback() {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.SIMPLE, false)) {
            TaskPlanMapper mapper = session.getMapper(TaskPlanMapper.class);
            withTenant("1", () -> mapper.insert(plan(950L, "1", "one")));
            withTenant("0", () -> assertTenantDenied(
                    () -> mapper.insert(plan(951L, "0", "zero"))));
            session.rollback();
        }
    }

    @Test
    void springTransactionCannotSwitchTenantAcrossDifferentSqlSessions() {
        try (SqlSession first = sqlSessionFactory.openSession(ExecutorType.SIMPLE, false);
                SqlSession second = sqlSessionFactory.openSession(ExecutorType.SIMPLE, false)) {
            withTenant("1", () -> first.getMapper(TaskPlanMapper.class)
                    .insert(plan(960L, "1", "one")));
            withTenant("0", () -> assertTenantDenied(() -> second.getMapper(TaskPlanMapper.class)
                    .insert(plan(961L, "0", "zero"))));
            first.rollback();
            second.rollback();
        }
    }

    @Test
    void batchCannotFlushQueuedWritesAfterTenantScopeChanges() {
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            TaskPlanMapper mapper = session.getMapper(TaskPlanMapper.class);
            withTenant("1", () -> mapper.insert(plan(970L, "1", "queued")));
            withTenant("0", () -> assertTenantDenied(session::flushStatements));
            session.rollback();
        }
    }

    @Test
    void nonWhitelistedWriteStatementsFailClosedBeforeChangingData() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            AdversarialWriteMapper mapper = session.getMapper(AdversarialWriteMapper.class);
            withTenant("1", () -> {
                assertTenantDenied(mapper::replaceTenantZeroPlan);
                assertTenantDenied(mapper::mergeTenantZeroPlan);
                assertTenantDenied(mapper::selectMappedTruncate);
                assertMybatisPlusDenied(mapper::multipleStatements);
                assertMybatisPlusDenied(mapper::callableStatement);
            });
            assertTenantDenied(mapper::truncatePlans);
            session.rollback();
        }

        withTenant("0", () -> {
            assertEquals("住房公积金", taskPlanMapper.selectById(20L).getName());
            assertEquals(2, taskPlanMapper.selectList(null).size());
        });
    }

    @Test
    void mysqlLockingReadPreservesTenantIsolationBindOrderAndFinalClauseOrder() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            TaskPlanMapper writer = session.getMapper(TaskPlanMapper.class);
            LockingReadMapper reader = session.getMapper(LockingReadMapper.class);
            withTenant("1", () -> {
                writer.insert(plan(980L, "1", "first-lock"));
                writer.insert(plan(981L, "1", "second-lock"));

                assertEquals(List.of(980L), reader.lockPlans("excluded", 1));
                String sql = normalizedFinalSql();
                assertTrue(sql.contains("name <> ?"));
                assertTrue(sql.contains("tenant_id = '1'"));
                assertTrue(sql.endsWith("ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"), sql);
                assertEquals(2, sql.chars().filter(character -> character == '?').count());
            });
            session.rollback();
        }

        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            LockingReadMapper reader = session.getMapper(LockingReadMapper.class);
            withTenant("0", () -> {
                assertEquals(List.of(20L), reader.lockPlans("excluded", 1));
                String sql = normalizedFinalSql();
                assertTrue(sql.contains("tenant_id = '0'"), sql);
                assertTrue(sql.endsWith("ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED"), sql);
            });
            session.rollback();
        }
    }

    @Test
    void lockingSerializationPreservesLiteralsNestedLocksAndSupportedMysqlModes() {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            LockingReadMapper reader = session.getMapper(LockingReadMapper.class);
            withTenant("0", () -> {
                assertEquals("literal FOR UPDATE LIMIT ?", reader.lockedLiteral(20L));
                String emitted = normalizedFinalSql();
                assertTrue(emitted.contains("'literal FOR UPDATE LIMIT ?'"), emitted);
                assertTrue(emitted.endsWith("ORDER BY id LIMIT 1 FOR UPDATE"), emitted);

                JsqlParserSupport parser = tenantParser();
                String nested = normalizeSql(parser.parserSingle(
                        "SELECT locked.id FROM (SELECT id FROM task_plan ORDER BY id LIMIT ? "
                                + "FOR UPDATE SKIP LOCKED) locked ORDER BY locked.id", null));
                assertTrue(nested.contains("ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED) locked"),
                        nested);
                assertTrue(nested.contains("tenant_id = '0'"), nested);

                String share = normalizeSql(parser.parserSingle(
                        "SELECT p.id FROM task_plan p JOIN task_item i ON i.plan_id = p.id "
                                + "ORDER BY p.id LIMIT ? FOR SHARE NOWAIT", null));
                assertTrue(share.endsWith("ORDER BY p.id LIMIT ? FOR SHARE NOWAIT"), share);
                assertTrue(share.contains("p.tenant_id = '0'"), share);
                assertTrue(share.contains("i.tenant_id = '0'"), share);

                String updateOf = normalizeSql(parser.parserSingle(
                        "SELECT p.id FROM task_plan p ORDER BY p.id LIMIT ? "
                                + "FOR UPDATE OF p SKIP LOCKED", null));
                assertTrue(updateOf.endsWith(
                        "ORDER BY p.id LIMIT ? FOR UPDATE OF p SKIP LOCKED"), updateOf);

                assertTenantDenied(() -> parser.parserSingle(
                        "SELECT id FROM task_plan FOR NO KEY UPDATE", null));
            });
            session.rollback();
        }
    }

    @Test
    void parenthesizedSelectPreservesMultipleCteSeparatorsAndNestedLocks() throws Exception {
        JsqlParserSupport parser = cteParser();
        String plain = normalizeSql(parser.parserSingle(
                "WITH a AS (SELECT id FROM task_plan), "
                        + "b AS (SELECT id FROM task_item) "
                        + "(SELECT a.id FROM a JOIN b ON b.id = a.id)", null));
        assertTrue(plain.contains("), b AS ("), plain);
        assertEquals(2, occurrences(plain, "tenant_id = '0'"), plain);
        CCJSqlParserUtil.parse(plain);

        String locked = normalizeSql(parser.parserSingle(
                "WITH a AS (SELECT id FROM task_plan ORDER BY id LIMIT ? "
                        + "FOR UPDATE SKIP LOCKED), "
                        + "b AS (SELECT id FROM task_item) "
                        + "(SELECT a.id FROM a JOIN b ON b.id = a.id)", null));
        assertTrue(locked.contains("), b AS ("), locked);
        assertTrue(locked.contains("ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED), b AS"), locked);
        CCJSqlParserUtil.parse(locked);
    }

    private JsqlParserSupport tenantParser() {
        return (JsqlParserSupport) sqlSessionFactory.getConfiguration().getInterceptors().stream()
                .filter(MybatisPlusInterceptor.class::isInstance)
                .map(MybatisPlusInterceptor.class::cast)
                .findFirst().orElseThrow().getInterceptors().get(0);
    }

    private static JsqlParserSupport cteParser() throws Exception {
        TenantLineHandler handler = new TenantLineHandler() {
            @Override
            public StringValue getTenantId() {
                return new StringValue("0");
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return "a".equalsIgnoreCase(tableName) || "b".equalsIgnoreCase(tableName);
            }
        };
        Class<?> type = Class.forName(
                "cn.jia.common.mybatis.MysqlLockingTenantLineInnerInterceptor");
        var constructor = type.getDeclaredConstructor(TenantLineHandler.class);
        constructor.setAccessible(true);
        return (JsqlParserSupport) constructor.newInstance(handler);
    }

    private static String normalizedFinalSql() {
        String sql = FinalSqlCapture.lastSql();
        assertTrue(sql != null && !sql.isBlank(), "No final SQL was captured");
        return normalizeSql(sql);
    }

    private static String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static int occurrences(String value, String expected) {
        return (value.length() - value.replace(expected, "").length()) / expected.length();
    }

    private static TaskPlanEntity plan(long id, String tenantId, String name) {
        TaskPlanEntity plan = new TaskPlanEntity().setId(id).setJiacn("owner").setType(1)
                .setPeriod(0).setName(name).setRemind(0).setStatus(1);
        plan.setTenantId(tenantId);
        return plan;
    }

    private static TaskItemEntity item(long id, long planId, String tenantId) {
        TaskItemEntity item = new TaskItemEntity().setId(id).setPlanId(planId).setStatus(1)
                .setExecuteTime(1L);
        item.setTenantId(tenantId);
        return item;
    }

    private static void withTenant(String tenantId, Runnable work) {
        TrustedTenantContext.runWithScope(new BusinessScope(TenantId.of(tenantId),
                DataDomainId.of("cyf"), "owner", "client"), work);
    }

    private static void assertTenantDenied(Runnable work) {
        RuntimeException exception = assertThrows(RuntimeException.class, work::run);
        assertTrue(hasCause(exception, TenantIsolationException.class),
                () -> "Expected TenantIsolationException but got " + exception);
    }

    private static void assertMybatisPlusDenied(Runnable work) {
        RuntimeException exception = assertThrows(RuntimeException.class, work::run);
        assertTrue(hasCause(exception, MybatisPlusException.class),
                () -> "Expected MybatisPlusException but got " + exception);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    interface AdversarialWriteMapper {
        @Update("REPLACE INTO task_plan (id, jiacn, type, period, name, remind, status, tenant_id) "
                + "VALUES (20, 'attacker', 1, 0, 'replaced', 0, 1, '1')")
        int replaceTenantZeroPlan();

        @Update("MERGE INTO task_plan (id, jiacn, type, period, name, remind, status, tenant_id) "
                + "KEY(id) VALUES (20, 'attacker', 1, 0, 'merged', 0, 1, '1')")
        int mergeTenantZeroPlan();

        @Update("TRUNCATE TABLE task_plan")
        void truncatePlans();

        @Select("TRUNCATE TABLE task_plan")
        List<Long> selectMappedTruncate();

        @Select("SELECT id FROM task_plan; TRUNCATE TABLE task_plan")
        List<Long> multipleStatements();

        @Options(statementType = StatementType.CALLABLE)
        @Select("{call unsafe_tenant_procedure()}")
        List<Long> callableStatement();
    }

    interface LockingReadMapper {
        @Select("SELECT id FROM task_plan WHERE name <> #{excluded} "
                + "ORDER BY id LIMIT #{limit} FOR UPDATE SKIP LOCKED")
        List<Long> lockPlans(@Param("excluded") String excluded, @Param("limit") long limit);

        @Select("SELECT 'literal FOR UPDATE LIMIT ?' FROM task_plan WHERE id = #{id} "
                + "ORDER BY id LIMIT 1 FOR UPDATE")
        String lockedLiteral(@Param("id") long id);
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static final class FinalSqlCapture implements Interceptor {
        private static final ThreadLocal<String> LAST_SQL = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            LAST_SQL.set(handler.getBoundSql().getSql());
            return result;
        }

        static String lastSql() { return LAST_SQL.get(); }
        static void clear() { LAST_SQL.remove(); }
    }
}
