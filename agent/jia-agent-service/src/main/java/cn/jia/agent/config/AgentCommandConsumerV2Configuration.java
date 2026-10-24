package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.service.AgentCommandAckV2Service;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentCommandV2RuntimeAuthority;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.agent.service.AgentRuntimeV2DispatchService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.impl.AgentCommandAckV2ServiceImpl;
import cn.jia.agent.service.impl.AgentCommandInboxV2ServiceImpl;
import cn.jia.agent.service.impl.AgentCommandRabbitV2Consumer;
import cn.jia.agent.service.impl.AgentCommandV2ExecutionPortImpl;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Durable v2 core plus an explicit, scope-bound broker listener. */
@Configuration(proxyBeanMethods = false)
public class AgentCommandConsumerV2Configuration {

    @Bean(name = "agentCommandV2ExecutionPort", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentCommandV2ExecutionPort executionPort(
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope,
            @Qualifier("agentRuntimeV2DispatchService") AgentRuntimeV2DispatchService durable) {
        return new AgentCommandV2ExecutionPortImpl(scope, durable);
    }

    @Bean(name = "agentCommandConsumerV2SchemaGate", defaultCandidate = false)
    @ConditionalOnMissingBean(name = "agentCommandConsumerV2SchemaGate")
    @ConditionalOnProperty(prefix = "agent.rabbit-consumer-v2-core", name = "enabled",
            havingValue = "true")
    AgentCommandConsumerV2SchemaGate schemaGate(JdbcTemplate jdbc) {
        return new AgentCommandConsumerV2SchemaGate(jdbc);
    }

    @Bean(name = "agentCommandInboxV2Service", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consumer-v2-core", name = "enabled",
            havingValue = "true")
    AgentCommandInboxV2Service service(
            @Qualifier("agentCommandConsumerV2SchemaGate")
            AgentCommandConsumerV2SchemaGate consumerSchemaGate,
            @Qualifier("agentOutboxRelayV2SchemaGate")
            AgentOutboxRelayV2SchemaGate relaySchemaGate,
            AgentCommandInboxV2Dao dao,
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope,
            @Qualifier("agentOutboxTenantModuleAdmissionGuard") TenantModuleAdmissionGuard guard,
            PlatformTransactionManager tx) {
        return new AgentCommandInboxV2ServiceImpl(dao, scope, guard, tx);
    }

    @Bean(name = "agentCommandAckV2Service", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consumer-v2-core", name = "enabled",
            havingValue = "true")
    AgentCommandAckV2Service ackService(
            @Qualifier("agentCommandConsumerV2SchemaGate")
            AgentCommandConsumerV2SchemaGate consumerSchemaGate,
            @Qualifier("agentOutboxRelayV2SchemaGate")
            AgentOutboxRelayV2SchemaGate relaySchemaGate,
            AgentCommandInboxV2Dao dao,
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope,
            @Qualifier("agentOutboxTenantModuleAdmissionGuard") TenantModuleAdmissionGuard guard,
            PlatformTransactionManager tx) {
        return new AgentCommandAckV2ServiceImpl(dao, scope, guard, tx);
    }

    @Bean(name = "agentCommandV2TopologyManifest", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentRabbitV2TopologyManifest v2Manifest(
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope) {
        return AgentRabbitV2TopologyManifest.forScope(scope);
    }

    @Bean(name = "agentCommandV2TopologyProvisionedSnapshot", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentRabbitV2TopologyReadiness.Snapshot v2TopologyProvisionedSnapshot(
            @Qualifier("agentCommandConsumerV2Preflight")
            AgentCommandConsumerV2Preflight preflight,
            @Qualifier("agentRabbitV2TopologyProvisioner")
            AgentRabbitV2TopologyProvisioner provisioner,
            @Qualifier("agentCommandV2TopologyManifest")
            AgentRabbitV2TopologyManifest manifest) {
        AgentRabbitV2TopologyReadiness.Snapshot snapshot = provisioner.provision(manifest);
        if (snapshot == null || !snapshot.canonicalTopologyReady(manifest)) {
            throw new IllegalStateException(
                    "Agent command consumer v2 unavailable: canonical provision did not reach READY");
        }
        return snapshot;
    }

    @Bean(name = "agentCommandConsumerV2Preflight", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentCommandConsumerV2Preflight preflight(Environment environment,
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope,
            @Qualifier("agentCommandV2TopologyManifest")
            AgentRabbitV2TopologyManifest manifest,
            @Qualifier("agentCommandInboxV2Service") AgentCommandInboxV2Service inboxService,
            @Qualifier("agentCommandAckV2Service") AgentCommandAckV2Service ackService,
            @Qualifier("agentCommandV2ExecutionPort")
            AgentCommandV2ExecutionPort executionPort,
            @Qualifier("agentConfirmedRabbitPublisher")
            AgentConfirmedRabbitPublisher publisher,
            @Qualifier("agentOutboxRelaySettings")
            AgentOutboxRelaySettings relaySettings,
            AgentRabbitSafetyGate safetyGate) {
        return new AgentCommandConsumerV2Preflight(environment, scope, manifest,
                inboxService, ackService, executionPort, publisher, relaySettings, safetyGate);
    }

    @Bean(name = "agentCommandConsumerV2ActivationGate", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentCommandConsumerV2ActivationGate activationGate(
            @Qualifier("agentCommandConsumerV2Preflight")
            AgentCommandConsumerV2Preflight preflight,
            @Qualifier("agentCommandV2TopologyManifest") AgentRabbitV2TopologyManifest manifest,
            @Qualifier("agentRabbitV2TopologyReadiness")
            AgentRabbitV2TopologyReadiness readiness,
            @Qualifier("agentCommandInboxV2Service") AgentCommandInboxV2Service inboxService,
            @Qualifier("agentCommandV2TopologyProvisionedSnapshot")
            AgentRabbitV2TopologyReadiness.Snapshot provisionedSnapshot,
            @Qualifier("agentCommandAckV2Service") AgentCommandAckV2Service ackService,
            @Qualifier("agentCommandV2ExecutionPort")
            AgentCommandV2ExecutionPort executionPort) {
        return new AgentCommandConsumerV2ActivationGate(
                manifest, readiness, provisionedSnapshot);
    }

    @Bean(name = "agentCommandRabbitV2Consumer", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    AgentCommandRabbitV2Consumer v2Consumer(
            @Qualifier("agentCommandConsumerV2ActivationGate")
            AgentCommandConsumerV2ActivationGate activationGate,
            @Qualifier("agentCommandInboxV2Service") AgentCommandInboxV2Service inboxService,
            @Qualifier("agentCommandV2ExecutionPort")
            AgentCommandV2ExecutionPort executionPort,
            @Qualifier("agentConfirmedRabbitPublisher")
            AgentConfirmedRabbitPublisher publisher,
            @Qualifier("agentOutboxRelaySettings")
            AgentOutboxRelaySettings relaySettings,
            @Qualifier("agentOutboxWorkerScope") WorkerScope scope,
            @Qualifier("agentCommandV2TopologyManifest") AgentRabbitV2TopologyManifest manifest) {
        return new AgentCommandRabbitV2Consumer(
                inboxService, executionPort, publisher, scope, manifest,
                relaySettings.confirmTimeoutMillis());
    }

    @Bean(name = "agentCommandV2ListenerContainerFactory", defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    SimpleRabbitListenerContainerFactory v2ListenerContainerFactory(
            @Qualifier("agentCommandConsumerV2ActivationGate")
            AgentCommandConsumerV2ActivationGate activationGate,
            @Qualifier("agentRabbitConnectionFactory") ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setDefaultRequeueRejected(false);
        factory.setConsumerBatchEnabled(false);
        factory.setBatchListener(false);
        return factory;
    }

    @Bean(name = "agentCommandV2ListenerContainer", destroyMethod = "destroy",
            defaultCandidate = false)
    @ConditionalOnProperty(prefix = "agent.rabbit-consume-v2", name = "enabled",
            havingValue = "true")
    SimpleMessageListenerContainer v2ListenerContainer(
            @Qualifier("agentCommandConsumerV2ActivationGate")
            AgentCommandConsumerV2ActivationGate activationGate,
            @Qualifier("agentCommandV2ListenerContainerFactory")
            SimpleRabbitListenerContainerFactory factory,
            @Qualifier("agentCommandRabbitV2Consumer") AgentCommandRabbitV2Consumer consumer,
            @Qualifier("agentCommandV2TopologyManifest") AgentRabbitV2TopologyManifest manifest) {
        SimpleRabbitListenerEndpoint endpoint = new SimpleRabbitListenerEndpoint();
        endpoint.setId(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2);
        endpoint.setQueueNames(manifest.dispatchQueue());
        endpoint.setMessageListener((ChannelAwareMessageListener) consumer::consume);
        SimpleMessageListenerContainer container = factory.createListenerContainer(endpoint);
        container.setListenerId(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2);
        container.setRecoveryInterval(5_000L);
        return container;
    }
}
