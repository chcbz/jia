package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRabbitSafetyConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentRabbitSafetyConfiguration.class);

    @Test
    void absentConfigurationIsOffAndRegistersNoConditionalBrokerBoundary() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            AgentRabbitSafetyGate gate = context.getBean(AgentRabbitSafetyGate.class);
            assertEquals(AgentRabbitActivationState.OFF, gate.state());
            assertFalse(gate.brokerRequired());
            assertEquals(0, gate.dispatchScopeCount());
            assertEquals(AgentRabbitActivationState.OFF,
                    context.getBean(AgentRabbitReadiness.class).state());
            assertFalse(context.containsBean("agentRabbitBrokerSettings"));
            assertSame(gate, context.getBean(AgentRabbitSafetyGate.class));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legalStates")
    void legalConfigurationsResolveFrozenStateMatrix(LegalCase legal) {
        RUNNER.withPropertyValues(legal.properties().toArray(String[]::new))
                .run(context -> {
                    assertNull(context.getStartupFailure(), legal.name());
                    AgentRabbitSafetyGate gate = context.getBean(AgentRabbitSafetyGate.class);
                    assertEquals(legal.state(), gate.state());
                    assertEquals(legal.brokerRequired(), gate.brokerRequired());
                    assertEquals(legal.state(),
                            context.getBean(AgentRabbitReadiness.class).state());
                    assertEquals(legal.brokerRequired(),
                            context.containsBean("agentRabbitBrokerSettings"));
                });
    }

    @Test
    void consumeDependsOnTopologyButNotOnOutbox() {
        RUNNER.withPropertyValues(concat(
                broker(),
                "agent.rabbit-topology.enabled=true",
                "agent.rabbit-consume.enabled=true"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentRabbitSafetyGate gate = context.getBean(AgentRabbitSafetyGate.class);
                    assertEquals(AgentRabbitActivationState.MQ_SHADOW, gate.state());
                    assertFalse(gate.commandOutboxEnabled());
                    assertTrue(gate.rabbitConsumeEnabled());
                });
    }

    @Test
    void dispatchScopeMatchingIsByteExactWithoutCaseFoldOrUnicodeNormalization() {
        String composed = "tenant-\u00e9";
        RUNNER.withPropertyValues(concat(
                broker(),
                fullDispatch(),
                "agent.rabbit-dispatch.allowed-scopes[0].tenant-id=" + composed,
                "agent.rabbit-dispatch.allowed-scopes[0].client-id=Client-A"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentRabbitSafetyGate gate = context.getBean(AgentRabbitSafetyGate.class);
                    assertEquals(AgentRabbitActivationState.DISPATCH_CANARY, gate.state());
                    assertTrue(gate.allowsDispatch(composed, "Client-A"));
                    assertFalse(gate.allowsDispatch("tenant-\u00e9 ", "Client-A"));
                    assertFalse(gate.allowsDispatch("tenant-e\u0301", "Client-A"));
                    assertFalse(gate.allowsDispatch(composed, "client-a"));
                });
    }

    @Test
    void directGatePreservesConfiguredScopeBytesWithoutTrimming() {
        AgentRabbitSafetyProperties properties = new AgentRabbitSafetyProperties(
                new AgentRabbitSafetyProperties.CommandOutbox(true),
                new AgentRabbitSafetyProperties.RabbitTopology(true),
                new AgentRabbitSafetyProperties.RabbitPublish(true),
                new AgentRabbitSafetyProperties.RabbitConsume(true),
                new AgentRabbitSafetyProperties.RabbitDispatch(true, List.of(
                        new AgentRabbitSafetyProperties.AllowedScope(
                                " tenant-a ", " client-a "))),
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "m3-rabbit.internal", 35672, "m3-user", "m3-pass", "/m3-test"));

        AgentRabbitSafetyGate gate = new AgentRabbitSafetyGate(properties);

        assertTrue(gate.allowsDispatch(" tenant-a ", " client-a "));
        assertFalse(gate.allowsDispatch("tenant-a", "client-a"));
    }

    @Test
    void runtimeEnvironmentMutationCannotRefreshGateOrRegisterConditionalBeans() {
        RUNNER.run(context -> {
            AgentRabbitSafetyGate gate = context.getBean(AgentRabbitSafetyGate.class);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "runtime-attempt", Map.of(
                            "agent.command-outbox.enabled", "true",
                            "agent.rabbit-topology.enabled", "true",
                            "agent.rabbit-publish.enabled", "true",
                            "agent.rabbit-consume.enabled", "true",
                            "agent.rabbit-dispatch.enabled", "true")));
            assertEquals(AgentRabbitActivationState.OFF, gate.state());
            assertFalse(gate.allowsDispatch("tenant-a", "client-a"));
            assertFalse(context.containsBean("agentRabbitBrokerSettings"));
        });
    }

    @Test
    void springRabbitmqCannotSatisfyDedicatedM3BrokerBoundary() {
        RUNNER.withPropertyValues(
                "agent.rabbit-topology.enabled=true",
                "spring.rabbitmq.host=container-only",
                "spring.rabbitmq.port=35672",
                "spring.rabbitmq.username=test",
                "spring.rabbitmq.password=test",
                "spring.rabbitmq.virtual-host=/m3-test")
                .run(context -> assertStartupFailure(context.getStartupFailure(),
                        "dedicated agent.rabbit-broker"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void invalidCombinationsFailContextStartup(InvalidCase invalid) {
        RUNNER.withPropertyValues(invalid.properties().toArray(String[]::new))
                .run(context -> assertStartupFailure(
                        context.getStartupFailure(), invalid.expectedMessage()));
    }

    @Test
    void gatePropertiesReadinessAndBrokerSettingsExposeNoMutationApi() {
        AgentRabbitSafetyProperties.RabbitBroker broker =
                new AgentRabbitSafetyProperties.RabbitBroker(
                        "host", 35672, "secret-user", "secret-password", "/m3");
        assertFalse(broker.toString().contains("secret-user"));
        assertFalse(broker.toString().contains("secret-password"));

        for (Class<?> type : List.of(
                AgentRabbitSafetyProperties.class,
                AgentRabbitSafetyGate.class,
                AgentRabbitReadiness.class,
                AgentRabbitBrokerSettings.class)) {
            assertEquals(0, Arrays.stream(type.getMethods())
                    .filter(method -> method.getName().startsWith("set"))
                    .count(), type.getName());
            for (var field : type.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    assertTrue(Modifier.isFinal(field.getModifiers()),
                            type.getName() + "." + field.getName());
                }
            }
        }
    }

    private static Stream<LegalCase> legalStates() {
        return Stream.of(
                legal("explicit all-off", AgentRabbitActivationState.OFF, false,
                        allFlagsFalse()),
                legal("outbox only", AgentRabbitActivationState.DB_SHADOW, false,
                        "agent.command-outbox.enabled=true"),
                legal("topology shadow", AgentRabbitActivationState.MQ_SHADOW, true,
                        concat(broker(), "agent.rabbit-topology.enabled=true")),
                legal("publish shadow", AgentRabbitActivationState.MQ_SHADOW, true,
                        concat(broker(),
                                "agent.command-outbox.enabled=true",
                                "agent.rabbit-topology.enabled=true",
                                "agent.rabbit-publish.enabled=true")),
                legal("dispatch canary", AgentRabbitActivationState.DISPATCH_CANARY, true,
                        concat(broker(), fullDispatch(),
                                "agent.rabbit-dispatch.allowed-scopes[0].tenant-id=tenant-a",
                                "agent.rabbit-dispatch.allowed-scopes[0].client-id=client-a")),
                legal("dispatch scoped", AgentRabbitActivationState.DISPATCH_SCOPED, true,
                        concat(broker(), fullDispatch(),
                                "agent.rabbit-dispatch.allowed-scopes[0].tenant-id=tenant-a",
                                "agent.rabbit-dispatch.allowed-scopes[0].client-id=client-a",
                                "agent.rabbit-dispatch.allowed-scopes[1].tenant-id=tenant-b",
                                "agent.rabbit-dispatch.allowed-scopes[1].client-id=client-b")));
    }

    private static Stream<InvalidCase> invalidConfigurations() {
        String scope = "agent.rabbit-dispatch.allowed-scopes[0].";
        return Stream.of(
                invalid("publish missing outbox", "rabbit publish requires",
                        concat(broker(),
                                "agent.rabbit-topology.enabled=true",
                                "agent.rabbit-publish.enabled=true")),
                invalid("publish missing topology", "rabbit publish requires",
                        concat(broker(),
                                "agent.command-outbox.enabled=true",
                                "agent.rabbit-publish.enabled=true")),
                invalid("consume missing topology", "rabbit consume requires",
                        concat(broker(), "agent.rabbit-consume.enabled=true")),
                invalid("dispatch missing publish", "dispatch prerequisites",
                        concat(broker(),
                                "agent.command-outbox.enabled=true",
                                "agent.rabbit-topology.enabled=true",
                                "agent.rabbit-consume.enabled=true",
                                "agent.rabbit-dispatch.enabled=true",
                                scope + "tenant-id=tenant-a",
                                scope + "client-id=client-a")),
                invalid("dispatch missing consume", "dispatch prerequisites",
                        concat(broker(),
                                "agent.command-outbox.enabled=true",
                                "agent.rabbit-topology.enabled=true",
                                "agent.rabbit-publish.enabled=true",
                                "agent.rabbit-dispatch.enabled=true",
                                scope + "tenant-id=tenant-a",
                                scope + "client-id=client-a")),
                invalid("dispatch empty allowlist", "dispatch prerequisites",
                        concat(broker(), fullDispatch())),
                invalid("blank tenant", "exact non-blank ids",
                        concat(broker(), fullDispatch(),
                                scope + "tenant-id=\u00a0",
                                scope + "client-id=client-a")),
                invalid("missing client", "exact non-blank ids",
                        concat(broker(), fullDispatch(),
                                scope + "tenant-id=tenant-a")),
                invalid("duplicate exact tuple", "unique exact tuples",
                        concat(broker(), fullDispatch(),
                                scope + "tenant-id=tenant-a",
                                scope + "client-id=client-a",
                                "agent.rabbit-dispatch.allowed-scopes[1].tenant-id=tenant-a",
                                "agent.rabbit-dispatch.allowed-scopes[1].client-id=client-a")),
                invalid("broker missing host", "dedicated agent.rabbit-broker",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-broker.port=35672",
                        "agent.rabbit-broker.username=m3-user",
                        "agent.rabbit-broker.password=m3-pass",
                        "agent.rabbit-broker.virtual-host=/m3-test"),
                invalid("broker missing username", "dedicated agent.rabbit-broker",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-broker.host=m3-rabbit.internal",
                        "agent.rabbit-broker.port=35672",
                        "agent.rabbit-broker.password=m3-pass",
                        "agent.rabbit-broker.virtual-host=/m3-test"),
                invalid("broker missing password", "dedicated agent.rabbit-broker",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-broker.host=m3-rabbit.internal",
                        "agent.rabbit-broker.port=35672",
                        "agent.rabbit-broker.username=m3-user",
                        "agent.rabbit-broker.virtual-host=/m3-test"),
                invalid("broker missing vhost", "dedicated agent.rabbit-broker",
                        "agent.rabbit-topology.enabled=true",
                        "agent.rabbit-broker.host=m3-rabbit.internal",
                        "agent.rabbit-broker.port=35672",
                        "agent.rabbit-broker.username=m3-user",
                        "agent.rabbit-broker.password=m3-pass"),
                invalid("broker invalid port", "dedicated agent.rabbit-broker",
                        concat(broker(),
                                "agent.rabbit-broker.port=0",
                                "agent.rabbit-topology.enabled=true")),
                invalid("broker default vhost", "dedicated agent.rabbit-broker",
                        concat(broker(),
                                "agent.rabbit-broker.virtual-host=/",
                                "agent.rabbit-topology.enabled=true")));
    }

    private static String[] allFlagsFalse() {
        return new String[] {
                "agent.command-outbox.enabled=false",
                "agent.rabbit-topology.enabled=false",
                "agent.rabbit-publish.enabled=false",
                "agent.rabbit-consume.enabled=false",
                "agent.rabbit-dispatch.enabled=false"
        };
    }

    private static String[] fullDispatch() {
        return new String[] {
                "agent.command-outbox.enabled=true",
                "agent.rabbit-topology.enabled=true",
                "agent.rabbit-publish.enabled=true",
                "agent.rabbit-consume.enabled=true",
                "agent.rabbit-dispatch.enabled=true"
        };
    }

    private static String[] broker() {
        return new String[] {
                "agent.rabbit-broker.host=m3-rabbit.internal",
                "agent.rabbit-broker.port=35672",
                "agent.rabbit-broker.username=m3-user",
                "agent.rabbit-broker.password=m3-pass",
                "agent.rabbit-broker.virtual-host=/m3-test"
        };
    }

    private static String[] concat(String[] first, String... rest) {
        String[] result = Arrays.copyOf(first, first.length + rest.length);
        System.arraycopy(rest, 0, result, first.length, rest.length);
        return result;
    }

    private static String[] concat(String[] first, String[] second, String... rest) {
        return concat(concat(first, second), rest);
    }

    private static LegalCase legal(String name, AgentRabbitActivationState state,
            boolean brokerRequired, String... properties) {
        return new LegalCase(name, state, brokerRequired, List.of(properties));
    }

    private static InvalidCase invalid(String name, String expectedMessage,
            String... properties) {
        return new InvalidCase(name, expectedMessage, List.of(properties));
    }

    private static void assertStartupFailure(Throwable failure, String expectedMessage) {
        assertNotNull(failure);
        assertTrue(failureChain(failure).contains(expectedMessage), failureChain(failure));
    }

    private static String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private record LegalCase(String name, AgentRabbitActivationState state,
                             boolean brokerRequired, List<String> properties) {
        @Override
        public String toString() {
            return name;
        }
    }

    private record InvalidCase(String name, String expectedMessage,
                               List<String> properties) {
        @Override
        public String toString() {
            return name;
        }
    }
}
