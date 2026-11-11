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
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
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

/** Real Node -> installation authorization/controller/normalizer -> independent Spring/MySQL ledger.
 * Installation, binding, original intent, FINISHING lease and ledger use actual SQL/locks.
 * Account/identity collaborators for ordinary session rejection and provider remain synthetic;
 * this does not claim full release orchestration or paid-model acceptance. */
@EnabledIfEnvironmentVariable(named = EconomyMySqlTestFixture.URL_ENV, matches = ".+")
@EnabledIfEnvironmentVariable(named = "CYF_KNOWLEDGE_NODE_FIXTURE", matches = ".+")
class PlatformKnowledgeUsageHttpMySqlTest {
    @Test void installationReceiptsSurviveDisabledOwnerAndKeepOriginalTariffAfterLostAck() throws Exception {
        try (var fixture = new EconomyMySqlTestFixture()) {
            fixture.start(); var db = fixture.newDatabase("knowledge");
            new EconomyUsageCostSchemaInitializer(db.dataSource()).afterPropertiesSet();
            var config = new com.baomidou.mybatisplus.core.MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(EconomyUsageCostMapper.class);
            config.addMapper(cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper.class);
            config.addMapper(cn.jia.agent.mapper.AgentPersonaBindingMapper.class);
            config.addMapper(HostingRentFinishingMapper.class);
            config.addMapper(cn.jia.economy.mapper.EconomyHostingRentMapper.class);
            config.addMapper(cn.jia.economy.mapper.EconomyHostingRenewalMapper.class);
            config.addMapper(cn.jia.economy.mapper.EconomyLedgerMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(db.dataSource()); factory.setConfiguration(config);
            var template = new SqlSessionTemplate(factory.getObject());
            var sql = template.getMapper(EconomyUsageCostMapper.class);
            var manager = new DataSourceTransactionManager(db.dataSource());
            var proxy = new ProxyFactory(new UsageCostServiceImpl(sql, new ObjectMapper()));
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var costs = (UsageCostService) proxy.getProxy();
            var identity = new AgentRuntimePersistentSessionFenceTest(); identity.setup();
            seedMaintenanceRelationship(db.jdbc());
            var installations = new cn.jia.agent.dao.impl.AgentRuntimeV1InstallationDaoImpl();
            org.springframework.test.util.ReflectionTestUtils.setField(installations, "baseMapper",
                    template.getMapper(cn.jia.agent.mapper.AgentRuntimeV1InstallationMapper.class));
            var bindings = new cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl();
            org.springframework.test.util.ReflectionTestUtils.setField(bindings, "baseMapper",
                    template.getMapper(cn.jia.agent.mapper.AgentPersonaBindingMapper.class));
            var gate = new cn.jia.economy.config.EconomyPreviewGate(new cn.jia.economy.config.EconomyPreviewProperties(
                    true, java.util.List.of(new cn.jia.economy.config.EconomyPreviewProperties.AllowedScope("0", "client"))));
            var accounts = template.getMapper(cn.jia.economy.mapper.EconomyLedgerMapper.class);
            var posting = new cn.jia.economy.service.impl.EconomyPostingServiceImpl(accounts, manager, gate);
            var renewal = new cn.jia.economy.service.impl.HostingRentRenewalServiceImpl(
                    template.getMapper(cn.jia.economy.mapper.EconomyHostingRentMapper.class),
                    template.getMapper(cn.jia.economy.mapper.EconomyHostingRenewalMapper.class), accounts, posting, gate, manager);
            // The old account cannot authenticate; installation maintenance must still deliver only platform evidence.
            when(identity.accounts.findUniqueByExactJiacn("owner")).thenReturn(Optional.of(new cn.jia.user.security.AccountSecuritySnapshot(7, "owner", cn.jia.user.security.AccountState.DISABLED, 3)));
            var runtimeTarget = new cn.jia.agent.service.impl.AgentRuntimeV1ServiceImpl(installations,
                    identity.identities, identity.registry, mock(org.springframework.beans.factory.ObjectProvider.class),
                    identity.auth, mock(org.springframework.beans.factory.ObjectProvider.class),
                    new cn.jia.core.security.FixedTenantAdmissionPolicy("0", "client"));
            var runtimeProxy = new ProxyFactory(runtimeTarget);
            runtimeProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            var runtime = (AgentRuntimeV1Service)runtimeProxy.getProxy();
            var oldPrice = new ModelTokenPrice("platform", "gpt-6-luna", "standard", "v1", "synthetic-cny",
                    Map.of(INPUT, new BigDecimal("1"), CACHED_INPUT, new BigDecimal("0.1"), OUTPUT, new BigDecimal("5")), null, Map.of());
            var newPrice = new ModelTokenPrice("platform", "gpt-6-luna", "standard", "v2", "synthetic-cny",
                    Map.of(INPUT, new BigDecimal("100"), CACHED_INPUT, new BigDecimal("10"), OUTPUT, new BigDecimal("500")), null, Map.of());
            var current = new AtomicReference<>(oldPrice);
            var catalog = mock(ModelPriceCatalog.class);
            when(catalog.find("platform", "gpt-6-luna", "standard")).thenAnswer(call -> Optional.of(current.get()));
            var recorder = new PlatformKnowledgeUsageRecorder(template.getMapper(HostingRentFinishingMapper.class), costs, catalog, bindings, renewal);
            var servletPrincipal = new SecurityContextHolderAwareRequestFilter(); servletPrincipal.afterPropertiesSet();
            var nativeFilter = new AgentRuntimeAuthenticationFilter(identity.auth);
            // Production HttpSecurity mounts this closed filter only on its native matcher.
            // The installation-Bearer session exchange is a different credential lane.
            jakarta.servlet.Filter nativeLane = (request, response, chain) -> {
                if (!cn.jia.agent.config.AgentRuntimeSecurityConfiguration.selectsRuntimeV1ClientLane((jakarta.servlet.http.HttpServletRequest) request)
                        && AgentRuntimeAuthenticationFilter.selectsRuntimeCredentialLane((jakarta.servlet.http.HttpServletRequest) request))
                    nativeFilter.doFilter(request, response, chain);
                else chain.doFilter(request, response);
            };
            var mvc = MockMvcBuilders.standaloneSetup(new PlatformKnowledgeUsageController(recorder, runtime), new AgentRuntimeV1Controller(runtime))
                    .setControllerAdvice(new ApiEnvelopeAdvice(), new ApiRequestEnvelopeAdvice())
                    .addFilters(new RequestIdFilter(), nativeLane, servletPrincipal).build();
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
                        if ("STARTED".equals(data.path("observation").path("phase").asString())) current.set(newPrice);
                        if ("gpt-6-luna".equals(data.path("observation").path("requestedModel").asString())
                                && data.path("observation").path("evidence").path("usage").has("total_tokens")) {
                            if (responses.incrementAndGet() == 1) { exchange.close(); return; } // real commit, lost network ACK
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
                assertTrue(read(log).contains("\"providerCalls\":1"));
                verify(identity.accounts, times(1)).findUniqueByExactJiacn("owner");
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
                assertEquals(0L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_transaction", Long.class));
                // Closing the original relationship also denies old-receipt replay; an installation
                // token alone must never keep writing costs after release or after revocation.
                var evidence = new PlatformKnowledgeUsageRecorder.Evidence("0", "extract",
                        PlatformKnowledgeUsageRecorder.Phase.STARTED, "platform", "gpt-6-luna", "standard", null);
                String bearer = "rta1_" + "d".repeat(64);
                db.jdbc().update("UPDATE economy_hosting_lease SET status='CLOSED',live_slot=NULL WHERE lease_id='lease'");
                assertThrows(cn.jia.agent.service.PersonalWorkspaceExecutionService.Failure.class,
                        () -> runtime.withInstallationAuthorization(bearer,
                                install -> recorder.record(install, "lease", "handover", evidence)));
                db.jdbc().update("UPDATE agent_runtime_v1_installation SET status='REVOKED',runtime_authorization_hash=NULL");
                assertThrows(RuntimeException.class, () -> runtime.withInstallationAuthorization(bearer,
                        install -> { fail("revoked installation must not reach maintenance"); return null; }));
                assertEquals(6L, db.jdbc().queryForObject("SELECT COUNT(*) FROM economy_usage_revision", Long.class));
            } finally {
                if (node != null && node.isAlive()) { node.destroy(); node.waitFor(); }
                server.stop(0);
                // Logs/receipt scratch remains under the task's disk-only TMPDIR for evidence.
            }
        }
    }
    private static void seedMaintenanceRelationship(org.springframework.jdbc.core.JdbcTemplate jdbc) throws Exception {
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("db/economy-v0-foundation.sql"),
                new org.springframework.core.io.ClassPathResource("db/economy-v0-hosting-rent.sql"),
                new org.springframework.core.io.ClassPathResource("db/agent-runtime-v1-schema.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        // Narrow binding fixture mirrors the persisted fields consumed by real binding/finishing SQL.
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                    id BIGINT PRIMARY KEY, jiacn VARCHAR(50), persona_code VARCHAR(50), agent_id VARCHAR(100),
                    bound_at BIGINT, status INT, tenant_id VARCHAR(50), client_id VARCHAR(50),
                    create_time BIGINT, update_time BIGINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        String agent = AgentRuntimePersistentSessionFenceTest.AGENT;
        String installation = AgentRuntimePersistentSessionFenceTest.ID;
        jdbc.update("""
                INSERT INTO agent_persona_binding (id,jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id)
                VALUES (3,'owner','wuyong',?,1,1,'0','client')
                """, agent);
        jdbc.update("""
                INSERT INTO agent_runtime_v1_installation (installation_id,canonical_agent_id,manifest_version,
                    manifest_sha256,enrollment_secret_hash,enrollment_expires_at,runtime_authorization_hash,
                    status,version,tenant_id,client_id)
                VALUES (?,?,'1',?,REPEAT('a',32),1,?,'ACTIVE',1,'0','client')
                """, installation, agent, "a".repeat(64), java.security.MessageDigest.getInstance("SHA-256")
                        .digest(("rta1_" + "d".repeat(64)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("""
                INSERT INTO economy_hosting_lease (lease_id,principal_type,principal_id,persona_code,agent_id,
                    binding_id,plan_id,plan_version,amount_wen,period_seconds,status,handover_id,executor_stopped_at,
                    latest_intent_id,version,tenant_id,client_id,create_time,update_time)
                VALUES ('lease','USER','7','wuyong',?,'3','hosted',1,500,2592000,'FINISHING','handover',100,
                    'initial',1,'0','client',1,1)
                """, agent);
        jdbc.update("""
                INSERT INTO economy_hosting_provisioning_intent (intent_id,lease_id,quote_id,quote_purpose,
                    principal_type,principal_id,persona_code,agent_id,amount_wen,period_seconds,status,
                    reserve_idempotency_key,reserve_request_hash,reserved_at,escrow_version,version,tenant_id,
                    client_id,create_time,update_time,runtime_installation_id,runtime_manifest_sha256)
                VALUES ('initial','lease','quote','INITIAL','USER','7','wuyong',?,500,2592000,'ACTIVE',
                    'original-key',REPEAT('a',32),1,1,1,'0','client',1,1,?,?)
                """, agent, installation, "a".repeat(64));
    }
    private static String read(Path path) { try { return Files.readString(path); } catch (Exception failure) { return failure.toString(); } }
}
