package cn.jia.economy.skill;

import cn.jia.economy.entity.skill.SkillEntitlementEntity;
import cn.jia.economy.entity.skill.SkillInstallationEntity;
import cn.jia.economy.entity.skill.SkillOrderEntity;
import cn.jia.economy.entity.skill.SkillOrderReceiptEntity;
import cn.jia.economy.entity.skill.SkillProductEntity;
import cn.jia.economy.entity.skill.SkillProductVersionEntity;
import cn.jia.economy.entity.skill.SkillPurchaseQuoteEntity;
import cn.jia.economy.mapper.EconomySkillMarketplaceMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Real H2/MyBatis coverage for scoped W08/W09 mapper contracts and fenced CAS transitions. */
class EconomySkillMarketplaceMapperH2Test {
    private static final String TENANT = "Tenant-A";
    private static final String CLIENT = "Client-A";
    private static final String USER = "user-1";
    private static final String AGENT = "agent-1";
    private static final byte[] PACKAGE_HASH = hash("package");
    private static final byte[] PERMISSIONS_HASH = hash("permissions");
    private static final byte[] REQUEST_HASH = hash("request");
    private static final byte[] IDEMPOTENCY_KEY =
            "00000000-0000-0000-0000-000000000701".getBytes(StandardCharsets.US_ASCII);

    private JdbcTemplate jdbc;
    private EconomySkillMarketplaceMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.h2.Driver");
        source.setUrl("jdbc:h2:mem:eco_v0_w07;MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE");
        source.setUsername("sa");
        source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        createTables();

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EconomySkillMarketplaceMapper.class);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) throw new IllegalStateException("SqlSessionFactory was not created");
        mapper = new SqlSessionTemplate(factory).getMapper(EconomySkillMarketplaceMapper.class);
    }

    @Test
    void freePurchaseFoundationIsScopedIdempotentAndFencedThroughActivation() {
        assertEquals(1, mapper.insertProduct(product()));
        assertEquals(1, mapper.insertProductVersion(productVersion()));
        assertEquals(1, mapper.publishProductVersion(TENANT, CLIENT, "sp-1", "spv-1", 1, 2));
        assertEquals(0L, mapper.selectPurchasableProductVersion(TENANT, CLIENT, "spv-1").getPriceMicro());
        assertNull(mapper.selectPurchasableProductVersion("tenant-a", CLIENT, "spv-1"));

        assertEquals(1, mapper.insertPurchaseQuote(quote()));
        assertEquals("sq-1", mapper.selectPurchaseQuoteByActorKeyForUpdate(
                TENANT, CLIENT, "USER", USER, IDEMPOTENCY_KEY).getQuoteId());
        assertNull(mapper.selectPurchaseQuoteByActorKeyForUpdate(
                TENANT, "client-a", "USER", USER, IDEMPOTENCY_KEY));

        assertEquals(1, mapper.insertOrder(order()));
        assertEquals(1, mapper.insertOrderReceipt(receipt()));
        assertEquals("so-1", mapper.selectOrderReceiptByActorKeyForUpdate(
                TENANT, CLIENT, "USER", USER, IDEMPOTENCY_KEY).getOrderId());
        assertNull(mapper.selectOrderByBuyer(TENANT, CLIENT, "USER", "other-user", "so-1"));

        assertEquals(1, mapper.insertInstallation(installation()));
        assertEquals(1, mapper.insertEntitlement(entitlement()));
        assertNull(mapper.selectInstallationForUpdate(TENANT, CLIENT, "other-agent", "si-1"));
        assertEquals(0, mapper.markInstallationInstalling(
                TENANT, CLIENT, AGENT, "si-1", "wrong-command", 1, 1, 1, 1, 3));

        assertEquals(1, mapper.markOrderInstalling(TENANT, CLIENT, "so-1", 1, 3));
        assertEquals(1, mapper.markInstallationInstalling(
                TENANT, CLIENT, AGENT, "si-1", "cmd-1", 1, 1, 1, 1, 3));
        assertEquals(1, mapper.markInstallationSucceeded(
                TENANT, CLIENT, AGENT, "si-1", "cmd-1", 1, 1, 1, 2, PACKAGE_HASH, 4));
        assertEquals(1, mapper.markEntitlementActive(
                TENANT, CLIENT, AGENT, "se-1", "si-1", 1, 4));
        assertEquals(1, mapper.markOrderActive(TENANT, CLIENT, "so-1", 2, null, 4));

        assertEquals("ACTIVE", mapper.selectOrderByBuyer(TENANT, CLIENT, "USER", USER, "so-1").getStatus());
        assertEquals("SUCCEEDED", mapper.selectInstallationForUpdate(TENANT, CLIENT, AGENT, "si-1").getStatus());
        assertEquals("ACTIVE", mapper.selectEntitlementsByAgent(TENANT, CLIENT, AGENT).getFirst().getStatus());
        assertEquals(0, mapper.markOrderRefunded(TENANT, CLIENT, "so-1", 3, null, 5));
        assertEquals(0, mapper.selectEntitlementsByAgent("tenant-a", CLIENT, AGENT).size());
    }

    private SkillProductEntity product() {
        return new SkillProductEntity()
                .setProductId("sp-1").setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                .setName("Repository Inspector").setDescription("Read-only repository inspection")
                .setStatus("DRAFT").setVersion(1L).setTenantId(TENANT).setClientId(CLIENT)
                .setCreateTime(1L).setUpdateTime(1L);
    }

    private SkillProductVersionEntity productVersion() {
        return new SkillProductVersionEntity()
                .setProductVersionId("spv-1").setProductId("sp-1").setVersionSequence(1L)
                .setSkillKey("repo-inspector").setSkillVersion("1.0.0").setPriceMicro(0L)
                .setPackageSha256(PACKAGE_HASH).setPackageSize(123L)
                .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(PERMISSIONS_HASH)
                .setDeploymentRestriction("NONE").setReviewStatus("APPROVED")
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(1L);
    }

    private SkillPurchaseQuoteEntity quote() {
        return new SkillPurchaseQuoteEntity()
                .setQuoteId("sq-1").setActorType("USER").setActorId(USER)
                .setIdempotencyKey(IDEMPOTENCY_KEY).setRequestHash(REQUEST_HASH)
                .setProductVersionId("spv-1").setTargetAgentId(AGENT)
                .setExpectedAgentVersion(7L).setExpectedPriceMicro(0L)
                .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(PERMISSIONS_HASH)
                .setDeploymentRestriction("NONE").setExpiresAt(100L)
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(2L);
    }

    private SkillOrderEntity order() {
        return new SkillOrderEntity()
                .setOrderId("so-1").setQuoteId("sq-1").setProductVersionId("spv-1")
                .setTargetAgentId(AGENT).setBuyerType("USER").setBuyerId(USER)
                .setSellerType("SYSTEM").setSellerId("SKILL_STORE")
                .setPriceMicro(0L).setExpectedAgentVersion(7L).setPermissionGrantVersion(1L)
                .setApprovedPermissionsManifest("[]").setApprovedPermissionsSha256(PERMISSIONS_HASH)
                .setStatus("FUNDS_HELD").setVersion(1L).setTenantId(TENANT).setClientId(CLIENT)
                .setHeldAt(2L).setUpdateTime(2L);
    }

    private SkillOrderReceiptEntity receipt() {
        return new SkillOrderReceiptEntity()
                .setOrderId("so-1").setActorType("USER").setActorId(USER)
                .setIdempotencyKey(IDEMPOTENCY_KEY).setRequestHash(REQUEST_HASH)
                .setOrderVersion(1L).setOrderStatus("FUNDS_HELD").setPriceMicro(0L)
                .setPermissionGrantVersion(1L).setApprovedPermissionsSha256(PERMISSIONS_HASH)
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(2L);
    }

    private SkillInstallationEntity installation() {
        return new SkillInstallationEntity()
                .setInstallationId("si-1").setOrderId("so-1").setProductVersionId("spv-1")
                .setTargetAgentId(AGENT).setSchemaVersion(1).setMessageType("command.dispatch")
                .setMessageId("msg-1").setRequestId("msg-1").setCommandType("SKILL_INSTALL")
                .setCommandId("cmd-1").setAttempt(1).setFencingToken(1L).setDeliveryEpoch(1L)
                .setSkillKey("repo-inspector").setSkillVersion("1.0.0")
                .setPackageSize(123L).setPackageSha256(PACKAGE_HASH)
                .setDownloadPath("/internal/agent/skill-installations/si-1/package")
                .setStatus("REQUESTED").setVersion(1L).setTenantId(TENANT).setClientId(CLIENT)
                .setCreateTime(2L).setUpdateTime(2L);
    }

    private SkillEntitlementEntity entitlement() {
        return new SkillEntitlementEntity()
                .setEntitlementId("se-1").setOrderId("so-1").setInstallationId("si-1")
                .setProductVersionId("spv-1").setTargetAgentId(AGENT)
                .setSkillKey("repo-inspector").setSkillVersion("1.0.0")
                .setPermissionGrantVersion(1L).setApprovedPermissionsManifest("[]")
                .setApprovedPermissionsSha256(PERMISSIONS_HASH)
                .setStatus("PENDING_INSTALLATION").setVersion(1L)
                .setTenantId(TENANT).setClientId(CLIENT).setCreateTime(2L).setUpdateTime(2L);
    }

    private static byte[] hash(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void createTables() {
        jdbc.execute("""
                CREATE TABLE economy_skill_product(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,product_id VARCHAR(100),seller_type VARCHAR(16),
                    seller_id VARCHAR(100),creator_agent_id VARCHAR(100),name VARCHAR(200),description VARCHAR(1000),
                    status VARCHAR(16),current_product_version_id VARCHAR(100),version BIGINT,
                    tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,update_time BIGINT,
                    UNIQUE(tenant_id,client_id,product_id))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_product_version(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,product_version_id VARCHAR(100),product_id VARCHAR(100),
                    version_sequence BIGINT,skill_key VARCHAR(100),skill_version VARCHAR(64),price_micro BIGINT,
                    package_sha256 BINARY(32),package_size BIGINT,approved_permissions_manifest TEXT,
                    approved_permissions_sha256 BINARY(32),deployment_restriction VARCHAR(20),review_status VARCHAR(16),
                    tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,
                    UNIQUE(tenant_id,client_id,product_version_id),UNIQUE(tenant_id,client_id,skill_key,skill_version))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_purchase_quote(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,quote_id VARCHAR(100),actor_type VARCHAR(16),actor_id VARCHAR(100),
                    idempotency_key VARBINARY(36),request_hash BINARY(32),product_version_id VARCHAR(100),
                    target_agent_id VARCHAR(100),expected_agent_version BIGINT,expected_price_micro BIGINT,
                    approved_permissions_manifest TEXT,approved_permissions_sha256 BINARY(32),
                    deployment_restriction VARCHAR(20),expires_at BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),
                    create_time BIGINT,UNIQUE(tenant_id,client_id,quote_id),
                    UNIQUE(tenant_id,client_id,actor_type,actor_id,idempotency_key))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_order(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id VARCHAR(100),quote_id VARCHAR(100),
                    product_version_id VARCHAR(100),target_agent_id VARCHAR(100),buyer_type VARCHAR(16),buyer_id VARCHAR(100),
                    seller_type VARCHAR(16),seller_id VARCHAR(100),price_micro BIGINT,expected_agent_version BIGINT,
                    permission_grant_version BIGINT,approved_permissions_manifest TEXT,
                    approved_permissions_sha256 BINARY(32),escrow_id VARCHAR(100),reserve_transaction_id VARCHAR(100),
                    capture_transaction_id VARCHAR(100),refund_transaction_id VARCHAR(100),status VARCHAR(16),version BIGINT,
                    tenant_id VARCHAR(50),client_id VARCHAR(50),held_at BIGINT,installing_at BIGINT,active_at BIGINT,
                    refunded_at BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,order_id),
                    UNIQUE(tenant_id,client_id,quote_id))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_order_receipt(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id VARCHAR(100),actor_type VARCHAR(16),actor_id VARCHAR(100),
                    idempotency_key VARBINARY(36),request_hash BINARY(32),order_version BIGINT,order_status VARCHAR(16),
                    price_micro BIGINT,permission_grant_version BIGINT,approved_permissions_sha256 BINARY(32),
                    escrow_id VARCHAR(100),tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,
                    UNIQUE(tenant_id,client_id,order_id),
                    UNIQUE(tenant_id,client_id,actor_type,actor_id,idempotency_key))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_installation(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,installation_id VARCHAR(100),order_id VARCHAR(100),
                    product_version_id VARCHAR(100),target_agent_id VARCHAR(100),schema_version INT,message_type VARCHAR(32),
                    message_id VARCHAR(100),request_id VARCHAR(100),command_type VARCHAR(32),command_id VARCHAR(100),
                    attempt INT,fencing_token BIGINT,delivery_epoch BIGINT,skill_key VARCHAR(100),skill_version VARCHAR(64),
                    package_size BIGINT,package_sha256 BINARY(32),download_path VARCHAR(300),status VARCHAR(16),
                    failure_code VARCHAR(64),version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,
                    started_at BIGINT,installed_at BIGINT,failed_at BIGINT,update_time BIGINT,
                    UNIQUE(tenant_id,client_id,installation_id),UNIQUE(tenant_id,client_id,order_id))
                """);
        jdbc.execute("""
                CREATE TABLE economy_skill_entitlement(
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,entitlement_id VARCHAR(100),order_id VARCHAR(100),
                    installation_id VARCHAR(100),product_version_id VARCHAR(100),target_agent_id VARCHAR(100),
                    skill_key VARCHAR(100),skill_version VARCHAR(64),permission_grant_version BIGINT,
                    approved_permissions_manifest TEXT,approved_permissions_sha256 BINARY(32),status VARCHAR(24),
                    version BIGINT,tenant_id VARCHAR(50),client_id VARCHAR(50),create_time BIGINT,activated_at BIGINT,
                    failed_at BIGINT,update_time BIGINT,UNIQUE(tenant_id,client_id,entitlement_id),
                    UNIQUE(tenant_id,client_id,target_agent_id,installation_id),
                    UNIQUE(tenant_id,client_id,target_agent_id,skill_key,skill_version))
                """);
    }
}
