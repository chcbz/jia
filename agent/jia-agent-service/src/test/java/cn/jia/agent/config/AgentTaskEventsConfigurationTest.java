package cn.jia.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskEventsConfigurationTest {
    private static final ApplicationContextRunner RUNNER = new ApplicationContextRunner()
            .withUserConfiguration(AgentTaskEventsConfiguration.class);

    @Test
    void absentConfigurationStartsDisabledWithOneSharedImmutableGateBean() {
        RUNNER.run(context -> {
            assertNull(context.getStartupFailure());
            assertSame(context.getBean(AgentTaskEventsGate.class),
                    context.getBean(AgentTaskEventsGate.class));
            assertFalse(context.getBean(AgentTaskEventsGate.class)
                    .allows("tenant-a", "client-a"));
        });
    }

    @Test
    void externalEnableUsesOnlyExactConfiguredTuplesWithoutNormalization() {
        String composed = "tenant-\u00e9";
        String supplementary = "t" + new String(Character.toChars(0x1f642)).repeat(49);
        RUNNER.withPropertyValues(
                "agent.task-events.enabled=true",
                "agent.task-events.allowed-scopes[0].tenant-id=Tenant-A",
                "agent.task-events.allowed-scopes[0].client-id=Client-A",
                "agent.task-events.allowed-scopes[1].tenant-id=" + composed,
                "agent.task-events.allowed-scopes[1].client-id=client-b",
                "agent.task-events.allowed-scopes[2].tenant-id=" + supplementary,
                "agent.task-events.allowed-scopes[2].client-id=client-c",
                "agent.task-events.allowed-scopes[3].tenant-id=tenant-a",
                "agent.task-events.allowed-scopes[3].client-id=client-a")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentTaskEventsGate gate = context.getBean(AgentTaskEventsGate.class);
                    assertTrue(gate.allows("Tenant-A", "Client-A"));
                    assertTrue(gate.allows(composed, "client-b"));
                    assertTrue(gate.allows(supplementary, "client-c"));
                    assertTrue(gate.allows("tenant-a", "client-a"));
                    assertFalse(gate.allows("tenant-a", "Client-A"));
                    assertFalse(gate.allows("Tenant-A", "client-a"));
                    assertFalse(gate.allows("tenant-e\u0301", "client-b"));
                    assertFalse(gate.allows(" Tenant-A", "Client-A"));
                });
    }

    @Test
    void disabledConfigurationDeniesEvenConfiguredValidScopeAndCannotRefresh() {
        RUNNER.withPropertyValues(
                "agent.task-events.enabled=false",
                "agent.task-events.allowed-scopes[0].tenant-id=tenant-a",
                "agent.task-events.allowed-scopes[0].client-id=client-a")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AgentTaskEventsGate gate = context.getBean(AgentTaskEventsGate.class);
                    assertFalse(gate.allows("tenant-a", "client-a"));
                    context.getEnvironment().getPropertySources().addFirst(
                            new MapPropertySource("runtime-attempt", Map.of(
                                    "agent.task-events.enabled", "true")));
                    assertFalse(gate.allows("tenant-a", "client-a"));
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidConfigurations")
    void invalidConfigurationFailsApplicationContextStartup(InvalidCase invalid) {
        RUNNER.withPropertyValues(invalid.properties().toArray(String[]::new))
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, invalid.name());
                    assertTrue(failureChain(failure).contains(
                            "Invalid agent.task-events configuration"),
                            invalid.name() + ": " + failureChain(failure));
                });
    }

    private static Stream<InvalidCase> invalidConfigurations() {
        String prefix = "agent.task-events.allowed-scopes[0].";
        String enabled = "agent.task-events.enabled=true";
        return Stream.of(
                invalid("enabled empty allowlist", enabled),
                invalid("missing tenant", enabled,
                        prefix + "client-id=client-a"),
                invalid("missing client", enabled,
                        prefix + "tenant-id=tenant-a"),
                invalid("tenant wildcard", enabled,
                        prefix + "tenant-id=tenant-*", prefix + "client-id=client-a"),
                invalid("client wildcard", enabled,
                        prefix + "tenant-id=tenant-a", prefix + "client-id=*"),
                invalid("blank", enabled,
                        prefix + "tenant-id=", prefix + "client-id=client-a"),
                invalid("unicode all-padding", enabled,
                        prefix + "tenant-id=\u00a0", prefix + "client-id=client-a"),
                invalid("unicode leading padding", enabled,
                        prefix + "tenant-id=\u2007tenant-a", prefix + "client-id=client-a"),
                invalid("unicode trailing padding", enabled,
                        prefix + "tenant-id=tenant-a", prefix + "client-id=client-a\u3000"),
                invalid("control", enabled,
                        prefix + "tenant-id=tenant\t-a", prefix + "client-id=client-a"),
                invalid("unpaired surrogate", enabled,
                        prefix + "tenant-id=tenant-\ud800", prefix + "client-id=client-a"),
                invalid("oversize code points", enabled,
                        prefix + "tenant-id=" + "x".repeat(51),
                        prefix + "client-id=client-a"),
                invalid("duplicate exact tuple", enabled,
                        prefix + "tenant-id=tenant-a", prefix + "client-id=client-a",
                        "agent.task-events.allowed-scopes[1].tenant-id=tenant-a",
                        "agent.task-events.allowed-scopes[1].client-id=client-a"),
                invalid("disabled malformed tuple",
                        "agent.task-events.enabled=false",
                        prefix + "tenant-id=tenant-a"));
    }

    private static InvalidCase invalid(String name, String... properties) {
        return new InvalidCase(name, List.of(properties));
    }

    private static String failureChain(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            result.append(current.getClass().getName()).append(':')
                    .append(current.getMessage()).append('\n');
        }
        return result.toString();
    }

    private record InvalidCase(String name, List<String> properties) {
        @Override
        public String toString() {
            return name;
        }
    }
}
