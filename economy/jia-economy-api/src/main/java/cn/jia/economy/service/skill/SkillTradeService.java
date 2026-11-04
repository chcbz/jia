package cn.jia.economy.service.skill;

import cn.jia.economy.service.*;

/** Skill trade boundary. Caller supplies trusted scope; mutations join the existing transaction.
 * Product/installation/position policy remains with the skill domain. */
public interface SkillTradeService {
    boolean allows(String tenantId, String clientId);
    EconomyPostingResult reserve(EconomyScope scope, EconomyPrincipal principal, String orderId, long amount, long now);
    String settle(EconomyScope scope, EconomyPrincipal principal, SkillOrder order, Long escrowVersion, boolean capture, long now);
    int insertPurchaseQuote(SkillPurchaseQuote quote);
    SkillPurchaseQuote selectPurchaseQuoteByActorKeyForUpdate(String tenantId, String clientId, String actorType, String actorId, byte[] idempotencyKey);
    SkillPurchaseQuote selectPurchaseQuoteForUpdate(String tenantId, String clientId, String quoteId, String actorType, String actorId);
    int insertOrder(SkillOrder order);
    SkillOrder selectOrderForUpdate(String tenantId, String clientId, String orderId);
    SkillOrder selectOrderByBuyer(String tenantId, String clientId, String buyerType, String buyerId, String orderId);
    int markOrderInstalling(String tenantId, String clientId, String orderId, long expectedVersion, long now);
    int markOrderActive(String tenantId, String clientId, String orderId, long expectedVersion, String captureTransactionId, long now);
    int markOrderRefunded(String tenantId, String clientId, String orderId, long expectedVersion, String refundTransactionId, long now);
    int insertOrderReceipt(SkillOrderReceipt receipt);
    SkillOrderReceipt selectOrderReceiptByActorKeyForUpdate(String tenantId, String clientId, String actorType, String actorId, byte[] idempotencyKey);
    SkillOrder order(String t, String c, String o);
    String orderForQuote(String t, String c, String q);
    int pendingInstalls(String t, String c, String a);
}
