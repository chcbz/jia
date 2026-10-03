package cn.jia.fixture.archive;

import cn.jia.agent.archive.ArchiveAgentExecutionAdapter;
import cn.jia.agent.config.AgentRabbitActivationState;
import cn.jia.agent.config.AgentRabbitReadiness;
import cn.jia.agent.config.AgentRabbitSafetyGate;
import cn.jia.agent.config.AgentRabbitTopologyManifest;
import cn.jia.agent.config.AgentRabbitTopologyProvisioner;
import cn.jia.agent.config.AgentRabbitTopologyReadiness;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.platform.PlatformInstalledSkillResolver;
import cn.jia.agent.platform.PlatformSkillInstallationService;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentCommandTransportWriter;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentManagedSessionLookup;
import cn.jia.agent.service.AgentService;
import cn.jia.agent.service.AgentTaskArtifactStorage;
import cn.jia.agent.service.impl.AgentCommandRabbitConsumer;
import cn.jia.agent.service.impl.AgentCommandTransportWriterImpl;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentOutboxRelayScheduler;
import cn.jia.agent.service.impl.AgentServiceImpl;
import cn.jia.agent.service.impl.FileSystemAgentTaskArtifactStorage;
import cn.jia.chat.archive.maintenance.service.ArchiveExecutionCommandDispatcher;
import cn.jia.chat.handler.AgentWebSocketHandler;
import cn.jia.chat.service.SkillManagedSessionAdapter;
import cn.jia.oauth.security.AccountSecurityJwtValidator;
import cn.jia.oauth.service.ApiKeyService;
import cn.jia.oauth.service.impl.ApiKeyServiceImpl;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecurityServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.aop.support.AopUtils;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Disposable, test-only shared production-runtime bootstrap. Main owns all execution. */
@SpringBootApplication
@ComponentScan(
        basePackages = "cn.jia",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "cn\\.jia\\.chat\\.tool\\.(KefuTools|MaterialTools|PointTools)"))
@EnableTransactionManagement
@EnableScheduling
@MapperScan("cn.jia.*.mapper")
public class ArchiveRealRuntimeFixtureApplication {
    private static final String FIXTURE_PROFILE = "archive-real-runtime";
    private static final String ACTIVE_PROFILES_PROPERTY = "spring.profiles.active";
    private static final String INCLUDED_PROFILES_PROPERTY = "spring.profiles.include";
    private static final String EXPECTED_TOPOLOGY_SHA =
            "96fd7d32aba468eacbcf96dfa5d441fd938f0d0cb5c6dafcdcae9e797fb4110e";
    private static final String EXPECTED_PACKAGE_SHA =
            "8894d96341067dd7f9e2f45696eef44057dc61346255a0323b2d713a3c7ea081";
    private static final String MYSQL_PREFLIGHT_ONLY = "--mysql-preflight-only";

    public static void main(String[] args) {
        requireMysqlPreflightEnvironment();
        boolean preflightOnly = mysqlPreflightOnly(args);
        if (!preflightOnly) {
            requireEnvironment();
        }
        requireNoForeignProfileSelection(args);
        preflightMysql();
        System.setProperty(ACTIVE_PROFILES_PROPERTY, FIXTURE_PROFILE);
        SpringApplication application = new SpringApplication(ArchiveRealRuntimeFixtureApplication.class);
        // All external property sources are now resolved, but no bean or SQL initializer has run yet.
        application.addInitializers(context -> {
            requireOnlyFixtureProfile(context.getEnvironment());
            requireTrustedDatasourceConfiguration(context.getEnvironment());
            if (preflightOnly) {
                throw new MysqlPreflightComplete();
            }
        });
        try {
            application.run(args);
        } catch (RuntimeException failure) {
            if (preflightOnly && causedByMysqlPreflightComplete(failure)) {
                return;
            }
            throw failure;
        }
    }

    /** The sole allowed provider double: any accidental paid-model invocation fails closed. */
    @Bean
    @Primary
    ChatModel fixtureChatModel() {
        return new ChatModel() {
            @Override public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("PAID_CHAT_MODEL_DISABLED_IN_ARCHIVE_REAL_RUNTIME_FIXTURE");
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new IllegalStateException(
                        "PAID_CHAT_MODEL_DISABLED_IN_ARCHIVE_REAL_RUNTIME_FIXTURE"));
            }
        };
    }

    @Bean
    @Primary
    ChatClient fixtureChatClient(ChatModel fixtureChatModel) {
        return ChatClient.builder(fixtureChatModel).build();
    }

    @Bean
    FixtureJwtMaterial fixtureJwtMaterial(AccountSecurityService accounts) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(UUID.randomUUID().toString()).algorithm(JWSAlgorithm.RS256).build();
        var source = new ImmutableJWKSet<SecurityContext>(new JWKSet(key));
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(source).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(required("CYF_FIXTURE_JWT_AUDIENCE"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(required("CYF_FIXTURE_JWT_ISSUER")),
                audience, new AccountSecurityJwtValidator(accounts)));
        return new FixtureJwtMaterial(decoder, new NimbusJwtEncoder(source));
    }

    @Bean
    @Primary
    JwtDecoder fixtureJwtDecoder(FixtureJwtMaterial material) {
        return material.decoder();
    }

    /** Provision the canonical production topology before any listener or relay SmartLifecycle starts. */
    @Bean
    SmartInitializingSingleton archiveRealRuntimeRabbitProvisioner(
            @Qualifier("agentRabbitTopologyProvisioner") AgentRabbitTopologyProvisioner provisioner,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest) {
        return () -> {
            if (!EXPECTED_TOPOLOGY_SHA.equals(manifest.sha256())) {
                throw new IllegalStateException("Unexpected Agent Rabbit topology manifest");
            }
            AgentRabbitTopologyReadiness.Snapshot snapshot = provisioner.provision();
            if (!snapshot.canonicalTopologyReady()
                    || !EXPECTED_TOPOLOGY_SHA.equals(snapshot.manifestSha256())) {
                throw new IllegalStateException("Canonical Agent Rabbit topology is not ready");
            }
        };
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner archiveRealRuntimeBootstrap(
            JdbcTemplate jdbc,
            FixtureJwtMaterial jwt,
            ObjectMapper mapper,
            ApplicationContext context,
            WebServerApplicationContext webServer,
            AgentRabbitSafetyGate rabbitGate,
            AgentRabbitReadiness rabbitReadiness,
            @Qualifier("agentRabbitTopologyReadiness") AgentRabbitTopologyReadiness topologyReadiness,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest topologyManifest,
            @Qualifier("agentOutboxRelayScheduler") AgentOutboxRelayScheduler relayScheduler,
            RabbitListenerEndpointRegistry listenerRegistry) {
        return args -> {
            requireOnlyFixtureProfile(context.getEnvironment());
            String host = required("CYF_FIXTURE_HOST");
            verifyLoopbackOrPrivate(host);
            verifyLoopbackOrPrivate(required("CYF_RABBIT_HOST"));
            verifyIsolatedDatabase(jdbc);
            long userId = seedPrerequisites(jdbc);
            requireRealSharedComponents(context);
            requireRabbitReady(rabbitGate, rabbitReadiness, topologyReadiness, topologyManifest,
                    relayScheduler, listenerRegistry);

            Path tokenFile = path("CYF_FIXTURE_ADMIN_TOKEN_FILE");
            Path readyFile = path("CYF_FIXTURE_READY_FILE");
            Path resultFile = path("CYF_FIXTURE_RESULT_FILE");
            atomic(tokenFile, jwt.issue(userId) + "\n");

            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("status", "awaiting-main-driver");
            pending.put("runtimeEvidence", "UNVERIFIED");
            pending.put("resultFile", resultFile.toString());
            atomic(resultFile, mapper.writeValueAsString(pending) + "\n");

            Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("status", "ready");
            ready.put("runtimeEvidence", "UNVERIFIED");
            ready.put("componentMode", "shared-production-wiring");
            ready.put("allowedDouble", "fail-closed-chat-model");
            ready.put("origin", "http://" + host + ":" + webServer.getWebServer().getPort());
            ready.put("rabbitTopologySha256", topologyManifest.sha256());
            ready.put("approvedPackageSha256", EXPECTED_PACKAGE_SHA);
            ready.put("resultFile", resultFile.toString());
            atomic(readyFile, mapper.writeValueAsString(ready) + "\n");
        };
    }

    record FixtureJwtMaterial(NimbusJwtDecoder decoder, NimbusJwtEncoder encoder) {
        String issue(long userId) {
            if (userId <= 0) throw new IllegalStateException("fixture account id is invalid");
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(required("CYF_FIXTURE_JWT_ISSUER"))
                    .audience(List.of(required("CYF_FIXTURE_JWT_AUDIENCE")))
                    .subject(required("CYF_FIXTURE_ACTOR"))
                    .issuedAt(now).expiresAt(now.plusSeconds(3600))
                    .claim("token_kind", "user")
                    .claim("uid", Long.toString(userId))
                    .claim("jiacn", required("CYF_FIXTURE_OWNER"))
                    .claim("username", required("CYF_FIXTURE_ACTOR"))
                    .claim("auth_epoch", 0L)
                    .claim("client_id", required("CYF_FIXTURE_CLIENT"))
                    .claim("scope", "archive.manage")
                    .build();
            JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
            return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        }
    }

    private static long seedPrerequisites(JdbcTemplate jdbc) {
        long now = System.currentTimeMillis();
        String owner = required("CYF_FIXTURE_OWNER");
        String client = required("CYF_FIXTURE_CLIENT");
        String actor = required("CYF_FIXTURE_ACTOR");
        String agent = required("CYF_FIXTURE_AGENT_ID");
        String apiKey = required("CYF_FIXTURE_API_KEY");
        String apiKeyId = required("CYF_FIXTURE_API_KEY_ID");
        if (!actor.equals(owner)) throw new IllegalStateException("fixture actor must equal scoped owner");
        if (!agent.matches("agt_[0-9a-f]{32}")) {
            throw new IllegalStateException("CYF_FIXTURE_AGENT_ID must be canonical opaque form");
        }

        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_info WHERE BINARY jiacn=BINARY ?", Integer.class, owner);
        if (userCount == null || userCount > 1) throw new IllegalStateException("fixture account is ambiguous");
        if (userCount == 0) {
            jdbc.update("INSERT INTO user_info(username,jiacn,client_id,account_state,auth_epoch,tenant_id,create_time,update_time) "
                            + "VALUES (?,?,?,'ACTIVE',0,'0',?,?)",
                    actor, owner, client, now, now);
        } else {
            int updated = jdbc.update("UPDATE user_info SET username=?,client_id=?,account_state='ACTIVE',auth_epoch=0,update_time=? "
                            + "WHERE BINARY jiacn=BINARY ? AND (tenant_id='0' OR tenant_id IS NULL)",
                    actor, client, now, owner);
            if (updated != 1) throw new IllegalStateException("fixture account scope changed");
        }
        Long userId = jdbc.queryForObject(
                "SELECT id FROM user_info WHERE BINARY jiacn=BINARY ?", Long.class, owner);
        if (userId == null || userId <= 0) throw new IllegalStateException("fixture account is missing");

        Integer keyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_api_key WHERE BINARY id=BINARY ?", Integer.class, apiKeyId);
        if (keyCount == null || keyCount > 1) throw new IllegalStateException("fixture API key id is ambiguous");
        if (keyCount == 0) {
            jdbc.update("INSERT INTO oauth_api_key(id,client_id,jiacn,api_key,key_name,status,expire_time,description,tenant_id,create_time,update_time) "
                            + "VALUES (?,?,?,?,?,1,?,'isolated archive real runtime fixture','0',?,?)",
                    apiKeyId, client, owner, apiKey, "archive-real-runtime", now + 3_600_000L, now, now);
        } else {
            int updated = jdbc.update("UPDATE oauth_api_key SET api_key=?,status=1,expire_time=?,update_time=? "
                            + "WHERE BINARY id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY jiacn=BINARY ? "
                            + "AND tenant_id='0' AND key_name='archive-real-runtime'",
                    apiKey, now + 3_600_000L, now, apiKeyId, client, owner);
            if (updated != 1) throw new IllegalStateException("fixture API key scope changed");
        }

        Integer personaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_persona WHERE BINARY persona_code=BINARY 'fixture-editor'",
                Integer.class);
        if (personaCount == null || personaCount > 1) throw new IllegalStateException("fixture persona is ambiguous");
        if (personaCount == 0) {
            jdbc.update("INSERT INTO agent_persona(persona_code,name,title,abilities,active,system_agent,tenant_id,client_id,create_time,update_time) "
                            + "VALUES ('fixture-editor','Fixture Editor','Archive fixture','[\"archive-maintainer\"]',1,0,'0',?,?,?)",
                    client, now, now);
        }

        Integer bindingCount = jdbc.queryForObject("SELECT COUNT(*) FROM agent_persona_binding WHERE tenant_id='0' "
                        + "AND BINARY client_id=BINARY ? AND BINARY jiacn=BINARY ? AND BINARY agent_id=BINARY ?",
                Integer.class, client, owner, agent);
        if (bindingCount == null || bindingCount > 1) throw new IllegalStateException("fixture identity binding is ambiguous");
        if (bindingCount == 0) {
            jdbc.update("INSERT INTO agent_persona_binding(jiacn,persona_code,agent_id,bound_at,status,tenant_id,client_id,create_time,update_time) "
                            + "VALUES (?,'fixture-editor',?,?,1,'0',?,?,?)",
                    owner, agent, now, client, now, now);
        }
        Long binding = jdbc.queryForObject("SELECT id FROM agent_persona_binding WHERE tenant_id='0' "
                        + "AND BINARY client_id=BINARY ? AND BINARY jiacn=BINARY ? AND BINARY agent_id=BINARY ? "
                        + "AND status=1 AND BINARY persona_code=BINARY 'fixture-editor'",
                Long.class, client, owner, agent);
        if (binding == null || binding <= 0) throw new IllegalStateException("fixture active binding is missing");

        Integer identityCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_identity_registry WHERE BINARY canonical_agent_id=BINARY ?",
                Integer.class, agent);
        if (identityCount == null || identityCount > 1) throw new IllegalStateException("fixture canonical identity is ambiguous");
        if (identityCount == 0) {
            jdbc.update("INSERT INTO agent_identity_registry(canonical_agent_id,canonical_type,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,activated_at,audit_reason,create_time,update_time) "
                            + "VALUES (?,'OPAQUE','ACTIVE',?,?,'0',?,?,?,'isolated real runtime fixture',?,?)",
                    agent, client, owner, binding, now, now, now, now);
        }
        Integer exactIdentity = jdbc.queryForObject("SELECT COUNT(*) FROM agent_identity_registry "
                        + "WHERE BINARY canonical_agent_id=BINARY ? AND canonical_type='OPAQUE' AND lifecycle_status='ACTIVE' "
                        + "AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? AND tenant_id='0' AND binding_id=?",
                Integer.class, agent, client, owner, binding);
        if (!Integer.valueOf(1).equals(exactIdentity)) {
            throw new IllegalStateException("fixture canonical identity scope changed");
        }

        Integer runtimeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_runtime WHERE BINARY agent_id=BINARY ?", Integer.class, agent);
        if (runtimeCount == null || runtimeCount > 1) throw new IllegalStateException("fixture runtime is ambiguous");
        if (runtimeCount == 0) {
            jdbc.update("INSERT INTO agent_runtime(agent_id,name,persona_name,persona_code,abilities,status,last_seen_at,owner_jiacn,binding_id,tenant_id,client_id,create_time,update_time) "
                            + "VALUES (?,'Fixture Editor','Fixture Editor','fixture-editor','[\"archive-maintainer\"]','offline',?,?,?,'0',?,?,?)",
                    agent, now, owner, binding, client, now, now);
        } else {
            int updated = jdbc.update("UPDATE agent_runtime SET name='Fixture Editor',persona_name='Fixture Editor',persona_code='fixture-editor',"
                            + "abilities='[\"archive-maintainer\"]',status='offline',token_hash=NULL,last_seen_at=?,update_time=? "
                            + "WHERE BINARY agent_id=BINARY ? AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? "
                            + "AND tenant_id='0' AND binding_id=?",
                    now, now, agent, client, owner, binding);
            if (updated != 1) throw new IllegalStateException("fixture runtime scope changed");
        }

        Integer grant = jdbc.queryForObject("SELECT COUNT(*) FROM archive_collection_manager WHERE collection_id='platform-classics' "
                        + "AND tenant_id='0' AND BINARY client_id=BINARY ? AND BINARY owner_jiacn=BINARY ? "
                        + "AND state='ACTIVE' AND revision=1",
                Integer.class, client, owner);
        if (!Integer.valueOf(1).equals(grant)) {
            throw new IllegalStateException("production manager-grant reconciliation did not establish fixture scope");
        }
        return userId;
    }

    private static void requireRabbitReady(
            AgentRabbitSafetyGate gate,
            AgentRabbitReadiness readiness,
            AgentRabbitTopologyReadiness topology,
            AgentRabbitTopologyManifest manifest,
            AgentOutboxRelayScheduler relay,
            RabbitListenerEndpointRegistry listeners) {
        if (gate.state() != AgentRabbitActivationState.DISPATCH_CANARY
                || readiness.state() != AgentRabbitActivationState.DISPATCH_CANARY
                || !readiness.brokerRequired()
                || gate.dispatchScopeCount() != 1
                || !gate.allowsDispatch("0", required("CYF_FIXTURE_CLIENT"))) {
            throw new IllegalStateException("Agent Rabbit dispatch gate is not the exact fixture canary");
        }
        AgentRabbitTopologyReadiness.Snapshot snapshot = topology.snapshot();
        if (!snapshot.canonicalTopologyReady()
                || !manifest.sha256().equals(snapshot.manifestSha256())
                || !EXPECTED_TOPOLOGY_SHA.equals(snapshot.manifestSha256())) {
            throw new IllegalStateException("Agent Rabbit canonical topology readiness was lost");
        }
        if (!relay.isRunning()) throw new IllegalStateException("Agent Rabbit outbox relay is not running");
        var listener = listeners.getListenerContainer(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V1);
        if (listener == null || !listener.isRunning()) {
            throw new IllegalStateException("Agent Rabbit command listener is not running");
        }
    }

    private static void requireRealSharedComponents(ApplicationContext context) {
        Map<Class<?>, Class<?>> requiredTypes = new LinkedHashMap<>();
        requiredTypes.put(AgentIdentityService.class, AgentIdentityServiceImpl.class);
        requiredTypes.put(AgentService.class, AgentServiceImpl.class);
        requiredTypes.put(AccountSecurityService.class, AccountSecurityServiceImpl.class);
        requiredTypes.put(ApiKeyService.class, ApiKeyServiceImpl.class);
        requiredTypes.put(AgentRuntimeAuthenticationService.class, AgentRuntimeAuthenticationService.class);
        requiredTypes.put(AgentWebSocketHandler.class, AgentWebSocketHandler.class);
        requiredTypes.put(AgentCommandTransportWriter.class, AgentCommandTransportWriterImpl.class);
        requiredTypes.put(AgentManagedSessionLookup.class, SkillManagedSessionAdapter.class);
        requiredTypes.put(PlatformSkillInstallationService.class, PlatformSkillInstallationService.class);
        requiredTypes.put(PlatformInstalledSkillResolver.class, PlatformInstalledSkillResolver.class);
        requiredTypes.put(ArchiveAgentExecutionAdapter.class, ArchiveAgentExecutionAdapter.class);
        requiredTypes.put(ArchiveExecutionCommandDispatcher.class, ArchiveExecutionCommandDispatcher.class);
        requiredTypes.put(AgentCommandRabbitConsumer.class, AgentCommandRabbitConsumer.class);
        requiredTypes.put(AgentTaskArtifactStorage.class, FileSystemAgentTaskArtifactStorage.class);
        for (Map.Entry<Class<?>, Class<?>> required : requiredTypes.entrySet()) {
            Object bean = context.getBean(required.getKey());
            Class<?> target = AopUtils.getTargetClass(bean);
            if (target == null || !required.getValue().isAssignableFrom(target)) {
                throw new IllegalStateException("Shared production component missing: " + required.getKey().getName());
            }
        }
    }

    private static void atomic(Path target, String value) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".aam-", ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path path(String name) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }

    private static void requireNoForeignProfileSelection(String[] args) {
        requireFixtureOnlyProfileValue(System.getenv("SPRING_PROFILES_ACTIVE"), "SPRING_PROFILES_ACTIVE");
        requireFixtureOnlyProfileValue(System.getProperty(ACTIVE_PROFILES_PROPERTY), ACTIVE_PROFILES_PROPERTY);
        requireUnsetProfileInclude(System.getenv("SPRING_PROFILES_INCLUDE"), "SPRING_PROFILES_INCLUDE");
        requireUnsetProfileInclude(System.getProperty(INCLUDED_PROFILES_PROPERTY), INCLUDED_PROFILES_PROPERTY);
        if (args == null) return;
        for (String argument : args) {
            if (argument == null) continue;
            if (argument.startsWith("--" + ACTIVE_PROFILES_PROPERTY)) {
                int separator = argument.indexOf('=');
                if (separator < 0) {
                    throw new IllegalStateException("External Spring profile selection is forbidden");
                }
                requireFixtureOnlyProfileValue(argument.substring(separator + 1), ACTIVE_PROFILES_PROPERTY);
            } else if (argument.startsWith("--" + INCLUDED_PROFILES_PROPERTY)) {
                throw new IllegalStateException("External Spring profile inclusion is forbidden");
            }
        }
    }

    private static void requireFixtureOnlyProfileValue(String value, String source) {
        if (value == null || value.isBlank()) return;
        String[] profiles = java.util.Arrays.stream(value.split(","))
                .map(String::trim).filter(profile -> !profile.isEmpty()).toArray(String[]::new);
        if (profiles.length != 1 || !FIXTURE_PROFILE.equals(profiles[0])) {
            throw new IllegalStateException(source + " must select only the disposable fixture profile");
        }
    }

    private static void requireUnsetProfileInclude(String value, String source) {
        if (value != null && !value.isBlank()) {
            throw new IllegalStateException(source + " is forbidden for the disposable fixture");
        }
    }

    private static void requireOnlyFixtureProfile(Environment environment) {
        String[] active = environment.getActiveProfiles();
        if (active.length != 1 || !FIXTURE_PROFILE.equals(active[0])) {
            throw new IllegalStateException("Only the disposable archive-real-runtime profile may be active");
        }
    }

    private static void requireTrustedDatasourceConfiguration(Environment environment) {
        requireExactDatasourceProperty(environment, "spring.datasource.url", required("CYF_H02_MYSQL_URL"), false);
        requireExactDatasourceProperty(environment, "spring.datasource.username", mysqlUser(), false);
        requireExactDatasourceProperty(environment, "spring.datasource.password",
                required("CYF_H02_MYSQL_PASSWORD"), true);
        requireExactDatasourceProperty(environment, "spring.datasource.driver-class-name",
                "com.mysql.cj.jdbc.Driver", false);
    }

    private static void requireExactDatasourceProperty(
            Environment environment, String property, String trustedValue, boolean credential) {
        String effectiveValue = environment.getProperty(property);
        if (!trustedValue.equals(effectiveValue)) {
            throw new IllegalStateException(credential
                    ? "Effective fixture datasource credential does not match the preflight input"
                    : "Effective fixture datasource property does not match the preflight input: " + property);
        }
    }

    private static boolean causedByMysqlPreflightComplete(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof MysqlPreflightComplete) return true;
        }
        return false;
    }

    private static final class MysqlPreflightComplete extends RuntimeException {
        private MysqlPreflightComplete() {
            super("Fixture MySQL and effective datasource preflight completed before context refresh");
        }
    }

    private static void requireEnvironment() {
        for (String name : List.of(
                "CYF_FIXTURE_HOST", "CYF_FIXTURE_WEB_ORIGIN", "CYF_FIXTURE_READY_FILE", "CYF_FIXTURE_RESULT_FILE",
                "CYF_FIXTURE_ADMIN_TOKEN_FILE", "CYF_FIXTURE_ARTIFACT_ROOT",
                "CYF_FIXTURE_JWT_ISSUER", "CYF_FIXTURE_JWT_AUDIENCE",
                "CYF_FIXTURE_ACTOR", "CYF_FIXTURE_OWNER", "CYF_FIXTURE_CLIENT",
                "CYF_FIXTURE_AGENT_ID", "CYF_FIXTURE_API_KEY", "CYF_FIXTURE_API_KEY_ID",
                "CYF_REDIS_HOST", "CYF_REDIS_PORT", "CYF_REDIS_PASSWORD",
                "CYF_RABBIT_HOST", "CYF_RABBIT_PORT", "CYF_RABBIT_VHOST",
                "CYF_RABBIT_USERNAME", "CYF_RABBIT_PASSWORD")) {
            required(name);
        }
        try {
            verifyLoopbackOrPrivate(required("CYF_REDIS_HOST"));
        } catch (Exception invalid) {
            throw new IllegalStateException("CYF_REDIS_HOST must be loopback or an explicit private address", invalid);
        }
        int redisPort;
        try {
            redisPort = Integer.parseInt(required("CYF_REDIS_PORT"));
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("CYF_REDIS_PORT must be an integer", invalid);
        }
        if (redisPort < 1 || redisPort > 65_535) {
            throw new IllegalStateException("CYF_REDIS_PORT is outside the TCP port range");
        }
        int rabbitPort;
        try {
            rabbitPort = Integer.parseInt(required("CYF_RABBIT_PORT"));
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("CYF_RABBIT_PORT must be an integer", invalid);
        }
        if (rabbitPort < 1 || rabbitPort > 65_535) {
            throw new IllegalStateException("CYF_RABBIT_PORT is outside the TCP port range");
        }
        if ("/".equals(required("CYF_RABBIT_VHOST"))) {
            throw new IllegalStateException("dedicated Rabbit vhost is required");
        }
    }

    private static boolean mysqlPreflightOnly(String[] args) {
        if (args == null || args.length == 0) return false;
        long requests = java.util.Arrays.stream(args).filter(MYSQL_PREFLIGHT_ONLY::equals).count();
        if (requests > 1) {
            throw new IllegalStateException("MySQL preflight mode may be requested only once");
        }
        if (requests == 0) return false;
        for (String argument : args) {
            if (!MYSQL_PREFLIGHT_ONLY.equals(argument)
                    && (argument == null || !argument.startsWith("--spring.datasource.url="))) {
                throw new IllegalStateException(
                        "MySQL preflight mode accepts only an optional datasource URL override probe");
            }
        }
        return true;
    }

    private static void requireMysqlPreflightEnvironment() {
        if (!"true".equals(required("CYF_H02_MYSQL_ISOLATED"))) {
            throw new IllegalStateException("isolated MySQL opt-in required");
        }
        for (String name : List.of(
                "CYF_H02_MYSQL_URL", "CYF_H02_MYSQL_DATABASE_CONFIRM",
                "CYF_H02_MYSQL_USER", "CYF_H02_MYSQL_PASSWORD",
                "CYF_FIXTURE_MYSQL_SERVER_PORT", "CYF_FIXTURE_MYSQL_DATA_DIRECTORY")) {
            required(name);
        }
        mysqlEndpoint();
        mysqlUser();
        expectedMysqlServerPort();
        normalizeMysqlDataDirectory(required("CYF_FIXTURE_MYSQL_DATA_DIRECTORY"),
                "CYF_FIXTURE_MYSQL_DATA_DIRECTORY");
    }

    private static void preflightMysql() {
        MysqlEndpoint endpoint = mysqlEndpoint();
        int expectedServerPort = expectedMysqlServerPort();
        String expectedDataDirectory = normalizeMysqlDataDirectory(
                required("CYF_FIXTURE_MYSQL_DATA_DIRECTORY"), "CYF_FIXTURE_MYSQL_DATA_DIRECTORY");
        try (Connection connection = DriverManager.getConnection(
                endpoint.jdbcUrl(), mysqlUser(), required("CYF_H02_MYSQL_PASSWORD"));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DATABASE(), @@port, @@datadir")) {
            if (!rows.next()) throw new IllegalStateException("fixture MySQL preflight returned no server facts");
            String actualDatabase = rows.getString(1);
            int actualServerPort = rows.getInt(2);
            if (rows.wasNull()) throw new IllegalStateException("fixture MySQL server port is unavailable");
            String actualDataDirectory = normalizeMysqlDataDirectory(rows.getString(3), "MySQL @@datadir");
            if (rows.next()) throw new IllegalStateException("fixture MySQL preflight returned ambiguous server facts");
            if (!endpoint.database().equals(actualDatabase)) {
                throw new IllegalStateException("fixture datasource is not the confirmed isolated database");
            }
            if (expectedServerPort != actualServerPort) {
                throw new IllegalStateException("fixture MySQL server port does not match the isolated server");
            }
            if (!sameMysqlDataDirectory(expectedDataDirectory, actualDataDirectory)) {
                throw new IllegalStateException("fixture MySQL data directory does not match the isolated server");
            }
        } catch (SQLException failure) {
            String state = failure.getSQLState();
            throw new IllegalStateException("fixture MySQL preflight connection or SELECT failed"
                    + (state == null ? "" : " (SQLState " + state + ")"));
        }
    }

    private static MysqlEndpoint mysqlEndpoint() {
        String jdbcUrl = required("CYF_H02_MYSQL_URL");
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("CYF_H02_MYSQL_URL must use jdbc:mysql with one explicit endpoint");
        }
        URI uri;
        try {
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException invalid) {
            throw new IllegalStateException("CYF_H02_MYSQL_URL is invalid");
        }
        String authority = uri.getRawAuthority();
        String path = uri.getRawPath();
        if (!"mysql".equals(uri.getScheme()) || authority == null || authority.contains(",")
                || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65_535
                || path == null || !path.matches("/[A-Za-z0-9_]{1,64}")) {
            throw new IllegalStateException("CYF_H02_MYSQL_URL must name one explicit database without credentials or options");
        }
        verifyExplicitLoopbackOrPrivateAddress(uri.getHost(), "CYF_H02_MYSQL_URL");
        String database = path.substring(1);
        if (!database.equals(required("CYF_H02_MYSQL_DATABASE_CONFIRM"))) {
            throw new IllegalStateException("CYF_H02_MYSQL_URL database does not match its confirmation");
        }
        return new MysqlEndpoint(jdbcUrl, database);
    }

    private static String mysqlUser() {
        String user = required("CYF_H02_MYSQL_USER");
        if (!user.matches("[A-Za-z0-9_.-]{1,64}") || "root".equalsIgnoreCase(user)) {
            throw new IllegalStateException("CYF_H02_MYSQL_USER must be an explicit non-root fixture account");
        }
        return user;
    }

    private static int expectedMysqlServerPort() {
        int port;
        try {
            port = Integer.parseInt(required("CYF_FIXTURE_MYSQL_SERVER_PORT"));
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("CYF_FIXTURE_MYSQL_SERVER_PORT must be an integer");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalStateException("CYF_FIXTURE_MYSQL_SERVER_PORT is outside the TCP port range");
        }
        return port;
    }

    private static String normalizeMysqlDataDirectory(String value, String source) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException(source + " is required");
        }
        String normalized = value.replace('\\', '/');
        while (normalized.length() > 3 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("//") || normalized.chars().anyMatch(character -> character < 0x20 || character == 0x7f)
                || !(normalized.matches("[A-Za-z]:/[^/].*") || normalized.matches("/[^/].*"))) {
            throw new IllegalStateException(source + " must be one absolute data directory");
        }
        for (String segment : normalized.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalStateException(source + " cannot contain relative path segments");
            }
        }
        return normalized;
    }

    private static boolean sameMysqlDataDirectory(String expected, String actual) {
        boolean windowsPath = expected.matches("[A-Za-z]:/.*") || actual.matches("[A-Za-z]:/.*");
        return windowsPath ? expected.equalsIgnoreCase(actual) : expected.equals(actual);
    }

    private static void verifyExplicitLoopbackOrPrivateAddress(String host, String source) {
        if ("::1".equals(host)) return;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalStateException(source + " must use an explicit loopback or RFC1918 address");
        }
        int[] octets = new int[4];
        for (int index = 0; index < parts.length; index++) {
            if (!parts[index].matches("0|[1-9][0-9]{0,2}")) {
                throw new IllegalStateException(source + " must use an explicit loopback or RFC1918 address");
            }
            octets[index] = Integer.parseInt(parts[index]);
            if (octets[index] > 255) {
                throw new IllegalStateException(source + " must use an explicit loopback or RFC1918 address");
            }
        }
        boolean allowed = octets[0] == 127 || octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
        if (!allowed) {
            throw new IllegalStateException(source + " must use an explicit loopback or RFC1918 address");
        }
    }

    private record MysqlEndpoint(String jdbcUrl, String database) { }

    private static void verifyIsolatedDatabase(JdbcTemplate jdbc) {
        String expected = required("CYF_H02_MYSQL_DATABASE_CONFIRM");
        if (!expected.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalStateException("CYF_H02_MYSQL_DATABASE_CONFIRM is invalid");
        }
        String actual = jdbc.queryForObject("SELECT DATABASE()", String.class);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("fixture datasource is not the confirmed isolated database");
        }
    }

    private static void verifyLoopbackOrPrivate(String host) throws Exception {
        var address = java.net.InetAddress.getByName(host);
        if (address.isAnyLocalAddress()
                || !(address.isLoopbackAddress() || address.isSiteLocalAddress())) {
            throw new IllegalStateException("fixture dependency host must be loopback or explicit private address");
        }
    }
}
