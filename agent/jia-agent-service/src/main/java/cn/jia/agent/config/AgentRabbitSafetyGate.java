package cn.jia.agent.config;

import cn.jia.core.tenant.WorkerScope;
import cn.jia.core.tenant.DataDomainId;
import cn.jia.core.tenant.TenantId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Single immutable startup gate for every M3 outbox and Rabbit activation flag. */
public final class AgentRabbitSafetyGate {
    private final boolean commandOutboxEnabled;
    private final boolean rabbitTopologyEnabled;
    private final boolean rabbitPublishEnabled;
    private final boolean rabbitConsumeEnabled;
    private final boolean rabbitConsumeV2Enabled;
    private final boolean rabbitDispatchEnabled;
    private final boolean brokerRequired;
    private final AgentRabbitActivationState state;
    private final Set<Scope> dispatchAllowedScopes;
    private final Set<WorkerLaneScope> dispatchAllowedWorkerScopes;

    public AgentRabbitSafetyGate(AgentRabbitSafetyProperties properties) {
        this(properties, null);
    }

    public AgentRabbitSafetyGate(
            AgentRabbitSafetyProperties properties,
            AgentRabbitDispatchScopeProperties dispatchScopes) {
        if (properties == null) {
            throw invalidConfiguration("properties are required");
        }
        commandOutboxEnabled = properties.commandOutbox().enabled();
        rabbitTopologyEnabled = properties.rabbitTopology().enabled();
        rabbitPublishEnabled = properties.rabbitPublish().enabled();
        rabbitConsumeEnabled = properties.rabbitConsume().enabled();
        rabbitConsumeV2Enabled = properties.rabbitConsumeV2().enabled();
        rabbitDispatchEnabled = properties.rabbitDispatch().enabled();
        brokerRequired = rabbitTopologyEnabled || rabbitPublishEnabled
                || rabbitConsumeEnabled || rabbitConsumeV2Enabled || rabbitDispatchEnabled;

        dispatchAllowedScopes = rabbitDispatchEnabled
                ? validateScopes(dispatchScopes == null
                        ? null : dispatchScopes.allowedScopes())
                : Set.of();
        dispatchAllowedWorkerScopes = rabbitDispatchEnabled
                ? validateWorkerScopes(dispatchScopes == null
                        ? null : dispatchScopes.allowedWorkerScopes())
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

    public boolean rabbitConsumeV2Enabled() {
        return rabbitConsumeV2Enabled;
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

    public boolean allowsWorkerDispatch(
            String tenantId, String dataDomainId, String executionPoolId) {
        return rabbitDispatchEnabled && dispatchAllowedWorkerScopes.contains(
                new WorkerLaneScope(tenantId, dataDomainId, executionPoolId));
    }

    public boolean allowsWorkerDispatch(WorkerScope scope) {
        return scope != null && allowsWorkerDispatch(
                scope.tenantId().value(), scope.dataDomainId().value(),
                scope.executionPoolId());
    }

    public int dispatchScopeCount() {
        return dispatchAllowedScopes.size() + dispatchAllowedWorkerScopes.size();
    }

    private void validateDependencies() {
        if (rabbitPublishEnabled && (!commandOutboxEnabled || !rabbitTopologyEnabled)) {
            throw invalidConfiguration("rabbit publish requires command outbox and topology");
        }
        if (rabbitConsumeEnabled && (!commandOutboxEnabled || !rabbitTopologyEnabled)) {
            throw invalidConfiguration("rabbit consume requires command outbox and topology");
        }
        if (rabbitConsumeV2Enabled && (!commandOutboxEnabled || !rabbitTopologyEnabled)) {
            throw invalidConfiguration("rabbit consume v2 requires command outbox and topology");
        }
        if (rabbitConsumeEnabled && rabbitConsumeV2Enabled) {
            throw invalidConfiguration("legacy and protocol-v2 Rabbit consumers are mutually exclusive");
        }
        boolean exactlyOneConsumer = rabbitConsumeEnabled ^ rabbitConsumeV2Enabled;
        if (rabbitDispatchEnabled
                && (!commandOutboxEnabled || !rabbitTopologyEnabled
                || !rabbitPublishEnabled || !exactlyOneConsumer
                || dispatchScopeCount() == 0)) {
            throw invalidConfiguration("rabbit dispatch prerequisites are incomplete");
        }
    }

    private AgentRabbitActivationState resolveState() {
        if (rabbitDispatchEnabled) {
            return dispatchScopeCount() == 1
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
            Iterable<AgentRabbitDispatchScopeProperties.AllowedScope> configuredScopes) {
        if (configuredScopes == null) {
            return Set.of();
        }
        LinkedHashSet<Scope> validated = new LinkedHashSet<>();
        for (AgentRabbitDispatchScopeProperties.AllowedScope configured : configuredScopes) {
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

    private static Set<WorkerLaneScope> validateWorkerScopes(
            Iterable<AgentRabbitDispatchScopeProperties.AllowedWorkerScope> configuredScopes) {
        if (configuredScopes == null) return Set.of();
        LinkedHashSet<WorkerLaneScope> validated = new LinkedHashSet<>();
        for (AgentRabbitDispatchScopeProperties.AllowedWorkerScope configured
                : configuredScopes) {
            if (configured == null) {
                throw invalidConfiguration(
                        "dispatch worker scopes must contain exact canonical ids");
            }
            try {
                TenantId.of(configured.tenantId());
                DataDomainId.of(configured.dataDomainId());
            } catch (RuntimeException invalid) {
                throw invalidConfiguration(
                        "dispatch worker scopes must contain exact canonical ids");
            }
            if (configured.executionPoolId() == null
                    || !configured.executionPoolId().matches("[a-z0-9-]{1,100}")) {
                throw invalidConfiguration(
                        "dispatch worker scopes must contain exact canonical ids");
            }
            WorkerLaneScope scope = new WorkerLaneScope(configured.tenantId(),
                    configured.dataDomainId(), configured.executionPoolId());
            if (!validated.add(scope)) {
                throw invalidConfiguration("dispatch worker scopes must be unique exact tuples");
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

    private record WorkerLaneScope(
            String tenantId, String dataDomainId, String executionPoolId) {
    }
}
