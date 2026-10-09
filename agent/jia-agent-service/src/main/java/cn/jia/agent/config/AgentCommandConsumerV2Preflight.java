package cn.jia.agent.config;

import cn.jia.agent.service.AgentCommandAckV2Service;
import cn.jia.agent.service.AgentCommandInboxV2Service;
import cn.jia.agent.service.AgentCommandV2ExecutionPort;
import cn.jia.agent.service.AgentConfirmedRabbitPublisher;
import cn.jia.core.tenant.WorkerScope;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import java.util.Objects;

/** Side-effect-free validation that must complete before scoped topology provisioning. */
final class AgentCommandConsumerV2Preflight implements InitializingBean {
    private final Environment environment;
    private final WorkerScope scope;
    private final AgentRabbitV2TopologyManifest manifest;
    private final AgentOutboxRelaySettings relaySettings;
    private final AgentRabbitSafetyGate safetyGate;

    AgentCommandConsumerV2Preflight(Environment environment, WorkerScope scope,
            AgentRabbitV2TopologyManifest manifest,
            AgentCommandInboxV2Service inboxService,
            AgentCommandAckV2Service ackService,
            AgentCommandV2ExecutionPort executionPort,
            AgentConfirmedRabbitPublisher publisher,
            AgentOutboxRelaySettings relaySettings,
            AgentRabbitSafetyGate safetyGate) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(inboxService, "inboxService");
        Objects.requireNonNull(ackService, "ackService");
        Objects.requireNonNull(executionPort, "executionPort");
        Objects.requireNonNull(publisher, "publisher");
        this.relaySettings = Objects.requireNonNull(relaySettings, "relaySettings");
        this.safetyGate = Objects.requireNonNull(safetyGate, "safetyGate");
    }

    @Override
    public void afterPropertiesSet() {
        Binder binder = Binder.get(environment);
        if (!enabled(binder, "agent.rabbit-consume-v2.enabled")
                || !enabled(binder, "agent.rabbit-consumer-v2-core.enabled")
                || !enabled(binder, "agent.command-outbox.enabled")
                || !enabled(binder, "agent.rabbit-topology.enabled")
                || !enabled(binder, "agent.rabbit-publish.enabled")
                || !enabled(binder, "agent.rabbit-dispatch.enabled")) {
            throw denied("protocol-v2 consumer prerequisites are incomplete");
        }
        if (enabled(binder, "agent.rabbit-consume.enabled")) {
            throw denied("legacy and protocol-v2 Rabbit consumers are mutually exclusive");
        }
        if (!safetyGate.rabbitConsumeV2Enabled()
                || safetyGate.rabbitConsumeEnabled()
                || !safetyGate.rabbitDispatchEnabled()
                || (safetyGate.state() != AgentRabbitActivationState.DISPATCH_CANARY
                    && safetyGate.state() != AgentRabbitActivationState.DISPATCH_SCOPED)
                || !safetyGate.allowsWorkerDispatch(scope)) {
            throw denied("protocol-v2 worker scope is not allowed by Rabbit dispatch safety gate");
        }
        AgentRabbitV2TopologyManifest trusted = AgentRabbitV2TopologyManifest.forScope(scope);
        if (!trusted.scope().equals(manifest.scope())
                || !trusted.sha256().equals(manifest.sha256())) {
            throw denied("protocol-v2 topology does not match worker authority");
        }
        long confirmTimeoutMillis = relaySettings.confirmTimeoutMillis();
        if (confirmTimeoutMillis <= 0L || confirmTimeoutMillis > 60_000L) {
            throw denied("publisher confirm timeout is invalid");
        }
    }

    private static boolean enabled(Binder binder, String property) {
        return binder.bind(property, Bindable.of(Boolean.class)).orElse(false);
    }

    private static IllegalStateException denied(String reason) {
        return new IllegalStateException("Agent command consumer v2 unavailable: " + reason);
    }
}
