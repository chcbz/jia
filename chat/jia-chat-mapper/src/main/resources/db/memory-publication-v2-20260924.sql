-- CYF-MEMORY-PUBLICATION-V2-20260924, manual MySQL 8 migration.
-- This script is not executed by application startup. It is resumable only for
-- the same migration_id + deterministic migration_digest. Every existing target
-- object is validated exactly through information_schema before it is reused.
-- Missing objects are created only for a fresh/APPLYING ledger; APPLIED drift and
-- unledgered partial schema fail closed.
--
-- Historical tenant_id <> '0' conversations require an operator-reviewed
-- chat_conversation_scope_audit table with the exact schema validated below.

SET @cyf_memory_v2_lock_name := CONCAT(
    COALESCE(DATABASE(), '<no-database>'), ':CYF-MEMORY-PUBLICATION-V2-20260924');
SET @cyf_memory_v2_lock_result := GET_LOCK(@cyf_memory_v2_lock_name, 0);
SET @cyf_memory_v2_lock_guard_sql := IF(
    @cyf_memory_v2_lock_result = 1,
    'SELECT 1',
    'SELECT * FROM `__cyf_memory_publication_v2_migration_lock_is_held__`');
PREPARE cyf_memory_v2_lock_guard FROM @cyf_memory_v2_lock_guard_sql;
EXECUTE cyf_memory_v2_lock_guard;
DEALLOCATE PREPARE cyf_memory_v2_lock_guard;

DROP PROCEDURE IF EXISTS cyf_memory_v2_assert_table;
DROP PROCEDURE IF EXISTS cyf_memory_v2_assert_column;
DROP PROCEDURE IF EXISTS cyf_memory_publication_v2_apply;

DELIMITER $$
CREATE PROCEDURE cyf_memory_v2_assert_table(
    IN p_table VARCHAR(64), IN p_columns LONGTEXT,
    IN p_indexes LONGTEXT, IN p_checks LONGTEXT)
BEGIN
  DECLARE v_columns LONGTEXT;
  DECLARE v_indexes LONGTEXT;
  DECLARE v_checks LONGTEXT;

  IF (SELECT COUNT(*) FROM information_schema.tables t
      WHERE t.table_schema = DATABASE()
        AND BINARY t.table_name = BINARY p_table
        AND t.table_type = 'BASE TABLE'
        AND t.engine = 'InnoDB'
        AND t.table_collation = 'utf8mb4_0900_bin') <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 table option drift';
  END IF;

  SELECT GROUP_CONCAT(CONCAT_WS('|', c.ordinal_position, c.column_name,
             LOWER(c.data_type), LOWER(c.column_type), c.is_nullable,
             COALESCE(c.column_default, '<NULL>'),
             COALESCE(c.character_set_name, '<NULL>'),
             COALESCE(c.collation_name, '<NULL>'), LOWER(c.extra),
             c.column_comment, c.generation_expression)
           ORDER BY c.ordinal_position SEPARATOR ';')
    INTO v_columns
  FROM information_schema.columns c
  WHERE c.table_schema = DATABASE() AND BINARY c.table_name = BINARY p_table;

  SELECT GROUP_CONCAT(CONCAT_WS('|', s.index_name, s.non_unique, s.seq_in_index,
             s.column_name, COALESCE(s.sub_part, '<NULL>'), s.collation,
             s.index_type, COALESCE(s.expression, '<NULL>'), s.is_visible)
           ORDER BY BINARY s.index_name, s.seq_in_index SEPARATOR ';')
    INTO v_indexes
  FROM information_schema.statistics s
  WHERE s.table_schema = DATABASE() AND BINARY s.table_name = BINARY p_table;

  SELECT GROUP_CONCAT(CONCAT(tc.constraint_name, '|',
             REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
               UPPER(cc.check_clause), '_UTF8MB4', ''), '_ASCII', ''),
               '\\', ''), '`', ''), ' ', ''), CHAR(10), ''),
               CHAR(13), ''), CHAR(9), ''), '(', ''), ')', ''))
           ORDER BY BINARY tc.constraint_name SEPARATOR ';')
    INTO v_checks
  FROM information_schema.table_constraints tc
  JOIN information_schema.check_constraints cc
    ON cc.constraint_schema = tc.constraint_schema
   AND BINARY cc.constraint_name = BINARY tc.constraint_name
  WHERE tc.table_schema = DATABASE()
    AND BINARY tc.table_name = BINARY p_table
    AND tc.constraint_type = 'CHECK' AND tc.enforced = 'YES';

  IF NOT (BINARY v_columns <=> BINARY p_columns)
     OR NOT (BINARY v_indexes <=> BINARY p_indexes)
     OR NOT (BINARY COALESCE(v_checks, '') <=> BINARY p_checks) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 table definition drift';
  END IF;
END$$

CREATE PROCEDURE cyf_memory_v2_assert_column(
    IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_ordinal INT,
    IN p_signature LONGTEXT)
BEGIN
  DECLARE v_signature LONGTEXT;
  SELECT CONCAT_WS('|', c.ordinal_position, c.column_name,
             LOWER(c.data_type), LOWER(c.column_type), c.is_nullable,
             COALESCE(c.column_default, '<NULL>'),
             COALESCE(c.character_set_name, '<NULL>'),
             COALESCE(c.collation_name, '<NULL>'), LOWER(c.extra),
             c.column_comment, c.generation_expression)
    INTO v_signature
  FROM information_schema.columns c
  WHERE c.table_schema = DATABASE()
    AND BINARY c.table_name = BINARY p_table
    AND BINARY c.column_name = BINARY p_column
    AND c.ordinal_position = p_ordinal;
  IF NOT (BINARY v_signature <=> BINARY p_signature) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 column definition drift';
  END IF;
END$$

CREATE PROCEDURE cyf_memory_publication_v2_apply()
main: BEGIN
  DECLARE v_lock_result INT DEFAULT 0;
  DECLARE v_lock_name VARCHAR(255);
  DECLARE v_count INT DEFAULT 0;
  DECLARE v_state VARCHAR(20);
  DECLARE v_stored_digest CHAR(64);
  DECLARE v_digest CHAR(64);
  DECLARE v_fresh INT DEFAULT 0;
  DECLARE v_applied INT DEFAULT 0;
  DECLARE v_ordinal INT;
  DECLARE v_sql LONGTEXT;

  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    IF v_lock_result = 1 THEN DO RELEASE_LOCK(v_lock_name); END IF;
    RESIGNAL;
  END;

  IF @@session.group_concat_max_len < 65535 THEN
    SET SESSION group_concat_max_len = 65535;
  END IF;
  IF DATABASE() IS NULL OR DATABASE() = '' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 requires an explicit database';
  END IF;
  SET v_lock_name = @cyf_memory_v2_lock_name;
  SET v_lock_result = @cyf_memory_v2_lock_result;
  IF v_lock_result <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 migration lock is held';
  END IF;
  SET v_digest = SHA2(
      'CYF-MEMORY-PUBLICATION-V2-20260924|contract-v3|resumable|exact-schema|audited-scope|stable-payload',
      256);

  IF (SELECT COUNT(*) FROM information_schema.tables
      WHERE table_schema = DATABASE() AND BINARY table_name = BINARY 'chat_conversation'
        AND table_type = 'BASE TABLE') <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 requires chat_conversation';
  END IF;

  SELECT COUNT(*) INTO v_count FROM information_schema.tables
  WHERE table_schema = DATABASE()
    AND BINARY table_name = BINARY 'chat_schema_migration_ledger';
  IF v_count = 0 THEN
    SET v_sql = 'CREATE TABLE chat_schema_migration_ledger (
      migration_id VARCHAR(100) NOT NULL,
      migration_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
      state VARCHAR(20) NOT NULL,
      started_at BIGINT NOT NULL,
      applied_at BIGINT DEFAULT NULL,
      PRIMARY KEY (migration_id),
      CONSTRAINT chk_chat_schema_migration_state CHECK (state IN (''APPLYING'', ''APPLIED''))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql;
    EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 ledger table is ambiguous';
  END IF;

  CALL cyf_memory_v2_assert_table('chat_schema_migration_ledger',
    '1|migration_id|varchar|varchar(100)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;2|migration_digest|char|char(64)|NO|<NULL>|ascii|ascii_bin|||;3|state|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;4|started_at|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;5|applied_at|bigint|bigint|YES|<NULL>|<NULL>|<NULL>|||',
    'PRIMARY|0|1|migration_id|<NULL>|A|BTREE|<NULL>|YES',
    'chk_chat_schema_migration_state|STATEIN''APPLYING'',''APPLIED''');

  SELECT COUNT(*) INTO v_count FROM chat_schema_migration_ledger
  WHERE migration_id = 'CYF-MEMORY-PUBLICATION-V2-20260924';
  IF v_count = 0 THEN
    SET v_fresh = 1;
    INSERT INTO chat_schema_migration_ledger
        (migration_id, migration_digest, state, started_at, applied_at)
    VALUES ('CYF-MEMORY-PUBLICATION-V2-20260924', v_digest, 'APPLYING',
            CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED), NULL);
  ELSEIF v_count = 1 THEN
    SELECT state, migration_digest INTO v_state, v_stored_digest
    FROM chat_schema_migration_ledger
    WHERE migration_id = 'CYF-MEMORY-PUBLICATION-V2-20260924';
    IF BINARY v_stored_digest <> BINARY v_digest THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'memory publication v2 migration digest mismatch';
    END IF;
    IF v_state = 'APPLIED' THEN
      SET v_applied = 1;
    ELSEIF v_state <> 'APPLYING' THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'memory publication v2 ledger state is invalid';
    END IF;
  ELSE
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 ledger row is ambiguous';
  END IF;

  SELECT COUNT(*) INTO v_count FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_conversation'
    AND column_name IN ('data_scope_mode', 'memory_published_token',
                        'memory_published_revision');
  IF v_fresh = 1 AND (v_count <> 0 OR EXISTS (
      SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND table_name IN ('chat_memory_publication', 'chat_memory_work'))) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 unledgered partial schema detected';
  END IF;

  IF EXISTS (SELECT 1 FROM chat_conversation WHERE tenant_id <> '0') THEN
    IF (SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND BINARY table_name = BINARY 'chat_conversation_scope_audit') <> 1 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'audited non-zero conversation scope mapping is required';
    END IF;
    CALL cyf_memory_v2_assert_table('chat_conversation_scope_audit',
      '1|conversation_id|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;2|scope_mode|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;3|scope_key|varchar|varchar(64)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;4|mapping_digest|char|char(64)|NO|<NULL>|ascii|ascii_bin|||;5|approved_at|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||',
      'PRIMARY|0|1|conversation_id|<NULL>|A|BTREE|<NULL>|YES', '');
    IF EXISTS (
      SELECT 1 FROM chat_conversation c
      LEFT JOIN chat_conversation_scope_audit a ON a.conversation_id = c.id
      WHERE c.tenant_id <> '0'
        AND (a.conversation_id IS NULL OR a.scope_mode <> 'DOMAIN'
          OR BINARY a.scope_key <> BINARY c.data_domain_id
          OR OCTET_LENGTH(a.scope_key) <> OCTET_LENGTH(c.data_domain_id)
          OR a.mapping_digest NOT REGEXP '^[0-9a-f]{64}$'
          OR a.approved_at IS NULL)
    ) THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'non-zero conversation scope mapping is incomplete or invalid';
    END IF;
  END IF;

  SELECT ordinal_position INTO v_ordinal FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_conversation'
    AND column_name = 'lifecycle_generation';
  IF v_ordinal IS NULL THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 lifecycle_generation is missing';
  END IF;
  SET v_ordinal = v_ordinal + 1;
  SELECT COUNT(*) INTO v_count FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_conversation'
    AND column_name = 'data_scope_mode';
  IF v_count = 0 THEN
    IF v_applied = 1 THEN SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 APPLIED column is missing'; END IF;
    SET v_sql = 'ALTER TABLE chat_conversation ADD COLUMN data_scope_mode VARCHAR(20)
      CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ''LEGACY_CLIENT''
      COMMENT ''LEGACY_CLIENT or DOMAIN'' AFTER lifecycle_generation';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql; EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory publication v2 data_scope_mode is ambiguous';
  END IF;
  CALL cyf_memory_v2_assert_column('chat_conversation', 'data_scope_mode', v_ordinal,
      CONCAT(v_ordinal, '|data_scope_mode|varchar|varchar(20)|NO|LEGACY_CLIENT|ascii|ascii_bin||LEGACY_CLIENT or DOMAIN|'));

  SET v_ordinal = v_ordinal + 1;
  SELECT COUNT(*) INTO v_count FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_conversation'
    AND column_name = 'memory_published_token';
  IF v_count = 0 THEN
    IF v_applied = 1 THEN SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 APPLIED token column is missing'; END IF;
    SET v_sql = 'ALTER TABLE chat_conversation ADD COLUMN memory_published_token CHAR(64)
      CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
      COMMENT ''Visible memory snapshot token'' AFTER data_scope_mode';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql; EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory publication v2 token column is ambiguous';
  END IF;
  CALL cyf_memory_v2_assert_column('chat_conversation', 'memory_published_token', v_ordinal,
      CONCAT(v_ordinal, '|memory_published_token|char|char(64)|YES|<NULL>|ascii|ascii_bin||Visible memory snapshot token|'));

  SET v_ordinal = v_ordinal + 1;
  SELECT COUNT(*) INTO v_count FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'chat_conversation'
    AND column_name = 'memory_published_revision';
  IF v_count = 0 THEN
    IF v_applied = 1 THEN SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 APPLIED revision column is missing'; END IF;
    SET v_sql = 'ALTER TABLE chat_conversation ADD COLUMN memory_published_revision BIGINT
      DEFAULT NULL COMMENT ''Visible memory Elasticsearch revision'' AFTER memory_published_token';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql; EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory publication v2 revision column is ambiguous';
  END IF;
  CALL cyf_memory_v2_assert_column('chat_conversation', 'memory_published_revision', v_ordinal,
      CONCAT(v_ordinal, '|memory_published_revision|bigint|bigint|YES|<NULL>|<NULL>|<NULL>||Visible memory Elasticsearch revision|'));

  UPDATE chat_conversation SET data_scope_mode = 'LEGACY_CLIENT'
  WHERE tenant_id = '0' AND data_scope_mode <> 'LEGACY_CLIENT';
  IF EXISTS (SELECT 1 FROM information_schema.tables
      WHERE table_schema = DATABASE()
        AND BINARY table_name = BINARY 'chat_conversation_scope_audit') THEN
    UPDATE chat_conversation c
    JOIN chat_conversation_scope_audit a ON a.conversation_id = c.id
    SET c.data_scope_mode = 'DOMAIN'
    WHERE c.tenant_id <> '0' AND a.scope_mode = 'DOMAIN'
      AND BINARY a.scope_key = BINARY c.data_domain_id
      AND OCTET_LENGTH(a.scope_key) = OCTET_LENGTH(c.data_domain_id);
  END IF;

  SELECT COUNT(*) INTO v_count FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'chat_memory_publication';
  IF v_count = 0 THEN
    IF v_applied = 1 THEN SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 APPLIED publication table is missing'; END IF;
    SET v_sql = 'CREATE TABLE chat_memory_publication (
      id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(50) NOT NULL,
      scope_mode VARCHAR(20) NOT NULL, scope_key VARCHAR(64) NOT NULL,
      owner_jiacn VARCHAR(50) NOT NULL, conversation_id VARCHAR(100) NOT NULL,
      lifecycle_generation BIGINT NOT NULL, snapshot_token CHAR(64) NOT NULL,
      es_revision BIGINT NOT NULL, document_id VARCHAR(96) NOT NULL,
      message_count INT NOT NULL, state VARCHAR(20) NOT NULL,
      summary_content LONGTEXT DEFAULT NULL,
      embedding_bytes BLOB DEFAULT NULL,
      payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
      published_at BIGINT DEFAULT NULL, tombstoned_at BIGINT DEFAULT NULL,
      create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
      PRIMARY KEY (id),
      UNIQUE KEY uk_memory_publication_token
        (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id, lifecycle_generation, snapshot_token),
      UNIQUE KEY uk_memory_publication_revision
        (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id, es_revision),
      UNIQUE KEY uk_memory_publication_document (document_id),
      CONSTRAINT chk_memory_publication_mode CHECK (scope_mode IN (''LEGACY_CLIENT'', ''DOMAIN'')),
      CONSTRAINT chk_memory_publication_state CHECK (state IN (''ALLOCATED'', ''STAGED'', ''PUBLISHED'', ''TOMBSTONED'')),
      CONSTRAINT chk_memory_publication_payload CHECK (
        (state IN (''ALLOCATED'', ''TOMBSTONED'') AND summary_content IS NULL
          AND embedding_bytes IS NULL AND payload_digest IS NULL)
        OR (state IN (''STAGED'', ''PUBLISHED'', ''TOMBSTONED'') AND summary_content IS NOT NULL
          AND embedding_bytes IS NOT NULL AND payload_digest IS NOT NULL)),
      CONSTRAINT chk_memory_publication_revision CHECK (es_revision > 0),
      CONSTRAINT chk_memory_publication_count CHECK (message_count > 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql; EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory publication v2 publication table is ambiguous';
  END IF;
  CALL cyf_memory_v2_assert_table('chat_memory_publication',
    '1|id|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|auto_increment||;2|tenant_id|varchar|varchar(50)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;3|scope_mode|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;4|scope_key|varchar|varchar(64)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;5|owner_jiacn|varchar|varchar(50)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;6|conversation_id|varchar|varchar(100)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;7|lifecycle_generation|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;8|snapshot_token|char|char(64)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;9|es_revision|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;10|document_id|varchar|varchar(96)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;11|message_count|int|int|NO|<NULL>|<NULL>|<NULL>|||;12|state|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;13|summary_content|longtext|longtext|YES|<NULL>|utf8mb4|utf8mb4_0900_bin|||;14|embedding_bytes|blob|blob|YES|<NULL>|<NULL>|<NULL>|||;15|payload_digest|char|char(64)|YES|<NULL>|ascii|ascii_bin|||;16|published_at|bigint|bigint|YES|<NULL>|<NULL>|<NULL>|||;17|tombstoned_at|bigint|bigint|YES|<NULL>|<NULL>|<NULL>|||;18|create_time|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;19|update_time|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||',
    'PRIMARY|0|1|id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_document|0|1|document_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|1|tenant_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|2|scope_mode|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|3|scope_key|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|4|owner_jiacn|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|5|conversation_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_revision|0|6|es_revision|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|1|tenant_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|2|scope_mode|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|3|scope_key|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|4|owner_jiacn|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|5|conversation_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|6|lifecycle_generation|<NULL>|A|BTREE|<NULL>|YES;uk_memory_publication_token|0|7|snapshot_token|<NULL>|A|BTREE|<NULL>|YES',
    'chk_memory_publication_count|MESSAGE_COUNT>0;chk_memory_publication_mode|SCOPE_MODEIN''LEGACY_CLIENT'',''DOMAIN'';chk_memory_publication_payload|STATEIN''ALLOCATED'',''TOMBSTONED''ANDSUMMARY_CONTENTISNULLANDEMBEDDING_BYTESISNULLANDPAYLOAD_DIGESTISNULLORSTATEIN''STAGED'',''PUBLISHED'',''TOMBSTONED''ANDSUMMARY_CONTENTISNOTNULLANDEMBEDDING_BYTESISNOTNULLANDPAYLOAD_DIGESTISNOTNULL;chk_memory_publication_revision|ES_REVISION>0;chk_memory_publication_state|STATEIN''ALLOCATED'',''STAGED'',''PUBLISHED'',''TOMBSTONED''');

  SELECT COUNT(*) INTO v_count FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'chat_memory_work';
  IF v_count = 0 THEN
    IF v_applied = 1 THEN SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 APPLIED work table is missing'; END IF;
    SET v_sql = 'CREATE TABLE chat_memory_work (
      id BIGINT NOT NULL AUTO_INCREMENT, tenant_id VARCHAR(50) NOT NULL,
      scope_mode VARCHAR(20) NOT NULL, scope_key VARCHAR(64) NOT NULL,
      owner_jiacn VARCHAR(50) NOT NULL, conversation_id VARCHAR(100) NOT NULL,
      lifecycle_generation BIGINT NOT NULL, state VARCHAR(20) NOT NULL,
      due_at BIGINT NOT NULL, lease_owner VARCHAR(100) DEFAULT NULL,
      lease_until BIGINT DEFAULT NULL, attempts INT NOT NULL DEFAULT 0,
      wake_requested TINYINT(1) NOT NULL DEFAULT 0,
      last_error VARCHAR(1000) DEFAULT NULL,
      create_time BIGINT NOT NULL, update_time BIGINT NOT NULL,
      PRIMARY KEY (id),
      UNIQUE KEY uk_memory_work_scope
        (tenant_id, scope_mode, scope_key, owner_jiacn, conversation_id),
      KEY idx_memory_work_claim (state, due_at, lease_until, id),
      CONSTRAINT chk_memory_work_mode CHECK (scope_mode IN (''LEGACY_CLIENT'', ''DOMAIN'')),
      CONSTRAINT chk_memory_work_state CHECK (state IN (''READY'', ''LEASED'', ''RETRY'', ''DONE'', ''DEAD'')),
      CONSTRAINT chk_memory_work_attempts CHECK (attempts >= 0)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin';
    SET @cyf_memory_v2_sql = v_sql;
    PREPARE cyf_memory_v2_stmt FROM @cyf_memory_v2_sql; EXECUTE cyf_memory_v2_stmt;
    DEALLOCATE PREPARE cyf_memory_v2_stmt;
  ELSEIF v_count <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'memory publication v2 work table is ambiguous';
  END IF;
  CALL cyf_memory_v2_assert_table('chat_memory_work',
    '1|id|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|auto_increment||;2|tenant_id|varchar|varchar(50)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;3|scope_mode|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;4|scope_key|varchar|varchar(64)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;5|owner_jiacn|varchar|varchar(50)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;6|conversation_id|varchar|varchar(100)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;7|lifecycle_generation|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;8|state|varchar|varchar(20)|NO|<NULL>|utf8mb4|utf8mb4_0900_bin|||;9|due_at|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;10|lease_owner|varchar|varchar(100)|YES|<NULL>|utf8mb4|utf8mb4_0900_bin|||;11|lease_until|bigint|bigint|YES|<NULL>|<NULL>|<NULL>|||;12|attempts|int|int|NO|0|<NULL>|<NULL>|||;13|wake_requested|tinyint|tinyint(1)|NO|0|<NULL>|<NULL>|||;14|last_error|varchar|varchar(1000)|YES|<NULL>|utf8mb4|utf8mb4_0900_bin|||;15|create_time|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||;16|update_time|bigint|bigint|NO|<NULL>|<NULL>|<NULL>|||',
    'PRIMARY|0|1|id|<NULL>|A|BTREE|<NULL>|YES;idx_memory_work_claim|1|1|state|<NULL>|A|BTREE|<NULL>|YES;idx_memory_work_claim|1|2|due_at|<NULL>|A|BTREE|<NULL>|YES;idx_memory_work_claim|1|3|lease_until|<NULL>|A|BTREE|<NULL>|YES;idx_memory_work_claim|1|4|id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_work_scope|0|1|tenant_id|<NULL>|A|BTREE|<NULL>|YES;uk_memory_work_scope|0|2|scope_mode|<NULL>|A|BTREE|<NULL>|YES;uk_memory_work_scope|0|3|scope_key|<NULL>|A|BTREE|<NULL>|YES;uk_memory_work_scope|0|4|owner_jiacn|<NULL>|A|BTREE|<NULL>|YES;uk_memory_work_scope|0|5|conversation_id|<NULL>|A|BTREE|<NULL>|YES',
    'chk_memory_work_attempts|ATTEMPTS>=0;chk_memory_work_mode|SCOPE_MODEIN''LEGACY_CLIENT'',''DOMAIN'';chk_memory_work_state|STATEIN''READY'',''LEASED'',''RETRY'',''DONE'',''DEAD''');

  IF EXISTS (
    SELECT 1 FROM chat_conversation
    WHERE data_scope_mode NOT IN ('LEGACY_CLIENT', 'DOMAIN')
       OR (data_scope_mode = 'DOMAIN' AND (data_domain_id IS NULL OR data_domain_id = ''))
       OR (data_scope_mode = 'LEGACY_CLIENT'
           AND (tenant_id <> '0' OR client_id IS NULL OR client_id = ''))
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'memory publication v2 canonical scope validation failed';
  END IF;

  IF v_applied = 0 THEN
    UPDATE chat_schema_migration_ledger
    SET state = 'APPLIED',
        applied_at = CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS UNSIGNED)
    WHERE migration_id = 'CYF-MEMORY-PUBLICATION-V2-20260924'
      AND migration_digest = v_digest AND state = 'APPLYING';
    IF ROW_COUNT() <> 1 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'memory publication v2 ledger finalization failed';
    END IF;
  END IF;

END$$

CALL cyf_memory_publication_v2_apply()$$
DROP PROCEDURE cyf_memory_publication_v2_apply$$
DROP PROCEDURE cyf_memory_v2_assert_column$$
DROP PROCEDURE cyf_memory_v2_assert_table$$
DELIMITER ;

DO RELEASE_LOCK(@cyf_memory_v2_lock_name);
SET @cyf_memory_v2_lock_result := NULL;
SET @cyf_memory_v2_lock_guard_sql := NULL;
SET @cyf_memory_v2_lock_name := NULL;
