-- CYF-MEMORY-PUBLICATION-V2-PENDING-BACKFILL-20260924, manual MySQL 8 migration.
-- Deployment order (workers remain disabled until every step succeeds):
-- 1. Apply memory-publication-v2-20260924.sql.
-- 2. Deploy every message signal writer that derives work scope from chat_conversation.
-- 3. Keep all memory publication workers disabled.
-- 4. Execute this backfill; it is safe to repeat after inspection or interruption.
-- 5. Inspect orphan/invalid rows, mixed-scope pollution, and canonical PENDING rows not enqueued.
-- 6. Enable workers only after orphan, invalid-scope, and canonical-not-enqueued results are empty.
--    Mixed-scope pollution remains an explicit cleanup report but never suppresses canonical work.

SET @cyf_memory_backfill_now := CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED);

DROP PROCEDURE IF EXISTS cyf_validate_memory_pending_backfill;
DELIMITER $$
CREATE PROCEDURE cyf_validate_memory_pending_backfill()
BEGIN
  IF EXISTS (
    SELECT 1
    FROM chat_message m
    LEFT JOIN chat_conversation c
      ON CAST(c.id AS CHAR) = m.conversation_id
     AND BINARY CAST(c.id AS CHAR) = BINARY m.conversation_id
     AND OCTET_LENGTH(CAST(c.id AS CHAR)) = OCTET_LENGTH(m.conversation_id)
    WHERE m.sync_status = 'PENDING' AND c.id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory backfill has orphan PENDING messages';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM chat_conversation c
    WHERE EXISTS (
      SELECT 1 FROM chat_message m
      WHERE m.sync_status = 'PENDING'
        AND CAST(c.id AS CHAR) = m.conversation_id
        AND BINARY CAST(c.id AS CHAR) = BINARY m.conversation_id
        AND OCTET_LENGTH(CAST(c.id AS CHAR)) = OCTET_LENGTH(m.conversation_id)
    )
      AND (c.data_scope_mode NOT IN ('LEGACY_CLIENT', 'DOMAIN')
        OR c.tenant_id IS NULL OR c.tenant_id = ''
        OR c.jiacn IS NULL OR c.jiacn = ''
        OR (c.data_scope_mode = 'LEGACY_CLIENT'
            AND (c.tenant_id <> '0' OR c.client_id IS NULL OR c.client_id = ''))
        OR (c.data_scope_mode = 'DOMAIN'
            AND (c.data_domain_id IS NULL OR c.data_domain_id = '')))
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory backfill has invalid canonical scope';
  END IF;
END$$
CALL cyf_validate_memory_pending_backfill()$$
DROP PROCEDURE cyf_validate_memory_pending_backfill$$
DELIMITER ;

-- BEGIN CANONICAL_PENDING_WORK_UPSERT
INSERT INTO chat_memory_work
    (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id,
     lifecycle_generation, state, due_at, lease_owner, lease_until,
     attempts, wake_requested, last_error, create_time, update_time)
SELECT c.tenant_id, c.data_scope_mode,
       CASE WHEN c.data_scope_mode = 'LEGACY_CLIENT'
            THEN c.client_id ELSE c.data_domain_id END,
       c.jiacn, CAST(c.id AS CHAR), c.lifecycle_generation,
       CASE
         WHEN c.conversation_scope_type = 'task_thread'
           OR EXISTS (
             SELECT 1 FROM agent_task_thread t
             WHERE BINARY t.conversation_id = BINARY CAST(c.id AS CHAR)
               AND OCTET_LENGTH(t.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
           ) THEN 'DEAD'
         ELSE 'READY'
       END,
       @cyf_memory_backfill_now, NULL, NULL, 0, 0,
       CASE
         WHEN c.conversation_scope_type = 'task_thread'
           OR EXISTS (
             SELECT 1 FROM agent_task_thread t
             WHERE BINARY t.conversation_id = BINARY CAST(c.id AS CHAR)
               AND OCTET_LENGTH(t.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
           ) THEN 'historical task-thread PENDING rows are excluded from memory'
         WHEN EXISTS (
           SELECT 1 FROM chat_message mixed
           WHERE mixed.sync_status = 'PENDING'
             AND BINARY mixed.conversation_id = BINARY CAST(c.id AS CHAR)
             AND OCTET_LENGTH(mixed.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
             AND (mixed.tenant_id IS NULL OR BINARY mixed.tenant_id <> BINARY c.tenant_id
               OR OCTET_LENGTH(mixed.tenant_id) <> OCTET_LENGTH(c.tenant_id)
               OR mixed.jiacn IS NULL OR BINARY mixed.jiacn <> BINARY c.jiacn
               OR OCTET_LENGTH(mixed.jiacn) <> OCTET_LENGTH(c.jiacn)
               OR (c.data_scope_mode = 'LEGACY_CLIENT'
                   AND (mixed.client_id IS NULL OR BINARY mixed.client_id <> BINARY c.client_id
                        OR OCTET_LENGTH(mixed.client_id) <> OCTET_LENGTH(c.client_id)))
               OR (c.data_scope_mode = 'DOMAIN'
                   AND (mixed.data_domain_id IS NULL
                        OR BINARY mixed.data_domain_id <> BINARY c.data_domain_id
                        OR OCTET_LENGTH(mixed.data_domain_id) <> OCTET_LENGTH(c.data_domain_id))))
         ) THEN 'mixed-scope PENDING rows ignored; canonical PENDING work remains processable'
         ELSE NULL
       END,
       @cyf_memory_backfill_now, @cyf_memory_backfill_now
FROM chat_conversation c
WHERE EXISTS (
  SELECT 1 FROM chat_message canonical
  WHERE canonical.sync_status = 'PENDING'
    AND BINARY canonical.conversation_id = BINARY CAST(c.id AS CHAR)
    AND OCTET_LENGTH(canonical.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
    AND canonical.tenant_id IS NOT NULL
    AND BINARY canonical.tenant_id = BINARY c.tenant_id
    AND OCTET_LENGTH(canonical.tenant_id) = OCTET_LENGTH(c.tenant_id)
    AND canonical.jiacn IS NOT NULL
    AND BINARY canonical.jiacn = BINARY c.jiacn
    AND OCTET_LENGTH(canonical.jiacn) = OCTET_LENGTH(c.jiacn)
    AND ((c.data_scope_mode = 'LEGACY_CLIENT'
          AND canonical.client_id IS NOT NULL
          AND BINARY canonical.client_id = BINARY c.client_id
          AND OCTET_LENGTH(canonical.client_id) = OCTET_LENGTH(c.client_id))
      OR (c.data_scope_mode = 'DOMAIN'
          AND canonical.data_domain_id IS NOT NULL
          AND BINARY canonical.data_domain_id = BINARY c.data_domain_id
          AND OCTET_LENGTH(canonical.data_domain_id) = OCTET_LENGTH(c.data_domain_id)))
)
ON DUPLICATE KEY UPDATE
  lifecycle_generation = CASE WHEN state = 'LEASED'
      THEN lifecycle_generation ELSE VALUES(lifecycle_generation) END,
  due_at = LEAST(due_at, VALUES(due_at)),
  wake_requested = CASE WHEN state = 'LEASED' THEN 1 ELSE 0 END,
  state = CASE WHEN state = 'LEASED' THEN state ELSE VALUES(state) END,
  last_error = CASE WHEN VALUES(last_error) IS NOT NULL THEN VALUES(last_error)
      WHEN state = 'LEASED' THEN last_error ELSE NULL END,
  update_time = VALUES(update_time);
-- END CANONICAL_PENDING_WORK_UPSERT

-- Orphan PENDING rows: must be empty.
SELECT m.id, m.conversation_id
FROM chat_message m
LEFT JOIN chat_conversation c
  ON BINARY CAST(c.id AS CHAR) = BINARY m.conversation_id
 AND OCTET_LENGTH(CAST(c.id AS CHAR)) = OCTET_LENGTH(m.conversation_id)
WHERE m.sync_status = 'PENDING' AND c.id IS NULL;

-- Conversations with invalid canonical scope: must be empty.
SELECT c.id, c.tenant_id, c.data_scope_mode, c.client_id, c.data_domain_id, c.jiacn
FROM chat_conversation c
WHERE EXISTS (
  SELECT 1 FROM chat_message m
  WHERE m.sync_status = 'PENDING'
    AND BINARY CAST(c.id AS CHAR) = BINARY m.conversation_id
    AND OCTET_LENGTH(CAST(c.id AS CHAR)) = OCTET_LENGTH(m.conversation_id)
)
AND (c.data_scope_mode NOT IN ('LEGACY_CLIENT', 'DOMAIN')
  OR (c.data_scope_mode = 'LEGACY_CLIENT'
      AND (c.tenant_id <> '0' OR c.client_id IS NULL OR c.client_id = ''))
  OR (c.data_scope_mode = 'DOMAIN'
      AND (c.data_domain_id IS NULL OR c.data_domain_id = '')));

-- Mixed-scope pollution report. These rows are never used to suppress canonical work.
SELECT mixed.id AS message_id, mixed.conversation_id,
       mixed.tenant_id AS message_tenant_id, c.tenant_id AS canonical_tenant_id,
       mixed.client_id AS message_client_id, c.client_id AS canonical_client_id,
       mixed.data_domain_id AS message_data_domain_id,
       c.data_domain_id AS canonical_data_domain_id,
       mixed.jiacn AS message_owner_jiacn, c.jiacn AS canonical_owner_jiacn
FROM chat_message mixed
JOIN chat_conversation c
  ON BINARY mixed.conversation_id = BINARY CAST(c.id AS CHAR)
 AND OCTET_LENGTH(mixed.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
WHERE mixed.sync_status = 'PENDING'
  AND (mixed.tenant_id IS NULL OR BINARY mixed.tenant_id <> BINARY c.tenant_id
    OR OCTET_LENGTH(mixed.tenant_id) <> OCTET_LENGTH(c.tenant_id)
    OR mixed.jiacn IS NULL OR BINARY mixed.jiacn <> BINARY c.jiacn
    OR OCTET_LENGTH(mixed.jiacn) <> OCTET_LENGTH(c.jiacn)
    OR (c.data_scope_mode = 'LEGACY_CLIENT'
        AND (mixed.client_id IS NULL OR BINARY mixed.client_id <> BINARY c.client_id
             OR OCTET_LENGTH(mixed.client_id) <> OCTET_LENGTH(c.client_id)))
    OR (c.data_scope_mode = 'DOMAIN'
        AND (mixed.data_domain_id IS NULL
             OR BINARY mixed.data_domain_id <> BINARY c.data_domain_id
             OR OCTET_LENGTH(mixed.data_domain_id) <> OCTET_LENGTH(c.data_domain_id))));

-- Canonical, non-task-thread PENDING rows without their exact work item: must be empty.
SELECT canonical.id AS message_id, canonical.conversation_id,
       c.tenant_id, c.data_scope_mode,
       CASE WHEN c.data_scope_mode = 'LEGACY_CLIENT'
            THEN c.client_id ELSE c.data_domain_id END AS scope_key,
       c.jiacn AS owner_jiacn
FROM chat_message canonical
JOIN chat_conversation c
  ON BINARY canonical.conversation_id = BINARY CAST(c.id AS CHAR)
 AND OCTET_LENGTH(canonical.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
LEFT JOIN chat_memory_work w
  ON BINARY w.tenant_id = BINARY c.tenant_id
 AND OCTET_LENGTH(w.tenant_id) = OCTET_LENGTH(c.tenant_id)
 AND BINARY w.scope_mode = BINARY c.data_scope_mode
 AND BINARY w.scope_key = BINARY CASE WHEN c.data_scope_mode = 'LEGACY_CLIENT'
                                      THEN c.client_id ELSE c.data_domain_id END
 AND BINARY w.owner_jiacn = BINARY c.jiacn
 AND BINARY w.conversation_id = BINARY CAST(c.id AS CHAR)
 AND OCTET_LENGTH(w.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
WHERE canonical.sync_status = 'PENDING'
  AND c.deleted_at IS NULL
  AND c.conversation_scope_type <> 'task_thread'
  AND NOT EXISTS (
    SELECT 1 FROM agent_task_thread t
    WHERE BINARY t.conversation_id = BINARY CAST(c.id AS CHAR)
      AND OCTET_LENGTH(t.conversation_id) = OCTET_LENGTH(CAST(c.id AS CHAR))
  )
  AND BINARY canonical.tenant_id = BINARY c.tenant_id
  AND OCTET_LENGTH(canonical.tenant_id) = OCTET_LENGTH(c.tenant_id)
  AND BINARY canonical.jiacn = BINARY c.jiacn
  AND OCTET_LENGTH(canonical.jiacn) = OCTET_LENGTH(c.jiacn)
  AND ((c.data_scope_mode = 'LEGACY_CLIENT'
        AND BINARY canonical.client_id = BINARY c.client_id
        AND OCTET_LENGTH(canonical.client_id) = OCTET_LENGTH(c.client_id))
    OR (c.data_scope_mode = 'DOMAIN'
        AND BINARY canonical.data_domain_id = BINARY c.data_domain_id
        AND OCTET_LENGTH(canonical.data_domain_id) = OCTET_LENGTH(c.data_domain_id)))
  AND w.id IS NULL;
