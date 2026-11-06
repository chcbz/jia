package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Single-statement projection for an authenticated user's public wallet snapshot. */
@Data
@Accessors(chain = true)
public class EconomyWalletSnapshotRow {
    private Long availableWen;
    private Long heldWen;
    private Long minimumHeldComponentWen;
    private Long version;
}
