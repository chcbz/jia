package cn.jia.point.service;

/**
 * Point balances belong to the point domain, not to the global user identity.
 * The process configuration selects the tenant; callers cannot override it.
 * A balance is shared by the user's application clients within that tenant.
 */
public interface PointAccountService {
    String tenantId();

    /** Does not create an account. A new account has zero points. */
    int balance(String jiacn);

    /** One scoped read for a user page; missing accounts have a zero balance. */
    java.util.Map<String, Integer> balances(java.util.Collection<String> jiacns);

    /**
     * Lock participating accounts in stable identity order before checking business
     * eligibility. Requires the caller's transaction; never changes a balance.
     */
    void lockAccounts(java.util.Collection<String> jiacns);

    /**
     * Requires an existing transaction owned by the business operation. The caller
     * must validate the user/authority and persist its point_record in that same
     * transaction. Retries keep the existing business operation's idempotency.
     */
    int changeAndGet(String jiacn, int delta);
}
