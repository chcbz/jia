package cn.jia.agent.platform;

import cn.jia.agent.dao.AgentCommandTransportDao;
import cn.jia.agent.dao.AgentRuntimeDao;
import cn.jia.agent.security.AgentRuntimeAuthenticationService;
import cn.jia.agent.service.AgentControlledCommandDispatcher;
import cn.jia.agent.service.AgentIdentityService;
import cn.jia.agent.service.InstalledSkillResolver;
import cn.jia.agent.skill.InstalledSkillResolverService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises real Spring constructor selection instead of accepting a reflection-only contract. */
class PlatformSkillInstallationWiringContractTest {
    @Test
    void enabledContextCreatesServiceFromProductionDependenciesWithoutTestClock() {
        PlatformInstallationStore store = mock(PlatformInstallationStore.class);
        PlatformSkillCatalog catalog = new PlatformSkillCatalog();
        AgentIdentityService identities = mock(AgentIdentityService.class);
        AgentRuntimeDao runtimes = mock(AgentRuntimeDao.class);
        AgentRuntimeAuthenticationService authentication = mock(AgentRuntimeAuthenticationService.class);
        AgentCommandTransportDao deliveries = mock(AgentCommandTransportDao.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "platformSkillInstallationWiringContractTest",
                    Map.of("agent.platform-skills.enabled", "true")));
            context.getBeanFactory().registerSingleton("platformInstallationStore", store);
            context.getBeanFactory().registerSingleton("platformSkillCatalog", catalog);
            context.getBeanFactory().registerSingleton("agentIdentityService", identities);
            context.getBeanFactory().registerSingleton("agentRuntimeDao", runtimes);
            context.getBeanFactory().registerSingleton("agentRuntimeAuthenticationService", authentication);
            context.getBeanFactory().registerSingleton("agentCommandTransportDao", deliveries);
            context.getBeanFactory().registerSingleton("platformTransactionManager", transactions);
            context.register(PlatformSkillInstallationService.class, PlatformSkillController.class,
                    PlatformInstalledSkillResolver.class, InstalledSkillResolverService.class);

            context.refresh();

            PlatformSkillInstallationService service = context.getBean(PlatformSkillInstallationService.class);
            assertNotNull(service);
            assertNotNull(context.getBean(PlatformSkillController.class));
            assertSame(service, context.getBean(AgentControlledCommandDispatcher.class));
            assertNotNull(context.getBean(InstalledSkillResolver.class));
            assertNotNull(context.getBean(PlatformInstalledSkillResolver.class));
            verifyNoInteractions(store, identities, runtimes, authentication, deliveries, transactions);
        }
    }

    @Test
    void disabledContextDoesNotCreatePlatformInstallationService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(PlatformSkillInstallationService.class, PlatformSkillController.class,
                    PlatformInstalledSkillResolver.class, InstalledSkillResolverService.class);
            context.refresh();
            assertTrue(context.getBeansOfType(PlatformSkillInstallationService.class).isEmpty());
            assertTrue(context.getBeansOfType(PlatformSkillController.class).isEmpty());
            assertTrue(context.getBeansOfType(PlatformInstalledSkillResolver.class).isEmpty());
            InstalledSkillResolver resolver=context.getBean(InstalledSkillResolver.class);
            var request=new InstalledSkillResolver.Request("0","client-a","owner-a","agent-a",7,
                    InstalledSkillResolver.Origin.PLATFORM_PROVISIONED,"archive-maintainer","1.0.0","a".repeat(64));
            assertSame(InstalledSkillResolver.State.UNAVAILABLE,resolver.resolve(request).state());
        }
    }
}
