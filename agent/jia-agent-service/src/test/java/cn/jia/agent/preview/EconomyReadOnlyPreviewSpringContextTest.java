package cn.jia.agent.preview;

import cn.jia.agent.api.EconomyReadOnlyPreviewController;
import cn.jia.agent.config.AgentHostingRentProperties;
import cn.jia.agent.config.EconomyReadOnlyPreviewConfiguration;
import cn.jia.agent.config.EconomyReadOnlyPreviewProperties;
import cn.jia.agent.hosting.HostingRentHttp;
import cn.jia.agent.hosting.HostingRentOwnerResolver;
import cn.jia.agent.mapper.EconomyReadOnlyPreviewMapper;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies real property binding, CGLIB transaction proxying and the HTTP JSON wire together. */
class EconomyReadOnlyPreviewSpringContextTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EconomyReadOnlyPreviewConfiguration.class, TestConfiguration.class);

    @Test
    void defaultSwitchIsFalseAndContextCreatesTransactionalService() {
        runner.run(context -> {
            assertFalse(context.getBean(EconomyReadOnlyPreviewProperties.class).enabled());
            assertTrue(AopUtils.isCglibProxy(context.getBean(EconomyReadOnlyPreviewService.class)));
        });
    }

    @Test
    void enabledBindingUsesActualReadOnlyTransactionAndExactJsonContract() {
        runner.withPropertyValues("economy.read-only-preview.enabled=true").run(context -> {
            EconomyReadOnlyPreviewMapper mapper = context.getBean(EconomyReadOnlyPreviewMapper.class);
            HostingRentOwnerResolver owners = context.getBean(HostingRentOwnerResolver.class);
            when(owners.requireOwner(any(HostingRentHttp.Actor.class))).thenReturn("Tenant-A");
            AtomicBoolean transactionObserved = new AtomicBoolean();
            when(mapper.selectWallet("Tenant-A", "Client-A", "actor-A")).thenAnswer(invocation -> {
                transactionObserved.set(TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isCurrentTransactionReadOnly());
                return new EconomyWalletSnapshotRow().setAvailableMicro(9L).setHeldMicro(2L)
                        .setMinimumHeldComponentMicro(2L).setVersion(4L);
            });

            EconomyReadOnlyPreviewService service = context.getBean(EconomyReadOnlyPreviewService.class);
            assertTrue(AopUtils.isCglibProxy(service));
            service.wallet(new EconomyReadOnlyPreviewDtos.Principal(
                    "Tenant-A", "0", "0", "0", "Client-A", "Tenant-A", "actor-A"));
            assertTrue(transactionObserved.get(), "mapper read must run in an actual read-only transaction");

            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    context.getBean(EconomyReadOnlyPreviewController.class)).build();
            mvc.perform(get("/economy/preview/wallet").principal(jwt()))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.code").value("E0"))
                    .andExpect(jsonPath("$.msg").doesNotExist())
                    .andExpect(jsonPath("$.message").doesNotExist())
                    .andExpect(jsonPath("$.status").doesNotExist())
                    .andExpect(jsonPath("$.location").doesNotExist())
                    .andExpect(jsonPath("$.data.availableMicro").value("9"))
                    .andExpect(jsonPath("$.data.heldMicro").value("2"))
                    .andExpect(jsonPath("$.data.version").value("4"));
        });
    }

    private static JwtAuthenticationToken jwt() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "none")
                .claim("jiacn", "Tenant-A").claim("client_id", "Client-A")
                .subject("actor-A").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        return new JwtAuthenticationToken(token, List.of(), "actor-A");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    @Import({EconomyReadOnlyPreviewService.class, EconomyReadOnlyPreviewController.class})
    static class TestConfiguration {
        @Bean(destroyMethod = "shutdown")
        DataSource dataSource() {
            return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true).build();
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        EconomyReadOnlyPreviewMapper economyReadOnlyPreviewMapper() {
            return mock(EconomyReadOnlyPreviewMapper.class);
        }

        @Bean
        AgentHostingRentProperties agentHostingRentProperties() {
            return new AgentHostingRentProperties(false, null, null, null);
        }

        @Bean
        HostingRentOwnerResolver hostingRentOwnerResolver() {
            return mock(HostingRentOwnerResolver.class);
        }
    }
}
