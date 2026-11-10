package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyScope;
import cn.jia.economy.service.EconomyPrincipal;

/** Internal reconciliation, never a caller-controlled HTTP charge. Period identity is the paid-through fact. */
public interface HostingRentRenewalService {
    /** Reconcile due/locked leases in expiry/key order; return the next authoritative user deadline. */
    Long reconcileUser(EconomyScope scope, String userId);
    Result reconcile(EconomyScope scope, String leaseId, long expectedPaidThrough);
    /** Stop future payments in the caller's removal transaction; retain the instance's live slot
     * and paid period until the Agent module completes private handover. No current-period refund. */
    Result stopRenewal(EconomyScope scope, EconomyPrincipal owner, String leaseId);
    /** Caller holds task and binding roots in an active transaction. Lock the financial lease
     * until that transaction ends; only an irreversible, expired relationship may force settlement. */
    boolean lockExpiredFinishing(EconomyScope scope, String leaseId, String bindingId, String agentId);
    record Result(String status, Long paidThrough, Long lockedAt, String transactionId) { }
}
