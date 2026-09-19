package cn.jia.agent.api;

import cn.jia.agent.dao.PersonalWorkspaceDao;
import cn.jia.agent.dao.impl.PersonalWorkspaceDaoImpl;
import cn.jia.agent.mapper.PersonalWorkspaceFileMapper;
import cn.jia.agent.mapper.PersonalWorkspaceOperationMapper;
import cn.jia.agent.mapper.PersonalWorkspaceVersionMapper;
import cn.jia.agent.service.PersonalWorkspaceService;
import cn.jia.agent.service.PersonalWorkspaceStorage;
import cn.jia.agent.service.impl.FileSystemPersonalWorkspaceStorage;
import cn.jia.agent.service.impl.PersonalWorkspaceServiceImpl;
import cn.jia.agent.service.impl.PersonalWorkspaceWriteService;
import cn.jia.core.util.JsonUtil;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import tools.jackson.databind.JsonNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc HTTP-boundary integration evidence backed by the real service/write-service,
 * Spring transaction proxy, MyBatis DAO, H2 database, and private filesystem storage.
 *
 * <p>H2's MySQL mode and the compatible DDL below deliberately exercise persistence and
 * byte-exact predicates, but they are not MySQL or production-migration evidence. MockMvc
 * likewise does not claim a socket/network E2E.</p>
 */
class PersonalWorkspaceHttpPersistenceIntegrationTest {
    private static final String OWNER = "Owner-A";
    private static final String CLIENT = "Client-A";
    private static final String MIME = "text/plain";
    private static final byte[] VERSION_ONE = "immutable version one\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VERSION_TWO = "immutable version two\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporaryDirectory;

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private PersonalWorkspaceDao dao;
    private CountingStorage storage;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:pws_http_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE;LOCK_TIMEOUT=10000");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        createCompatibleTables();

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(PersonalWorkspaceFileMapper.class);
        configuration.addMapper(PersonalWorkspaceVersionMapper.class);
        configuration.addMapper(PersonalWorkspaceOperationMapper.class);
        GlobalConfig globalConfig = new GlobalConfig();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setGlobalConfig(globalConfig);
        SqlSessionFactory sessionFactory = Objects.requireNonNull(factoryBean.getObject());
        SqlSessionTemplate session = new SqlSessionTemplate(sessionFactory);
        dao = new PersonalWorkspaceDaoImpl(
                session.getMapper(PersonalWorkspaceFileMapper.class),
                session.getMapper(PersonalWorkspaceVersionMapper.class),
                session.getMapper(PersonalWorkspaceOperationMapper.class));

        storage = newStorage();
        mvc = controller(dataSource);
    }

    @Test
    void multipartReplayConflictAppendAndReconstructionPreserveFixedVersionsAndSafeDownloads()
            throws Exception {
        MvcResult created = upload("/agent/personal-workspace/files", "create-key", VERSION_ONE, null)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.operation.state").value("COMMITTED"))
                .andExpect(jsonPath("$.file.latestVersion").value(1))
                .andExpect(jsonPath("$.version.version").value(1))
                .andExpect(jsonPath("$.version.originalFilename").value("report:Q1.txt"))
                .andExpect(jsonPath("$.version.sha256").value(sha256(VERSION_ONE)))
                .andExpect(jsonPath("$.version.storageUri").doesNotExist())
                .andExpect(jsonPath("$.storageUri").doesNotExist())
                .andReturn();
        JsonNode first = JsonUtil.getMapper().readTree(created.getResponse().getContentAsByteArray());
        String fileId = first.at("/file/fileId").asText();
        String operationId = first.at("/operation/operationId").asText();
        assertFalse(created.getResponse().getContentAsString().contains(temporaryDirectory.toString()));
        assertEquals(1, storage.storeCalls());

        MvcResult replayed = upload("/agent/personal-workspace/files", "create-key", VERSION_ONE, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.operation.operationId").value(operationId))
                .andExpect(jsonPath("$.file.fileId").value(fileId))
                .andExpect(jsonPath("$.version.version").value(1))
                .andReturn();
        assertEquals(first, JsonUtil.getMapper().readTree(replayed.getResponse().getContentAsByteArray()));
        assertEquals(1, storage.storeCalls(), "an idempotent replay must not write storage again");
        assertCounts(1, 1, 1);

        upload("/agent/personal-workspace/files", "create-key",
                "different request".getBytes(StandardCharsets.UTF_8), null)
                .andExpect(status().isConflict())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("report:Q1.txt"))));
        assertEquals(1, storage.storeCalls(), "a conflicting key must be rejected before storage");
        assertCounts(1, 1, 1);

        upload("/agent/personal-workspace/files/" + fileId + "/versions",
                "append-key", VERSION_TWO, "1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.file.fileId").value(fileId))
                .andExpect(jsonPath("$.file.latestVersion").value(2))
                .andExpect(jsonPath("$.version.version").value(2))
                .andExpect(jsonPath("$.version.sha256").value(sha256(VERSION_TWO)))
                .andExpect(jsonPath("$.version.storageUri").doesNotExist());
        assertEquals(2, storage.storeCalls());
        assertCounts(1, 2, 2);

        assertSafeDownload(mvc, fileId, 1, VERSION_ONE);
        assertSafeDownload(mvc, fileId, 2, VERSION_TWO);

        String firstPersistedHash = jdbc.queryForObject(
                "SELECT content_hash FROM agent_personal_workspace_file_version WHERE file_id=? AND version=1",
                String.class, fileId);
        String secondPersistedHash = jdbc.queryForObject(
                "SELECT content_hash FROM agent_personal_workspace_file_version WHERE file_id=? AND version=2",
                String.class, fileId);
        assertEquals(sha256(VERSION_ONE), firstPersistedHash);
        assertEquals(sha256(VERSION_TWO), secondPersistedHash);
        assertNotEquals(firstPersistedHash, secondPersistedHash);

        // Rebuild storage, service and controller while retaining only durable H2 metadata/filesystem bytes.
        storage = newStorage();
        MockMvc reconstructed = controller(dataSource);
        assertSafeDownload(reconstructed, fileId, 1, VERSION_ONE);
        assertSafeDownload(reconstructed, fileId, 2, VERSION_TWO);
    }

    @Test
    void foreignOwnerAndClientPreviewFailBeforeAnyFilesystemReadEvenUnderCiColumns()
            throws Exception {
        MvcResult created = upload("/agent/personal-workspace/files", "isolation-key", VERSION_ONE, null)
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = JsonUtil.getMapper().readTree(created.getResponse().getContentAsByteArray())
                .at("/file/fileId").asText();
        String persistedStorageUri = jdbc.queryForObject(
                "SELECT storage_uri FROM agent_personal_workspace_file_version WHERE file_id=? AND version=1",
                String.class, fileId);
        assertTrue(Objects.requireNonNull(persistedStorageUri).startsWith("cyf-personal-workspace://"));
        storage.resetReads();

        assertOpaqueMissingPreview(jwt("owner-a", CLIENT), fileId, persistedStorageUri);
        assertEquals(0, storage.readCalls(),
                "case-changed owner must fail at exact DAO scope before storage");

        assertOpaqueMissingPreview(jwt(OWNER, "client-a"), fileId, persistedStorageUri);
        assertEquals(0, storage.readCalls(),
                "case-changed client must fail at exact DAO scope before storage");

        mvc.perform(get("/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        fileId, 1).principal(jwt(OWNER, CLIENT)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.state").value("READY"))
                .andExpect(jsonPath("$.parts[0].partId").value("content"))
                .andExpect(jsonPath("$.storageUri").doesNotExist());
        assertEquals(1, storage.readCalls(), "authorized preview reads the immutable object once");
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            String path, String key, byte[] bytes, String expectedPreviousVersion) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report:Q1.txt", MIME, bytes);
        var request = multipart(path)
                .file(file)
                .param("displayName", "Quarterly report")
                .header("Idempotency-Key", key)
                .principal(jwt(OWNER, CLIENT));
        if (expectedPreviousVersion != null) {
            request.param("expectedPreviousVersion", expectedPreviousVersion);
        }
        return mvc.perform(request);
    }

    private void assertSafeDownload(MockMvc target, String fileId, int version, byte[] expected)
            throws Exception {
        MvcResult result = target.perform(get(
                        "/agent/personal-workspace/files/{fileId}/versions/{version}/content",
                        fileId, version).principal(jwt(OWNER, CLIENT)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, expected.length))
                .andExpect(content().contentType(MIME))
                .andReturn();
        assertArrayEquals(expected, result.getResponse().getContentAsByteArray());
        assertEquals(sha256(expected), sha256(result.getResponse().getContentAsByteArray()));
        String disposition = Objects.requireNonNull(
                result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertTrue(disposition.startsWith("attachment;"));
        assertTrue(disposition.contains("report_Q1.txt"));
        assertFalse(disposition.contains("report:Q1.txt"));
        assertFalse(disposition.contains("\r"));
        assertFalse(disposition.contains("\n"));
        assertFalse(disposition.contains(temporaryDirectory.toString()));
        assertFalse(disposition.contains("cyf-personal-workspace"));
    }

    private void assertOpaqueMissingPreview(
            JwtAuthenticationToken authentication, String fileId, String storageUri) throws Exception {
        MvcResult result = mvc.perform(get(
                        "/agent/personal-workspace/files/{fileId}/versions/{version}/preview",
                        fileId, 1).principal(authentication))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-store"))
                .andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("report:Q1.txt"));
        assertFalse(body.contains(sha256(VERSION_ONE)));
        assertFalse(body.contains(storageUri));
        assertFalse(body.contains(temporaryDirectory.toString()));
    }

    private CountingStorage newStorage() {
        return new CountingStorage(new FileSystemPersonalWorkspaceStorage(
                temporaryDirectory.resolve("private-workspace").toAbsolutePath(),
                1024 * 1024,
                Set.of(MIME)));
    }

    private MockMvc controller(DataSource dataSource) {
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionInterceptor transactions = new TransactionInterceptor();
        transactions.setTransactionManager(transactionManager);
        transactions.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        PersonalWorkspaceWriteService rawWrites = new PersonalWorkspaceWriteService(dao);
        ProxyFactory proxyFactory = new ProxyFactory(rawWrites);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(transactions);
        PersonalWorkspaceWriteService transactionalWrites =
                (PersonalWorkspaceWriteService) proxyFactory.getProxy();
        PersonalWorkspaceService service = new PersonalWorkspaceServiceImpl(
                dao, storage, transactionalWrites);
        return MockMvcBuilders.standaloneSetup(new PersonalWorkspaceController(service)).build();
    }

    private void assertCounts(int files, int versions, int operations) {
        assertEquals(files, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_file", Integer.class));
        assertEquals(versions, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_file_version", Integer.class));
        assertEquals(operations, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_personal_workspace_operation", Integer.class));
    }

    private static JwtAuthenticationToken jwt(String owner, String client) {
        Jwt token = Jwt.withTokenValue("fixture")
                .header("alg", "none")
                .claim("jiacn", owner)
                .claim("client_id", client)
                .build();
        return new JwtAuthenticationToken(token, List.of());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * H2-compatible projection of db/agent-personal-workspace-v1.sql. It keeps every
     * production column plus behavioral checks/uniques. VARCHAR_IGNORECASE makes the
     * mapper's CAST/OCTET_LENGTH exact predicates observable under an adversarial CI type.
     */
    private void createCompatibleTables() {
        jdbc.execute("CREATE TABLE agent_personal_workspace_file ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "file_id VARCHAR_IGNORECASE(100) NOT NULL,"
                + "owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,"
                + "source_kind VARCHAR(24) NOT NULL,"
                + "origin_kind VARCHAR(24) DEFAULT 'USER_UPLOAD' NOT NULL,"
                + "display_name VARCHAR(255) NOT NULL,"
                + "media_family VARCHAR(32) NOT NULL,"
                + "state VARCHAR(24) NOT NULL,"
                + "metadata_revision BIGINT NOT NULL,latest_version INT NOT NULL,created_at BIGINT NOT NULL,"
                + "tenant_id VARCHAR_IGNORECASE(50) NOT NULL,client_id VARCHAR_IGNORECASE(50) NOT NULL,"
                + "create_time BIGINT,update_time BIGINT,"
                + "CONSTRAINT uk_pws_file_scope UNIQUE (tenant_id,client_id,owner_jiacn,file_id),"
                + "CONSTRAINT chk_pws_file_revision CHECK (metadata_revision>=1 AND latest_version>=1),"
                + "CONSTRAINT chk_pws_file_state CHECK (state IN ('ACTIVE','TRASHED')),"
                + "CONSTRAINT chk_pws_file_source CHECK (source_kind='UPLOAD'),"
                + "CONSTRAINT chk_pws_file_origin CHECK (origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY')))" );
        jdbc.execute("CREATE TABLE agent_personal_workspace_file_version ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "file_id VARCHAR_IGNORECASE(100) NOT NULL,owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,"
                + "version INT NOT NULL,original_filename VARCHAR(255) NOT NULL,"
                + "content_mime_type VARCHAR(127) NOT NULL,byte_length BIGINT NOT NULL,"
                + "content_hash CHAR(64) NOT NULL,storage_uri VARCHAR(1000) NOT NULL,created_at BIGINT NOT NULL,"
                + "tenant_id VARCHAR_IGNORECASE(50) NOT NULL,client_id VARCHAR_IGNORECASE(50) NOT NULL,"
                + "create_time BIGINT,update_time BIGINT,"
                + "CONSTRAINT uk_pws_version_scope UNIQUE (tenant_id,client_id,owner_jiacn,file_id,version),"
                + "CONSTRAINT chk_pws_version_positive CHECK (version>=1 AND byte_length>=0),"
                + "CONSTRAINT chk_pws_version_hash CHECK (CHAR_LENGTH(content_hash)=64))");
        jdbc.execute("CREATE TABLE agent_personal_workspace_operation ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "operation_id VARCHAR_IGNORECASE(100) NOT NULL,owner_jiacn VARCHAR_IGNORECASE(50) NOT NULL,"
                + "operation_type VARCHAR_IGNORECASE(40) NOT NULL,idempotency_key VARCHAR_IGNORECASE(100) NOT NULL,"
                + "request_hash CHAR(64) NOT NULL,state VARCHAR(32) NOT NULL,"
                + "file_id VARCHAR_IGNORECASE(100),file_version INT,error_code VARCHAR(80),"
                + "created_at BIGINT NOT NULL,completed_at BIGINT,"
                + "tenant_id VARCHAR_IGNORECASE(50) NOT NULL,client_id VARCHAR_IGNORECASE(50) NOT NULL,"
                + "create_time BIGINT,update_time BIGINT,"
                + "CONSTRAINT uk_pws_operation_key UNIQUE "
                + "(tenant_id,client_id,owner_jiacn,operation_type,idempotency_key),"
                + "CONSTRAINT uk_pws_operation_id UNIQUE (tenant_id,client_id,operation_id),"
                + "CONSTRAINT chk_pws_operation_hash CHECK (CHAR_LENGTH(request_hash)=64),"
                + "CONSTRAINT chk_pws_operation_state CHECK (state IN ('PROCESSING','COMMITTED','FAILED')))" );
    }

    private static final class CountingStorage implements PersonalWorkspaceStorage {
        private final PersonalWorkspaceStorage delegate;
        private final AtomicInteger stores = new AtomicInteger();
        private final AtomicInteger reads = new AtomicInteger();

        private CountingStorage(PersonalWorkspaceStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public StoredObject store(Scope scope, byte[] content, String mimeType) {
            stores.incrementAndGet();
            return delegate.store(scope, content, mimeType);
        }

        @Override
        public StoredContent read(Scope scope, String storageUri, String expectedSha256,
                long expectedByteLength, String expectedMimeType) {
            reads.incrementAndGet();
            return delegate.read(scope, storageUri, expectedSha256, expectedByteLength, expectedMimeType);
        }

        @Override
        public long maxContentBytes() {
            return delegate.maxContentBytes();
        }

        int storeCalls() {
            return stores.get();
        }

        int readCalls() {
            return reads.get();
        }

        void resetReads() {
            reads.set(0);
        }
    }
}
