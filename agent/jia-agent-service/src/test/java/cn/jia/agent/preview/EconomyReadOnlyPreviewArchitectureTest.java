package cn.jia.agent.preview;

import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EconomyReadOnlyPreviewArchitectureTest {
    @Test
    void everyMapperMethodIsSelectOnlyWithoutLocksOrMutationSql() {
        for (Method method : EconomyReadOnlyPreviewMapper.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            Select select = method.getAnnotation(Select.class);
            assertNotNull(select, method + " must be an explicit SELECT");
            assertNull(method.getAnnotation(Insert.class));
            assertNull(method.getAnnotation(Update.class));
            assertNull(method.getAnnotation(Delete.class));
            String sql = String.join(" ", select.value()).toLowerCase(Locale.ROOT)
                    .replaceAll("\\s+", " ");
            assertFalse(sql.matches(".*\\b(insert|update|delete|replace|merge|call)\\b.*"), sql);
            assertFalse(sql.contains("for update"), sql);
            assertFalse(sql.contains("get_lock"), sql);
            assertFalse(sql.contains("release_lock"), sql);
        }
    }


    @Test
    void aclSqlSeparatesClientSharedCatalogFromOwnerAndActorScopedResources() throws Exception {
        String catalog = sql("selectProducts");
        assertTrue(catalog.contains("tenant_id=#{tenantid}"), catalog);
        assertTrue(catalog.contains("client_id=#{clientid}"), catalog);
        assertFalse(catalog.contains("ownerjiacn"), catalog);
        assertFalse(catalog.contains("actorid"), catalog);

        String owner = sql("selectOwnedAgent");
        assertTrue(owner.contains("owner_jiacn=#{ownerjiacn}"), owner);
        assertTrue(owner.contains("cast(i.owner_jiacn as binary)=cast(#{ownerjiacn} as binary)"), owner);
        assertTrue(owner.contains("octet_length(i.owner_jiacn)=octet_length(#{ownerjiacn})"), owner);

        String lease = sql("selectLatestLease");
        assertTrue(lease.contains("principal_id=#{actorid}"), lease);
        assertTrue(lease.contains("cast(principal_id as binary)=cast(#{actorid} as binary)"), lease);
        assertTrue(lease.contains("octet_length(principal_id)=octet_length(#{actorid})"), lease);
    }

    @Test
    void serviceConstructorCannotReceiveWriteOrNetworkCollaborators() {
        Set<Class<?>> dependencies = Arrays.stream(EconomyReadOnlyPreviewService.class.getConstructors()[0]
                        .getParameterTypes()).collect(Collectors.toSet());
        assertEquals(Set.of(EconomyReadOnlyPreviewMapper.class,
                cn.jia.agent.config.EconomyReadOnlyPreviewProperties.class,
                cn.jia.agent.config.AgentHostingRentProperties.class,
                cn.jia.agent.hosting.HostingRentOwnerResolver.class), dependencies);
    }

    private static String sql(String methodName) throws Exception {
        Method method = Arrays.stream(EconomyReadOnlyPreviewMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        return String.join(" ", method.getAnnotation(Select.class).value()).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    @Test
    void serviceHasNoWriteGatePublisherProvisionerProviderOrLegacyMarketplaceDependency() throws Exception {
        Path source = Path.of("src/main/java/cn/jia/agent/preview/EconomyReadOnlyPreviewService.java");
        if (!Files.exists(source)) source = Path.of("agent/jia-agent-service").resolve(source);
        String text = Files.readString(source);
        for (String forbidden : Arrays.asList("EconomyPostingService", "EconomyTreasuryPostingService",
                "SkillMarketplaceService", "SkillAgentVersions", "HostingRentApplicationService",
                "AgentCommandTransportWriter", "ManagedHostingProvisioner", "WebClient", "RestTemplate",
                "economy.preview.enabled", "economy.skill.marketplace.enabled")) {
            assertFalse(text.contains(forbidden), "forbidden dependency: " + forbidden);
        }
        assertTrue(text.contains("FundedBountyQuoteCalculator.calculate"));
        assertTrue(text.contains("@Transactional(readOnly = true)"));
    }
}
