package cn.jia.economy.bounty;

public interface FundedBountyLedgerService {
    FundedBountyReserveReceipt reserve(FundedBountyReserveCommand command);
    FundedBountyRefundReceipt refund(FundedBountyRefundCommand command);
}
