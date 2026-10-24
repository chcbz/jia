package cn.jia.agent.entity;

import java.util.Objects;

/** Handshake proof is an identity hint, never a receiver authority snapshot. */
public record AgentRuntimeV2RegistrationContext(String credentialKind, String credentialReference,
        String agentId, String credentialKeyId, byte[] credentialProof) {
    public AgentRuntimeV2RegistrationContext {
        Objects.requireNonNull(credentialKind);
        RuntimeV2ExactIdentity.require(credentialReference, 200, "credentialReference");
        RuntimeV2ExactIdentity.require(agentId, 100, "agentId");
        RuntimeV2ExactIdentity.require(credentialKeyId, 100, "credentialKeyId");
        credentialProof = AgentRuntimeV2RegistrationFingerprints.copy32(credentialProof, "credentialProof");
    }
    @Override public byte[] credentialProof() { return credentialProof.clone(); }
    @Override public String toString() { return "RegistrationContext[credentialKind=" + credentialKind + "]"; }
}
