package cn.jia.task.service.impl;

import cn.jia.core.context.EsContextHolder;
import cn.jia.task.common.TaskConstants;
import cn.jia.task.entity.TaskPlanEntity;
import cn.jia.task.mapper.TaskItemMapper;
import cn.jia.task.mapper.TaskPlanMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Calendar;

import static org.junit.jupiter.api.Assertions.*;

/** Real generated inserts and scoped SQL; no request context, tenant interceptor or external DB. */
class TaskPlanTenantPersistenceTest {
    private TaskServiceImpl service;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        EsContextHolder.clearContext();
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:task_plan_tenant;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE task_plan (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, jiacn VARCHAR(32), type INT, period INT,
                  crond VARCHAR(20), name VARCHAR(30), description VARCHAR(200), lunar INT,
                  start_time BIGINT, end_time BIGINT, amount DECIMAL(10,2), remind INT,
                  remind_phone VARCHAR(20), remind_msg VARCHAR(200), status INT,
                  create_time BIGINT, update_time BIGINT, client_id VARCHAR(50),
                  tenant_id VARCHAR(50) DEFAULT '0')
                """);
        jdbc.execute("""
                CREATE TABLE task_item (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, plan_id BIGINT REFERENCES task_plan(id),
                  execute_time BIGINT, status INT DEFAULT 1, create_time BIGINT, update_time BIGINT,
                  client_id VARCHAR(50), tenant_id VARCHAR(50) DEFAULT '0')
                """);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(TaskPlanMapper.class);
        config.addMapper(TaskItemMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        factory.setGlobalConfig(new GlobalConfig());
        var template = new SqlSessionTemplate(factory.getObject());
        var plans = new TaskPlanDaoImpl();
        ReflectionTestUtils.setField(plans, "baseMapper", template.getMapper(TaskPlanMapper.class));
        var items = new TaskItemDaoImpl();
        ReflectionTestUtils.setField(items, "baseMapper", template.getMapper(TaskItemMapper.class));
        service = new TaskServiceImpl();
        ReflectionTestUtils.setField(service, "baseDao", plans);
        ReflectionTestUtils.setField(service, "taskItemDao", items);
    }

    @AfterEach
    void clearContext() {
        EsContextHolder.clearContext();
    }

    @Test
    void everyGeneratedItemKeepsPersistedParentScopeWithoutThreadContext() {
        for (String tenant : new String[]{"0", "1"}) {
            for (int period : new int[]{TaskConstants.TASK_PERIOD_ALLTIME,
                    TaskConstants.TASK_PERIOD_DATE, TaskConstants.TASK_PERIOD_DAY,
                    TaskConstants.TASK_PERIOD_MONTH}) {
                var plan = plan(tenant);
                plan.setPeriod(period);
                var calendar = Calendar.getInstance();
                calendar.clear();
                calendar.set(2020, Calendar.JANUARY, 1);
                plan.setStartTime(calendar.getTimeInMillis());
                calendar.add(Calendar.MONTH, 2);
                plan.setEndTime(calendar.getTimeInMillis());
                plan.setLunar(period == TaskConstants.TASK_PERIOD_MONTH ? 1 : 0);
                service.create(plan);
                assertEquals(tenant, jdbc.queryForObject(
                        "SELECT tenant_id FROM task_plan WHERE id=?", String.class, plan.getId()));
                var rows = jdbc.queryForList(
                        "SELECT tenant_id,client_id FROM task_item WHERE plan_id=?", plan.getId());
                assertFalse(rows.isEmpty(), "period=" + period);
                rows.forEach(row -> {
                    assertEquals(tenant, row.get("TENANT_ID"));
                    assertEquals("client-A", row.get("CLIENT_ID"));
                });
            }
        }
    }

    @Test
    void privatePlanLookupNeverFallsBackToProductionOrAnotherOwnerOrClient() {
        for (String tenant : new String[]{"0", "1"}) {
            var plan = service.create(plan(tenant));
            assertEquals("private title", service.getInOwnerScope(
                    tenant, "client-A", "owner-A", plan.getId()).getName());
            assertNull(service.getInOwnerScope("0".equals(tenant) ? "1" : "0",
                    "client-A", "owner-A", plan.getId()));
            assertNull(service.getInOwnerScope(tenant, "client-B", "owner-A", plan.getId()));
            assertNull(service.getInOwnerScope(tenant, "client-A", "owner-B", plan.getId()));
            assertNull(service.getInOwnerScope(tenant, "client-a", "owner-A", plan.getId()));
            assertNull(service.getInOwnerScope(tenant, "client-A", "owner-a", plan.getId()));
            jdbc.update("UPDATE task_plan SET jiacn='owner-A ' WHERE id=?", plan.getId());
            assertNull(service.getInOwnerScope(tenant, "client-A", "owner-A", plan.getId()));
        }
    }

    @Test
    void invalidScopeIsRejectedInsteadOfBroadeningLookup() {
        var plan = service.create(plan("1"));
        for (String invalid : new String[]{null, "", " ", "1 ", "1\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.getInOwnerScope(invalid, "client-A", "owner-A", plan.getId()));
        }
        assertThrows(IllegalArgumentException.class,
                () -> service.getInOwnerScope("1", null, "owner-A", plan.getId()));
        assertThrows(IllegalArgumentException.class,
                () -> service.getInOwnerScope("1", "client-A", "0", plan.getId()));
    }

    private TaskPlanEntity plan(String tenant) {
        var plan = new TaskPlanEntity();
        plan.setTenantId(tenant);
        plan.setClientId("client-A");
        plan.setJiacn("owner-A");
        plan.setName("private title");
        plan.setPeriod(TaskConstants.TASK_PERIOD_ALLTIME);
        return plan;
    }
}
