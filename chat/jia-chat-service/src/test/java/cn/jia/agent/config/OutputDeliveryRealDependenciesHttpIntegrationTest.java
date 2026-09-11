package cn.jia.agent.config;

import cn.jia.agent.api.OutputDeliveryController;
import cn.jia.agent.api.OutputUploadController;
import cn.jia.agent.dao.AgentIdentityAliasDao;
import cn.jia.agent.dao.AgentIdentityRegistryDao;
import cn.jia.agent.dao.AgentPersonaBindingDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.dao.AgentTaskArtifactDao;
import cn.jia.agent.dao.AgentTaskEventDao;
import cn.jia.agent.dao.AgentTaskMemberDao;
import cn.jia.agent.dao.AgentTaskMetaDao;
import cn.jia.agent.dao.AgentTaskWorkItemDao;
import cn.jia.agent.dao.impl.AgentIdentityAliasDaoImpl;
import cn.jia.agent.dao.impl.AgentIdentityRegistryDaoImpl;
import cn.jia.agent.dao.impl.AgentPersonaBindingDaoImpl;
import cn.jia.agent.dao.impl.AgentRuntimeDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskArtifactDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskEventDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMemberDaoImpl;
import cn.jia.agent.dao.impl.AgentTaskMetaDaoImpl;
import cn.jia.agent.mapper.AgentIdentityAliasMapper;
import cn.jia.agent.mapper.AgentIdentityRegistryMapper;
import cn.jia.agent.mapper.AgentPersonaBindingMapper;
import cn.jia.agent.mapper.AgentRuntimeMapper;
import cn.jia.agent.mapper.AgentTaskArtifactMapper;
import cn.jia.agent.mapper.AgentTaskEventMapper;
import cn.jia.agent.mapper.AgentTaskMemberMapper;
import cn.jia.agent.mapper.AgentTaskMetaMapper;
import cn.jia.agent.mapper.AgentTaskOutputMapper;
import cn.jia.agent.output.OutputConstants;
import cn.jia.agent.output.OutputDeliveryService;
import cn.jia.agent.output.OutputMalwareScanner;
import cn.jia.agent.output.OutputObjectStorage;
import cn.jia.agent.output.OutputRunAuthorizationService;
import cn.jia.agent.output.OutputUploadService;
import cn.jia.agent.output.dao.OutputAccessTicketDao;
import cn.jia.agent.output.dao.OutputRunBindingDao;
import cn.jia.agent.output.dao.OutputSourceBindingDao;
import cn.jia.agent.output.dao.OutputUploadDao;
import cn.jia.agent.output.dao.impl.OutputAccessTicketDaoImpl;
import cn.jia.agent.output.dao.impl.OutputRunBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputSourceBindingDaoImpl;
import cn.jia.agent.output.dao.impl.OutputUploadDaoImpl;
import cn.jia.agent.output.mapper.OutputAccessTicketMapper;
import cn.jia.agent.output.mapper.OutputRunBindingMapper;
import cn.jia.agent.output.mapper.OutputSourceBindingMapper;
import cn.jia.agent.output.service.ClamAvOutputMalwareScanner;
import cn.jia.agent.output.service.OutputDeliveryServiceImpl;
import cn.jia.agent.output.service.OutputRunAuthorizationServiceImpl;
import cn.jia.agent.output.service.OutputSourceAuthorizerRegistry;
import cn.jia.agent.output.service.OutputUploadServiceImpl;
import cn.jia.agent.output.service.S3OutputObjectStorage;
import cn.jia.agent.output.service.TaskOutputSourceAuthorizer;
import cn.jia.agent.output.service.TaskOutputVersionProvider;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.AgentTaskCollaborationAccessService;
import cn.jia.agent.service.AgentTaskEventAfterCommitPublisher;
import cn.jia.agent.service.impl.AgentIdentityServiceImpl;
import cn.jia.agent.service.impl.AgentTaskCollaborationAccessServiceImpl;
import cn.jia.agent.service.impl.AgentTaskEventWriterImpl;
import cn.jia.chat.api.ConversationOutputController;
import cn.jia.chat.config.ChatOutputSchemaInitializer;
import cn.jia.chat.dao.ChatConversationDao;
import cn.jia.chat.dao.ChatOutputDao;
import cn.jia.chat.dao.impl.ChatConversationDaoImpl;
import cn.jia.chat.dao.impl.ChatOutputDaoImpl;
import cn.jia.chat.mapper.ChatConversationMapper;
import cn.jia.chat.output.ConversationOutputSourceAuthorizer;
import cn.jia.chat.output.ConversationOutputVersionProvider;
import cn.jia.oauth.config.ResourceServerConfig;
import cn.jia.user.security.AccountSecurityService;
import cn.jia.user.security.AccountSecuritySnapshot;
import cn.jia.user.security.AccountState;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real MySQL/MinIO/ClamAV proof of the OD03 HTTP composition with production ticket checks. */
@EnabledIfEnvironmentVariable(named = "OD02_MYSQL_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OD02_S3_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OD02_CLAM_HOST", matches = ".+")
class OutputDeliveryRealDependenciesHttpIntegrationTest {
    private static final byte[] BYTES = "hello world!\n".getBytes(StandardCharsets.UTF_8);
    private static final String HASH = "ecf701f727d9e2d77c4aa49ac6fbbcc997278aca010bddeeb961c10cf54d435a";
    private static final String TASK_RUN = "11111111111111111111111111111111";
    private static final String CHAT_RUN = "22222222222222222222222222222222";
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate admin;
    private JdbcTemplate jdbc;
    private String database;
    private String bucket;
    private S3Client s3;
    private S3OutputObjectStorage storage;
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private TransactionTemplate transaction;
    private OutputRunAuthorizationService authorization;

    @BeforeEach
    void setUp() throws Exception {
        String base = env("OD02_MYSQL_URL");
        admin = new JdbcTemplate(dataSource(base));
        database = "cyf_od03_http_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE `" + database
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin");
        DataSource dataSource = dataSource(url(base, database));
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(tx);
        createBusinessAndIdentityTables();
        new OutputDeliverySchemaInitializer(jdbc).afterPropertiesSet();
        new OutputObjectSchemaInitializer(jdbc).afterPropertiesSet();
        new ChatOutputSchemaInitializer(jdbc).run(null);
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE agent_runtime SET output_capabilities_json=CAST(? AS JSON),
                  output_capabilities_runtime_id='runtime-1',output_capabilities_updated_at=?
                  WHERE agent_id='agent-1'
                """, "[\"output.http.v1\",\"task.owner-share.v1\"]", now);
        seedRun("TASK", "task-1", TASK_RUN, now);
        seedRun("CONVERSATION", "101", CHAT_RUN, now);

        SqlSessionTemplate sql = new SqlSessionTemplate(factory(dataSource));
        AgentTaskMetaDao taskDao = wire(new AgentTaskMetaDaoImpl(),
                sql.getMapper(AgentTaskMetaMapper.class));
        AgentTaskMemberDao memberDao = new AgentTaskMemberDaoImpl(sql.getMapper(AgentTaskMemberMapper.class));
        AgentTaskArtifactDao artifactDao = new AgentTaskArtifactDaoImpl(
                sql.getMapper(AgentTaskArtifactMapper.class));
        AgentTaskEventDao eventDao = wire(new AgentTaskEventDaoImpl(),
                sql.getMapper(AgentTaskEventMapper.class));
        AgentTaskCollaborationAccessService taskAccess =
                new AgentTaskCollaborationAccessServiceImpl(taskDao, memberDao);
        ChatConversationDao conversationDao = wire(new ChatConversationDaoImpl(),
                sql.getMapper(ChatConversationMapper.class));
        OutputSourceBindingDao sourceDao = wire(new OutputSourceBindingDaoImpl(),
                sql.getMapper(OutputSourceBindingMapper.class));
        OutputRunBindingDao runDao = wire(new OutputRunBindingDaoImpl(),
                sql.getMapper(OutputRunBindingMapper.class));
        OutputAccessTicketDao ticketDao = wire(new OutputAccessTicketDaoImpl(),
                sql.getMapper(OutputAccessTicketMapper.class));
        AgentRuntimeDao runtimeDao = wire(new AgentRuntimeDaoImpl(),
                sql.getMapper(AgentRuntimeMapper.class));
        AgentIdentityRegistryDao registryDao = wire(new AgentIdentityRegistryDaoImpl(),
                sql.getMapper(AgentIdentityRegistryMapper.class));
        AgentIdentityAliasDao aliasDao = wire(new AgentIdentityAliasDaoImpl(),
                sql.getMapper(AgentIdentityAliasMapper.class));
        AgentPersonaBindingDao bindingDao = wire(new AgentPersonaBindingDaoImpl(),
                sql.getMapper(AgentPersonaBindingMapper.class));
        AgentIdentityService identityService = new AgentIdentityServiceImpl(
                registryDao, aliasDao, bindingDao);
        OutputRunAuthorizationServiceImpl authorizationTarget = new OutputRunAuthorizationServiceImpl(
                new OutputSourceAuthorizerRegistry(List.of(
                        new TaskOutputSourceAuthorizer(taskAccess),
                        new ConversationOutputSourceAuthorizer(conversationDao, taskAccess))),
                sourceDao, runDao, ticketDao, runtimeDao, identityService, true);
        ProxyFactory authorizationProxy = new ProxyFactory(authorizationTarget);
        authorizationProxy.setInterfaces(OutputRunAuthorizationService.class);
        authorizationProxy.addAdvice(new TransactionInterceptor(tx,
                new AnnotationTransactionAttributeSource()));
        authorization = (OutputRunAuthorizationService) authorizationProxy.getProxy();

        String endpoint = env("OD02_S3_ENDPOINT");
        String access = env("OD02_S3_ACCESS_KEY");
        String secret = env("OD02_S3_SECRET_KEY");
        bucket = "od03-http-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        s3 = S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(access, secret)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        storage = new S3OutputObjectStorage(endpoint, access, secret, bucket);
        OutputDeliveryProperties properties = new OutputDeliveryProperties(true, endpoint, access,
                secret, bucket, env("OD02_CLAM_HOST"), Integer.parseInt(env("OD02_CLAM_PORT")),
                10, 50L * 1024 * 1024, 90L * 1024 * 1024, null, false,
                "od03-real-http-cursor-signing-key");
        OutputUploadDao outputDao = new OutputUploadDaoImpl(jdbc);
        OutputMalwareScanner scanner = new ClamAvOutputMalwareScanner(
                env("OD02_CLAM_HOST"), Integer.parseInt(env("OD02_CLAM_PORT")));
        OutputUploadService uploadService = new OutputUploadServiceImpl(
                authorization, outputDao, storage, scanner, tx, properties);
        var eventWriter = new AgentTaskEventWriterImpl(eventDao, tx,
                mock(AgentTaskEventAfterCommitPublisher.class));
        var taskProvider = new TaskOutputVersionProvider(taskDao,
                mock(AgentTaskWorkItemDao.class), artifactDao,
                sql.getMapper(AgentTaskOutputMapper.class), eventWriter);
        ChatOutputDao chatOutputDao = new ChatOutputDaoImpl(jdbc);
        var chatProvider = new ConversationOutputVersionProvider(conversationDao, chatOutputDao);
        OutputDeliveryService deliveryService = new OutputDeliveryServiceImpl(
                authorization, outputDao, storage, tx, properties,
                List.of(taskProvider, chatProvider));

        context = new AnnotationConfigWebApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource("od03", Map.of(
                        "agent.output-delivery.enabled", true,
                        "oauth.resource.uris[0]", "/agent/output-capabilities",
                        "oauth.resource.uris[1]", "/agent/tasks/**",
                        "oauth.resource.uris[2]", "/chat/conversations/**")));
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("outputRunAuthorizationService", authorization);
            beanFactory.registerSingleton("outputUploadController",
                    new OutputUploadController(uploadService));
            beanFactory.registerSingleton("outputDeliveryController",
                    new OutputDeliveryController(deliveryService));
            beanFactory.registerSingleton("conversationOutputController",
                    new ConversationOutputController(deliveryService));
        });
        context.register(TestWebConfiguration.class);
        context.setServletContext(new org.springframework.mock.web.MockServletContext());
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(FilterChainProxy.class)).build();
    }

    @AfterEach
    void tearDown() {
        if (context != null) context.close();
        if (storage != null) storage.close();
        if (s3 != null && bucket != null) {
            try {
                for (var object : s3.listObjectsV2(
                        ListObjectsV2Request.builder().bucket(bucket).build()).contents()) {
                    s3.deleteObject(DeleteObjectRequest.builder()
                            .bucket(bucket).key(object.key()).build());
                }
                s3.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
            } finally {
                s3.close();
            }
        }
        if (admin != null && database != null) {
            assertEquals(true, database.matches("cyf_od03_http_[0-9a-f]{32}"));
            admin.execute("DROP DATABASE IF EXISTS `" + database + "`");
        }
    }

    @Test
    void taskAndConversationTravelFromHttpUploadThroughProductionTicketToUserDownload()
            throws Exception {
        String taskToken = ticket(TASK_RUN, "task-ticket");
        String chatToken = ticket(CHAT_RUN, "chat-ticket");
        assertSourceRoundTrip(taskToken, "TASK", "task-1", "task-artifact", "task.txt", true);
        assertSourceRoundTrip(chatToken, "CONVERSATION", "101", "chat-output", "chat.txt", false);
        mvc.perform(get("/agent/tasks/task-1/artifacts"))
                .andExpect(status().isUnauthorized());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_access_ticket", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object WHERE lifecycle_status='READY'", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM output_object_reference WHERE reference_kind='OWNER_SHARE'",
                Integer.class));
    }

    private void assertSourceRoundTrip(String token, String sourceType, String sourceId,
            String outputId, String fileName, boolean task) throws Exception {
        String prefix = task ? "/agent/tasks/task-1/artifacts"
                : "/chat/conversations/101/outputs";
        String runId = task ? TASK_RUN : CHAT_RUN;
        MvcResult created = mvc.perform(post("/agent/output-uploads")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", outputId + "-create-key")
                        .contentType("application/json")
                        .content("{\"runId\":\"" + runId + "\",\"source\":{\"type\":\""
                                + sourceType + "\",\"id\":\"" + sourceId
                                + "\"},\"name\":\"" + fileName
                                + "\",\"size\":\"13\",\"sha256\":\"" + HASH
                                + "\",\"mime\":\"text/plain\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("CREATED"))
                .andReturn();
        JsonNode createdJson = json.readTree(created.getResponse().getContentAsByteArray());
        String uploadId = createdJson.get("data").get("uploadId").asText();
        String objectId = createdJson.get("data").get("objectId").asText();
        mvc.perform(put("/agent/output-uploads/{id}/content", uploadId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/octet-stream").content(BYTES))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UPLOADING"));
        mvc.perform(post("/agent/output-uploads/{id}/complete", uploadId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", outputId + "-complete-key"))
                .andExpect(status().isAccepted());
        mvc.perform(get("/agent/output-uploads/{id}", uploadId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("READY"));

        String publication = task
                ? "{\"runId\":\"" + runId
                        + "\",\"expectedPreviousVersion\":\"0\",\"title\":\"task result\","
                        + "\"artifactType\":\"document\",\"objectId\":\"" + objectId
                        + "\",\"artifactId\":\"" + outputId
                        + "\",\"artifactVersion\":\"1\",\"visibility\":\"task_members\","
                        + "\"publishToOwner\":true}"
                : "{\"runId\":\"" + runId
                        + "\",\"expectedPreviousVersion\":\"0\",\"title\":\"chat result\","
                        + "\"artifactType\":\"document\",\"objectId\":\"" + objectId
                        + "\",\"outputId\":\"" + outputId + "\",\"version\":\"1\"}";
        mvc.perform(post(prefix).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", outputId + "-publish-key")
                        .contentType("application/json").content(publication))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.sha256").value(HASH));
        mvc.perform(get(prefix).header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].outputId").value(outputId));
        mvc.perform(get(prefix + "/" + outputId + "/versions/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.item.sha256").value(HASH));
        MvcResult pending = mvc.perform(get(prefix + "/" + outputId + "/versions/1/download")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userJwt()))
                .andExpect(request().asyncStarted()).andReturn();
        MvcResult downloaded = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andReturn();
        assertArrayEquals(BYTES, downloaded.getResponse().getContentAsByteArray());
    }

    private String ticket(String runId, String messageId) {
        return transaction.execute(status -> authorization.issueTicket(
                "owner", "client", "agent-1", "runtime-1", messageId, runId).token());
    }

    private String userJwt() throws Exception {
        @SuppressWarnings("unchecked")
        JWKSource<SecurityContext> source = context.getBean(JWKSource.class);
        RSAKey key = (RSAKey) source.get(new JWKSelector(
                new JWKMatcher.Builder().privateOnly(true).build()), null).getFirst();
        long now = System.currentTimeMillis();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(key.getKeyID()).build(), new JWTClaimsSet.Builder()
                .subject("user-1").claim("client_id", "client").claim("token_kind", "user")
                .claim("uid", "1").claim("username", "owner").claim("jiacn", "owner")
                .claim("auth_epoch", 0L)
                .issueTime(new Date(now)).expirationTime(new Date(now + 60_000)).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private void createBusinessAndIdentityTables() {
        jdbc.execute("""
                CREATE TABLE agent_runtime (
                  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,agent_id VARCHAR(100) NOT NULL,
                  name VARCHAR(100) NOT NULL,avatar VARCHAR(500),owner_jiacn VARCHAR(50),
                  persona_code VARCHAR(50),persona_name VARCHAR(50),binding_id BIGINT,
                  abilities JSON,endpoint VARCHAR(500),token_hash VARCHAR(200),status VARCHAR(20) NOT NULL,
                  current_task_id VARCHAR(100),current_task_title VARCHAR(200),last_seen_at BIGINT,
                  error_message VARCHAR(1000),create_time BIGINT,update_time BIGINT,
                  tenant_id VARCHAR(50),client_id VARCHAR(50),UNIQUE KEY uk_runtime_agent(agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_persona_binding (
                  id BIGINT NOT NULL AUTO_INCREMENT,jiacn VARCHAR(50) NOT NULL,
                  persona_code VARCHAR(50) NOT NULL,agent_id VARCHAR(100) NOT NULL,bound_at BIGINT NOT NULL,
                  status INT NOT NULL,create_time BIGINT,update_time BIGINT,tenant_id VARCHAR(50),
                  client_id VARCHAR(50),PRIMARY KEY(id),UNIQUE KEY uk_binding_agent(agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_registry (
                  id BIGINT NOT NULL AUTO_INCREMENT,canonical_agent_id VARCHAR(100) NOT NULL,
                  canonical_type VARCHAR(32) NOT NULL,lifecycle_status VARCHAR(20) NOT NULL,
                  client_id VARCHAR(50),owner_jiacn VARCHAR(50),tenant_id VARCHAR(50),binding_id BIGINT,
                  provisioned_at BIGINT,activated_at BIGINT,suspended_at BIGINT,retired_at BIGINT,
                  audit_reason VARCHAR(1000) NOT NULL,create_time BIGINT,update_time BIGINT,
                  PRIMARY KEY(id),UNIQUE KEY uk_identity_agent(canonical_agent_id),
                  UNIQUE KEY uk_identity_binding(binding_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_identity_alias (
                  id BIGINT NOT NULL AUTO_INCREMENT,registry_id BIGINT NOT NULL,
                  canonical_agent_id VARCHAR(100) NOT NULL,alias_type VARCHAR(32) NOT NULL,
                  alias_value VARCHAR(100) NOT NULL,alias_status VARCHAR(20) NOT NULL,
                  valid_from BIGINT NOT NULL,valid_to BIGINT,client_id VARCHAR(50) NOT NULL,
                  owner_jiacn VARCHAR(50) NOT NULL,tenant_id VARCHAR(50) NOT NULL,
                  audit_reason VARCHAR(1000) NOT NULL,create_time BIGINT,update_time BIGINT,PRIMARY KEY(id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_meta (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
                  reward_status VARCHAR(20),assigned_agent_id VARCHAR(100),required_abilities TEXT,
                  reward INT,assigned_at BIGINT,started_at BIGINT,completed_at BIGINT,
                  failure_reason VARCHAR(500),collaboration_mode VARCHAR(20),risk_level VARCHAR(20),
                  max_agents INT,coordinator_agent_id VARCHAR(100),review_required BOOLEAN,
                  task_version BIGINT,current_event_version BIGINT,tenant_id VARCHAR(50) NOT NULL,
                  client_id VARCHAR(50) NOT NULL,create_time BIGINT,update_time BIGINT,
                  UNIQUE KEY uk_task(tenant_id,client_id,task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_member (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100) NOT NULL,
                  agent_id VARCHAR(100) NOT NULL,member_role VARCHAR(20) NOT NULL,
                  member_status VARCHAR(20) NOT NULL,assignment_source VARCHAR(20) NOT NULL,
                  joined_at BIGINT,accepted_at BIGINT,started_at BIGINT,completed_at BIGINT,
                  last_heartbeat_at BIGINT,failure_reason VARCHAR(1000),version BIGINT NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT,update_time BIGINT,
                  UNIQUE KEY uk_member(tenant_id,client_id,task_id,agent_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_artifact (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,artifact_id VARCHAR(100) NOT NULL,
                  task_id VARCHAR(100) NOT NULL,work_item_id VARCHAR(100),
                  producer_agent_id VARCHAR(100) NOT NULL,artifact_type VARCHAR(30) NOT NULL,
                  title VARCHAR(255) NOT NULL,content MEDIUMTEXT,storage_uri VARCHAR(1000),
                  object_id VARBINARY(100),run_id VARBINARY(100),file_name VARCHAR(255),
                  content_byte_length BIGINT,mime_type VARCHAR(100),owner_shared_at BIGINT,
                  retain_until BIGINT,content_hash VARCHAR(128),artifact_version INT NOT NULL,
                  visibility VARCHAR(20),metadata_json TEXT,created_at BIGINT NOT NULL,
                  tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT,update_time BIGINT,
                  UNIQUE KEY uk_artifact_version(tenant_id,client_id,artifact_id,artifact_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE agent_task_event (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,task_id VARCHAR(100),event_version BIGINT,
                  event_id VARCHAR(100),event_type VARCHAR(50),actor_type VARCHAR(20),actor_id VARCHAR(100),
                  aggregate_type VARCHAR(30),aggregate_id VARCHAR(100),event_json TEXT,occurred_at BIGINT,
                  tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,
                  UNIQUE KEY uk_event(tenant_id,client_id,event_id),
                  UNIQUE KEY uk_version(tenant_id,client_id,task_id,event_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        jdbc.execute("""
                CREATE TABLE chat_conversation (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,title VARCHAR(200),jiacn VARCHAR(50),
                  conversation_type VARCHAR(30),conversation_scope_type VARCHAR(30),
                  conversation_scope_key VARCHAR(200),task_id VARCHAR(100),target_agent_id VARCHAR(100),
                  status INT,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,
                  create_time BIGINT,update_time BIGINT
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
                """);
        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO agent_task_meta(task_id,reward_status,collaboration_mode,risk_level,
                  max_agents,review_required,task_version,current_event_version,tenant_id,client_id,
                  create_time,update_time) VALUES ('task-1','running','single','low',1,0,0,0,
                  'owner','client',?,?)
                """, now, now);
        jdbc.update("""
                INSERT INTO agent_task_member(task_id,agent_id,member_role,member_status,
                  assignment_source,joined_at,accepted_at,version,tenant_id,client_id,create_time,update_time)
                  VALUES ('task-1','agent-1','worker','accepted','manual',?,?,0,
                  'owner','client',?,?)
                """, now, now, now, now);
        jdbc.update("""
                INSERT INTO chat_conversation(id,title,jiacn,conversation_type,target_agent_id,status,
                  tenant_id,client_id,create_time,update_time)
                  VALUES (101,'owned','owner','normal','agent-1',1,'owner','client',?,?)
                """, now, now);
        jdbc.update("""
                INSERT INTO agent_runtime(agent_id,name,owner_jiacn,binding_id,abilities,endpoint,
                  token_hash,status,last_seen_at,create_time,update_time,tenant_id,client_id)
                  VALUES ('agent-1','Agent 1','owner',7,'[]','wss://agent','registration','online',
                  ?,?,?, 'owner','client')
                """, now, now, now);
        jdbc.update("""
                INSERT INTO agent_persona_binding(id,jiacn,persona_code,agent_id,bound_at,status,
                  create_time,update_time,tenant_id,client_id)
                  VALUES (7,'owner','test','agent-1',?,1,?,?,'owner','client')
                """, now, now, now);
        jdbc.update("""
                INSERT INTO agent_identity_registry(id,canonical_agent_id,canonical_type,
                  lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id,provisioned_at,
                  activated_at,audit_reason,create_time,update_time)
                  VALUES (7,'agent-1','LEGACY_CANONICAL','ACTIVE','client','owner','owner',7,
                  ?,?,'OD03 real HTTP integration',?,?)
                """, now, now, now, now);
    }

    private void seedRun(String sourceType, String sourceId, String runId, long now) {
        jdbc.update("""
                INSERT INTO output_source_binding(tenant_id,client_id,source_type,source_id,
                  owner_jiacn,ownership_state,created_at,updated_at,row_version)
                  VALUES ('owner','client',?,?,'owner','ACTIVE',?,?,0)
                """, sourceType, sourceId, now, now);
        jdbc.update("""
                INSERT INTO output_run_binding(tenant_id,client_id,run_id,source_type,source_id,
                  producer_agent_id,binding_id,original_runtime_id,origin_type,origin_id,state,
                  policy_version,recovery_until,max_bytes,max_files,work_item_id,created_at,updated_at,row_version)
                  VALUES ('owner','client',?,?,?,'agent-1','7','runtime-1','COMMAND',?,'ACTIVE',0,
                  ?,209715200,100,NULL,?,?,0)
                """, runId, sourceType, sourceId, "command-" + sourceType.toLowerCase(),
                now + OutputConstants.RUN_RECOVERY_MILLIS, now, now);
    }

    private SqlSessionFactory factory(DataSource source) throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(AgentTaskMetaMapper.class, AgentTaskMemberMapper.class,
                AgentTaskArtifactMapper.class, AgentTaskOutputMapper.class, AgentTaskEventMapper.class,
                ChatConversationMapper.class, OutputSourceBindingMapper.class,
                OutputRunBindingMapper.class, OutputAccessTicketMapper.class,
                AgentRuntimeMapper.class, AgentIdentityRegistryMapper.class,
                AgentIdentityAliasMapper.class, AgentPersonaBindingMapper.class)) {
            configuration.addMapper(mapper);
        }
        GlobalConfig global = new GlobalConfig();
        global.setIdentifierGenerator(new DefaultIdentifierGenerator());
        global.setBanner(false);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        return factory.getObject();
    }

    private <T> T wire(T target, Object mapper) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            for (String name : List.of("baseMapper", "mapper")) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    field.set(target, mapper);
                    return target;
                } catch (NoSuchFieldException ignored) {
                    // Continue with the other supported mapper field name.
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchFieldException("baseMapper");
    }

    private DriverManagerDataSource dataSource(String jdbcUrl) {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(jdbcUrl);
        source.setUsername(env("OD02_MYSQL_USER"));
        source.setPassword(env("OD02_MYSQL_PASSWORD"));
        return source;
    }

    private static String url(String base, String targetDatabase) {
        int query = base.indexOf('?');
        String host = query < 0 ? base : base.substring(0, query);
        String tail = query < 0 ? "" : base.substring(query);
        int slash = host.indexOf('/', "jdbc:mysql://".length());
        return (slash < 0 ? host + "/" : host.substring(0, slash + 1)) + targetDatabase + tail;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null) throw new IllegalStateException(name + " missing");
        return value;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @EnableWebSecurity
    @EnableWebMvc
    @Import({OutputUploadSecurityConfiguration.class, ResourceServerConfig.class})
    static class TestWebConfiguration {
        @Bean
        AccountSecurityService accountSecurityService() {
            return new AccountSecurityService() {
                @Override public Optional<AccountSecuritySnapshot> findByUserId(long userId) {
                    return userId == 1L ? Optional.of(new AccountSecuritySnapshot(
                            1L, "owner", AccountState.ACTIVE, 0L)) : Optional.empty();
                }
                @Override public Optional<AccountSecuritySnapshot> findUniqueByExactJiacn(String jiacn) {
                    return "owner".equals(jiacn) ? Optional.of(new AccountSecuritySnapshot(
                            1L, "owner", AccountState.ACTIVE, 0L)) : Optional.empty();
                }
                @Override public int revokeAllSessions(long userId, long expectedEpoch) { return 0; }
            };
        }
    }
}
