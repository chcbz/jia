-- Operator-only routine definition. Never source from application startup.
-- Preconditions: authorized exact datasource, stopped writers, recoverable fixed backup.
-- SOURCE this file using the MySQL client, then explicitly CALL the routine with 'WEN'.
-- 'MICRO' is only for reversal BEFORE business writes resume, under the same backup window.
-- DROP PROCEDURE cyf_beta_free_skill_price_columns after successful verification.
-- No exchange/conversion: only existing zero-price facts without financial references qualify.
DELIMITER $$
CREATE PROCEDURE cyf_beta_free_skill_price_columns(IN target_unit VARCHAR(16))
SQL SECURITY INVOKER
BEGIN
    DECLARE table_name_ VARCHAR(64);
    DECLARE old_column VARCHAR(64);
    DECLARE new_column VARCHAR(64);
    DECLARE current_column VARCHAR(64);
    DECLARE target_column VARCHAR(64);
    DECLARE matching_columns INT;
    DECLARE phase INT DEFAULT 0;
    DECLARE table_index INT DEFAULT 0;
    DECLARE lock_owned BOOLEAN DEFAULT FALSE;
    DECLARE statement_ready BOOLEAN DEFAULT FALSE;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF statement_ready THEN DEALLOCATE PREPARE price_statement; END IF;
        IF lock_owned THEN DO RELEASE_LOCK('cyf:economy-v0:skill-schema'); END IF;
        RESIGNAL;
    END;
    IF target_unit IS NULL OR BINARY target_unit NOT IN ('WEN','MICRO') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Free skill cutover requires WEN or MICRO';
    END IF;
    SET lock_owned = COALESCE(GET_LOCK('cyf:economy-v0:skill-schema',0),0);
    IF NOT lock_owned THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Skill schema maintenance lock is owned elsewhere';
    END IF;
    IF EXISTS (SELECT 1 FROM economy_skill_order WHERE escrow_id IS NOT NULL
            OR reserve_transaction_id IS NOT NULL OR capture_transaction_id IS NOT NULL OR refund_transaction_id IS NOT NULL)
        OR EXISTS (SELECT 1 FROM economy_skill_order_receipt WHERE escrow_id IS NOT NULL) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Free skill cutover cannot discard financial references';
    END IF;
    -- Pass 0 proves ALL price inputs before the first DDL. Pass 1 renames only unfinished columns.
    WHILE phase < 2 DO
        SET table_index = 0;
        WHILE table_index < 4 DO
            SET table_name_=ELT(table_index+1,'agent_skill_product_version','economy_skill_purchase_quote','economy_skill_order','economy_skill_order_receipt');
            SET old_column=IF(table_index=1,'expected_price_micro','price_micro');
            SET new_column=IF(table_index=1,'expected_price_wen','price_wen');
            SELECT COUNT(*),MIN(column_name) INTO matching_columns,current_column
                FROM information_schema.columns WHERE table_schema=DATABASE()
                AND table_name=table_name_ AND column_name IN (old_column,new_column);
            IF matching_columns <> 1 THEN
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Free skill cutover requires exactly one price column per table';
            END IF;
            IF phase = 0 THEN
                IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
                        AND table_name=table_name_ AND column_name=current_column
                        AND column_type='bigint' AND is_nullable='NO') THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Free skill cutover price column shape is not the recorded BIGINT';
                END IF;
                SET @cyf_free_skill_sql=CONCAT('SELECT EXISTS(SELECT 1 FROM `',table_name_,'` WHERE `',current_column,'` <> 0) INTO @cyf_free_skill_nonzero');
                PREPARE price_statement FROM @cyf_free_skill_sql;
                SET statement_ready=TRUE;
                EXECUTE price_statement;
                DEALLOCATE PREPARE price_statement;
                SET statement_ready=FALSE;
                IF @cyf_free_skill_nonzero THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Free skill cutover cannot convert a nonzero price';
                END IF;
            ELSE
                SET target_column=IF(BINARY target_unit='WEN',new_column,old_column);
                IF current_column <> target_column THEN
                    SET @cyf_free_skill_sql=CONCAT('ALTER TABLE `',table_name_,'` RENAME COLUMN `',current_column,'` TO `',target_column,'`');
                    PREPARE price_statement FROM @cyf_free_skill_sql;
                    SET statement_ready=TRUE;
                    EXECUTE price_statement;
                    DEALLOCATE PREPARE price_statement;
                    SET statement_ready=FALSE;
                END IF;
            END IF;
            SET table_index=table_index+1;
        END WHILE;
        SET phase=phase+1;
    END WHILE;
    DO RELEASE_LOCK('cyf:economy-v0:skill-schema');
END$$
DELIMITER ;
