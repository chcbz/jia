package cn.jia.agent.entity.funding;

import lombok.Data;

@Data
public class AgentTaskFundingOperationEntity {
    private Long id;
    private String principalType;
    private String principalId;
    private byte[] idempotencyKey;
    private byte[] requestHash;
    private String taskId;
    private String status;
    private String reserveTransactionId;
    private Long receiptTaskVersion;
    private Long receiptCreatedAt;
    private Long receiptUpdatedAt;
    private String tenantId;
    private String clientId;
    private Long createTime;
    private Long updateTime;

    public byte[] getIdempotencyKey() {
        return idempotencyKey == null ? null : idempotencyKey.clone();
    }

    public void setIdempotencyKey(byte[] value) {
        idempotencyKey = value == null ? null : value.clone();
    }

    public byte[] getRequestHash() {
        return requestHash == null ? null : requestHash.clone();
    }

    public void setRequestHash(byte[] value) {
        requestHash = value == null ? null : value.clone();
    }
}
