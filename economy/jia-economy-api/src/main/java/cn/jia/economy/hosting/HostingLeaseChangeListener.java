package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyScope;

/** Same-transaction lease fact. Consumers only register durable work; no network or business callbacks. */
public interface HostingLeaseChangeListener {
    void changed(Change change);
    /** A reconciliation page finished in this transaction. Preserve the nearest remaining lease
     * before commit; a process crash must not lose another Agent's earlier deadline. */
    default void reconciled(EconomyScope scope, String userId, Long nextDeadline) { }

    record Change(EconomyScope scope, String leaseId, String userId, String status,
                  Long paidThrough, Long lockedAt) { }
}
