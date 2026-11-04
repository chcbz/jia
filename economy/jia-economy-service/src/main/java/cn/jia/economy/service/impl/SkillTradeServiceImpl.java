package cn.jia.economy.service.impl;
import cn.jia.economy.service.skill.*;
import cn.jia.economy.service.*;
import cn.jia.economy.common.*;
import cn.jia.economy.entity.skill.*;
import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.mapper.SkillTradeMapper;
import cn.jia.economy.mapper.EconomyLedgerMapper;
import cn.jia.economy.config.EconomyPreviewGate;
import cn.jia.economy.exception.EconomyPostingException;
import org.springframework.stereotype.Service;
import java.util.*;

/** Only trade persistence and money. No dependency on agent entities, mappers or services. */
@Service
public class SkillTradeServiceImpl implements SkillTradeService {
    private final SkillTradeMapper trades;
    private final EconomyLedgerMapper ledger;
    private final EconomyPostingService posting;
    private final EconomyPreviewGate preview;
    public SkillTradeServiceImpl(SkillTradeMapper trades, EconomyLedgerMapper ledger,
            EconomyPostingService posting, EconomyPreviewGate preview) {
        this.trades=trades;this.ledger=ledger;this.posting=posting;this.preview=preview;
    }
    @Override public boolean allows(String tenantId,String clientId) {return preview.allows(tenantId,clientId);}
    @Override public int insertPurchaseQuote(SkillPurchaseQuote quote) { return trades.insertPurchaseQuote(entity(quote)); }
    @Override public SkillPurchaseQuote selectPurchaseQuoteByActorKeyForUpdate(String tenantId, String clientId, String actorType, String actorId, byte[] idempotencyKey) { return view(trades.selectPurchaseQuoteByActorKeyForUpdate(tenantId,clientId,actorType,actorId,idempotencyKey)); }
    @Override public SkillPurchaseQuote selectPurchaseQuoteForUpdate(String tenantId, String clientId, String quoteId, String actorType, String actorId) { return view(trades.selectPurchaseQuoteForUpdate(tenantId,clientId,quoteId,actorType,actorId)); }
    @Override public int insertOrder(SkillOrder order) { return trades.insertOrder(entity(order)); }
    @Override public SkillOrder selectOrderForUpdate(String tenantId, String clientId, String orderId) { return view(trades.selectOrderForUpdate(tenantId,clientId,orderId)); }
    @Override public SkillOrder selectOrderByBuyer(String tenantId, String clientId, String buyerType, String buyerId, String orderId) { return view(trades.selectOrderByBuyer(tenantId,clientId,buyerType,buyerId,orderId)); }
    @Override public int markOrderInstalling(String tenantId, String clientId, String orderId, long expectedVersion, long now) { return trades.markOrderInstalling(tenantId,clientId,orderId,expectedVersion,now); }
    @Override public int markOrderActive(String tenantId, String clientId, String orderId, long expectedVersion, String captureTransactionId, long now) { return trades.markOrderActive(tenantId,clientId,orderId,expectedVersion,captureTransactionId,now); }
    @Override public int markOrderRefunded(String tenantId, String clientId, String orderId, long expectedVersion, String refundTransactionId, long now) { return trades.markOrderRefunded(tenantId,clientId,orderId,expectedVersion,refundTransactionId,now); }
    @Override public int insertOrderReceipt(SkillOrderReceipt receipt) { return trades.insertOrderReceipt(entity(receipt)); }
    @Override public SkillOrderReceipt selectOrderReceiptByActorKeyForUpdate(String tenantId, String clientId, String actorType, String actorId, byte[] idempotencyKey) { return view(trades.selectOrderReceiptByActorKeyForUpdate(tenantId,clientId,actorType,actorId,idempotencyKey)); }
    @Override public SkillOrder order(String t, String c, String o) { return view(trades.order(t,c,o)); }
    @Override public String orderForQuote(String t, String c, String q) { return trades.orderForQuote(t,c,q); }
    @Override public int pendingInstalls(String t, String c, String a) { return trades.pendingInstalls(t,c,a); }
    @Override public EconomyPostingResult reserve(EconomyScope scope,EconomyPrincipal principal,String order,long amount,long now) {
        if(amount==0) return null;
        preview.requireMutationAllowed(scope.tenantId(),scope.clientId());
        var escrow=escrow(order); ensureAccount(scope,escrow,now);
        return posting.post(new EconomyPostingCommand(scope,principal,UUID.randomUUID().toString(),
                hash("skill-reserve",Map.of("order",order)),EconomyJournalType.RESERVE_SKILL,order,
                List.of(new EconomyPostingLine(availableAccount(principal.id()),-amount),new EconomyPostingLine(escrow,amount)),
                new EconomyEscrowFunding(EconomyEscrowType.SKILL_ORDER,availableAccount(principal.id()),escrow,amount,null)));
    }
    @Override public String settle(EconomyScope scope,EconomyPrincipal principal,SkillOrder o,Long escrowVersion,boolean capture,long now) {
        if(o.getPriceMicro()==0) return null;
        preview.requireMutationAllowed(scope.tenantId(),scope.clientId());
        var dest=capture?new EconomyAccountKey("SILVER",EconomyAccountOwnerType.SYSTEM,"SKILL_STORE",EconomyAccountPurpose.SKILL_STORE):availableAccount(principal.id());
        if(capture) ensureAccount(scope,dest,now);
        if(escrowVersion==null) throw new EconomyPostingException(EconomyPostingException.Reason.ESCROW_CONFLICT,"missing skill escrow version");
        var result=posting.post(new EconomyPostingCommand(scope,principal,UUID.randomUUID().toString(),
                hash(capture?"skill-capture":"skill-refund",Map.of("order",o.getOrderId())),
                capture?EconomyJournalType.CAPTURE_SKILL:EconomyJournalType.REFUND_SKILL,o.getOrderId(),
                List.of(new EconomyPostingLine(escrow(o.getOrderId()),-o.getPriceMicro()),new EconomyPostingLine(dest,o.getPriceMicro())),null,
                new EconomyEscrowSettlement(EconomyEscrowType.SKILL_ORDER,escrow(o.getOrderId()),dest,o.getPriceMicro(),escrowVersion,o.getReserveTransactionId())));
        return result.transactionId();
    }
    private void ensureAccount(EconomyScope scope,EconomyAccountKey k,long now) {
        ledger.insertAccountIfAbsent(new EconomyAccountEntity().setAccountId(("skill_ac_"+UUID.randomUUID().toString().replace("-",""))).setOwnerType(k.ownerType().name()).setOwnerId(k.ownerId())
                .setPurpose(k.purpose().name()).setCurrency("SILVER").setBalanceMicro(0L).setAllowNegative(0).setStatus("ACTIVE").setVersion(0L)
                .setTenantId(scope.tenantId()).setClientId(scope.clientId()).setCreateTime(now).setUpdateTime(now));
    }
    static EconomyAccountKey escrow(String order) { return new EconomyAccountKey("SILVER",EconomyAccountOwnerType.ORDER,order,EconomyAccountPurpose.ESCROW); }
    static EconomyAccountKey availableAccount(String actor) { return new EconomyAccountKey("SILVER",EconomyAccountOwnerType.USER,actor,EconomyAccountPurpose.AVAILABLE); }
    private static byte[] hash(String operation, Map<String,String> body) {
        try {
            var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);
            out.writeUTF(operation);
            for(var entry:new TreeMap<>(body).entrySet()) {out.writeUTF(entry.getKey());out.writeBoolean(entry.getValue()!=null);if(entry.getValue()!=null)out.writeUTF(entry.getValue());}
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException e) {throw new IllegalStateException("cannot encode skill trade request",e);}
    }
    private static SkillOrder view(SkillOrderEntity v) {
        if (v == null) return null;
        return new SkillOrder().setId(v.getId())
                .setOrderId(v.getOrderId())
                .setQuoteId(v.getQuoteId())
                .setProductVersionId(v.getProductVersionId())
                .setTargetAgentId(v.getTargetAgentId())
                .setBuyerType(v.getBuyerType())
                .setBuyerId(v.getBuyerId())
                .setSellerType(v.getSellerType())
                .setSellerId(v.getSellerId())
                .setPriceMicro(v.getPriceMicro())
                .setExpectedAgentVersion(v.getExpectedAgentVersion())
                .setPermissionGrantVersion(v.getPermissionGrantVersion())
                .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setEscrowId(v.getEscrowId())
                .setReserveTransactionId(v.getReserveTransactionId())
                .setCaptureTransactionId(v.getCaptureTransactionId())
                .setRefundTransactionId(v.getRefundTransactionId())
                .setStatus(v.getStatus())
                .setVersion(v.getVersion())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setHeldAt(v.getHeldAt())
                .setInstallingAt(v.getInstallingAt())
                .setActiveAt(v.getActiveAt())
                .setRefundedAt(v.getRefundedAt())
                .setUpdateTime(v.getUpdateTime());
    }
    private static SkillOrderEntity entity(SkillOrder v) {
        if (v == null) return null;
        return new SkillOrderEntity().setId(v.getId())
                .setOrderId(v.getOrderId())
                .setQuoteId(v.getQuoteId())
                .setProductVersionId(v.getProductVersionId())
                .setTargetAgentId(v.getTargetAgentId())
                .setBuyerType(v.getBuyerType())
                .setBuyerId(v.getBuyerId())
                .setSellerType(v.getSellerType())
                .setSellerId(v.getSellerId())
                .setPriceMicro(v.getPriceMicro())
                .setExpectedAgentVersion(v.getExpectedAgentVersion())
                .setPermissionGrantVersion(v.getPermissionGrantVersion())
                .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setEscrowId(v.getEscrowId())
                .setReserveTransactionId(v.getReserveTransactionId())
                .setCaptureTransactionId(v.getCaptureTransactionId())
                .setRefundTransactionId(v.getRefundTransactionId())
                .setStatus(v.getStatus())
                .setVersion(v.getVersion())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setHeldAt(v.getHeldAt())
                .setInstallingAt(v.getInstallingAt())
                .setActiveAt(v.getActiveAt())
                .setRefundedAt(v.getRefundedAt())
                .setUpdateTime(v.getUpdateTime());
    }
    private static SkillPurchaseQuote view(SkillPurchaseQuoteEntity v) {
        if (v == null) return null;
        return new SkillPurchaseQuote().setId(v.getId())
                .setQuoteId(v.getQuoteId())
                .setActorType(v.getActorType())
                .setActorId(v.getActorId())
                .setIdempotencyKey(v.getIdempotencyKey())
                .setRequestHash(v.getRequestHash())
                .setProductVersionId(v.getProductVersionId())
                .setTargetAgentId(v.getTargetAgentId())
                .setExpectedAgentVersion(v.getExpectedAgentVersion())
                .setExpectedPriceMicro(v.getExpectedPriceMicro())
                .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setDeploymentRestriction(v.getDeploymentRestriction())
                .setExpiresAt(v.getExpiresAt())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setCreateTime(v.getCreateTime());
    }
    private static SkillPurchaseQuoteEntity entity(SkillPurchaseQuote v) {
        if (v == null) return null;
        return new SkillPurchaseQuoteEntity().setId(v.getId())
                .setQuoteId(v.getQuoteId())
                .setActorType(v.getActorType())
                .setActorId(v.getActorId())
                .setIdempotencyKey(v.getIdempotencyKey())
                .setRequestHash(v.getRequestHash())
                .setProductVersionId(v.getProductVersionId())
                .setTargetAgentId(v.getTargetAgentId())
                .setExpectedAgentVersion(v.getExpectedAgentVersion())
                .setExpectedPriceMicro(v.getExpectedPriceMicro())
                .setApprovedPermissionsManifest(v.getApprovedPermissionsManifest())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setDeploymentRestriction(v.getDeploymentRestriction())
                .setExpiresAt(v.getExpiresAt())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setCreateTime(v.getCreateTime());
    }
    private static SkillOrderReceipt view(SkillOrderReceiptEntity v) {
        if (v == null) return null;
        return new SkillOrderReceipt().setId(v.getId())
                .setOrderId(v.getOrderId())
                .setActorType(v.getActorType())
                .setActorId(v.getActorId())
                .setIdempotencyKey(v.getIdempotencyKey())
                .setRequestHash(v.getRequestHash())
                .setOrderVersion(v.getOrderVersion())
                .setOrderStatus(v.getOrderStatus())
                .setPriceMicro(v.getPriceMicro())
                .setPermissionGrantVersion(v.getPermissionGrantVersion())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setEscrowId(v.getEscrowId())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setCreateTime(v.getCreateTime());
    }
    private static SkillOrderReceiptEntity entity(SkillOrderReceipt v) {
        if (v == null) return null;
        return new SkillOrderReceiptEntity().setId(v.getId())
                .setOrderId(v.getOrderId())
                .setActorType(v.getActorType())
                .setActorId(v.getActorId())
                .setIdempotencyKey(v.getIdempotencyKey())
                .setRequestHash(v.getRequestHash())
                .setOrderVersion(v.getOrderVersion())
                .setOrderStatus(v.getOrderStatus())
                .setPriceMicro(v.getPriceMicro())
                .setPermissionGrantVersion(v.getPermissionGrantVersion())
                .setApprovedPermissionsSha256(v.getApprovedPermissionsSha256())
                .setEscrowId(v.getEscrowId())
                .setTenantId(v.getTenantId())
                .setClientId(v.getClientId())
                .setCreateTime(v.getCreateTime());
    }
}
