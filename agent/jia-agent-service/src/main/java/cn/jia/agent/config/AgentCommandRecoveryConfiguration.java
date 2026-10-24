package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.impl.AgentCommandAckServiceImpl;
import cn.jia.agent.service.impl.AgentCommandDualLaneReissueService;
import cn.jia.agent.service.impl.AgentCommandReissueCoordinator;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** D06 wiring exists only for exact-scope Rabbit dispatch. Construction performs no DB or network I/O. */
@Configuration(proxyBeanMethods = false)
@Conditional(AgentLegacyRabbitDispatchEnabledCondition.class)
public class AgentCommandRecoveryConfiguration {
    @Bean(name = "agentCommandReissueSettings", defaultCandidate = false)
    public AgentCommandReissueSettings agentCommandReissueSettings() {
        return new AgentCommandReissueSettings();
    }

    @Bean
    public AgentCommandDualLaneReissueService agentCommandReissueService(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            @Qualifier("agentCommandReissueSettings") AgentCommandReissueSettings settings,
            @Qualifier("agentOutboxTenantModuleAdmissionGuard")
            ObjectProvider<TenantModuleAdmissionGuard> admissionGuardProvider,
            @Qualifier("agentOutboxWorkerScope")
            ObjectProvider<WorkerScope> workerScopeProvider,
            PlatformTransactionManager transactionManager) {
        TenantModuleAdmissionGuard admissionGuard = admissionGuardProvider.getIfAvailable();
        WorkerScope workerScope = workerScopeProvider.getIfAvailable();
        return new AgentCommandDualLaneReissueService(
                dao, gate, dispatcher, manifest, settings.sentAckTimeoutMillis(),
                admissionGuard, workerScope, transactionManager);
    }

    @Bean
    public AgentCommandAckService agentCommandAckService(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            PlatformTransactionManager transactionManager) {
        return new AgentCommandAckServiceImpl(dao, gate, transactionManager);
    }

    @Bean(name = "agentCommandReconnectSignal", destroyMethod = "close")
    public AgentCommandReconnectSignal agentCommandReconnectSignal(
            AgentCommandDualLaneReissueService service,
            @Qualifier("agentCommandReissueSettings") AgentCommandReissueSettings settings) {
        return new AgentCommandReissueCoordinator(service, settings);
    }
}
