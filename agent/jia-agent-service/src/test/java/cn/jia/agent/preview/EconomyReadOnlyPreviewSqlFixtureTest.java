package cn.jia.agent.preview;

import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.EconomyReadOnlyPreviewProperties;
import cn.jia.agent.hosting.HostingRentApplicationException;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static cn.jia.agent.preview.EconomyReadOnlyPreviewDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Executes the preview's annotation mapper against a private H2/MySQL-mode database. */
class EconomyReadOnlyPreviewSqlFixtureTest {
    private static final String CLIENT = "Client-A";
    private static final String AGENT = "agt_0123456789abcdef0123456789abcdef";
    private static final Principal A = principal("Tenant-A", "actor-A");
    private static final Principal C = principal("Tenant-C", "actor-C");
    private static final Principal SAME_OWNER_OTHER_SUB = principal("Tenant-A", "actor-other");
    private static final List<String> MUTATION_TABLES = List.of(
            "economy_account", "economy_transaction", "economy_entry", "economy_escrow",
            "economy_skill_product", "economy_skill_product_version", "economy_skill_installation",
            "economy_skill_entitlement", "agent_persona_binding", "agent_identity_registry",
            "agent_hosted_profile", "economy_hosting_rent_plan", "economy_hosting_lease",
            "agent_outbox_event");

    private JdbcTemplate jdbc;
    private EconomyReadOnlyPreviewService service;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:preview_" + UUID.randomUUID()
                        + ";MODE=MYSQL;DB_CLOSE_DELAY=-1;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
                "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/economy-readonly-preview-fixture.sql"))
                .execute(dataSource);

        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(EconomyReadOnlyPreviewMapper.class);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        SqlSessionFactory factory = Objects.requireNonNull(factoryBean.getObject());
        EconomyReadOnlyPreviewMapper mapper = new SqlSessionTemplate(factory)
                .getMapper(EconomyReadOnlyPreviewMapper.class);

        HostingRentOwnerResolver owners = mock(HostingRentOwnerResolver.class);
        when(owners.requireOwner(any(HostingRentHttp.Actor.class))).thenAnswer(invocation -> {
            HostingRentHttp.Actor actor = invocation.getArgument(0);
            boolean validA = actor.actorId().equals("actor-A") && actor.ownerJiacn().equals("Tenant-A");
            boolean validC = actor.actorId().equals("actor-C") && actor.ownerJiacn().equals("Tenant-C");
            if (!"0".equals(actor.tenantId()) || !CLIENT.equals(actor.clientId()) || !(validA || validC)) {
                throw new HostingRentApplicationException(403, "HOSTING_RENT_OWNER_UNPROVEN");
            }
            return actor.ownerJiacn();
        });
        service = new EconomyReadOnlyPreviewService(mapper, new EconomyReadOnlyPreviewProperties(true),
                new AgentHostingRentProperties(false, "", "", ""), owners);
        seed();
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void allReadSurfacesExecuteActualMapperSqlWithoutChangingRowsOrOutbox() {
        Map<String, Integer> before = counts();

        Wallet wallet = service.wallet(A);
        assertEquals("1000", wallet.availableMicro());
        assertEquals("350", wallet.heldMicro());
        assertEquals("6", wallet.version());

        LedgerPage ledger = service.ledger(A, null, 10);
        assertEquals(2, ledger.items().size());
        assertEquals("DEBIT", ledger.items().get(0).direction());
        assertEquals("70", ledger.items().get(0).amountMicro());
        assertEquals("CREDIT", ledger.items().get(1).direction());
        assertNull(ledger.nextCursor());

        ProductPage products = service.products(A, 0, 10);
        assertEquals(1, products.items().size());
        assertEquals("9007199254740993", products.items().getFirst().priceMicro());
        assertEquals(List.of("repo:read"), products.items().getFirst().permissions());
        assertEquals(products.items().getFirst(), service.product(A, "product-1"));

        AgentSkills skills = service.agentSkills(A, AGENT);
        assertEquals(2, skills.entitlements().size());
        assertEquals("VERIFIED_INSTALLED", skills.installationEvidence().get(0).status());
        assertEquals("150", skills.installationEvidence().get(0).verifiedAt());
        assertEquals("UNCONFIRMED", skills.installationEvidence().get(1).status());
        assertEquals("NO_PERSISTED_INSTALLATION", skills.installationEvidence().get(1).evidenceKind());

        HostingPlan plan = service.hostingPlan(A);
        assertEquals("PERSISTED_REFERENCE", plan.source());
        assertEquals("1000000000", plan.amountMicro());
        assertFalse(plan.activationAllowed());

        HostingLease hosting = service.hostingLease(A, AGENT);
        assertEquals("APPLICABLE", hosting.applicability());
        assertNotNull(hosting.lease());
        assertEquals("lease-1", hosting.lease().leaseId());
        assertEquals("actor-A", jdbc.queryForObject(
                "SELECT principal_id FROM economy_hosting_lease WHERE lease_id='lease-1'", String.class));

        BountyEstimate estimate = service.estimate(A, new EstimateInput(1_000_000_000L, 0L,
                new ParsedTokens(18_000, 4_000, 6_000, 3_000),
                new ParsedTokens(36_000, 8_000, 12_000, 6_000)));
        assertEquals("SIMULATION", estimate.mode());
        assertFalse(estimate.charged());
        assertFalse(estimate.persisted());

        assertEquals(before, counts(), "preview reads must not mutate any domain table or outbox");
    }

    @Test
    void domainScopesRemainByteExactAndSameClientIdentitiesStayIsolated() {
        assertEquals("1000", service.wallet(A).availableMicro());
        assertEquals("2200", service.wallet(C).availableMicro());
        assertEquals("3300", service.wallet(SAME_OWNER_OTHER_SUB).availableMicro());

        Principal lowerCaseOwner = principal("tenant-a", "actor-A");
        assertEquals("9900", service.wallet(lowerCaseOwner).availableMicro(),
                "case-insensitive fixture would leak Tenant-A without the mapper's binary fence");
        Principal trailingClient = new Principal("Tenant-A", "0", "0", "0",
                "Client-A ", "Tenant-A", "actor-A");
        assertEquals("0", service.wallet(trailingClient).availableMicro());

        assertEquals(service.products(A, 0, 10), service.products(C, 0, 10),
                "marketplace is shared at tenant 0 only within the exact client");
        assertTrue(service.products(trailingClient, 0, 10).items().isEmpty());

        EconomyReadOnlyPreviewException wrongSubject = assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.agentSkills(SAME_OWNER_OTHER_SUB, AGENT));
        assertEquals("PREVIEW_RESOURCE_NOT_FOUND", wrongSubject.code());
        EconomyReadOnlyPreviewException wrongOwner = assertThrows(EconomyReadOnlyPreviewException.class,
                () -> service.hostingLease(C, AGENT));
        assertEquals("PREVIEW_RESOURCE_NOT_FOUND", wrongOwner.code());
    }

    private void seed() {
        account("wallet-a", "Tenant-A", "actor-A", 1_000, 4);
        account("wallet-c", "Tenant-C", "actor-C", 2_200, 8);
        account("wallet-other-sub", "Tenant-A", "actor-other", 3_300, 9);
        account("wallet-lower", "tenant-a", "actor-A", 9_900, 10);

        jdbc.update("""
                INSERT INTO economy_escrow
                  (escrow_id,business_type,business_id,payer_account_id,escrow_account_id,currency,
                   gross_micro,captured_micro,refunded_micro,status,version,tenant_id,client_id,create_time,update_time)
                VALUES('escrow-a','HOSTING_RENT','lease-1','wallet-a','escrow-account','SILVER',
                       500,100,50,'ACTIVE',2,'Tenant-A',?,1,1)
                """, CLIENT);
        transaction("tx-credit", "ISSUE_SILVER", "issue-1", 100, 100);
        entry("entry-credit", "tx-credit", "wallet-a", 120, 100, 1_120);
        transaction("tx-debit", "HOSTING_RENT", "lease-1", 200, 200);
        entry("entry-debit", "tx-debit", "wallet-a", 200, -70, 1_050);
        transaction("tx-foreign", "ISSUE_SILVER", "foreign", 300, 300, "Tenant-C", "actor-C");
        entry("entry-foreign", "tx-foreign", "wallet-c", 300, 999, 3_199, "Tenant-C");

        jdbc.update("""
                INSERT INTO economy_skill_product
                  (product_id,name,description,status,current_product_version_id,tenant_id,client_id)
                VALUES('product-1','Repository Reader','Read-only repository skill','PUBLISHED','version-1','0',?)
                """, CLIENT);
        jdbc.update("""
                INSERT INTO economy_skill_product_version
                  (product_version_id,product_id,skill_key,skill_version,price_micro,
                   approved_permissions_manifest,deployment_restriction,review_status,tenant_id,client_id)
                VALUES('version-1','product-1','repo-read','1.0.0',9007199254740993,
                       '[\"repo:read\"]','NONE','APPROVED','0',?)
                """, CLIENT);
        jdbc.update("""
                INSERT INTO economy_skill_product
                  (product_id,name,description,status,current_product_version_id,tenant_id,client_id)
                VALUES('case-collision','Wrong client','Must remain hidden','PUBLISHED','case-version','0','client-a')
                """);
        jdbc.update("""
                INSERT INTO economy_skill_product_version
                  (product_version_id,product_id,skill_key,skill_version,price_micro,
                   approved_permissions_manifest,deployment_restriction,review_status,tenant_id,client_id)
                VALUES('case-version','case-collision','wrong','1.0.0',1,'[]','NONE','APPROVED','0','client-a')
                """);

        jdbc.update("""
                INSERT INTO agent_persona_binding
                  (id,owner_jiacn,persona_code,agent_id,status,tenant_id,client_id)
                VALUES(7,'Tenant-A','wuyong',?,1,'0',?)
                """, AGENT, CLIENT);
        jdbc.update("""
                INSERT INTO agent_identity_registry
                  (canonical_agent_id,lifecycle_status,client_id,owner_jiacn,tenant_id,binding_id)
                VALUES(?,'ACTIVE',?,'Tenant-A','0',7)
                """, AGENT, CLIENT);
        installation("install-1", "order-1", "version-1", "repo-read", "1.0.0", 150);
        entitlement("entitlement-1", "order-1", "install-1", "version-1", "repo-read", "1.0.0", "ACTIVE", 140);
        entitlement("entitlement-2", "order-2", "install-missing", "version-2", "write", "2.0.0", "ACTIVE", 141);
        jdbc.update("""
                INSERT INTO agent_hosted_profile
                  (binding_id,owner_jiacn,canonical_agent_id,persona_code,profile_key,api_key_id,
                   lifecycle_state,resume_state,generation,desired_enabled,last_error,tenant_id,client_id,
                   create_time,update_time)
                VALUES(7,'Tenant-A',?,'wuyong','profile-7','key-7','ACTIVE',NULL,2,1,NULL,'0',?,1,2)
                """, AGENT, CLIENT);
        jdbc.update("""
                INSERT INTO economy_hosting_rent_plan
                  (plan_id,plan_version,amount_micro,period_seconds,quote_ttl_seconds,currency,status,
                   tenant_id,client_id,create_time)
                VALUES('hosting-default',3,1000000000,2592000,300,'SILVER','ACTIVE','0',?,1)
                """, CLIENT);
        jdbc.update("""
                INSERT INTO economy_hosting_lease
                  (lease_id,principal_type,principal_id,persona_code,agent_id,binding_id,plan_id,plan_version,
                   amount_micro,period_seconds,status,paid_from,paid_through,latest_intent_id,version,
                   tenant_id,client_id,create_time,update_time)
                VALUES('lease-1','USER','actor-A','wuyong',?,'7','hosting-default',3,
                       1000000000,2592000,'ACTIVE',100,2592100,'intent-1',4,'0',?,1,2)
                """, AGENT, CLIENT);
        jdbc.update("INSERT INTO agent_outbox_event(marker) VALUES('preexisting-sentinel')");
    }

    private void account(String accountId, String tenant, String actor, long balance, long version) {
        account(accountId, tenant, actor, balance, version, CLIENT);
    }

    private void account(String accountId, String tenant, String actor, long balance, long version, String client) {
        jdbc.update("""
                INSERT INTO economy_account
                  (account_id,owner_type,owner_id,purpose,currency,balance_micro,allow_negative,status,
                   version,tenant_id,client_id,create_time,update_time)
                VALUES(?,'USER',?,'AVAILABLE','SILVER',?,0,'ACTIVE',?,?,?,1,1)
                """, accountId, actor, balance, version, tenant, client);
    }

    private void transaction(String id, String type, String ref, long postedAt, long ignored) {
        transaction(id, type, ref, postedAt, ignored, "Tenant-A", "actor-A");
    }

    private void transaction(String id, String type, String ref, long postedAt, long ignored,
            String tenant, String actor) {
        jdbc.update("""
                INSERT INTO economy_transaction
                  (transaction_id,principal_type,principal_id,business_type,business_id,currency,status,
                   posted_at,tenant_id,client_id,create_time,update_time)
                VALUES(?,'USER',?,?,?,'SILVER','POSTED',?,?,?,1,1)
                """, id, actor, type, ref, postedAt, tenant, CLIENT);
    }

    private void entry(String id, String transaction, String account, long postedAt, long signed, long balance) {
        entry(id, transaction, account, postedAt, signed, balance, "Tenant-A");
    }

    private void entry(String id, String transaction, String account, long postedAt, long signed,
            long balance, String tenant) {
        jdbc.update("""
                INSERT INTO economy_entry
                  (entry_id,transaction_id,account_id,entry_sequence,signed_amount_micro,balance_after_micro,
                   currency,status,posted_at,tenant_id,client_id,create_time)
                VALUES(?,?,?,1,?,?,'SILVER','POSTED',?,?,?,1)
                """, id, transaction, account, signed, balance, postedAt, tenant, CLIENT);
    }

    private void installation(String id, String order, String version, String key,
            String skillVersion, long installedAt) {
        jdbc.update("""
                INSERT INTO economy_skill_installation
                  (installation_id,order_id,product_version_id,target_agent_id,schema_version,message_type,
                   message_id,request_id,command_type,command_id,attempt,fencing_token,delivery_epoch,
                   skill_key,skill_version,package_size,package_sha256,download_path,status,failure_code,
                   version,tenant_id,client_id,create_time,started_at,installed_at,failed_at,update_time)
                VALUES(?,?,?, ?,1,'command.dispatch','message-1','message-1','SKILL_INSTALL','command-1',
                       1,1,1,?,?,32,?,?,'SUCCEEDED',NULL,2,'0',?,1,100,?,NULL,?)
                """, id, order, version, AGENT, key, skillVersion, new byte[32],
                "/internal/agent/skill-installations/" + id + "/package", CLIENT, installedAt, installedAt);
    }

    private void entitlement(String id, String order, String installation, String version,
            String key, String skillVersion, String status, long activatedAt) {
        jdbc.update("""
                INSERT INTO economy_skill_entitlement
                  (entitlement_id,order_id,installation_id,product_version_id,target_agent_id,skill_key,
                   skill_version,permission_grant_version,approved_permissions_manifest,
                   approved_permissions_sha256,status,version,tenant_id,client_id,create_time,
                   activated_at,failed_at,update_time)
                VALUES(?,?,?,?,?,?,?,1,'[]',?,?,1,'0',?,1,?,NULL,?)
                """, id, order, installation, version, AGENT, key, skillVersion, new byte[32], status,
                CLIENT, activatedAt, activatedAt);
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String table : MUTATION_TABLES) {
            result.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
        return result;
    }

    private static Principal principal(String owner, String actor) {
        return new Principal(owner, "0", "0", "0", CLIENT, owner, actor);
    }
}
