package cn.jia.economy.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/** Narrow read projection for the public wallet ledger; intentionally excludes account/counterparty data. */
@Data
@Accessors(chain = true)
public class EconomyWalletLedgerRow {
    private Long rowId;
    private String transactionId;
    private String entryId;
    private String businessType;
    private String businessRef;
    private Long signedAmountMicro;
    private String status;
    private Long postedAt;
}
