package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.dao.AgentCommandRecoveryDao;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import cn.jia.agent.service.AgentCommandReconnectSignal;
import cn.jia.agent.service.AgentCommandOperationsService;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentRawCommandDispatcher;
import cn.jia.agent.service.impl.AgentCommandDualLaneReissueService;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;
import cn.jia.core.tenant.TenantModuleAdmissionGuard;
import cn.jia.core.tenant.WorkerScope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentCommandOperationsConfigurationTest {
    @Test
    void allFlagsOffRegistersNoOperationsServiceOrDao() {
        runner(outboxGate()).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(AgentCommandOperationsService.class).isEmpty());
            assertTrue(context.getBeansOfType(AgentCommandOperationsDao.class).isEmpty());
            assertFalse(context.containsBean("agentCommandOperationsService"));
            assertFalse(context.containsBean("agentCommandOperationsDao"));
        });
    }

    @Test
    void readEnabledRequiresCommandOutboxAndCreatesNoBrokerClient() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        runner(offGate()).withBean("agentRabbitConnectionFactory",
                        ConnectionFactory.class, () -> connectionFactory)
                .withPropertyValues("agent.rabbit-operations.read-enabled=true")
                .run(context -> assertFailure(context.getStartupFailure(),
                        "read operations require command outbox"));
        verify(connectionFactory, never()).createConnection();

        runner(outboxGate()).withBean("agentRabbitConnectionFactory",
                        ConnectionFactory.class, () -> connectionFactory)
                .withPropertyValues("agent.rabbit-operations.read-enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(
                            AgentCommandOperationsService.class).size());
                    assertEquals(1, context.getBeansOfType(
                            AgentCommandOperationsDao.class).size());
                });
        verify(connectionFactory, never()).createConnection();
    }

    @Test
    void brokerRedriveCreatesDedicatedBoundedNonRecoveringInfrastructure() {
        String[] flags = {
                "agent.rabbit-operations.read-enabled=true",
                "agent.rabbit-operations.redrive-enabled=true"};
        runner(dispatchGate()).withPropertyValues(flags)
                .run(context -> assertNotNull(context.getStartupFailure()));

        redriveRunner(dispatchGate()).withPropertyValues(flags).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.containsBean("agentCommandRedriveOneShotRawFactory"));
            assertTrue(context.containsBean("agentCommandRedriveRawProvider"));
            assertFalse(context.containsBean("agentRedriveRabbitConnectionFactory"));
            assertFalse(context.containsBean("agentRedriveRabbitTemplate"));
            assertFalse(context.containsBean("agentRedriveConfirmedRabbitPublisher"));
            assertTrue(context.containsBean("agentCommandRedriveRabbitSettings"));
            com.rabbitmq.client.ConnectionFactory raw = context.getBean(
                    "agentCommandRedriveOneShotRawFactory",
                    com.rabbitmq.client.ConnectionFactory.class);
            com.rabbitmq.client.ConnectionFactory canonical = context.getBean(
                    "agentRabbitCanonicalRawConnectionFactory",
                    com.rabbitmq.client.ConnectionFactory.class);
            assertEquals(2_000, raw.getConnectionTimeout());
            assertEquals(2_000, raw.getHandshakeTimeout());
            assertEquals(2_000, raw.getChannelRpcTimeout());
            assertEquals(1_000, raw.getShutdownTimeout());
            assertFalse(raw.isAutomaticRecoveryEnabled());
            assertFalse(raw.isTopologyRecoveryEnabled());
            AgentRabbitRawConnectionFactoryBuilder builder = context.getBean(
                    "agentRabbitRawConnectionFactoryBuilder",
                    AgentRabbitRawConnectionFactoryBuilder.class);
            assertEquals(builder.configurationFingerprint(canonical),
                    builder.configurationFingerprint(raw));
            assertEquals(1, context.getBeansOfType(
                    AgentCommandOperationsService.class).size());
        });

        redriveRunner(outboxGate()).withPropertyValues(flags)
                .run(context -> assertFailure(context.getStartupFailure(),
                        "write operations require scoped Rabbit dispatch activation"));
    }

    @Test
    void redriveConfirmTimeoutAboveDedicatedBudgetFailsStartup() {
        redriveRunner(dispatchGate()).withPropertyValues(
                        "agent.rabbit-operations.read-enabled=true",
                        "agent.rabbit-operations.redrive-enabled=true",
                        "agent.rabbit-operations.confirm-timeout-millis=10001")
                .run(context -> assertFailure(context.getStartupFailure(),
                        "Invalid D09 redrive Rabbit timeout budget"));
    }

    @Test
    void manualReissueRequiresFinalD06Service() {
        String[] flags = {
                "agent.rabbit-operations.read-enabled=true",
                "agent.rabbit-operations.reissue-enabled=true"};
        runner(dispatchGate()).withPropertyValues(flags)
                .run(context -> assertFailure(context.getStartupFailure(),
                        "manual reissue requires D06 recovery service"));

        runner(dispatchGate()).withBean(AgentCommandReissueService.class,
                        () -> mock(AgentCommandReissueService.class))
                .withPropertyValues(flags)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(
                            AgentCommandOperationsService.class).size());
                });
    }

    @Test
    void productionRecoveryAndOperationsShareOneDualLaneService() {
        combinedRunner(dispatchGate(), true, true).run(context -> {
            assertNull(context.getStartupFailure());
            AgentCommandDualLaneReissueService dual = context.getBean(
                    AgentCommandDualLaneReissueService.class);
            assertNotNull(dual.legacyLane());
            assertNotNull(dual.scopedLane());
            assertEquals(1, context.getBeansOfType(AgentCommandReissueService.class).size());
            assertTrue(context.getBean(AgentCommandReissueService.class) == dual);
            assertEquals(1, context.getBeansOfType(
                    AgentCommandOperationsService.class).size());
            assertEquals(1, context.getBeansOfType(
                    AgentCommandReconnectSignal.class).size());
        });
    }

    @Test
    void productionRecoveryIsLegacyOnlyWithoutAuthorityAndRejectsHalfAuthority() {
        combinedRunner(dispatchGate(), false, false).run(context -> {
            assertNull(context.getStartupFailure());
            AgentCommandDualLaneReissueService dual = context.getBean(
                    AgentCommandDualLaneReissueService.class);
            assertNotNull(dual.legacyLane());
            assertNull(dual.scopedLane());
        });

        combinedRunner(dispatchGate(), true, false).run(context ->
                assertFailure(context.getStartupFailure(),
                        "recovery worker authority is incomplete"));
        combinedRunner(dispatchGate(), false, true).run(context ->
                assertFailure(context.getStartupFailure(),
                        "recovery worker authority is incomplete"));
    }

    @Test
    void protocolV2DispatchDoesNotStartLegacyRecoveryCoordinator() {
        recoveryRunner()
                .withPropertyValues(
                        "agent.rabbit-dispatch.enabled=true",
                        "agent.rabbit-consume.enabled=false",
                        "agent.rabbit-consume-v2.enabled=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(
                            AgentCommandReissueService.class).isEmpty());
                    assertTrue(context.getBeansOfType(
                            AgentCommandReconnectSignal.class).isEmpty());
                });
    }

    @Test
    void malformedFlagsAndBoundsFailClosed() {
        runner(outboxGate()).withPropertyValues(
                        "agent.rabbit-operations.read-enabled=maybe")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(failureChain(context.getStartupFailure())
                            .contains("agent.rabbit-operations"));
                });
        runner(outboxGate()).withPropertyValues(
                        "agent.rabbit-operations.read-enabled=true",
                        "agent.rabbit-operations.max-page-size=201")
                .run(context -> assertFailure(context.getStartupFailure(),
                        "Invalid D09 Rabbit operations bounds"));
        runner(outboxGate()).withPropertyValues(
                        "agent.rabbit-operations.read-enabled=false",
                        "agent.rabbit-operations.redrive-enabled=true")
                .run(context -> assertFailure(context.getStartupFailure(),
                        "write operations require read-enabled"));
    }

    private ApplicationContextRunner runner(AgentRabbitSafetyGate gate) {
        return new ApplicationContextRunner()
                .withBean(AgentCommandOperationsMapper.class,
                        () -> mock(AgentCommandOperationsMapper.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(AgentRabbitSafetyGate.class, () -> gate)
                .withUserConfiguration(AgentCommandOperationsConfiguration.class);
    }

    private ApplicationContextRunner redriveRunner(AgentRabbitSafetyGate gate) {
        AgentRabbitTopologyManifest manifest = AgentRabbitTopologyManifest.canonical();
        return runner(gate)
                .withBean("agentRabbitRawConnectionFactoryBuilder",
                        AgentRabbitRawConnectionFactoryBuilder.class,
                        AgentRabbitRawConnectionFactoryBuilder::new)
                .withBean("agentRabbitCanonicalRawConnectionFactory",
                        com.rabbitmq.client.ConnectionFactory.class, () ->
                                new AgentRabbitRawConnectionFactoryBuilder().canonical(
                                        new AgentRabbitBrokerSettings(
                                                new AgentRabbitSafetyProperties.RabbitBroker(
                                                        "isolated.invalid", 35672, "user",
                                                        "secret", "/isolated"))))
                .withBean(AgentRabbitBrokerSettings.class, () ->
                        new AgentRabbitBrokerSettings(
                                new AgentRabbitSafetyProperties.RabbitBroker(
                                        "isolated.invalid", 35672, "user", "secret", "/isolated")))
                .withBean("agentRabbitTopologyManifest", AgentRabbitTopologyManifest.class,
                        () -> manifest)
                .withBean("agentRabbitTopologyReadiness", AgentRabbitTopologyReadiness.class,
                        () -> new AgentRabbitTopologyReadiness(manifest))
                .withBean("agentRabbitV2TopologyReadiness",
                        AgentRabbitV2TopologyReadiness.class,
                        AgentRabbitV2TopologyReadiness::new)
                .withBean("agentOutboxWorkerScope", WorkerScope.class, this::workerScope)
                .withBean("agentOutboxTenantModuleAdmissionGuard",
                        TenantModuleAdmissionGuard.class,
                        () -> mock(TenantModuleAdmissionGuard.class));
    }

    private ApplicationContextRunner combinedRunner(
            AgentRabbitSafetyGate gate, boolean workerScope, boolean admissionGuard) {
        ApplicationContextRunner combined = runner(gate)
                .withUserConfiguration(AgentCommandRecoveryConfiguration.class)
                .withBean(AgentCommandRecoveryDao.class,
                        () -> mock(AgentCommandRecoveryDao.class))
                .withBean(AgentRawCommandDispatcher.class,
                        () -> mock(AgentRawCommandDispatcher.class))
                .withBean("agentRabbitTopologyManifest", AgentRabbitTopologyManifest.class,
                        AgentRabbitTopologyManifest::canonical)
                .withPropertyValues(
                        "agent.rabbit-dispatch.enabled=true",
                        "agent.rabbit-consume.enabled=true",
                        "agent.rabbit-consume-v2.enabled=false",
                        "agent.rabbit-operations.read-enabled=true",
                        "agent.rabbit-operations.reissue-enabled=true");
        if (workerScope) {
            combined = combined.withBean("agentOutboxWorkerScope", WorkerScope.class,
                    this::workerScope);
        }
        if (admissionGuard) {
            combined = combined.withBean("agentOutboxTenantModuleAdmissionGuard",
                    TenantModuleAdmissionGuard.class,
                    () -> mock(TenantModuleAdmissionGuard.class));
        }
        return combined;
    }

    private ApplicationContextRunner recoveryRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(AgentCommandRecoveryConfiguration.class)
                .withBean(AgentCommandRecoveryDao.class,
                        () -> mock(AgentCommandRecoveryDao.class))
                .withBean(AgentRawCommandDispatcher.class,
                        () -> mock(AgentRawCommandDispatcher.class))
                .withBean(AgentRabbitSafetyGate.class, this::dispatchGate)
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean("agentRabbitTopologyManifest", AgentRabbitTopologyManifest.class,
                        AgentRabbitTopologyManifest::canonical);
    }

    private WorkerScope workerScope() {
        return new WorkerScope(TenantId.of("1"), DataDomainId.of("cyf"),
                "pool-a", "deployment-a", 7, 11, 13, "agent-command", 17);
    }

    private AgentRabbitSafetyGate offGate() {
        return gate(false, false);
    }

    private AgentRabbitSafetyGate outboxGate() {
        return gate(true, false);
    }

    private AgentRabbitSafetyGate dispatchGate() {
        return gate(true, true);
    }

    private AgentRabbitSafetyGate gate(boolean outbox, boolean dispatch) {
        return new AgentRabbitSafetyGate(new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(outbox),
                new AgentRabbitSafetyProperties.RabbitTopology(dispatch),
                new AgentRabbitSafetyProperties.RabbitPublish(dispatch),
                new AgentRabbitSafetyProperties.RabbitConsume(dispatch),
                new AgentRabbitSafetyProperties.RabbitDispatch(dispatch),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "isolated.invalid", 35672, "user", "secret", "/isolated")),
                dispatch ? new AgentRabbitDispatchScopeProperties(List.of(
                        new AgentRabbitDispatchScopeProperties.AllowedScope(
                                "tenant-a", "client-a"))) : null);
    }

    private void assertFailure(Throwable failure, String expected) {
        assertNotNull(failure);
        assertTrue(failureChain(failure).contains(expected), failureChain(failure));
    }

    private String failureChain(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            chain.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
        }
        return chain.toString();
    }
}
