package cn.jia.agent.entity.skill;

import lombok.Data;
import lombok.experimental.Accessors;

/** Read projection of an existing captured initial rent and its current relationship, not a new ledger. */
@Data
@Accessors(chain = true)
public class PaidSkillInventoryEntity {
    private String principalId;
    private String ownerJiacn;
    private Long bindingId;
    private String leaseId;
    private String intentId;
    private String captureTransactionId;
    private String runtimeInstallationId;
    private String runtimeHostId;
    private String inventorySnapshotJson;
}
