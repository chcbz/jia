package cn.jia.economy.bounty;

/** Must join the task-root transaction; not an externally exposed payment API. */
public interface FundedBountyCaptureService {
    void requirePreviewScope(cn.jia.economy.service.EconomyScope scope);
    FundedBountyCaptureReceipt capture(FundedBountyCaptureCommand command);
}
