-- ============================================================================
-- Migration: Set tenant_id default to '0' and backfill NULL values
-- Date: 2026-08-01
-- Target: jia / jia_dev (MySQL 8.x)
-- Run as: mysql -h <host> -u <admin_user> -p <database> < this_file.sql
-- ============================================================================

-- =========================================================
-- Phase 1: Backfill all NULL tenant_id values to '0'
-- Collaboration-owned agent_task_meta is intentionally excluded; B09/C01H owns its audited migration.
-- Shared chat facts are excluded from historical NULL-to-'0' rewrites because this script cannot
-- distinguish task-thread rows; any data normalization requires a separate audited migration.
-- =========================================================

UPDATE `agent_persona`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `agent_runtime`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `agent_task_note`      SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `core_dict`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `core_log`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `core_notice`          SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `dialogue_template`    SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `dwz_record`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `isp_file`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `kefu_faq`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `kefu_message`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `kefu_msg_log`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `kefu_msg_subscribe`   SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `kefu_msg_type`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_media`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_news`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_phrase`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_phrase_vote`      SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_pv_log`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_tip`              SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_vote`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_vote_item`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_vote_question`    SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `mat_vote_tick`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `oauth_api_key`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `oauth_client`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `point_gift`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `point_gift_usage`     SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `point_record`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `point_referral`       SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `point_sign`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_buy`              SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_code`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_config`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_message`          SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_package`          SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_reply`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_send`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `sms_template`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `task_item`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `task_plan`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_group`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_group_rel`       SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_info`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_msg`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_org`             SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_org_rel`         SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_perms`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_perms_rel`       SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_role`            SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `user_role_rel`        SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `wx_mp_info`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `wx_mp_template`       SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `wx_mp_user`           SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `wx_pay_info`          SET tenant_id = '0' WHERE tenant_id IS NULL;
UPDATE `wx_pay_order`         SET tenant_id = '0' WHERE tenant_id IS NULL;

-- =========================================================
-- Phase 2: ALTER DEFAULT NULL → DEFAULT '0'
-- Collaboration-owned agent_task_meta keeps its nullable legacy default until audited B09/C01H handling.
-- Shared chat DEFAULT '0' remains for future generic chat writes; no historical chat facts are rewritten here.
-- =========================================================

ALTER TABLE `agent_persona`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `agent_runtime`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `agent_task_note`      MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `chat_conversation`    MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `chat_message`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `core_dict`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `core_log`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `core_notice`          MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `dialogue_template`    MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `dwz_record`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `isp_file`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `kefu_faq`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `kefu_message`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `kefu_msg_log`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `kefu_msg_subscribe`   MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `kefu_msg_type`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_media`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_news`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_phrase`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_phrase_vote`      MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_pv_log`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_tip`              MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_vote`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_vote_item`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_vote_question`    MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `mat_vote_tick`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `oauth_api_key`        MODIFY COLUMN tenant_id varchar(100) DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `oauth_client`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `point_gift`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `point_gift_usage`     MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `point_record`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `point_referral`       MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `point_sign`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_buy`              MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_code`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_config`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_message`          MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_package`          MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_reply`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_send`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `sms_template`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `task_item`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `task_plan`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_group`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_group_rel`       MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_info`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_msg`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_org`             MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_org_rel`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_perms`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_perms_rel`       MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_role`            MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `user_role_rel`        MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `wx_mp_info`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `wx_mp_template`       MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `wx_mp_user`           MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `wx_pay_info`          MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';
ALTER TABLE `wx_pay_order`         MODIFY COLUMN tenant_id varchar(50)  DEFAULT '0' COMMENT '租户ID';

-- =========================================================
-- Phase 3: Update CHECK constraint for agent_persona_binding
-- (This table may not exist in all schemas; safe to error)
-- =========================================================

-- ALTER TABLE agent_persona_binding DROP CONSTRAINT chk_agent_binding_tenant_owner;
-- ALTER TABLE agent_persona_binding ADD CONSTRAINT chk_agent_binding_tenant_owner
--     CHECK (tenant_id = '0' OR tenant_id = owner_jiacn);

-- =========================================================
-- Verification (uncomment to run)
-- =========================================================
-- SELECT 'NULL check' AS phase, COUNT(*) AS remaining FROM (
--   SELECT 'agent_persona' AS t, COUNT(*) AS n FROM agent_persona WHERE tenant_id IS NULL UNION ALL
--   SELECT 'agent_runtime', COUNT(*) FROM agent_runtime WHERE tenant_id IS NULL UNION ALL
--   -- ... (copy all tables from Phase 1)
-- ) x WHERE n > 0;
