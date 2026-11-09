package cn.jia.economy.hosting;

import cn.jia.economy.service.EconomyScope;

/** Same-transaction lease fact. Consumers only register durable work; no network or business callbacks. */
public interface HostingLeaseChangeListener {
    void changed(Change change);
    record Change(EconomyScope scope, String leaseId, String userId, String status,
                  Long paidThrough, Long lockedAt) { }
}
