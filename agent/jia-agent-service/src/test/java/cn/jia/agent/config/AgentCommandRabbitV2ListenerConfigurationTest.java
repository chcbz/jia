package cn.jia.agent.config;

import cn.jia.agent.common.AgentCommandAmqpContract;
import cn.jia.agent.dao.AgentCommandInboxV2Dao;
import cn.jia.agent.entity.AgentConfirmedPublishRequest;
import cn.jia.agent.entity.AgentInboxConsumers;
import cn.jia.agent.entity.AgentRabbitPublishResult;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentCommandV2RuntimeAuthority;
import cn.jia.agent.service.AgentCommandV2RuntimeGateway;
import cn.jia.agent.service.AgentRuntimeV2DispatchService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.agent.service.impl.AgentCommandRabbitConsumer;
import cn.jia.agent.service.impl.AgentConfirmedRabbitPublisherImpl;
import cn.jia.agent.service.impl.AgentCommandV2ExecutionPortImpl;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentCommandRabbitV2ListenerConfigurationTest {
    private static final WorkerScope SCOPE = new WorkerScope(TenantId.of("1"),
            DataDomainId.of("cyf"), "local-test", "consumer-a", 3, 5,
            7, "agent-command", 9);
    private static final String[] ENABLED = {
            "agent.rabbit-consume-v2.enabled=true",
            "agent.rabbit-consumer-v2-core.enabled=true",
            "agent.command-outbox.enabled=true",
            "agent.rabbit-topology.enabled=true",
            "agent.rabbit-publish.enabled=true",
            "agent.rabbit-consume.enabled=false",
            "agent.rabbit-dispatch.enabled=true",
            "agent.rabbit-dispatch.allowed-worker-scopes[0].tenant-id=1",
            "agent.rabbit-dispatch.allowed-worker-scopes[0].data-domain-id=cyf",
            "agent.rabbit-dispatch.allowed-worker-scopes[0].execution-pool-id=local-test",
            "agent.rabbit-broker.host=isolated.invalid",
            "agent.rabbit-broker.port=5673",
            "agent.rabbit-broker.username=user",
            "agent.rabbit-broker.password=secret",
            "agent.rabbit-broker.virtual-host=/isolated"
    };

    @Test
    void defaultConfigurationIsDormantAndSideEffectFree() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        new ApplicationContextRunner()
                .withBean("agentRabbitAdmin", RabbitAdmin.class, () -> admin)
                .withUserConfiguration(CanonicalDependencies.class,
                        AgentCommandConsumerV2Configuration.class)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNoTopologyMutations(admin);
                    assertFalse(context.containsBean("agentCommandV2TopologyManifest"));
                    assertFalse(context.containsBean("agentCommandV2TopologyProvisionedSnapshot"));
                    assertFalse(context.containsBean("agentCommandConsumerV2ActivationGate"));
                    assertFalse(context.containsBean("agentCommandRabbitV2Consumer"));
                    assertFalse(context.containsBean("agentCommandV2ListenerContainerFactory"));
                    assertFalse(context.containsBean("agentCommandV2ListenerContainer"));
                });
    }

    @Test
    void completeTrustedConfigurationCreatesOneExactV2ListenerAndNoLegacyFactory() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, admin).run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(
                    SimpleRabbitListenerContainerFactory.class).size());
            assertFalse(context.containsBean("agentCommandListenerContainerFactory"));
            assertEquals(1, context.getBeansOfType(
                    SimpleMessageListenerContainer.class).size());

            AgentRabbitV2TopologyManifest manifest = context.getBean(
                    "agentCommandV2TopologyManifest", AgentRabbitV2TopologyManifest.class);
            AgentRabbitV2TopologyReadiness.Snapshot provisioned = context.getBean(
                    "agentCommandV2TopologyProvisionedSnapshot",
                    AgentRabbitV2TopologyReadiness.Snapshot.class);
            assertTrue(provisioned.canonicalTopologyReady(manifest));
            verify(admin, times(manifest.exchanges().size())).declareExchange(any());
            verify(admin, times(manifest.queues().size())).declareQueue(any());
            verify(admin, times(manifest.bindings().size())).declareBinding(any());
            verify(admin, never()).deleteExchange(any());
            verify(admin, never()).deleteQueue(any());
            SimpleMessageListenerContainer container = context.getBean(
                    "agentCommandV2ListenerContainer", SimpleMessageListenerContainer.class);
            assertEquals(AgentInboxConsumers.AGENT_COMMAND_DISPATCH_V2,
                    container.getListenerId());
            assertEquals(1, container.getQueueNames().length);
            assertEquals(manifest.dispatchQueue(), container.getQueueNames()[0]);
            assertEquals(AcknowledgeMode.MANUAL, container.getAcknowledgeMode());
            assertSame(context.getBean("agentRabbitConnectionFactory"),
                    container.getConnectionFactory());
            assertEquals(1, ReflectionTestUtils.getField(container, "prefetchCount"));
            assertEquals(1, ReflectionTestUtils.getField(container, "concurrentConsumers"));
            assertEquals(1, ReflectionTestUtils.getField(container, "maxConcurrentConsumers"));
            assertEquals(false, ReflectionTestUtils.getField(
                    container, "defaultRequeueRejected"));
            assertFalse(container.isConsumerBatchEnabled());
            Object recoveryBackOff = ReflectionTestUtils.getField(
                    container, "recoveryBackOff");
            assertTrue(recoveryBackOff instanceof FixedBackOff);
            assertEquals(5_000L, ((FixedBackOff) recoveryBackOff).getInterval());
            assertNotNull(container.getMessageListener());
            assertTrue(Arrays.equals(new String[]{manifest.dispatchQueue()},
                    container.getQueueNames()));

            AgentConfirmedRabbitPublisher publisher = context.getBean(
                    "agentConfirmedRabbitPublisher", AgentConfirmedRabbitPublisher.class);
            assertInstanceOf(AgentConfirmedRabbitPublisherImpl.class, publisher);
            AgentRabbitPublishResult main = publisher.publish(
                    scopedRequest(manifest, false), 1_000L);
            AgentRabbitPublishResult retry = publisher.publish(
                    scopedRequest(manifest, true), 1_000L);
            assertEquals(AgentRabbitPublishResult.Type.ACK, main.type());
            assertEquals(AgentRabbitPublishResult.Type.ACK, retry.type());
            RabbitTemplate template = context.getBean(
                    "agentRabbitTemplate", RabbitTemplate.class);
            verify(template).send(eq(manifest.mainExchange()),
                    eq(manifest.generalRoutingKey()), any(), any(CorrelationData.class));
            verify(template).send(eq(manifest.deadLetterExchange()),
                    eq(manifest.retry5sRoutingKey()), any(), any(CorrelationData.class));
            assertTrue(context.getBeansOfType(AgentCommandReconnectSignal.class).isEmpty());
            assertTrue(context.getBeansOfType(AgentCommandRabbitConsumer.class).isEmpty());
            assertInstanceOf(AgentCommandV2ExecutionPortImpl.class,
                    context.getBean("agentCommandV2ExecutionPort"));
            assertTrue(context.getBean("agentCommandV2ExecutionPort",AgentCommandV2ExecutionPort.class).durableHandoff());
            org.mockito.Mockito.verifyNoInteractions(context.getBean(AgentCommandV2RuntimeAuthority.class),context.getBean(AgentCommandV2RuntimeGateway.class));
        });
    }

    @Test
    void dispatchDisabledOrWorkerScopeMismatchFailsBeforeTopologyMutation() {
        RabbitAdmin dispatchAdmin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, dispatchAdmin,
                "agent.rabbit-dispatch.enabled=false")
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("prerequisites are incomplete"));
                    assertNoTopologyMutations(dispatchAdmin);
                });
        RabbitAdmin scopeAdmin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, scopeAdmin,
                "agent.rabbit-dispatch.allowed-worker-scopes[0].tenant-id=2")
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("worker scope is not allowed"));
                    assertNoTopologyMutations(scopeAdmin);
                });
    }

    @Test
    void legacyAndV2CannotBeEnabledTogether() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, admin,
                "agent.rabbit-consume.enabled=true")
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("mutually exclusive"));
                    assertNoTopologyMutations(admin);
                });
    }

    @Test
    void missingProvisionerAndPassiveOnlyReadinessFailClosed() {
        RabbitAdmin missingAdmin = mock(RabbitAdmin.class);
        runner(ReadinessWithoutProvisioner.class, missingAdmin)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(missingAdmin);
                });
        RabbitAdmin passiveAdmin = mock(RabbitAdmin.class);
        runner(PassiveDependencies.class, passiveAdmin)
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("canonical provision did not reach READY"));
                    assertNoTopologyMutations(passiveAdmin);
                });
    }

    @Test
    void provisionFailureAndWrongScopeOrDigestFailClosed() {
        RabbitAdmin failingAdmin = mock(RabbitAdmin.class);
        runner(FailingProvisionDependencies.class, failingAdmin)
                .run(context -> assertTrue(rootCause(context.getStartupFailure())
                        .getMessage().contains("PROVISION_FAILED")));
        verify(failingAdmin).declareExchange(any());

        RabbitAdmin scopeAdmin = mock(RabbitAdmin.class);
        runner(ScopeMismatchDependencies.class, scopeAdmin)
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("canonical provision did not reach READY"));
                    assertNoTopologyMutations(scopeAdmin);
                });
        RabbitAdmin digestAdmin = mock(RabbitAdmin.class);
        runner(DigestMismatchDependencies.class, digestAdmin)
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains("canonical provision did not reach READY"));
                    assertNoTopologyMutations(digestAdmin);
                });
    }

    @Test
    void missingCoreOrHalfAuthorityFailsClosed() {
        RabbitAdmin coreAdmin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, coreAdmin,
                "agent.rabbit-consumer-v2-core.enabled=false")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(coreAdmin);
                });
        RabbitAdmin halfAdmin = mock(RabbitAdmin.class);
        runner(BaseDependencies.class, halfAdmin)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(halfAdmin);
                });
    }

    @Test
    void topologyAndPublishSafetyFlagsAreMandatory() {
        RabbitAdmin topologyAdmin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, topologyAdmin,
                "agent.rabbit-topology.enabled=false")
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains(
                                    "rabbit publish requires command outbox and topology"));
                    assertNoTopologyMutations(topologyAdmin);
                });
        RabbitAdmin publishAdmin = mock(RabbitAdmin.class);
        runner(CanonicalDependencies.class, publishAdmin,
                "agent.rabbit-publish.enabled=false")
                .run(context -> {
                    assertTrue(rootCause(context.getStartupFailure())
                            .getMessage().contains(
                                    "rabbit dispatch prerequisites are incomplete"));
                    assertNoTopologyMutations(publishAdmin);
                });
    }

    @Test
    void missingExecutionPublisherOrRelaySettingsFailsBeforeTopologyMutation() {
        RabbitAdmin executionAdmin = mock(RabbitAdmin.class);
        runner(MissingExecutionPortDependencies.class, executionAdmin)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(executionAdmin);
                });
        RabbitAdmin publisherAdmin = mock(RabbitAdmin.class);
        runner(MissingPublisherDependencies.class, publisherAdmin)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(publisherAdmin);
                });
        RabbitAdmin settingsAdmin = mock(RabbitAdmin.class);
        runner(MissingRelaySettingsDependencies.class, settingsAdmin)
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertNoTopologyMutations(settingsAdmin);
                });
    }

    private static ApplicationContextRunner runner(
            Class<?> dependencies, RabbitAdmin admin, String... overrides) {
        String[] properties = Arrays.copyOf(ENABLED, ENABLED.length + overrides.length);
        System.arraycopy(overrides, 0, properties, ENABLED.length, overrides.length);
        return new ApplicationContextRunner()
                .withBean("agentRabbitAdmin", RabbitAdmin.class, () -> admin)
                .withUserConfiguration(dependencies,
                        NoStartContainerPostProcessor.class,
                        AgentCommandConsumerV2Configuration.class,
                        AgentCommandRecoveryConfiguration.class,
                        AgentCommandRabbitConsumer.class)
                .withPropertyValues(properties);
    }

    private static AgentConfirmedPublishRequest scopedRequest(
            AgentRabbitV2TopologyManifest manifest, boolean retry) {
        byte[] wire = ("{\"schemaVersion\":2,\"messageType\":\"command.dispatch\"," +
                "\"messageId\":\"msg-listener\",\"commandId\":\"cmd-listener\"," +
                "\"deliveryId\":\"41\"," +
                "\"tenantId\":\"1\",\"dataDomainId\":\"cyf\"," +
                "\"executionPoolId\":\"local-test\"," +
                "\"producerDeploymentId\":\"producer-a\",\"admissionEpoch\":13," +
                "\"moduleId\":\"agent-command\",\"migrationEpoch\":17," +
                "\"clientId\":\"client-a\",\"taskId\":\"task-a\"," +
                "\"targetAgentId\":\"agent-a\",\"commandType\":\"task.invite\"," +
                "\"attempt\":1,\"expiresAt\":9999999," +
                "\"payload\":{\"opaque\":true}}")
                .getBytes(StandardCharsets.UTF_8);
        AgentRabbitV2TopologyManifest.PublishRoute route = retry
                ? new AgentRabbitV2TopologyManifest.PublishRoute(
                        manifest.deadLetterExchange(), manifest.retry5sRoutingKey())
                : manifest.defaultCommandPublishRoute();
        return new AgentConfirmedPublishRequest(route.destination(), route.routingKey(), wire,
                AgentCommandAmqpContract.sha256(wire), "msg-listener", "evt-listener", 41L,
                "cmd-listener", "1", "cyf", "local-test", 13L, "producer-a",
                "agent-command", 17L, AgentCommandAmqpContract.WIRE_VERSION,
                "client-a", "task-a", "agent-a", "task.invite", 1,
                9_999_999L, manifest.sha256(), retry ? 1 : 0);
    }

    private static void assertNoTopologyMutations(RabbitAdmin admin) {
        verify(admin, never()).declareExchange(any());
        verify(admin, never()).declareQueue(any());
        verify(admin, never()).declareBinding(any());
        verify(admin, never()).deleteExchange(any());
        verify(admin, never()).deleteQueue(any());
    }

    private static Throwable rootCause(Throwable failure) {
        assertNotNull(failure);
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AgentRabbitSafetyProperties.class,
            AgentRabbitDispatchScopeProperties.class})
    static class BaseDependencies {
        @Bean
        AgentRabbitSafetyGate safetyGate(AgentRabbitSafetyProperties properties,
                AgentRabbitDispatchScopeProperties scopes) {
            return new AgentRabbitSafetyGate(properties, scopes);
        }

        @Bean(name = "agentCommandConsumerV2SchemaGate")
        AgentCommandConsumerV2SchemaGate consumerSchemaGate() {
            return mock(AgentCommandConsumerV2SchemaGate.class);
        }

        @Bean(name = "agentOutboxRelayV2SchemaGate")
        AgentOutboxRelayV2SchemaGate relaySchemaGate() {
            return mock(AgentOutboxRelayV2SchemaGate.class);
        }

        @Bean
        AgentCommandInboxV2Dao inboxDao() {
            return mock(AgentCommandInboxV2Dao.class);
        }

        @Bean(name = "agentOutboxWorkerScope")
        WorkerScope workerScope() {
            return SCOPE;
        }

        @Bean(name = "agentOutboxTenantModuleAdmissionGuard")
        TenantModuleAdmissionGuard admissionGuard() {
            return mock(TenantModuleAdmissionGuard.class);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return mock(PlatformTransactionManager.class);
        }

        @Bean(name = "agentRabbitConnectionFactory")
        ConnectionFactory connectionFactory() {
            return mock(ConnectionFactory.class);
        }

    }

    @Configuration(proxyBeanMethods = false)
    static class ConsumerDependencies extends BaseDependencies {
        @Bean(name="agentRuntimeV2DispatchService",defaultCandidate=false)
        AgentRuntimeV2DispatchService durableDispatch() {return mock(AgentRuntimeV2DispatchService.class);}
        @Bean(name = "agentConfirmedRabbitPublisher")
        AgentConfirmedRabbitPublisher publisher(
                @org.springframework.beans.factory.annotation.Qualifier("agentRabbitTemplate")
                RabbitTemplate template,
                AgentRabbitSafetyGate gate,
                @org.springframework.beans.factory.annotation.Qualifier("agentRabbitTopologyManifest")
                AgentRabbitTopologyManifest manifest,
                @org.springframework.beans.factory.annotation.Qualifier("agentRabbitTopologyReadiness")
                AgentRabbitTopologyReadiness readiness,
                @org.springframework.beans.factory.annotation.Qualifier("agentRabbitV2TopologyReadiness")
                AgentRabbitV2TopologyReadiness v2Readiness) {
            return new AgentConfirmedRabbitPublisherImpl(
                    template, gate, manifest, readiness, v2Readiness);
        }

        @Bean(name = "agentRabbitTemplate")
        RabbitTemplate rabbitTemplate() {
            RabbitTemplate template = mock(RabbitTemplate.class);
            doAnswer(invocation -> {
                invocation.<CorrelationData>getArgument(3).getFuture()
                        .complete(new CorrelationData.Confirm(true, null));
                return null;
            }).when(template).send(any(), any(), any(), any(CorrelationData.class));
            return template;
        }

        @Bean(name = "agentRabbitTopologyManifest")
        AgentRabbitTopologyManifest legacyManifest() {
            return AgentRabbitTopologyManifest.canonical();
        }

        @Bean(name = "agentRabbitTopologyReadiness")
        AgentRabbitTopologyReadiness legacyReadiness() {
            AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
            return new AgentRabbitTopologyConfiguration()
                    .agentRabbitTopologyReadiness(manifest);
        }

        @Bean(name = "agentOutboxRelaySettings")
        AgentOutboxRelaySettings relaySettings() {
            return new AgentOutboxRelaySettings(
                    new AgentRabbitSafetyProperties.RabbitPublish(true));
        }

        @Bean
        AgentCommandV2RuntimeAuthority runtimeAuthority() {
            return mock(AgentCommandV2RuntimeAuthority.class);
        }

        @Bean
        AgentCommandV2RuntimeGateway runtimeGateway() {
            return mock(AgentCommandV2RuntimeGateway.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CanonicalDependencies extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(RabbitAdmin admin,
                ConnectionFactory connectionFactory,
                AgentRabbitV2TopologyReadiness readiness) {
            AgentRabbitV2TopologyManifest manifest =
                    AgentRabbitV2TopologyManifest.forScope(SCOPE);
            assertEquals(AgentRabbitV2TopologyReadiness.Status.NOT_CHECKED,
                    readiness.snapshot(manifest).status());
            return new AgentRabbitV2TopologyProvisioner(
                    admin, connectionFactory, readiness);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PassiveDependencies extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            AgentRabbitV2TopologyReadiness readiness = new AgentRabbitV2TopologyReadiness();
            readiness.markExistenceConfirmed(AgentRabbitV2TopologyManifest.forScope(SCOPE));
            return readiness;
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(
                AgentRabbitV2TopologyReadiness readiness) {
            AgentRabbitV2TopologyProvisioner provisioner =
                    mock(AgentRabbitV2TopologyProvisioner.class);
            AgentRabbitV2TopologyManifest manifest =
                    AgentRabbitV2TopologyManifest.forScope(SCOPE);
            when(provisioner.provision(any())).thenReturn(readiness.snapshot(manifest));
            return provisioner;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ReadinessWithoutProvisioner extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingProvisionDependencies extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(RabbitAdmin admin,
                ConnectionFactory connectionFactory,
                AgentRabbitV2TopologyReadiness readiness) {
            doThrow(new IllegalStateException("declaration failed"))
                    .when(admin).declareExchange(any());
            return new AgentRabbitV2TopologyProvisioner(
                    admin, connectionFactory, readiness);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ScopeMismatchDependencies extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner() {
            AgentRabbitV2TopologyProvisioner provisioner =
                    mock(AgentRabbitV2TopologyProvisioner.class);
            AgentRabbitV2TopologyManifest foreign = AgentRabbitV2TopologyManifest.forScope(
                    TenantId.of("2"), DataDomainId.of("cyf"), "local-test");
            AgentRabbitV2TopologyReadiness.Snapshot snapshot =
                    new AgentRabbitV2TopologyReadiness.Snapshot(
                            AgentRabbitV2TopologyReadiness.Status.READY,
                            AgentRabbitV2TopologyReadiness.Source.PROVISION,
                            AgentRabbitV2TopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                            foreign.scope(), foreign.sha256(), 1L, null);
            when(provisioner.provision(any())).thenReturn(snapshot);
            return provisioner;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DigestMismatchDependencies extends ConsumerDependencies {
        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner() {
            AgentRabbitV2TopologyProvisioner provisioner =
                    mock(AgentRabbitV2TopologyProvisioner.class);
            AgentRabbitV2TopologyManifest manifest =
                    AgentRabbitV2TopologyManifest.forScope(SCOPE);
            AgentRabbitV2TopologyReadiness.Snapshot snapshot =
                    new AgentRabbitV2TopologyReadiness.Snapshot(
                            AgentRabbitV2TopologyReadiness.Status.READY,
                            AgentRabbitV2TopologyReadiness.Source.PROVISION,
                            AgentRabbitV2TopologyReadiness.Coverage.CANONICAL_TOPOLOGY,
                            manifest.scope(), "0".repeat(64), 1L, null);
            when(provisioner.provision(any())).thenReturn(snapshot);
            return provisioner;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingExecutionPortDependencies extends BaseDependencies {
        @Bean(name = "agentConfirmedRabbitPublisher")
        AgentConfirmedRabbitPublisher publisher() {
            return mock(AgentConfirmedRabbitPublisher.class);
        }

        @Bean(name = "agentOutboxRelaySettings")
        AgentOutboxRelaySettings relaySettings() {
            return new AgentOutboxRelaySettings(
                    new AgentRabbitSafetyProperties.RabbitPublish(true));
        }

        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(RabbitAdmin admin,
                ConnectionFactory connectionFactory,
                AgentRabbitV2TopologyReadiness readiness) {
            return new AgentRabbitV2TopologyProvisioner(admin, connectionFactory, readiness);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingPublisherDependencies extends BaseDependencies {
        @Bean(name = "agentOutboxRelaySettings")
        AgentOutboxRelaySettings relaySettings() {
            return new AgentOutboxRelaySettings(
                    new AgentRabbitSafetyProperties.RabbitPublish(true));
        }

        @Bean
        AgentCommandV2RuntimeAuthority runtimeAuthority() {
            return mock(AgentCommandV2RuntimeAuthority.class);
        }

        @Bean
        AgentCommandV2RuntimeGateway runtimeGateway() {
            return mock(AgentCommandV2RuntimeGateway.class);
        }

        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(RabbitAdmin admin,
                ConnectionFactory connectionFactory,
                AgentRabbitV2TopologyReadiness readiness) {
            return new AgentRabbitV2TopologyProvisioner(admin, connectionFactory, readiness);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingRelaySettingsDependencies extends BaseDependencies {
        @Bean(name = "agentConfirmedRabbitPublisher")
        AgentConfirmedRabbitPublisher publisher() {
            return mock(AgentConfirmedRabbitPublisher.class);
        }

        @Bean
        AgentCommandV2RuntimeAuthority runtimeAuthority() {
            return mock(AgentCommandV2RuntimeAuthority.class);
        }

        @Bean
        AgentCommandV2RuntimeGateway runtimeGateway() {
            return mock(AgentCommandV2RuntimeGateway.class);
        }

        @Bean(name = "agentRabbitV2TopologyReadiness")
        AgentRabbitV2TopologyReadiness readiness() {
            return new AgentRabbitV2TopologyReadiness();
        }

        @Bean(name = "agentRabbitV2TopologyProvisioner")
        AgentRabbitV2TopologyProvisioner provisioner(RabbitAdmin admin,
                ConnectionFactory connectionFactory,
                AgentRabbitV2TopologyReadiness readiness) {
            return new AgentRabbitV2TopologyProvisioner(admin, connectionFactory, readiness);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NoStartContainerPostProcessor {
        @Bean
        static BeanPostProcessor noStartV2Container() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName)
                        throws BeansException {
                    if (bean instanceof SimpleMessageListenerContainer container
                            && "agentCommandV2ListenerContainer".equals(beanName)) {
                        container.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }
}
