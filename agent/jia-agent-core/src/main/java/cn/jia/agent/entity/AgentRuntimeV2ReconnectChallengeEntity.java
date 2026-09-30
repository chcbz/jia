package cn.jia.agent.entity;

import cn.jia.core.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

@Data @Accessors(chain = true) @EqualsAndHashCode(callSuper = true)
@TableName("agent_runtime_v2_reconnect_challenge")
public class AgentRuntimeV2ReconnectChallengeEntity extends BaseEntity {
    @TableId(value="id",type=IdType.AUTO) private Long id;
    private String challengeRequestId;
    private String challengeId;
    @ToString.Exclude private byte[] requestWireSha256;
    @ToString.Exclude private byte[] semanticSha256;
    @ToString.Exclude private byte[] registrationSubjectSha256;
    @ToString.Exclude private byte[] registrationAuthoritySha256;
    private String registrationAuthorityId;
    private Long registrationAuthorityRevision;
    private String runtimeBindingId;
    private Long bindingGeneration;
    private String priorRegistrationReceiptId;
    @ToString.Exclude private byte[] priorRegistrationReceiptSha256;
    private String runtimeInstanceId;
    @ToString.Exclude private byte[] processPublicKeySha256;
    @ToString.Exclude private byte[] challengeDigest;
    private String challengeDigestKeyId;
    private String challengeCipherSuite;
    @ToString.Exclude private byte[] serverEphemeralPublicKeySpki;
    @ToString.Exclude private byte[] nonce;
    @ToString.Exclude private byte[] sealedChallenge;
    @ToString.Exclude private byte[] challengeSha256;
    @ToString.Exclude private byte[] challengeWireBytes;
    @ToString.Exclude private byte[] challengeWireSha256;
    private String issuerSocketOwnerInstanceId;
    private Long issuerSocketOwnerEpoch;
    private String provisionalSessionFence;
    private Long issuedAt;
    private Long expiresAt;
    private String status;
    private String consumedReconnectRequestId;
    private Long consumedAt;
    private String denialCode;
    private Integer contractVersion;
}
