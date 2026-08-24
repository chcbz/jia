package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.service.AgentCommandAckService;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.impl.AgentCommandAckServiceImpl;
import cn.jia.agent.service.impl.AgentCommandReissueCoordinator;
import cn.jia.agent.service.impl.AgentCommandReissueServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** D06 wiring exists only for exact-scope Rabbit dispatch. Construction performs no DB or network I/O. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "agent.rabbit-dispatch", name = "enabled", havingValue = "true")
public class AgentCommandRecoveryConfiguration {
    @Bean(name = "agentCommandReissueSettings", defaultCandidate = false)
    public AgentCommandReissueSettings agentCommandReissueSettings() {
        return new AgentCommandReissueSettings();
    }

    @Bean
    public AgentCommandReissueService agentCommandReissueService(
            AgentCommandRecoveryDao dao,
            AgentRabbitSafetyGate gate,
            AgentRawCommandDispatcher dispatcher,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            PlatformTransactionManager transactionManager) {
        return new AgentCommandReissueServiceImpl(
                dao, gate, dispatcher, manifest, transactionManager);
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
            AgentCommandReissueService service,
            @Qualifier("agentCommandReissueSettings") AgentCommandReissueSettings settings) {
        return new AgentCommandReissueCoordinator(service, settings);
    }
}
