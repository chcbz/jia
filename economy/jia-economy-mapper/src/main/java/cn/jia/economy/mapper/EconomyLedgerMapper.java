package cn.jia.economy.mapper;

import cn.jia.economy.entity.EconomyAccountEntity;
import cn.jia.economy.entity.EconomyEntryEntity;
import cn.jia.economy.entity.EconomyEscrowEntity;
import cn.jia.economy.entity.EconomyEscrowFundingLotEntity;
import cn.jia.economy.entity.EconomyTransactionEntity;
import cn.jia.economy.entity.EconomyWalletLedgerRow;
import cn.jia.economy.entity.EconomyWalletSnapshotRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Curated mapper: no generic update/delete access exists for immutable journal rows. */
public interface EconomyLedgerMapper {
    String EXACT_SCOPE = " tenant_id=#{tenantId} AND client_id=#{clientId}"
            + " AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(#{tenantId})"
            + " AND OCTET_LENGTH(client_id)=OCTET_LENGTH(#{clientId}) ";

    @Insert("""
            INSERT INTO economy_transaction(
                transaction_id,principal_type,principal_id,idempotency_key,request_hash,
                business_type,business_id,currency,status,entry_count,
                debit_total_micro,credit_total_micro,posted_at,
                tenant_id,client_id,create_time,update_time)
            VALUES(
                #{transactionId},#{principalType},#{principalId},#{idempotencyKey},#{requestHash},
                #{businessType},#{businessId},#{currency},#{status},#{entryCount},
                #{debitTotalMicro},#{creditTotalMicro},#{postedAt},
                #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertTransaction(EconomyTransactionEntity transaction);

    @Select("""
            SELECT id,transaction_id,principal_type,principal_id,idempotency_key,request_hash,
                   business_type,business_id,currency,status,entry_count,
                   debit_total_micro,credit_total_micro,posted_at,
                   tenant_id,client_id,create_time,update_time
            FROM economy_transaction
            WHERE """ + EXACT_SCOPE + """
              AND principal_type=#{principalType} AND principal_id=#{principalId}
              AND idempotency_key=#{idempotencyKey}
              AND OCTET_LENGTH(principal_type)=OCTET_LENGTH(#{principalType})
              AND OCTET_LENGTH(principal_id)=OCTET_LENGTH(#{principalId})
              AND OCTET_LENGTH(idempotency_key)=OCTET_LENGTH(#{idempotencyKey})
            LIMIT 1 FOR UPDATE
            """)
    EconomyTransactionEntity selectTransactionByActorKeyForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("principalType") String principalType,
            @Param("principalId") String principalId,
            @Param("idempotencyKey") byte[] idempotencyKey);

    @Select("""
            SELECT id,transaction_id,principal_type,principal_id,idempotency_key,request_hash,
                   business_type,business_id,currency,status,entry_count,
                   debit_total_micro,credit_total_micro,posted_at,
                   tenant_id,client_id,create_time,update_time
            FROM economy_transaction
            WHERE """ + EXACT_SCOPE + """
              AND transaction_id=#{transactionId}
              AND OCTET_LENGTH(transaction_id)=OCTET_LENGTH(#{transactionId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyTransactionEntity selectTransactionByIdForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("transactionId") String transactionId);

    @Update("""
            UPDATE economy_transaction
            SET status='POSTED',entry_count=#{entryCount},
                debit_total_micro=#{debitTotalMicro},credit_total_micro=#{creditTotalMicro},
                posted_at=#{postedAt},update_time=#{postedAt}
            WHERE """ + EXACT_SCOPE + """
              AND transaction_id=#{transactionId} AND status='POSTING'
              AND entry_count=0 AND debit_total_micro=0 AND credit_total_micro=0
              AND posted_at IS NULL
              AND OCTET_LENGTH(transaction_id)=OCTET_LENGTH(#{transactionId})
            """)
    int markTransactionPosted(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("transactionId") String transactionId,
            @Param("entryCount") int entryCount,
            @Param("debitTotalMicro") long debitTotalMicro,
            @Param("creditTotalMicro") long creditTotalMicro,
            @Param("postedAt") long postedAt);

    @Select("""
            SELECT id,account_id,owner_type,owner_id,purpose,currency,balance_micro,
                   allow_negative,status,version,tenant_id,client_id,create_time,update_time
            FROM economy_account
            WHERE """ + EXACT_SCOPE + """
              AND currency=#{currency} AND owner_type=#{ownerType}
              AND owner_id=#{ownerId} AND purpose=#{purpose}
              AND OCTET_LENGTH(currency)=OCTET_LENGTH(#{currency})
              AND OCTET_LENGTH(owner_type)=OCTET_LENGTH(#{ownerType})
              AND OCTET_LENGTH(owner_id)=OCTET_LENGTH(#{ownerId})
              AND OCTET_LENGTH(purpose)=OCTET_LENGTH(#{purpose})
            LIMIT 1 FOR UPDATE
            """)
    EconomyAccountEntity selectAccountForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("currency") String currency,
            @Param("ownerType") String ownerType,
            @Param("ownerId") String ownerId,
            @Param("purpose") String purpose);

    @Select("""
            SELECT COALESCE(wallet.balance_micro,0) AS available_micro,
                   COALESCE(held.held_micro,0) AS held_micro,
                   COALESCE(held.minimum_held_component_micro,0) AS minimum_held_component_micro,
                   COALESCE(wallet.version,0) AS version
            FROM (SELECT 1 AS singleton) snapshot_anchor
            LEFT JOIN economy_account wallet
              ON wallet.tenant_id=#{tenantId} AND wallet.client_id=#{clientId}
             AND OCTET_LENGTH(wallet.tenant_id)=OCTET_LENGTH(#{tenantId})
             AND OCTET_LENGTH(wallet.client_id)=OCTET_LENGTH(#{clientId})
             AND wallet.currency='SILVER' AND wallet.owner_type='USER'
             AND wallet.owner_id=#{ownerId} AND wallet.purpose='AVAILABLE'
             AND OCTET_LENGTH(wallet.owner_id)=OCTET_LENGTH(#{ownerId})
            LEFT JOIN (
                SELECT SUM(e.gross_micro-e.captured_micro-e.refunded_micro) AS held_micro,
                       MIN(e.gross_micro-e.captured_micro-e.refunded_micro) AS minimum_held_component_micro
                FROM economy_escrow e
                JOIN economy_account payer
                  ON payer.tenant_id=e.tenant_id AND payer.client_id=e.client_id
                 AND payer.account_id=e.payer_account_id
                 AND OCTET_LENGTH(payer.tenant_id)=OCTET_LENGTH(e.tenant_id)
                 AND OCTET_LENGTH(payer.client_id)=OCTET_LENGTH(e.client_id)
                 AND OCTET_LENGTH(payer.account_id)=OCTET_LENGTH(e.payer_account_id)
                WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId}
                  AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
                  AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
                  AND payer.owner_type='USER' AND payer.owner_id=#{ownerId}
                  AND payer.purpose='AVAILABLE'
                  AND OCTET_LENGTH(payer.owner_id)=OCTET_LENGTH(#{ownerId})
            ) held ON snapshot_anchor.singleton=1
            LIMIT 1
            """)
    EconomyWalletSnapshotRow selectUserWalletSnapshot(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerId") String ownerId);

    @Select("""
            SELECT e.id AS row_id,e.transaction_id,e.entry_id,t.business_type,t.business_id AS business_ref,
                   e.signed_amount_micro,e.status,e.posted_at
            FROM economy_entry e
            JOIN economy_account a
              ON a.tenant_id=e.tenant_id AND a.client_id=e.client_id AND a.account_id=e.account_id
             AND OCTET_LENGTH(a.tenant_id)=OCTET_LENGTH(e.tenant_id)
             AND OCTET_LENGTH(a.client_id)=OCTET_LENGTH(e.client_id)
             AND OCTET_LENGTH(a.account_id)=OCTET_LENGTH(e.account_id)
            JOIN economy_transaction t
              ON t.tenant_id=e.tenant_id AND t.client_id=e.client_id AND t.transaction_id=e.transaction_id
             AND OCTET_LENGTH(t.tenant_id)=OCTET_LENGTH(e.tenant_id)
             AND OCTET_LENGTH(t.client_id)=OCTET_LENGTH(e.client_id)
             AND OCTET_LENGTH(t.transaction_id)=OCTET_LENGTH(e.transaction_id)
            WHERE e.tenant_id=#{tenantId} AND e.client_id=#{clientId}
              AND OCTET_LENGTH(e.tenant_id)=OCTET_LENGTH(#{tenantId})
              AND OCTET_LENGTH(e.client_id)=OCTET_LENGTH(#{clientId})
              AND a.owner_type='USER' AND a.owner_id=#{ownerId} AND a.purpose='AVAILABLE'
              AND OCTET_LENGTH(a.owner_id)=OCTET_LENGTH(#{ownerId})
              AND e.status='POSTED' AND t.status='POSTED'
              AND (#{cursorPostedAt} IS NULL OR e.posted_at < #{cursorPostedAt}
                   OR (e.posted_at=#{cursorPostedAt} AND e.id < #{cursorRowId}))
            ORDER BY e.posted_at DESC,e.id DESC
            LIMIT #{limit}
            """)
    List<EconomyWalletLedgerRow> selectUserAvailableLedger(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("ownerId") String ownerId,
            @Param("cursorPostedAt") Long cursorPostedAt,
            @Param("cursorRowId") Long cursorRowId,
            @Param("limit") int limit);

    @Insert("""
            INSERT IGNORE INTO economy_account(
                account_id,owner_type,owner_id,purpose,currency,balance_micro,
                allow_negative,status,version,tenant_id,client_id,create_time,update_time)
            VALUES(
                #{accountId},#{ownerType},#{ownerId},#{purpose},#{currency},#{balanceMicro},
                #{allowNegative},#{status},#{version},#{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertAccountIfAbsent(EconomyAccountEntity account);

    @Update("""
            UPDATE economy_account
            SET balance_micro=#{newBalance},version=#{newVersion},update_time=#{now}
            WHERE id=#{account.id} AND """ + EXACT_SCOPE + """
              AND account_id=#{account.accountId}
              AND balance_micro=#{account.balanceMicro} AND version=#{account.version}
              AND status=#{account.status}
              AND OCTET_LENGTH(account_id)=OCTET_LENGTH(#{account.accountId})
            """)
    int updateAccountBalance(
            @Param("account") EconomyAccountEntity account,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("newBalance") long newBalance,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Insert("""
            INSERT INTO economy_entry(
                entry_id,transaction_id,account_id,entry_sequence,signed_amount_micro,
                balance_after_micro,currency,status,posted_at,
                tenant_id,client_id,create_time)
            VALUES(
                #{entryId},#{transactionId},#{accountId},#{entrySequence},#{signedAmountMicro},
                #{balanceAfterMicro},#{currency},#{status},#{postedAt},
                #{tenantId},#{clientId},#{createTime})
            """)
    int insertEntry(EconomyEntryEntity entry);

    @Select("""
            SELECT id,entry_id,transaction_id,account_id,entry_sequence,signed_amount_micro,
                   balance_after_micro,currency,status,posted_at,tenant_id,client_id,create_time
            FROM economy_entry
            WHERE """ + EXACT_SCOPE + """
              AND transaction_id=#{transactionId}
              AND OCTET_LENGTH(transaction_id)=OCTET_LENGTH(#{transactionId})
            ORDER BY entry_sequence ASC
            """)
    List<EconomyEntryEntity> selectEntriesByTransaction(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("transactionId") String transactionId);

    @Select("""
            SELECT id,escrow_id,business_type,business_id,payer_account_id,escrow_account_id,
                   currency,gross_micro,captured_micro,refunded_micro,status,version,
                   tenant_id,client_id,create_time,update_time
            FROM economy_escrow
            WHERE """ + EXACT_SCOPE + """
              AND business_type=#{businessType} AND business_id=#{businessId}
              AND OCTET_LENGTH(business_type)=OCTET_LENGTH(#{businessType})
              AND OCTET_LENGTH(business_id)=OCTET_LENGTH(#{businessId})
            LIMIT 1 FOR UPDATE
            """)
    EconomyEscrowEntity selectEscrowByBusinessForUpdate(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("businessType") String businessType,
            @Param("businessId") String businessId);

    @Insert("""
            INSERT INTO economy_escrow(
                escrow_id,business_type,business_id,payer_account_id,escrow_account_id,
                currency,gross_micro,captured_micro,refunded_micro,status,version,
                tenant_id,client_id,create_time,update_time)
            VALUES(
                #{escrowId},#{businessType},#{businessId},#{payerAccountId},#{escrowAccountId},
                #{currency},#{grossMicro},#{capturedMicro},#{refundedMicro},#{status},#{version},
                #{tenantId},#{clientId},#{createTime},#{updateTime})
            """)
    int insertEscrow(EconomyEscrowEntity escrow);

    @Update("""
            UPDATE economy_escrow
            SET gross_micro=#{newGross},version=#{newVersion},update_time=#{now}
            WHERE id=#{escrow.id} AND """ + EXACT_SCOPE + """
              AND escrow_id=#{escrow.escrowId} AND version=#{escrow.version}
              AND gross_micro=#{escrow.grossMicro} AND status='ACTIVE'
              AND OCTET_LENGTH(escrow_id)=OCTET_LENGTH(#{escrow.escrowId})
            """)
    int updateEscrowGross(
            @Param("escrow") EconomyEscrowEntity escrow,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("newGross") long newGross,
            @Param("newVersion") long newVersion,
            @Param("now") long now);

    @Select("""
            SELECT MAX(funding_sequence)
            FROM economy_escrow_funding_lot
            WHERE """ + EXACT_SCOPE + """
              AND escrow_id=#{escrowId}
              AND OCTET_LENGTH(escrow_id)=OCTET_LENGTH(#{escrowId})
            """)
    Integer selectMaxFundingSequence(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("escrowId") String escrowId);

    @Insert("""
            INSERT INTO economy_escrow_funding_lot(
                escrow_id,funding_sequence,reserve_transaction_id,payer_account_id,
                amount_micro,escrow_gross_after_micro,escrow_version_after,currency,
                tenant_id,client_id,created_at)
            VALUES(
                #{escrowId},#{fundingSequence},#{reserveTransactionId},#{payerAccountId},
                #{amountMicro},#{escrowGrossAfterMicro},#{escrowVersionAfter},#{currency},
                #{tenantId},#{clientId},#{createdAt})
            """)
    int insertFundingLot(EconomyEscrowFundingLotEntity lot);

    @Select("""
            SELECT id,escrow_id,funding_sequence,reserve_transaction_id,payer_account_id,
                   amount_micro,escrow_gross_after_micro,escrow_version_after,currency,
                   tenant_id,client_id,created_at
            FROM economy_escrow_funding_lot
            WHERE """ + EXACT_SCOPE + """
              AND reserve_transaction_id=#{transactionId}
              AND OCTET_LENGTH(reserve_transaction_id)=OCTET_LENGTH(#{transactionId})
            LIMIT 1
            """)
    EconomyEscrowFundingLotEntity selectFundingLotByTransaction(
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("transactionId") String transactionId);

    @Update("""
            UPDATE economy_escrow
            SET captured_micro=#{capturedAfter},refunded_micro=#{refundedAfter},
                status=#{status},version=#{versionAfter},update_time=#{now}
            WHERE id=#{escrow.id} AND """ + EXACT_SCOPE + """
              AND escrow_id=#{escrow.escrowId} AND version=#{escrow.version}
              AND gross_micro=#{escrow.grossMicro}
              AND captured_micro=#{escrow.capturedMicro} AND refunded_micro=#{escrow.refundedMicro}
              AND status=#{escrow.status}
              AND OCTET_LENGTH(escrow_id)=OCTET_LENGTH(#{escrow.escrowId})
            """)
    int updateEscrowSettlement(
            @Param("escrow") EconomyEscrowEntity escrow,
            @Param("tenantId") String tenantId,
            @Param("clientId") String clientId,
            @Param("capturedAfter") long capturedAfter,
            @Param("refundedAfter") long refundedAfter,
            @Param("status") String status,
            @Param("versionAfter") long versionAfter,
            @Param("now") long now);
}
