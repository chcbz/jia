package cn.jia.agent.security;

import cn.jia.agent.api.AgentRuntimeV1Controller;
import cn.jia.agent.api.PlatformKnowledgeUsageController;
import cn.jia.agent.entity.AgentPersonaBindingEntity;
import cn.jia.agent.entity.AgentRuntimeV1SessionRequest;
import cn.jia.agent.mapper.HostingRentFinishingMapper;
import cn.jia.agent.service.AgentRuntimeV1Service;
import cn.jia.agent.service.usage.PlatformKnowledgeUsageRecorder;
import cn.jia.core.config.ApiEnvelopeAdvice;
import cn.jia.core.config.ApiRequestEnvelopeAdvice;
import cn.jia.core.filter.RequestIdFilter;
import cn.jia.economy.config.EconomyMySqlTestFixture;
import cn.jia.economy.config.EconomyUsageCostSchemaInitializer;
import cn.jia.economy.mapper.EconomyUsageCostMapper;
import cn.jia.economy.pricing.ModelPriceCatalog;
import cn.jia.economy.pricing.ModelTokenPrice;
import cn.jia.economy.service.impl.UsageCostServiceImpl;
import cn.jia.economy.usage.UsageCostService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.web.servletapi.SecurityContextHolderAwareRequestFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static cn.jia.economy.pricing.ModelTokenPrice.Category.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Real Node -> native filter/controller/normalizer -> independent Spring/MySQL ledger.
 * Identity repositories and FINISHING discovery are seeded mocks; provider is synthetic.
 * This does not claim production identity SQL, full release orchestration or paid-model acceptance. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
@EnabledIfEnvironmentVariable(named = "CYF_KNOWLEDGE_NODE_FIXTURE", matches = ".+")
class PlatformKnowledgeUsageHttpMySqlTest {
    @Test void realNativeReceiptsKeepOriginalTariffAndPlatformOwnershipAfterLostAck() throws Exception {
        try (var fixture = new EconomyMySqlTestFixture()) {
            fixture.start(); var db = fixture.newDatabase("knowledge");
            new EconomyUsageCostSchemaInitializer(db.dataSource()).afterPropertiesSet();
            var config = new org.apache.ibatis.session.Configuration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(EconomyUsageCostMapper.class);
            var factory = new SqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
            var sql = new SqlSessionTemplate(factory.getObject()).getMapper(EconomyUsageCostMapper.class);
            var manager = new DataSourceTransactionManager(db.dataSource());
            var proxy = new ProxyFactory(new UsageCostServiceImpl(sql, new ObjectMapper()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var costs = (UsageCostService) proxy.getProxy();
            var identity = new AgentRuntimePersistentSessionFenceTest(); identity.setup();
            var runtime = mock(AgentRuntimeV1Service.class);
            when(runtime.session(anyString(), any(), anyLong())).thenAnswer(invocation -> {
                assertEquals("rta1_" + "d".repeat(64), invocation.getArgument(0));
                AgentRuntimeV1SessionRequest session = invocation.getArgument(1);
                return identity.auth.issue(identity.installation, session, invocation.getArgument(2));
            });
            var relations = mock(HostingRentFinishingMapper.class);
            when(relations.findFinishingBinding("0", "client", "lease")).thenReturn(new AgentPersonaBindingEntity()
                    .setJiacn("owner").setAgentId(AgentRuntimePersistentSessionFenceTest.AGENT));
            var oldPrice = new ModelTokenPrice("platform", "gpt-6-luna", "standard", "v1", "synthetic-cny",
                    Map.of(INPUT, new BigDecimal("1"), CACHED_INPUT, new BigDecimal("0.1"), OUTPUT, new BigDecimal("5")), null, Map.of());
            var newPrice = new ModelTokenPrice("platform", "gpt-6-luna", "standard", "v2", "synthetic-cny",
                    Map.of(INPUT, new BigDecimal("100"), CACHED_INPUT, new BigDecimal("10"), OUTPUT, new BigDecimal("500")), null, Map.of());
            var current = new AtomicReference<>(oldPrice);
            var catalog = mock(ModelPriceCatalog.class);
            when(catalog.find("platform", "gpt-6-luna", "standard")).thenAnswer(call -> Optional.of(current.get()));
            var recorder = new PlatformKnowledgeUsageRecorder(relations, costs, catalog);
            var servletPrincipal = new SecurityContextHolderAwareRequestFilter(); servletPrincipal.afterPropertiesSet();
            var mvc = MockMvcBuilders.standaloneSetup(new PlatformKnowledgeUsageController(recorder, identity.auth), new AgentRuntimeV1Controller(runtime))
                    .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice())
                    .addFilters(new RequestIdFilter(), new AgentRuntimeAuthenticationFilter(identity.auth), servletPrincipal).build();
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var serverError = new AtomicReference<Throwable>(); var responses = new AtomicInteger();
            server.createContext("/", exchange -> {
                try {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    var builder = request(org.springframework.http.HttpMethod.valueOf(exchange.getRequestMethod()), exchange.getRequestURI()).content(bytes);
                    exchange.getRequestHeaders().forEach((key, values) -> builder.header(key, values.toArray()));
                    var result = mvc.perform(builder).andReturn().getResponse();
                    System.out.println("KNOWLEDGE_HTTP " + exchange.getRequestURI().getPath() + " status=" + result.getStatus());
                    var body = new ObjectMapper().readTree(bytes);
                    var data = body.get("data");
                    if (exchange.getRequestURI().getPath().endsWith("knowledge-usage") && result.getStatus() == 200) {
                        assertFalse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("Synthetic private lesson"));
                        if ("handover".equals(data.path("handoverId").asString())) {
                            if ("STARTED".equals(data.path("observation").path("phase").asString())) current.set(newPrice);
                            else if (responses.incrementAndGet() == 1) { exchange.close(); return; } // real commit, lost network ACK
                        }
                    }
                    for (String key : result.getHeaderNames()) exchange.getResponseHeaders().put(key, new java.util.ArrayList<>(result.getHeaders(key)));
                    byte[] output = result.getContentAsByteArray();
                    exchange.sendResponseHeaders(result.getStatus(), output.length); exchange.getResponseBody().write(output);
                } catch (Throwable failure) {
                    serverError.compareAndSet(null, failure);
                    failure.printStackTrace(); // synthetic loopback fixture only; retained diagnosis
                    try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { }
                } finally { exchange.close(); }
            });
            Path scratch = Files.createTempDirectory("knowledge-java-");
            Path log = scratch.resolve("node.log"); Process node = null;
            try {
                server.start();
                node = new ProcessBuilder(System.getenv("CYF_KNOWLEDGE_NODE"), System.getenv("CYF_KNOWLEDGE_NODE_FIXTURE"),
                        "http://127.0.0.1:" + server.getAddress().getPort(), scratch.toString(),
                        AgentRuntimePersistentSessionFenceTest.AGENT, AgentRuntimePersistentSessionFenceTest.ID)
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                int exit = node.waitFor();
                assertNull(serverError.get(), () -> String.valueOf(serverError.get()));
                assertEquals(0, exit, () -> read(log));
                assertTrue(read(log).contains("\"providerCalls\":3"));
                assertEquals(2, responses.get(), "one paid response, same receipt after lost ACK");
                assertEquals(3L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_attempt", Long.class));
                assertEquals(6L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_revision", Long.class));
                assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_task_cost", Long.class));
                assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_attempt WHERE owner_jiacn IS NOT NULL OR task_id IS NOT NULL", Long.class));
                assertEquals(new BigDecimal("0.000132"), db.jdbc().queryForObject("SELECT cost_yuan FROM economy_usage_attempt WHERE status='PRICED'", BigDecimal.class));
                assertEquals(1L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_attempt WHERE status='PENDING_PRICE' AND cost_yuan IS NULL", Long.class));
                assertEquals(1L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_attempt WHERE status='PENDING_USAGE' AND cost_yuan IS NULL", Long.class));
                var snapshots = db.jdbc().queryForList("SELECT price_snapshot_json FROM economy_usage_revision WHERE cost_yuan IS NOT NULL", String.class);
                assertEquals(1, snapshots.size()); assertTrue(snapshots.getFirst().contains("v1")); assertFalse(snapshots.getFirst().contains("v2"));
            } finally {
                if (node != null && node.isAlive()) { node.destroy(); node.waitFor(); }
                server.stop(0);
                // Logs/receipt scratch remains under the task's disk-only TMPDIR for evidence.
            }
        }
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return failure.toString(); } }
}
