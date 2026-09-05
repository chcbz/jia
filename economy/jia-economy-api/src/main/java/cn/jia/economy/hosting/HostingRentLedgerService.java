package cn.jia.economy.hosting;

public interface HostingRentLedgerService {
    HostingRentQuoteReceipt quote(HostingRentQuoteCommand command);

    HostingRentMutationReceipt reserve(HostingRentReserveCommand command);

    void markProvisioningUnknown(HostingRentOutcomeCommand command);

    void confirmProvisioningFailedNoEffect(HostingRentOutcomeCommand command);

    HostingRentMutationReceipt capture(HostingRentSettlementCommand command);

    HostingRentMutationReceipt refund(HostingRentSettlementCommand command);
}
