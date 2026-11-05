package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyTaskQuoteEntity;
import cn.jia.economy.entity.EconomyTaskChargeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;

/** Curated SQL boundary: exact owner-scoped reads, immutable quotes, locked task charge transitions. */
public interface EconomyTaskBillingMapper {
    String SCOPE = "tenant_id=#{tenantId} AND client_id=#{clientId} AND owner_jiacn=#{ownerJiacn} AND task_id=#{taskId}";

    @Select("SELECT * FROM economy_task_quote WHERE " + SCOPE + " AND binding_sha256=#{bindingSha256}")
    EconomyTaskQuoteEntity findQuoteByBinding(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task, @Param("bindingSha256") String binding);

    @Select("SELECT * FROM economy_task_quote WHERE " + SCOPE + " AND quote_id=#{quoteId}")
    EconomyTaskQuoteEntity findQuote(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task, @Param("quoteId") String quote);

    @Insert("""
            INSERT INTO economy_task_quote(quote_id,binding_sha256,tenant_id,client_id,owner_jiacn,task_id,
                payer_user_id,amount_wen,snapshot_json,created_at)
            VALUES(#{quoteId},#{bindingSha256},#{tenantId},#{clientId},#{ownerJiacn},#{taskId},
                #{payerUserId},#{amountWen},#{snapshotJson},#{createdAt})
            """)
    int insertQuote(EconomyTaskQuoteEntity quote);

    @Select("SELECT * FROM economy_task_charge WHERE " + SCOPE + " FOR UPDATE")
    EconomyTaskChargeEntity lockCharge(@Param("tenantId") String tenant, @Param("clientId") String client,
            @Param("ownerJiacn") String owner, @Param("taskId") String task);

    @Insert("""
            INSERT INTO economy_task_charge(tenant_id,client_id,owner_jiacn,task_id,quote_id,payer_user_id,
                amount_wen,state,created_at,updated_at)
            VALUES(#{tenantId},#{clientId},#{ownerJiacn},#{taskId},#{quoteId},#{payerUserId},
                #{amountWen},'RESERVING',#{createdAt},#{updatedAt})
            ON DUPLICATE KEY UPDATE task_id=task_id
            """)
    int insertChargeIfAbsent(EconomyTaskChargeEntity charge);

    @Update("UPDATE economy_task_charge SET state='HELD',reserve_transaction_id=#{reserveTransactionId},"
            + "escrow_version=#{escrowVersion},updated_at=#{updatedAt} WHERE " + SCOPE + " AND state='RESERVING'")
    int markHeld(EconomyTaskChargeEntity charge);

    @Update("UPDATE economy_task_charge SET state=#{state},terminal_transaction_id=#{terminalTransactionId},"
            + "escrow_version=#{escrowVersion},updated_at=#{updatedAt} WHERE " + SCOPE + " AND state='HELD'")
    int markTerminal(EconomyTaskChargeEntity charge);
}
