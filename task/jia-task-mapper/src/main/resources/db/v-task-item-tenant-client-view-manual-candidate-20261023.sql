-- MANUAL-ONLY view replacement candidate captured from SHOW CREATE VIEW jia.v_task_item
-- on 2026-10-23. The projection and ordering are unchanged; only the join scope is hardened.
-- Execute with character_set_client=utf8mb4 and collation_connection=utf8mb4_0900_ai_ci,
-- matching the captured live view metadata.

CREATE OR REPLACE ALGORITHM=UNDEFINED
DEFINER=`root`@`%`
SQL SECURITY DEFINER
VIEW `v_task_item` AS
SELECT
  `i`.`id` AS `id`,
  `i`.`plan_id` AS `plan_id`,
  `p`.`jiacn` AS `jiacn`,
  `p`.`type` AS `type`,
  `p`.`period` AS `period`,
  `p`.`crond` AS `crond`,
  `p`.`name` AS `name`,
  `p`.`description` AS `description`,
  `p`.`amount` AS `amount`,
  `p`.`remind` AS `remind`,
  `p`.`remind_phone` AS `remind_phone`,
  `p`.`remind_msg` AS `remind_msg`,
  `i`.`status` AS `status`,
  `i`.`execute_time` AS `execute_time`,
  `i`.`create_time` AS `create_time`,
  `i`.`update_time` AS `update_time`,
  `i`.`client_id` AS `client_id`,
  `i`.`tenant_id` AS `tenant_id`
FROM (`task_plan` `p`
  JOIN `task_item` `i`
    ON (`p`.`id` = `i`.`plan_id`
      AND `p`.`tenant_id` = `i`.`tenant_id`
      AND CAST(`p`.`tenant_id` AS BINARY) = CAST(`i`.`tenant_id` AS BINARY)
      AND OCTET_LENGTH(`p`.`tenant_id`) = OCTET_LENGTH(`i`.`tenant_id`)
      AND `p`.`client_id` = `i`.`client_id`
      AND CAST(`p`.`client_id` AS BINARY) = CAST(`i`.`client_id` AS BINARY)
      AND OCTET_LENGTH(`p`.`client_id`) = OCTET_LENGTH(`i`.`client_id`)))
ORDER BY `i`.`create_time`;
