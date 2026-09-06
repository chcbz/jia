package cn.jia.economy.bounty;

import java.util.List;

public record FundedBountyCaptureReceipt(List<String> transactionIds, long escrowVersion, long postedAt) {
    public FundedBountyCaptureReceipt { transactionIds = List.copyOf(transactionIds); }
}
