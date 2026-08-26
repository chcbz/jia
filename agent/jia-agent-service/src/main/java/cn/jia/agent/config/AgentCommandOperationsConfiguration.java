package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.dao.impl.AgentCommandOperationsDaoImpl;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import cn.jia.agent.service.AgentCommandDlqRedriver;
import cn.jia.agent.service.AgentCommandOperationsService;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.impl.AgentCommandDlqRedriverImpl;
import cn.jia.agent.service.impl.AgentCommandOperationsServiceImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;

/** D09 wiring. When all operation flags are false this creates no bean or infrastructure access. */
@Configuration(proxyBeanMethods = false)
@Conditional(AgentCommandOperationsEnabledCondition.class)
@EnableConfigurationProperties(AgentCommandOperationsProperties.class)
public class AgentCommandOperationsConfiguration {
    @Bean(name = "agentCommandOperationsDao", defaultCandidate = false)
    public AgentCommandOperationsDao agentCommandOperationsDao(
            AgentCommandOperationsMapper mapper) {
        return new AgentCommandOperationsDaoImpl(mapper);
    }

    @Bean(name = "agentCommandOperationsService", defaultCandidate = false)
    public AgentCommandOperationsService agentCommandOperationsService(
            @Qualifier("agentCommandOperationsDao") AgentCommandOperationsDao dao,
            AgentRabbitSafetyGate gate,
            ObjectProvider<AgentRabbitTopologyManifest> manifestProvider,
            @Qualifier("agentConfirmedRabbitPublisher")
            ObjectProvider<AgentConfirmedRabbitPublisher> publisherProvider,
            @Qualifier("agentRabbitConnectionFactory")
            ObjectProvider<ConnectionFactory> connectionFactoryProvider,
            ObjectProvider<AgentCommandReissueService> reissueProvider,
            AgentCommandOperationsProperties properties,
            PlatformTransactionManager transactionManager) {
        AgentRabbitTopologyManifest manifest = manifestProvider.getIfAvailable(
                AgentRabbitTopologyManifest::canonical);
        AgentConfirmedRabbitPublisher publisher = publisherProvider.getIfAvailable();
        ConnectionFactory connectionFactory = connectionFactoryProvider.getIfAvailable();
        AgentCommandReissueService reissueService = reissueProvider.getIfAvailable();
        if (properties.readEnabled() && !gate.commandOutboxEnabled()) {
            throw new IllegalStateException("D09 read operations require command outbox");
        }
        if ((properties.redriveEnabled() || properties.reissueEnabled())
                && gate.state() != AgentRabbitActivationState.DISPATCH_CANARY
                && gate.state() != AgentRabbitActivationState.DISPATCH_SCOPED) {
            throw new IllegalStateException(
                    "D09 write operations require scoped Rabbit dispatch activation");
        }
        if (properties.redriveEnabled() && (publisher == null || connectionFactory == null)) {
            throw new IllegalStateException("D09 broker redrive requires dedicated Rabbit publish infrastructure");
        }
        if (properties.reissueEnabled() && reissueService == null) {
            throw new IllegalStateException("D09 manual reissue requires D06 recovery service");
        }
        AgentCommandDlqRedriver redriver = publisher == null || connectionFactory == null
                ? null : new AgentCommandDlqRedriverImpl(connectionFactory, publisher);
        return new AgentCommandOperationsServiceImpl(
                dao, gate, manifest, redriver, reissueService, properties, transactionManager);
    }
}
