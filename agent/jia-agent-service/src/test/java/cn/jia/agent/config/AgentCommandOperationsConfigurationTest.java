package cn.jia.agent.config;

import cn.jia.agent.dao.AgentCommandOperationsDao;
import cn.jia.agent.mapper.AgentCommandOperationsMapper;
import cn.jia.agent.service.AgentCommandOperationsService;
import cn.jia.agent.service.AgentCommandReissueService;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
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
    void brokerRedriveRequiresScopedDispatchPublisherAndDedicatedConnectionFactory() {
        String[] flags = {
                "agent.rabbit-operations.read-enabled=true",
                "agent.rabbit-operations.redrive-enabled=true"};
        runner(dispatchGate()).withPropertyValues(flags)
                .run(context -> assertFailure(context.getStartupFailure(),
                        "broker redrive requires dedicated Rabbit publish infrastructure"));

        runner(dispatchGate()).withBean("agentConfirmedRabbitPublisher",
                        AgentConfirmedRabbitPublisher.class,
                        () -> mock(AgentConfirmedRabbitPublisher.class))
                .withPropertyValues(flags)
                .run(context -> assertFailure(context.getStartupFailure(),
                        "broker redrive requires dedicated Rabbit publish infrastructure"));

        runner(outboxGate()).withBean("agentConfirmedRabbitPublisher",
                        AgentConfirmedRabbitPublisher.class,
                        () -> mock(AgentConfirmedRabbitPublisher.class))
                .withBean("agentRabbitConnectionFactory", ConnectionFactory.class,
                        () -> mock(ConnectionFactory.class))
                .withPropertyValues(flags)
                .run(context -> assertFailure(context.getStartupFailure(),
                        "write operations require scoped Rabbit dispatch activation"));
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
