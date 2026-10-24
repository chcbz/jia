package cn.jia.agent.mapper;

import cn.jia.agent.entity.AgentRuntimeV2ReconnectChallengeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AgentRuntimeV2ReconnectChallengeMapper {
    @Insert("""
            INSERT INTO agent_runtime_v2_reconnect_challenge(
              challenge_request_id,challenge_id,request_wire_sha256,semantic_sha256,
              registration_subject_sha256,registration_authority_sha256,registration_authority_id,
              registration_authority_revision,runtime_binding_id,binding_generation,
              prior_registration_receipt_id,prior_registration_receipt_sha256,runtime_instance_id,
              process_public_key_sha256,challenge_digest,challenge_digest_key_id,challenge_cipher_suite,
              server_ephemeral_public_key_spki,nonce,sealed_challenge,challenge_sha256,
              challenge_wire_bytes,challenge_wire_sha256,issuer_socket_owner_instance_id,
              issuer_socket_owner_epoch,provisional_session_fence,issued_at,expires_at,status,
              consumed_reconnect_request_id,consumed_at,denial_code,contract_version,
              tenant_id,client_id,create_time,update_time)
            VALUES(#{e.challengeRequestId},#{e.challengeId},#{e.requestWireSha256},#{e.semanticSha256},
              #{e.registrationSubjectSha256},#{e.registrationAuthoritySha256},#{e.registrationAuthorityId},
              #{e.registrationAuthorityRevision},#{e.runtimeBindingId},#{e.bindingGeneration},
              #{e.priorRegistrationReceiptId},#{e.priorRegistrationReceiptSha256},#{e.runtimeInstanceId},
              #{e.processPublicKeySha256},#{e.challengeDigest},#{e.challengeDigestKeyId},#{e.challengeCipherSuite},
              #{e.serverEphemeralPublicKeySpki},#{e.nonce},#{e.sealedChallenge},#{e.challengeSha256},
              #{e.challengeWireBytes},#{e.challengeWireSha256},#{e.issuerSocketOwnerInstanceId},
              #{e.issuerSocketOwnerEpoch},#{e.provisionalSessionFence},#{e.issuedAt},#{e.expiresAt},#{e.status},
              #{e.consumedReconnectRequestId},#{e.consumedAt},#{e.denialCode},#{e.contractVersion},
              #{e.tenantId},#{e.clientId},#{e.createTime},#{e.updateTime})
            """)
    int insert(@Param("e") AgentRuntimeV2ReconnectChallengeEntity entity);
    @Select("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE challenge_request_id=#{id} AND CAST(challenge_request_id AS BINARY)=CAST(#{id} AS BINARY) AND OCTET_LENGTH(challenge_request_id)=OCTET_LENGTH(#{id}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2ReconnectChallengeEntity lockByRequestId(@Param("id") String requestId);
    @Select("SELECT * FROM agent_runtime_v2_reconnect_challenge WHERE challenge_id=#{id} AND CAST(challenge_id AS BINARY)=CAST(#{id} AS BINARY) AND OCTET_LENGTH(challenge_id)=OCTET_LENGTH(#{id}) LIMIT 1 FOR UPDATE")
    AgentRuntimeV2ReconnectChallengeEntity lockByChallengeId(@Param("id") String challengeId);
}
