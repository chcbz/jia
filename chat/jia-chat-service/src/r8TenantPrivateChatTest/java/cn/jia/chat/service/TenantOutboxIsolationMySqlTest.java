package cn.jia.chat.service;

import cn.jia.agent.skill.SkillManagementMySqlFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;

/** Same two-process discovery/claim regression on the existing private MySQL fixture, no external DB. */
class TenantOutboxIsolationMySqlTest extends SkillManagementMySqlFixture {
    private String database;
    private R8TenantPrivateChatTest scenario;
    @BeforeEach void prepare() throws Exception {
        database = "tenant_outbox_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        scenario = new R8TenantPrivateChatTest();
        scenario.setUp(source(database));
    }
    @AfterEach void cleanup() {
        if (scenario != null) scenario.tearDown();
        if (database != null) admin.execute("DROP DATABASE " + database);
    }
    @Test void concurrentLegacyAndGreyConsumersNeverClaimEachOthersEvents() throws Exception {
        scenario.legacyAndGreyWorkersDiscoverAndClaimOnlyTheirOwnRowsIncludingExpiredLeases();
    }
    @Test void configuredLegacyTenantPreservesAllOfItsClients() {
        scenario.legacyDiscoveryHonorsConfiguredTenantAndNeverRestrictsItsMultipleClients();
    }
}
