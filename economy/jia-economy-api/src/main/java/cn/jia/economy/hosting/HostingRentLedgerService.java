package cn.jia.economy.hosting;

public interface HostingRentLedgerService {
    HostingRentQuoteReceipt quote(HostingRentQuoteCommand command);

    HostingRentMutationReceipt reserve(HostingRentReserveCommand command);

    /** Explicit prepaid renewal; reserve, capture and lease version CAS commit atomically. */
    HostingRentMutationReceipt renew(HostingRentReserveCommand command);

    void markProvisioningUnknown(HostingRentOutcomeCommand command);

    void confirmProvisioningFailedNoEffect(HostingRentOutcomeCommand command);

    /**
     * Trusted server-side reconciliation only, never a caller-controlled HTTP outcome.
     * The coordinator must verify service readiness for this exact intent/Agent before calling;
     * only its opaque evidence reference is persisted. No probe or other I/O occurs here.
     */
    void confirmProvisioningSucceeded(HostingRentOutcomeCommand command);

    HostingRentMutationReceipt capture(HostingRentSettlementCommand command);

    HostingRentMutationReceipt refund(HostingRentSettlementCommand command);
}
