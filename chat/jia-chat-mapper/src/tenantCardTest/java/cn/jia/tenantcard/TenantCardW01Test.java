package cn.jia.tenantcard;

import cn.jia.chat.deliberation.ChatDeliberationDao;
import cn.jia.chat.deliberation.ChatDeliberationDaoImpl;
import cn.jia.chat.deliberation.ChatDeliberationMapper;
import cn.jia.chat.deliberation.ChatDispatchOutboxEntity;
import cn.jia.core.tenant.BusinessScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TrustedTenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantCardW01Test {
    private static final long NOW = 10_000L;
    private JdbcTemplate jdbc;
    private ChatDeliberationMapper mapper;
    private ChatDeliberationDao dao;
    private MybatisConfiguration configuration;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:tenant_card_w01;MODE=MYSQL;DB_CLOSE_DELAY=-1");
        source.setUsername("sa");
        source.setPassword("");
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE chat_dispatch_outbox (
                  event_id VARCHAR(64) PRIMARY KEY,
                  tenant_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,
                  turn_id VARCHAR(64) NOT NULL,
                  dispatch_id VARCHAR(64) NOT NULL,
                  event_type VARCHAR(30) NOT NULL,
                  status VARCHAR(30) NOT NULL,
                  payload_json VARCHAR(4000) NOT NULL,
                  version BIGINT NOT NULL,
                  available_at BIGINT,
                  lease_owner VARCHAR(100),
                  lease_until BIGINT,
                  attempt_count INT NOT NULL,
                  fencing_token BIGINT NOT NULL,
                  last_error VARCHAR(500),
                  sent_at BIGINT,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL)
                """);
        configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatDeliberationMapper.class);
        MybatisPlusInterceptor tenantPlugin = new MybatisPlusInterceptor();
        tenantPlugin.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override public Expression getTenantId() {
                return new StringValue(TrustedTenantContext.requireTenantId().value());
            }

            @Override public boolean ignoreTable(String tableName) {
                return !"chat_dispatch_outbox".equalsIgnoreCase(tableName);
            }
        }));
        configuration.addInterceptor(tenantPlugin);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("missing SqlSessionFactory");
        mapper = new SqlSessionTemplate(factory).getMapper(ChatDeliberationMapper.class);
        dao = new ChatDeliberationDaoImpl(mapper);
    }

    @AfterEach
    void tearDown() {
        TrustedTenantContext.clear();
        jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void allowedTenantFilterPrecedesLimitAndPreservesAllDueStateLeaseRules() {
        insert("zero-earlier", "0", "READY", NOW - 100, null);
        insert("one-ready", "1", "READY", NOW - 10, null);
        insert("one-retry", "1", "RETRY", NOW - 9, null);
        insert("one-awaiting", "1", "AWAITING_ACK", NOW - 8, null);
        insert("one-claimed", "1", "CLAIMED", NOW - 7, NOW - 1);
        insert("one-future", "1", "RETRY", NOW + 1, null);
        insert("one-active-claim", "1", "CLAIMED", NOW - 6, NOW + 1);
        insert("one-sent", "1", "SENT", NOW - 20, null);
        insert("one-dead", "1", "DEAD", NOW - 20, null);

        List<ChatDispatchOutboxEntity> first = dao.findDueOutbox(Set.of("1"), NOW, 1);
        assertEquals(List.of("one-ready"), first.stream().map(ChatDispatchOutboxEntity::getEventId).toList());

        List<ChatDispatchOutboxEntity> due = dao.findDueOutbox(Set.of("1"), NOW, 10);
        assertEquals(List.of("one-ready", "one-retry", "one-awaiting", "one-claimed"),
                due.stream().map(ChatDispatchOutboxEntity::getEventId).toList());
        assertTrue(due.stream().allMatch(row -> "1".equals(row.getTenantId())));
    }

    @Test
    void legacyEntrypointsRemainTenantZeroAndEmptyAllowedSetCannotScan() {
        insert("zero-ready", "0", "READY", NOW - 2, null);
        insert("one-ready", "1", "READY", NOW - 3, null);

        assertEquals(List.of("zero-ready"), mapper.findDueOutbox(NOW, 10)
                .stream().map(ChatDispatchOutboxEntity::getEventId).toList());
        assertEquals(List.of("zero-ready"), dao.findDueOutbox(NOW, 10)
                .stream().map(ChatDispatchOutboxEntity::getEventId).toList());
        assertTrue(dao.findDueOutbox(Set.of(), NOW, 10).isEmpty());
        assertTrue(dao.findDueOutbox(null, NOW, 10).isEmpty());
        assertTrue(mapper.findDueOutboxInTenants(null, NOW, 10).isEmpty());

        BoundSql emptySql = boundSql(Set.of(), NOW, 10);
        assertTrue(normalize(emptySql.getSql()).contains("where 1=0 and"), emptySql.getSql());
        assertFalse(normalize(emptySql.getSql()).contains("tenant_id in"), emptySql.getSql());
    }

    @Test
    void generatedBoundSqlCarriesTenantInAndByteExactBindingsBeforeOrderAndLimit() {
        LinkedHashSet<String> tenants = new LinkedHashSet<>(List.of("1", "2"));
        BoundSql boundSql = boundSql(tenants, NOW, 3);
        String sql = normalize(boundSql.getSql());

        assertTrue(sql.contains("tenant_id in ( ? , ? )"), sql);
        assertTrue(sql.contains("tenant_id=? and cast(tenant_id as binary)=cast(? as binary)"
                + " and octet_length(tenant_id)=octet_length(?)"), sql);
        assertTrue(sql.indexOf("tenant_id in") < sql.indexOf("order by available_at,event_id"), sql);
        assertTrue(sql.indexOf("tenant_id in") < sql.indexOf("limit ?"), sql);
        assertEquals(List.of("1", "2", "1", "1", "1", "2", "2", "2", NOW, NOW, 3),
                boundValues(boundSql, tenants, NOW, 3));
    }

    @Test
    void onlyBoundedDiscoveryMayRunWithoutContextAndOrdinaryReadsUseActualTenantLine() {
        insert("one-ready", "1", "READY", NOW - 1, null);

        assertFalse(TrustedTenantContext.currentScope().isPresent());
        assertEquals(List.of("one-ready"), mapper.findDueOutboxInTenants(Set.of("1"), NOW, 1)
                .stream().map(ChatDispatchOutboxEntity::getEventId).toList());
        assertThrows(RuntimeException.class,
                () -> mapper.findOutboxById("1", "owner-a", "client-a", "one-ready"));

        ChatDispatchOutboxEntity allowed = withScope("1",
                () -> mapper.findOutboxById("1", "owner-a", "client-a", "one-ready"));
        assertEquals("one-ready", allowed.getEventId());
        assertNull(withScope("0",
                () -> mapper.findOutboxById("1", "owner-a", "client-a", "one-ready")));
        assertFalse(TrustedTenantContext.currentScope().isPresent());
    }

    private void insert(String eventId, String tenantId, String status, long availableAt,
            Long leaseUntil) {
        jdbc.update("""
                INSERT INTO chat_dispatch_outbox
                  (event_id,tenant_id,owner_jiacn,client_id,turn_id,dispatch_id,event_type,status,
                   payload_json,version,available_at,lease_owner,lease_until,attempt_count,fencing_token,
                   last_error,sent_at,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, eventId, tenantId, "owner-a", "client-a", "turn-" + eventId,
                "dispatch-" + eventId, "DISPATCH", status, "{}", 0L, availableAt,
                null, leaseUntil, 0, 0L, null, null, NOW - 100, NOW - 100);
    }

    private BoundSql boundSql(Set<String> tenants, long now, int limit) {
        MappedStatement statement = configuration.getMappedStatement(
                ChatDeliberationMapper.class.getName() + ".findDueOutboxInTenants");
        Map<String, Object> parameters = parameters(tenants, now, limit);
        return statement.getBoundSql(parameters);
    }

    private List<Object> boundValues(BoundSql boundSql, Set<String> tenants, long now, int limit) {
        Map<String, Object> parameters = parameters(tenants, now, limit);
        MetaObject metaObject = configuration.newMetaObject(parameters);
        return boundSql.getParameterMappings().stream().map(ParameterMapping::getProperty)
                .map(property -> boundSql.hasAdditionalParameter(property)
                        ? boundSql.getAdditionalParameter(property) : metaObject.getValue(property))
                .toList();
    }

    private static Map<String, Object> parameters(Set<String> tenants, long now, int limit) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("allowedTenants", tenants);
        parameters.put("now", now);
        parameters.put("limit", limit);
        return parameters;
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static <T> T withScope(String tenantId, java.util.function.Supplier<T> work) {
        return TrustedTenantContext.withScope(new BusinessScope(TenantId.of(tenantId),
                DataDomainId.of("cyf"), "owner-a", "client-a"), work);
    }
}
