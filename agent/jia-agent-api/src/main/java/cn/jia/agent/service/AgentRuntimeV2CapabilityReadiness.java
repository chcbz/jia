package cn.jia.agent.service;

import java.util.Set;

/** Future components must publish live verified capability evidence, not property-only opt-ins. */
public interface AgentRuntimeV2CapabilityReadiness {
    enum Capability { RECEIVER_DISPATCH, EXECUTION_HOLDER_TRANSFER, NODE_INTEROPERABILITY, CONSUMER_TOPOLOGY }
    Set<Capability> verifiedCapabilities();
}
