package cn.jia.agent.config;

import org.springframework.beans.factory.InitializingBean;

import java.util.Objects;

/** Exact startup fence for the single trusted protocol-v2 consumer lane. */
final class AgentCommandConsumerV2ActivationGate implements InitializingBean {
    private final AgentRabbitV2TopologyManifest manifest;
    private final AgentRabbitV2TopologyReadiness readiness;
    private final AgentRabbitV2TopologyReadiness.Snapshot provisionedSnapshot;

    AgentCommandConsumerV2ActivationGate(AgentRabbitV2TopologyManifest manifest,
            AgentRabbitV2TopologyReadiness readiness,
            AgentRabbitV2TopologyReadiness.Snapshot provisionedSnapshot) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.readiness = Objects.requireNonNull(readiness, "readiness");
        this.provisionedSnapshot = Objects.requireNonNull(
                provisionedSnapshot, "provisionedSnapshot");
    }

    @Override
    public void afterPropertiesSet() {
        if (!provisionedSnapshot.canonicalTopologyReady(manifest)
                || !readiness.snapshot(manifest).canonicalTopologyReady(manifest)) {
            throw denied("protocol-v2 topology is not canonical provision READY");
        }
    }

    private static IllegalStateException denied(String reason) {
        return new IllegalStateException("Agent command consumer v2 unavailable: " + reason);
    }
}
