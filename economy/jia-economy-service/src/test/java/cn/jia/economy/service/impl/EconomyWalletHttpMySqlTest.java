package cn.jia.economy.service.impl;

import cn.jia.core.config.*;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.economy.api.*;
import cn.jia.economy.config.*;
import cn.jia.economy.mapper.*;
import cn.jia.economy.pricing.*;
import cn.jia.economy.service.*;
import cn.jia.economy.task.TaskBillingService;
import cn.jia.economy.usage.*;
import jakarta.servlet.DispatcherType;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP/Q-R/JWT/SQL wallet boundary. Task transitions below are service calls under a
 * fixture root lock, NOT the Agent assignment endpoint or a full application/browser test. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
class EconomyWalletHttpMySqlTest {
    @TempDir Path scratch;
    private static final EconomyScope SCOPE = new EconomyScope("tenant-a", "client-a");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ModelTokenPrice PRICE = new ModelTokenPrice("test", "model", "standard", "v1", "synthetic",
            Map.of(ModelTokenPrice.Category.INPUT, new BigDecimal("0.125001")), null, Map.of());
    private static DataSource dataSource;
    private static byte[] signingKey;
    private static EconomyCampaignProperties campaigns;
    private final EconomyMySqlTestFixture database = new EconomyMySqlTestFixture();
    private final HttpClient client = HttpClient.newHttpClient();
    private JdbcTemplate jdbc;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext app;
    private String origin;
    private int starts;

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({ApiEnvelopeJsonConfig.class, ApiEnvelopeAdvice.class, ApiRequestEnvelopeAdvice.class, ExceptionHandlerAdvice.class})
    public static class App {
        @Bean DataSource dataSource() { return dataSource; }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionTemplate sql(DataSource ds) throws Exception {
            var config = new org.apache.ibatis.session.Configuration();
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(EconomyLedgerMapper.class); config.addMapper(EconomyTaskBillingMapper.class);
            config.addMapper(EconomyUsageCostMapper.class);
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
            return new SqlSessionTemplate(factory.getObject());
        }
        @Bean EconomyPreviewGate gate() {
            return new EconomyPreviewGate(new EconomyPreviewProperties(true, false,
                    List.of(new EconomyPreviewProperties.AllowedScope(SCOPE.tenantId(), SCOPE.clientId()))));
        }
        @Bean EconomyPostingServiceImpl posting(SqlSessionTemplate sql, DataSourceTransactionManager tx, EconomyPreviewGate gate) {
            return new EconomyPostingServiceImpl(sql.getMapper(EconomyLedgerMapper.class), tx, gate);
        }
        @Bean EconomyTreasuryPostingService treasury(EconomyPostingServiceImpl posting) { return new EconomyTreasuryPostingServiceImpl(posting); }
        @Bean EconomyWalletService wallet(SqlSessionTemplate sql, EconomyTreasuryPostingService treasury,
                org.springframework.core.env.Environment environment, EconomyPreviewGate gate) {
            return new EconomyWalletService(sql.getMapper(EconomyLedgerMapper.class), treasury, environment, gate, campaigns);
        }
        @Bean EconomyWalletController controller(EconomyPreviewGate gate, EconomyWalletService wallet) { return new EconomyWalletController(gate, wallet); }
        @Bean TaskBillingService billing(SqlSessionTemplate sql, EconomyPostingServiceImpl posting) {
            var pricing = new ConfiguredTaskPricingService(new EconomyPricingProperties(List.of(PRICE), List.of()));
            return new TaskBillingServiceImpl(sql.getMapper(EconomyTaskBillingMapper.class), sql.getMapper(EconomyLedgerMapper.class), posting, JSON, pricing);
        }
        @Bean UsageCostService costs(SqlSessionTemplate sql) { return new UsageCostServiceImpl(sql.getMapper(EconomyUsageCostMapper.class), JSON); }
        @Bean JwtDecoder decoder() {
            var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(signingKey, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("cyf-wallet-fixture")); return decoder;
        }
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .oauth2ResourceServer(o -> o.jwt(j -> {})).build();
        }
    }

    @BeforeEach void prepare() throws Exception {
        database.start(); var db = database.newDatabase("wallet_http"); dataSource = db.dataSource(); jdbc = db.jdbc();
        new EconomySchemaInitializer(jdbc).afterPropertiesSet();
        new EconomyUsageCostSchemaInitializer(dataSource).afterPropertiesSet();
        jdbc.execute("CREATE TABLE test_task_root (id VARCHAR(100) PRIMARY KEY) ENGINE=InnoDB");
        jdbc.update("INSERT INTO test_task_root VALUES ('accepted'),('cancelled')");
        signingKey = new byte[32]; new SecureRandom().nextBytes(signingKey);
        campaigns = campaign("welcome", true, 3500); startHttp();
    }
    @AfterEach void close() throws Exception { try { stopHttp(); } finally { database.close(); } }
    private void startHttp() throws Exception {
        Path base = Files.createDirectory(scratch.resolve("http-" + starts++));
        tomcat = new Tomcat(); tomcat.setBaseDir(base.toString()); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        var context = tomcat.addContext("", Files.createDirectory(base.resolve("webroot")).toString());
        context.setParentClassLoader(getClass().getClassLoader());
        app = new AnnotationConfigWebApplicationContext(); app.setServletContext(context.getServletContext()); app.register(App.class); app.refresh();
        Tomcat.addServlet(context, "dispatcher", new DispatcherServlet(app)).setLoadOnStartup(1); context.addServletMappingDecoded("/", "dispatcher");
        addFilter(context, "requestId", new RequestIdFilter());
        addFilter(context, "security", new DelegatingFilterProxy("springSecurityFilterChain", app));
        tomcat.start(); origin = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
    }
    private static void addFilter(org.apache.catalina.Context context, String name, jakarta.servlet.Filter filter) {
        var definition = new FilterDef(); definition.setFilterName(name); definition.setFilter(filter); context.addFilterDef(definition);
        var mapping = new FilterMap(); mapping.setFilterName(name); mapping.addURLPattern("/*");
        mapping.setDispatcher(DispatcherType.REQUEST.name()); context.addFilterMap(mapping);
    }
    private void stopHttp() throws Exception {
        try { if (tomcat != null) { tomcat.stop(); tomcat.destroy(); } }
        finally { tomcat = null; if (app != null) app.close(); app = null; }
    }
    private void reconfigure(EconomyCampaignProperties value) throws Exception { stopHttp(); campaigns = value; startHttp(); }

    @Test void eventGrantIsDurableOncePerCampaignAndConfigChangesNeedNoCodeChange() throws Exception {
        String token = token("payer", SCOPE.tenantId(), SCOPE.clientId());
        wallet(token, 0, 0);
        var first = event(token); String transaction = first.at("/data/grants/0/transactionId").asText();
        assertFalse(transaction.isBlank()); assertEquals("3500", first.at("/data/grants/0/amount/amountMinor").asText());
        assertEquals(transaction, event(token).at("/data/grants/0/transactionId").asText()); wallet(token, 3500, 0);
        assertEquals(1, count("economy_transaction"));
        reconfigure(campaign("welcome", true, 9999));
        var changed = event(token); assertEquals(transaction, changed.at("/data/grants/0/transactionId").asText());
        assertEquals("3500", changed.at("/data/grants/0/amount/amountMinor").asText()); wallet(token, 3500, 0);
        reconfigure(campaign("welcome", false, 9999));
        assertEquals(0, event(token).at("/data/grants").size()); wallet(token, 3500, 0);
        String newcomer = token("new-user", SCOPE.tenantId(), SCOPE.clientId());
        assertEquals(0, event(newcomer).at("/data/grants").size()); wallet(newcomer, 0, 0);
        reconfigure(campaign("second-activity", true, 7)); event(token); wallet(token, 3507, 0);
        assertEquals(2, count("economy_transaction"));
        var ledger = get(token, "/economy/ledger?pageSize=50", 200);
        assertEquals(2, ledger.at("/data/items").size());
        assertEquals(Set.of("7", "3500"), Set.of(ledger.at("/data/items/0/amount/amountMinor").asText(), ledger.at("/data/items/1/amount/amountMinor").asText()));
        wallet(newcomer, 0, 0);
    }

    @Test void verifiedHttpIdentityAndEnvelopeCannotSelectAnotherWalletOrMintClientAmounts() throws Exception {
        String token = token("payer", SCOPE.tenantId(), SCOPE.clientId()); event(token); wallet(token, 3500, 0);
        get(null, "/economy/wallet", 401);
        get(token + "corrupted", "/economy/wallet", 401);
        get(token("payer", "other-tenant", SCOPE.clientId()), "/economy/wallet", 403);
        get(token("payer", SCOPE.tenantId(), "other-client"), "/economy/wallet", 403);
        get(token, "/economy/wallet?actorId=someone-else", 400);
        wallet(token("another-user", SCOPE.tenantId(), SCOPE.clientId()), 0, 0);
        post(token, "{\"event\":\"HALL_ENTERED\",\"amountWen\":99999}", true, 400);
        post(token, "{\"event\":\"HALL_ENTERED\"}", false, 400);
        assertEquals(1, count("economy_transaction")); wallet(token, 3500, 0);
    }

    @Test void walletHttpReflectsActualReservationsSettlementReleaseAndIndependentLateUsage() throws Exception {
        String token = token("payer", SCOPE.tenantId(), SCOPE.clientId()); event(token);
        var billing = app.getBean(TaskBillingService.class);
        var binding = new TaskBillingService.Binding(1, "requirement", "inputs", "agent", "recommendation");
        var task = new TaskBillingService.Task(SCOPE, "owner", "accepted");
        var quote = inTask(task, () -> billing.quote(task, binding, "payer", new TaskWorkload("test", 1_000_000, 0, 1, Map.of()),
                new TaskPricingService.ModelKey("test", "model", "standard")));
        assertEquals(13, quote.amountWen()); wallet(token, 3500, 0);
        inTask(task, () -> billing.reserve(task, quote.quoteId(), binding, "payer")); wallet(token, 3487, 13);
        inTask(task, () -> billing.reserve(task, quote.quoteId(), binding, "payer")); wallet(token, 3487, 13);
        inTask(task, () -> billing.capture(task)); wallet(token, 3487, 0);
        inTask(task, () -> billing.capture(task)); wallet(token, 3487, 0);
        long financialTransactions = count("economy_transaction");
        var observation = new UsageCostService.Observation(new UsageCostService.Binding(SCOPE, "owner", task.taskId(), "TEST_PROVIDER_RECEIPT", "attempt"),
                "usage", 0, "{\"input_tokens\":1000000}",
                new TokenUsageCost.Usage("test", "model", "standard", "synthetic-disjoint", Map.of(ModelTokenPrice.Category.INPUT, 1_000_000L)), PRICE, 1);
        var costs = app.getBean(UsageCostService.class); assertEquals(new BigDecimal("0.125001"), costs.record(observation).costYuan());
        assertTrue(costs.record(observation).replay()); wallet(token, 3487, 0);
        assertEquals(financialTransactions, count("economy_transaction"));
        var cancelled = new TaskBillingService.Task(SCOPE, "owner", "cancelled");
        var other = inTask(cancelled, () -> billing.quote(cancelled, binding, "payer", new TaskWorkload("test", 1_000_000, 0, 1, Map.of()),
                new TaskPricingService.ModelKey("test", "model", "standard")));
        inTask(cancelled, () -> billing.reserve(cancelled, other.quoteId(), binding, "payer")); wallet(token, 3474, 13);
        inTask(cancelled, () -> billing.release(cancelled, true)); wallet(token, 3487, 0);
        inTask(cancelled, () -> billing.release(cancelled, true)); wallet(token, 3487, 0);
        assertEquals("CAPTURED", jdbc.queryForObject("SELECT state FROM economy_task_charge WHERE task_id='accepted'", String.class));
        assertEquals("RELEASED", jdbc.queryForObject("SELECT state FROM economy_task_charge WHERE task_id='cancelled'", String.class));
    }

    private <T> T inTask(TaskBillingService.Task task, Supplier<T> work) {
        return new TransactionTemplate(app.getBean(DataSourceTransactionManager.class)).execute(status -> {
            jdbc.queryForObject("SELECT id FROM test_task_root WHERE id=? FOR UPDATE", String.class, task.taskId()); return work.get();
        });
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private static EconomyCampaignProperties campaign(String id, boolean enabled, long amount) {
        return new EconomyCampaignProperties(List.of(new EconomyCampaignProperties.Campaign(id, EconomyCampaignProperties.Event.HALL_ENTERED, enabled, amount, null, null)));
    }
    private void wallet(String token, long available, long held) throws Exception {
        var body = get(token, "/economy/wallet", 200);
        assertEquals("WEN", body.at("/data/currency").asText());
        assertEquals(Long.toString(available), body.at("/data/available/amountMinor").asText());
        assertEquals(Long.toString(held), body.at("/data/held/amountMinor").asText());
    }
    private JsonNode event(String token) throws Exception { return post(token, "{\"event\":\"HALL_ENTERED\"}", true, 200); }
    private JsonNode post(String token, String data, boolean envelope, int expected) throws Exception {
        String body = envelope ? "{\"protocolVersion\":1,\"requestId\":\"wallet-test\",\"data\":" + data + "}" : data;
        return send(token, HttpRequest.newBuilder(URI.create(origin + "/economy/events")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), expected);
    }
    private JsonNode get(String token, String route, int expected) throws Exception { return send(token, HttpRequest.newBuilder(URI.create(origin + route)).GET(), expected); }
    private JsonNode send(String token, HttpRequest.Builder request, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.header("X-Request-Id", "wallet-test");
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode(), response.body());
        if (expected == 401) return JSON.createObjectNode();
        var body = JSON.readTree(response.body());
        assertEquals(1, body.path("protocolVersion").asInt(), response.body());
        if (expected == 200) { assertEquals("E0", body.path("code").asText()); assertEquals("wallet-test", body.path("requestId").asText()); }
        return body;
    }
    private static String token(String subject, String tenant, String client) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String payload = encoder.encodeToString(JSON.writeValueAsBytes(Map.of("iss", "cyf-wallet-fixture", "sub", subject,
                "jiacn", "owner-" + subject, "tenant_id", tenant, "client_id", client, "iat", now, "exp", now + 3600)));
        String content = header + "." + payload; Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
        return content + "." + encoder.encodeToString(mac.doFinal(content.getBytes(StandardCharsets.US_ASCII)));
    }
}
