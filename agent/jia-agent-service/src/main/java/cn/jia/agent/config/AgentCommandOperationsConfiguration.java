package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.dao.impl.AgentCommandOperationsDaoImpl;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.agent.service.AgentCommandOperationsService;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.impl.AgentCommandDlqRedriverImpl;
import cn.jia.agent.service.impl.AgentCommandOperationsServiceImpl;
import cn.jia.agent.service.impl.AgentCommandRedriveRawProvider;
import cn.jia.agent.service.impl.AgentCommandRedriveRawProviderImpl;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** D09 wiring. When all operation flags are false this creates no bean or infrastructure access. */
@Configuration(proxyBeanMethods = false)
@Conditional(AgentCommandOperationsEnabledCondition.class)
@EnableConfigurationProperties(AgentCommandOperationsProperties.class)
public class AgentCommandOperationsConfiguration {
    @Bean(name = "agentCommandRedriveRabbitSettings", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-operations", name = "redrive-enabled",
            havingValue = "true")
    public AgentCommandRedriveRabbitSettings agentCommandRedriveRabbitSettings(
            AgentCommandOperationsProperties properties) {
        return new AgentCommandRedriveRabbitSettings(properties);
    }

    @Bean(name = "agentCommandRedriveOneShotRawFactory", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-operations", name = "redrive-enabled",
            havingValue = "true")
    public com.rabbitmq.client.ConnectionFactory agentCommandRedriveOneShotRawFactory(
            @Qualifier("agentRabbitCanonicalRawConnectionFactory")
            com.rabbitmq.client.ConnectionFactory canonical,
            @Qualifier("agentRabbitRawConnectionFactoryBuilder")
            AgentRabbitRawConnectionFactoryBuilder builder,
            @Qualifier("agentCommandRedriveRabbitSettings")
            AgentCommandRedriveRabbitSettings settings) {
        return builder.oneShot(canonical, settings);
    }

    @Bean(name = "agentCommandRedriveRawProvider", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-operations", name = "redrive-enabled",
            havingValue = "true")
    public AgentCommandRedriveRawProvider agentCommandRedriveRawProvider(
            @Qualifier("agentCommandRedriveOneShotRawFactory")
            com.rabbitmq.client.ConnectionFactory oneShotFactory,
            @Qualifier("agentCommandRedriveRabbitSettings")
            AgentCommandRedriveRabbitSettings settings,
            AgentRabbitSafetyGate gate,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            @Qualifier("agentRabbitTopologyReadiness")
            AgentRabbitTopologyReadiness readiness,
            @Qualifier("agentRabbitV2TopologyReadiness")
            AgentRabbitV2TopologyReadiness v2Readiness) {
        return new AgentCommandRedriveRawProviderImpl(
                oneShotFactory, settings, gate, manifest, readiness, v2Readiness);
    }

    @Bean(name = "agentCommandOperationsDao", defaultCandidate = false)
    public AgentCommandOperationsDao agentCommandOperationsDao(
            AgentCommandOperationsMapper mapper) {
        return new AgentCommandOperationsDaoImpl(mapper);
    }

    @Bean(name = "agentCommandOperationsService", defaultCandidate = false,
            destroyMethod = "close")
    public AgentCommandOperationsService agentCommandOperationsService(
            @Qualifier("agentCommandOperationsDao") AgentCommandOperationsDao dao,
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentRabbitTopologyManifest> manifestProvider,
            @Qualifier("agentCommandRedriveRawProvider")
            ObjectProvider<AgentCommandRedriveRawProvider> rawProvider,
            @Qualifier("agentCommandRedriveRabbitSettings")
            ObjectProvider<AgentCommandRedriveRabbitSettings> redriveSettingsProvider,
            @Qualifier("agentOutboxWorkerScope")
            ObjectProvider<WorkerScope> workerScopeProvider,
            @Qualifier("agentOutboxTenantModuleAdmissionGuard")
            ObjectProvider<TenantModuleAdmissionGuard> admissionGuardProvider,
            ObjectProvider<AgentCommandReissueService> reissueProvider,
            AgentCommandOperationsProperties properties,
            PlatformTransactionManager transactionManager) {
        AgentRabbitTopologyManifest manifest = manifestProvider.getIfAvailable(
                AgentRabbitTopologyManifest::canonical);
        AgentCommandRedriveRawProvider provider = rawProvider.getIfAvailable();
        AgentCommandRedriveRabbitSettings redriveSettings =
                redriveSettingsProvider.getIfAvailable();
        AgentCommandReissueService reissueService = reissueProvider.getIfAvailable();
        WorkerScope workerScope = workerScopeProvider.getIfAvailable();
        TenantModuleAdmissionGuard admissionGuard = admissionGuardProvider.getIfAvailable();
        if (properties.readEnabled() && !gate.commandOutboxEnabled()) {
            throw new IllegalStateException("D09 read operations require command outbox");
        }
        if ((properties.redriveEnabled() || properties.reissueEnabled())
                && gate.state() != AgentRabbitActivationState.DISPATCH_CANARY
                && gate.state() != AgentRabbitActivationState.DISPATCH_SCOPED) {
            throw new IllegalStateException(
                    "D09 write operations require scoped Rabbit dispatch activation");
        }
        if (properties.redriveEnabled()
                && (provider == null || workerScope == null || admissionGuard == null
                    || redriveSettings == null)) {
            throw new IllegalStateException(
                    "D09 broker redrive requires raw one-shot Rabbit and worker authority");
        }
        if (properties.reissueEnabled() && reissueService == null) {
            throw new IllegalStateException("D09 manual reissue requires D06 recovery service");
        }
        AgentCommandDlqRedriver redriver = !properties.redriveEnabled()
                ? null : new AgentCommandDlqRedriverImpl(
                        provider, dao, workerScope, admissionGuard,
                        transactionManager, redriveSettings);
        return new AgentCommandOperationsServiceImpl(
                dao, gate, manifest, redriver, reissueService, properties, transactionManager);
    }
}
