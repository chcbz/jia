package cn.jia.economy.service;

/** Transactional balance fact extension. Implementations may register same-database durable work only;
 * no external I/O or renewal here. A registration failure must propagate to roll back the posting.
 */
@FunctionalInterface
public interface EconomyAvailableBalanceListener {
    record Increase(EconomyScope scope, String userId, long amountWen, String transactionId, long occurredAt) { }
    void increased(Increase increase);
}
