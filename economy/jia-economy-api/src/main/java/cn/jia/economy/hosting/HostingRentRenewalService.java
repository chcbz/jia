package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyScope;

/** Internal reconciliation, never a caller-controlled HTTP charge. Period identity is the paid-through fact. */
public interface HostingRentRenewalService {
    /** Reconcile due/locked leases in expiry/key order; return the next authoritative user deadline. */
    Long reconcileUser(EconomyScope scope, String userId);
    Result reconcile(EconomyScope scope, String leaseId, long expectedPaidThrough);
    record Result(String status, Long paidThrough, Long lockedAt, String transactionId) { }
}
