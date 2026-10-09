package cn.jia.agent.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Dedicated, lazy D04 Rabbit infrastructure registered only for topology-enabled states. */
@Configuration(value = "agentRabbitTopologyConfiguration", proxyBeanMethods = false)
@Conditional(AgentRabbitTopologyEnabledCondition.class)
public class AgentRabbitTopologyConfiguration {
    @Bean(name = "agentRabbitTopologyManifest", defaultCandidate = false)
    public AgentRabbitTopologyManifest agentRabbitTopologyManifest() {
        return AgentRabbitTopologyManifest.canonical();
    }

    @Bean(name = "agentRabbitRawConnectionFactoryBuilder", defaultCandidate = false)
    public AgentRabbitRawConnectionFactoryBuilder agentRabbitRawConnectionFactoryBuilder() {
        return new AgentRabbitRawConnectionFactoryBuilder();
    }

    @Bean(name = "agentRabbitCanonicalRawConnectionFactory", defaultCandidate = false)
    public com.rabbitmq.client.ConnectionFactory agentRabbitCanonicalRawConnectionFactory(
            AgentRabbitBrokerSettings settings,
            @Qualifier("agentRabbitRawConnectionFactoryBuilder")
            AgentRabbitRawConnectionFactoryBuilder builder) {
        return builder.canonical(settings);
    }

    @Bean(name = "agentRabbitConnectionFactory", defaultCandidate = false)
    public CachingConnectionFactory agentRabbitConnectionFactory(
            @Qualifier("agentRabbitCanonicalRawConnectionFactory")
            com.rabbitmq.client.ConnectionFactory rawFactory) {
        CachingConnectionFactory factory = new CachingConnectionFactory(rawFactory);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    @Bean(name = "agentRabbitAdmin", defaultCandidate = false)
    public RabbitAdmin agentRabbitAdmin(
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(false);
        admin.setExplicitDeclarationsOnly(true);
        return admin;
    }

    @Bean(name = "agentRabbitTopologyReadiness", defaultCandidate = false)
    public AgentRabbitTopologyReadiness agentRabbitTopologyReadiness(
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest) {
        return new AgentRabbitTopologyReadiness(manifest);
    }

    @Bean(name = "agentRabbitTopologyProvisioner", defaultCandidate = false)
    public AgentRabbitTopologyProvisioner agentRabbitTopologyProvisioner(
            @Qualifier("agentRabbitTopologyManifest") AgentRabbitTopologyManifest manifest,
            @Qualifier("agentRabbitAdmin") RabbitAdmin admin,
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory,
            @Qualifier("agentRabbitTopologyReadiness") AgentRabbitTopologyReadiness readiness) {
        return new AgentRabbitTopologyProvisioner(
                manifest, admin, connectionFactory, readiness);
    }

    @Bean(name = "agentRabbitV2TopologyReadiness", defaultCandidate = false)
    public AgentRabbitV2TopologyReadiness agentRabbitV2TopologyReadiness() {
        return new AgentRabbitV2TopologyReadiness();
    }

    @Bean(name = "agentRabbitV2TopologyProvisioner", defaultCandidate = false)
    public AgentRabbitV2TopologyProvisioner agentRabbitV2TopologyProvisioner(
            @Qualifier("agentRabbitAdmin") RabbitAdmin admin,
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory,
            @Qualifier("agentRabbitV2TopologyReadiness")
            AgentRabbitV2TopologyReadiness readiness) {
        return new AgentRabbitV2TopologyProvisioner(admin, connectionFactory, readiness);
    }

    @Bean(name = "agentCommandListenerContainerFactory", defaultCandidate = false)
    @Conditional(AgentRabbitConsumeEnabledCondition.class)
    public SimpleRabbitListenerContainerFactory agentCommandListenerContainerFactory(
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(20);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        factory.setConsumerBatchEnabled(false);
        return factory;
    }
}
