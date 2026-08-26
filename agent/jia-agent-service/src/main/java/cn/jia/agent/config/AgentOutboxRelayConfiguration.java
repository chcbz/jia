package cn.jia.agent.config;

import cn.jia.agent.dao.AgentOutboxRelayDao;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.AgentOutboxPublisher;
import cn.jia.agent.service.AgentOutboxRelayService;
import cn.jia.agent.service.impl.AgentConfirmedRabbitPublisherImpl;
import cn.jia.agent.service.impl.AgentOutboxRelayScheduler;
import cn.jia.agent.service.impl.AgentOutboxRelayServiceImpl;
import cn.jia.agent.service.impl.AgentRabbitOutboxPublisher;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** D03 dedicated publish boundary; construction is side-effect-free and never probes RabbitMQ. */
@Configuration(value = "agentOutboxRelayConfiguration", proxyBeanMethods = false)
@Conditional(AgentRabbitPublishEnabledCondition.class)
public class AgentOutboxRelayConfiguration {
    @Bean(name = "agentOutboxRelaySettings", defaultCandidate = false)
    public AgentOutboxRelaySettings agentOutboxRelaySettings(
            AgentRabbitSafetyProperties properties) {
        return new AgentOutboxRelaySettings(properties.rabbitPublish());
    }

    @Bean(name = "agentRabbitTemplate", defaultCandidate = false)
    public RabbitTemplate agentRabbitTemplate(
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setChannelTransacted(false);
        return template;
    }

    @Bean(name = "agentConfirmedRabbitPublisher", defaultCandidate = false)
    public AgentConfirmedRabbitPublisher agentConfirmedRabbitPublisher(
            @Qualifier("agentRabbitTemplate") RabbitTemplate template,
            AgentRabbitSafetyGate gate,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            @Qualifier("agentRabbitTopologyReadiness") AgentRabbitTopologyReadiness readiness) {
        return new AgentConfirmedRabbitPublisherImpl(template, gate, manifest, readiness);
    }

    @Bean(name = "agentOutboxPublisher", defaultCandidate = false)
    public AgentOutboxPublisher agentOutboxPublisher(
            @Qualifier("agentConfirmedRabbitPublisher") AgentConfirmedRabbitPublisher publisher,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest) {
        return new AgentRabbitOutboxPublisher(publisher, manifest);
    }

    @Bean(name = "agentOutboxRelayService", defaultCandidate = false)
    public AgentOutboxRelayService agentOutboxRelayService(
            AgentOutboxRelayDao dao,
            AgentRabbitSafetyGate gate,
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            @Qualifier("agentRabbitTopologyReadiness") AgentRabbitTopologyReadiness readiness,
            @Qualifier("agentOutboxRelaySettings") AgentOutboxRelaySettings settings,
            PlatformTransactionManager transactionManager) {
        return new AgentOutboxRelayServiceImpl(
                dao, gate, manifest, readiness, settings, transactionManager);
    }

    @Bean(name = "agentOutboxRelayScheduler", defaultCandidate = false,
            destroyMethod = "close")
    public AgentOutboxRelayScheduler agentOutboxRelayScheduler(
            AgentRabbitSafetyGate gate,
            @Qualifier("agentRabbitTopologyReadiness") AgentRabbitTopologyReadiness readiness,
            @Qualifier("agentOutboxRelaySettings") AgentOutboxRelaySettings settings,
            @Qualifier("agentOutboxRelayService") AgentOutboxRelayService relayService,
            @Qualifier("agentOutboxPublisher") AgentOutboxPublisher publisher) {
        return new AgentOutboxRelayScheduler(
                gate, readiness, settings, relayService, publisher);
    }
}
