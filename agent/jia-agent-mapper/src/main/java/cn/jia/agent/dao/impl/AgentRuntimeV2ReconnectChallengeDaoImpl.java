package cn.jia.agent.dao.impl;

import cn.jia.agent.dao.AgentRuntimeV2ReconnectChallengeDao;
import cn.jia.agent.entity.AgentRuntimeV2ReconnectChallengeEntity;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationCodec;
import cn.jia.agent.entity.AgentRuntimeV2RegistrationFingerprints;
import cn.jia.agent.mapper.AgentRuntimeV2ReconnectChallengeMapper;
import jakarta.inject.Named;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.util.Objects;

import static cn.jia.agent.dao.impl.AgentRuntimeV2RegistrationDaoSupport.*;

@Named
@RequiredArgsConstructor
public class AgentRuntimeV2ReconnectChallengeDaoImpl implements AgentRuntimeV2ReconnectChallengeDao {
    private final AgentRuntimeV2ReconnectChallengeMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int append(AgentRuntimeV2ReconnectChallengeEntity e) {
        transaction(true);
        if (e.getId() != null || e.getContractVersion() == null || e.getContractVersion() != 2) {
            throw new IllegalArgumentException("challenge must be new contract-v2");
        }
        id(e.getChallengeRequestId(), "challengeRequestId");
        id(e.getChallengeId(), "challengeId");
        hash(e.getRequestWireSha256(), "requestWireSha256");
        hash(e.getSemanticSha256(), "semanticSha256");
        hash(e.getRegistrationSubjectSha256(), "subjectSha256");
        hash(e.getRegistrationAuthoritySha256(), "authoritySha256");
        id(e.getRegistrationAuthorityId(), "registrationAuthorityId");
        nonnegative(e.getRegistrationAuthorityRevision(), "registrationAuthorityRevision");
        id(e.getRuntimeBindingId(), "runtimeBindingId");
        nonnegative(e.getBindingGeneration(), "bindingGeneration");
        id(e.getPriorRegistrationReceiptId(), "priorRegistrationReceiptId");
        hash(e.getPriorRegistrationReceiptSha256(), "priorRegistrationReceiptSha256");
        id(e.getRuntimeInstanceId(), "runtimeInstanceId");
        hash(e.getProcessPublicKeySha256(), "processPublicKeySha256");
        hash(e.getChallengeDigest(), "challengeDigest");
        exact(e.getChallengeDigestKeyId(), 100, "challengeDigestKeyId");
        hash(e.getChallengeSha256(), "challengeSha256");
        hash(e.getChallengeWireSha256(), "challengeWireSha256");
        tenant(e.getTenantId());
        exact(e.getClientId(), 50, "clientId");
        exact(e.getIssuerSocketOwnerInstanceId(), 100, "issuerSocketOwnerInstanceId");
        nonnegative(e.getIssuerSocketOwnerEpoch(), "issuerSocketOwnerEpoch");
        id(e.getProvisionalSessionFence(), "provisionalSessionFence");
        if (!"ISSUED".equals(e.getStatus()) || e.getConsumedReconnectRequestId() != null
                || e.getConsumedAt() != null || e.getDenialCode() != null
                || e.getIssuedAt() == null || e.getIssuedAt() <= 0
                || e.getExpiresAt() == null || e.getExpiresAt() <= e.getIssuedAt()) {
            throw new IllegalArgumentException("new challenge must be fresh ISSUED state");
        }
        if (e.getChallengeWireBytes() == null
                || !MessageDigest.isEqual(AgentRuntimeV2RegistrationFingerprints.sha256(
                        e.getChallengeWireBytes()), e.getChallengeWireSha256())) {
            throw new IllegalArgumentException("challenge wire hash is not exact");
        }
        AgentRuntimeV2RegistrationCodec.Challenge wire =
                AgentRuntimeV2RegistrationCodec.decodeChallenge(e.getChallengeWireBytes());
        boolean exact = Objects.equals(e.getChallengeRequestId(), wire.challengeRequestId())
                && Objects.equals(e.getChallengeId(), wire.challengeId())
                && Objects.equals(e.getRuntimeBindingId(), wire.runtimeBindingId())
                && Objects.equals(e.getBindingGeneration(), wire.bindingGeneration())
                && Objects.equals(e.getRuntimeInstanceId(), wire.runtimeInstanceId())
                && Objects.equals(e.getPriorRegistrationReceiptId(), wire.priorRegistrationReceiptId())
                && bytes(e.getPriorRegistrationReceiptSha256(), wire.priorRegistrationReceiptSha256())
                && Objects.equals(e.getChallengeCipherSuite(), AgentRuntimeV2RegistrationCodec.CIPHER_SUITE)
                && bytes(e.getServerEphemeralPublicKeySpki(), wire.serverEphemeralPublicKeySpki())
                && bytes(e.getNonce(), wire.nonce())
                && bytes(e.getSealedChallenge(), wire.sealedChallenge())
                && bytes(e.getChallengeSha256(), wire.challengeSha256())
                && Objects.equals(e.getExpiresAt(), wire.expiresAt());
        if (!exact) throw new IllegalArgumentException("challenge wire and persistence projection differ");
        return mapper.insert(e);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2ReconnectChallengeEntity lockByRequestStableId(String requestId) {
        transaction(false);
        id(requestId, "requestId");
        return mapper.lockByRequestId(requestId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public AgentRuntimeV2ReconnectChallengeEntity lockByChallengeStableId(String challengeId) {
        transaction(false);
        id(challengeId, "challengeId");
        return mapper.lockByChallengeId(challengeId);
    }

    private static boolean bytes(byte[] left, byte[] right) {
        return left != null && right != null && MessageDigest.isEqual(left, right);
    }
}
