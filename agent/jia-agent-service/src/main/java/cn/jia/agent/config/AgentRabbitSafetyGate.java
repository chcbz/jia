package cn.jia.agent.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Single immutable startup gate for every M3 outbox and Rabbit activation flag. */
public final class AgentRabbitSafetyGate {
    private final boolean commandOutboxEnabled;
    private final boolean rabbitTopologyEnabled;
    private final boolean rabbitPublishEnabled;
    private final boolean rabbitConsumeEnabled;
    private final boolean rabbitDispatchEnabled;
    private final boolean brokerRequired;
    private final AgentRabbitActivationState state;
    private final Set<Scope> dispatchAllowedScopes;

    public AgentRabbitSafetyGate(AgentRabbitSafetyProperties properties) {
        if (properties == null) {
            throw invalidConfiguration("properties are required");
        }
        commandOutboxEnabled = properties.commandOutbox().enabled();
        rabbitTopologyEnabled = properties.rabbitTopology().enabled();
        rabbitPublishEnabled = properties.rabbitPublish().enabled();
        rabbitConsumeEnabled = properties.rabbitConsume().enabled();
        rabbitDispatchEnabled = properties.rabbitDispatch().enabled();
        brokerRequired = rabbitTopologyEnabled || rabbitPublishEnabled
                || rabbitConsumeEnabled || rabbitDispatchEnabled;

        dispatchAllowedScopes = rabbitDispatchEnabled
                ? validateScopes(properties.rabbitDispatch().allowedScopes())
                : Set.of();
        validateDependencies();
        if (brokerRequired) {
            validateBroker(properties.rabbitBroker());
        }
        state = resolveState();
    }

    public AgentRabbitActivationState state() {
        return state;
    }

    public boolean commandOutboxEnabled() {
        return commandOutboxEnabled;
    }

    public boolean rabbitTopologyEnabled() {
        return rabbitTopologyEnabled;
    }

    public boolean rabbitPublishEnabled() {
        return rabbitPublishEnabled;
    }

    public boolean rabbitConsumeEnabled() {
        return rabbitConsumeEnabled;
    }

    public boolean rabbitDispatchEnabled() {
        return rabbitDispatchEnabled;
    }

    public boolean brokerRequired() {
        return brokerRequired;
    }

    public boolean allowsDispatch(String tenantId, String clientId) {
        return rabbitDispatchEnabled
                && dispatchAllowedScopes.contains(new Scope(tenantId, clientId));
    }

    public int dispatchScopeCount() {
        return dispatchAllowedScopes.size();
    }

    private void validateDependencies() {
        if (rabbitPublishEnabled && (!commandOutboxEnabled || !rabbitTopologyEnabled)) {
            throw invalidConfiguration("rabbit publish requires command outbox and topology");
        }
        if (rabbitConsumeEnabled && !rabbitTopologyEnabled) {
            throw invalidConfiguration("rabbit consume requires topology");
        }
        if (rabbitDispatchEnabled
                && (!commandOutboxEnabled || !rabbitTopologyEnabled
                || !rabbitPublishEnabled || !rabbitConsumeEnabled
                || dispatchAllowedScopes.isEmpty())) {
            throw invalidConfiguration("rabbit dispatch prerequisites are incomplete");
        }
    }

    private AgentRabbitActivationState resolveState() {
        if (rabbitDispatchEnabled) {
            return dispatchAllowedScopes.size() == 1
                    ? AgentRabbitActivationState.DISPATCH_CANARY
                    : AgentRabbitActivationState.DISPATCH_SCOPED;
        }
        if (brokerRequired) {
            return AgentRabbitActivationState.MQ_SHADOW;
        }
        return commandOutboxEnabled
                ? AgentRabbitActivationState.DB_SHADOW
                : AgentRabbitActivationState.OFF;
    }

    private static Set<Scope> validateScopes(
            Iterable<AgentRabbitSafetyProperties.AllowedScope> configuredScopes) {
        LinkedHashSet<Scope> validated = new LinkedHashSet<>();
        for (AgentRabbitSafetyProperties.AllowedScope configured : configuredScopes) {
            if (configured == null
                    || !nonBlankExact(configured.tenantId())
                    || !nonBlankExact(configured.clientId())) {
                throw invalidConfiguration("dispatch scopes must contain exact non-blank ids");
            }
            Scope scope = new Scope(configured.tenantId(), configured.clientId());
            if (!validated.add(scope)) {
                throw invalidConfiguration("dispatch scopes must be unique exact tuples");
            }
        }
        return Collections.unmodifiableSet(validated);
    }

    private static void validateBroker(AgentRabbitSafetyProperties.RabbitBroker broker) {
        if (broker == null
                || !nonBlankExact(broker.host())
                || broker.port() == null || broker.port() < 1 || broker.port() > 65_535
                || !nonBlankExact(broker.username())
                || !nonBlankExact(broker.password())
                || !nonBlankExact(broker.virtualHost())
                || "/".equals(broker.virtualHost())) {
            throw invalidConfiguration("dedicated agent.rabbit-broker is incomplete or unsafe");
        }
    }

    private static boolean nonBlankExact(String value) {
        return value != null && !value.codePoints()
                .allMatch(codePoint -> Character.isWhitespace(codePoint)
                        || Character.isSpaceChar(codePoint));
    }

    private static IllegalStateException invalidConfiguration(String reason) {
        return new IllegalStateException("Invalid M3 agent Rabbit configuration: " + reason);
    }

    private record Scope(String tenantId, String clientId) {
    }
}
