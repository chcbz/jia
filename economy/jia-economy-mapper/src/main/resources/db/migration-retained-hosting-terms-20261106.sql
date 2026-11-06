-- Operator-only one-time MICRO -> WEN hosting cutover. Never run at application startup.
-- Requires an authorized exact datasource, stopped writers and a verified full read-only backup.
-- This preserves business identities/terms; it does NOT clear wallets, seed prices, refund,
-- extend leases or provision runtimes. All old monetary records remain in the fixed backup.
-- MySQL DDL commits implicitly: after any partial failure keep writers stopped and restore
-- the fixed snapshot (including this schema's triggers). Do not blindly retry or reverse prices.
-- SOURCE, CALL cyf_beta_retain_hosting_terms(), verify, then DROP PROCEDURE explicitly.
DELIMITER $$
CREATE PROCEDURE cyf_beta_retain_hosting_terms()
SQL SECURITY INVOKER
BEGIN
    DECLARE locked INT DEFAULT 0;
    DECLARE n INT;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        IF locked = 1 THEN DO RELEASE_LOCK('cyf:economy-v0:hosting-rent-schema'); END IF;
        RESIGNAL;
    END;
    SELECT GET_LOCK('cyf:economy-v0:hosting-rent-schema',0) INTO locked;
    IF locked IS NULL OR locked <> 1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='hosting schema lock unavailable'; END IF;
    SELECT COUNT(*) INTO n FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name IN ('economy_hosting_rent_plan','economy_hosting_rent_quote','economy_hosting_lease','economy_hosting_provisioning_intent')
      AND column_name='amount_micro' AND data_type='bigint';
    IF n <> 4 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='expected original four MICRO columns; partial migrations require snapshot recovery'; END IF;
    SELECT COUNT(*) INTO n FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name IN ('economy_hosting_rent_plan','economy_hosting_rent_quote','economy_hosting_lease','economy_hosting_provisioning_intent')
      AND column_name IN ('amount_wen','settlement_kind');
    IF n <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='target hosting columns already present'; END IF;
    SELECT COUNT(*) INTO n FROM economy_hosting_provisioning_intent
      WHERE status NOT IN ('ACTIVE','REFUNDED');
    IF n <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='unsettled hosting intents require explicit reconciliation'; END IF;
    SELECT COUNT(*) INTO n FROM economy_hosting_lease l
      LEFT JOIN economy_hosting_provisioning_intent i ON i.tenant_id=l.tenant_id AND i.client_id=l.client_id
        AND i.intent_id=l.latest_intent_id AND i.lease_id=l.lease_id AND i.agent_id=l.agent_id
      WHERE l.status NOT IN ('ACTIVE','REFUNDED') OR i.id IS NULL OR i.status<>l.status
        OR (l.status='ACTIVE' AND (l.paid_from IS NULL OR l.paid_through IS NULL OR l.paid_through<l.paid_from));
    IF n <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='hosting lease completion/term facts are incomplete'; END IF;
    SELECT COUNT(*) INTO n FROM economy_hosting_provisioning_intent i
      LEFT JOIN economy_hosting_lease l ON l.tenant_id=i.tenant_id AND l.client_id=i.client_id AND l.lease_id=i.lease_id
      WHERE l.id IS NULL;
    IF n <> 0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='hosting intent has no retained lease'; END IF;

    -- Schema maintenance restores the four immutable triggers before writers resume.
    DROP TRIGGER IF EXISTS trg_hosting_plan_no_update;
    DROP TRIGGER IF EXISTS trg_hosting_plan_no_delete;
    DROP TRIGGER IF EXISTS trg_hosting_quote_no_update;
    DROP TRIGGER IF EXISTS trg_hosting_quote_no_delete;
    ALTER TABLE economy_hosting_rent_plan RENAME COLUMN amount_micro TO amount_wen;
    ALTER TABLE economy_hosting_rent_quote RENAME COLUMN amount_micro TO amount_wen;
    ALTER TABLE economy_hosting_lease RENAME COLUMN amount_micro TO amount_wen;
    ALTER TABLE economy_hosting_provisioning_intent RENAME COLUMN amount_micro TO amount_wen,
      ADD COLUMN settlement_kind VARCHAR(16) NOT NULL DEFAULT 'PAID' AFTER status,
      MODIFY reserve_transaction_id VARCHAR(100) NULL;
    START TRANSACTION;
    -- Old quotes/prices are not WEN offers; no new price is inferred from them.
    DELETE FROM economy_hosting_rent_quote;
    DELETE FROM economy_hosting_rent_plan;
    UPDATE economy_hosting_lease SET amount_wen=0;
    UPDATE economy_hosting_provisioning_intent SET settlement_kind='RETAINED',amount_wen=0,
      reserve_transaction_id=NULL,capture_transaction_id=NULL,refund_transaction_id=NULL,escrow_version=0;
    COMMIT;
    DO RELEASE_LOCK('cyf:economy-v0:hosting-rent-schema');
END$$
DELIMITER ;
-- After CALL succeeds, the existing schema-maintenance initializer restores and verifies
-- the four canonical immutable plan/quote triggers before any writers are resumed.
-- On failure it must NOT be used to conceal a partial cutover: restore the fixed backup.
