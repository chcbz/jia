-- WX-DAILY-VOTE-OPT-01A08DAA
-- MySQL 8.0.21 additive migration. No historical vote rows are deleted or rewritten.

CREATE TABLE IF NOT EXISTS wx_daily_vote_receipt (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    appid                 VARCHAR(50) NOT NULL,
    message_key           CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_key              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id           BIGINT NOT NULL,
    processor_token       CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status                VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    correct               TINYINT DEFAULT NULL,
    point_awarded         INT DEFAULT NULL,
    reply_content         VARCHAR(512) DEFAULT NULL,
    create_time           BIGINT NOT NULL,
    update_time           BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wx_daily_vote_message (appid, message_key),
    UNIQUE KEY uk_wx_daily_vote_user_question (appid, user_key, question_id),
    KEY idx_wx_daily_vote_created (create_time, id),
    CONSTRAINT chk_wx_daily_vote_message_key CHECK (message_key REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_wx_daily_vote_user_key CHECK (user_key REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_wx_daily_vote_fingerprint CHECK (request_fingerprint REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_wx_daily_vote_result CHECK (
        (status = 'PROCESSING' AND correct IS NULL AND point_awarded IS NULL AND reply_content IS NULL)
        OR
        (status = 'COMPLETED' AND correct IN (0, 1) AND point_awarded >= 0 AND reply_content IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Persistent idempotency and replay receipt for WeChat daily vote answers';

CREATE TABLE IF NOT EXISTS wx_daily_vote_message_receipt (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    receipt_id            BIGINT NOT NULL,
    appid                 VARCHAR(50) NOT NULL,
    message_key           CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_key              CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_fingerprint   CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_id           BIGINT NOT NULL,
    create_time           BIGINT NOT NULL,
    update_time           BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wx_daily_vote_message_alias (appid, message_key),
    KEY idx_wx_daily_vote_message_receipt (receipt_id),
    CONSTRAINT fk_wx_daily_vote_message_receipt
        FOREIGN KEY (receipt_id) REFERENCES wx_daily_vote_receipt (id) ON DELETE CASCADE,
    CONSTRAINT chk_wx_daily_vote_alias_message_key CHECK (message_key REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_wx_daily_vote_alias_user_key CHECK (user_key REGEXP '^[0-9a-f]{64}$'),
    CONSTRAINT chk_wx_daily_vote_alias_fingerprint CHECK (request_fingerprint REGEXP '^[0-9a-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Maps every retried WeChat message to its canonical daily vote receipt';

SET @wxdv_schema := DATABASE();
SET @wxdv_has_mp_user_index := (
    SELECT COUNT(*)
    FROM (
        SELECT index_name,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
        FROM information_schema.statistics
        WHERE table_schema = @wxdv_schema
          AND table_name = 'wx_mp_user'
        GROUP BY index_name
    ) indexes_for_wx_mp_user
    WHERE indexed_columns = 'appid,open_id'
       OR indexed_columns LIKE 'appid,open_id,%'
);
SET @wxdv_sql := IF(@wxdv_has_mp_user_index = 0,
    'ALTER TABLE wx_mp_user ADD INDEX idx_wx_mp_user_appid_open_id (appid, open_id)',
    'SELECT ''wx_mp_user appid/open_id index already present'' AS wx_daily_vote_migration');
PREPARE wxdv_stmt FROM @wxdv_sql; EXECUTE wxdv_stmt; DEALLOCATE PREPARE wxdv_stmt;

SET @wxdv_has_tick_index := (
    SELECT COUNT(*)
    FROM (
        SELECT index_name,
               GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
        FROM information_schema.statistics
        WHERE table_schema = @wxdv_schema
          AND table_name = 'mat_vote_tick'
        GROUP BY index_name
    ) indexes_for_mat_vote_tick
    WHERE indexed_columns = 'jiacn,question_id'
       OR indexed_columns LIKE 'jiacn,question_id,%'
);
SET @wxdv_sql := IF(@wxdv_has_tick_index = 0,
    'ALTER TABLE mat_vote_tick ADD INDEX idx_mat_vote_tick_jiacn_question (jiacn, question_id)',
    'SELECT ''mat_vote_tick jiacn/question index already present'' AS wx_daily_vote_migration');
PREPARE wxdv_stmt FROM @wxdv_sql; EXECUTE wxdv_stmt; DEALLOCATE PREPARE wxdv_stmt;

-- Release gate: every value below must be zero.
SELECT IF(COUNT(*) = 2, 0, 1) AS invalid_receipt_table_count
FROM information_schema.tables
WHERE table_schema = DATABASE()
  AND table_name IN ('wx_daily_vote_receipt', 'wx_daily_vote_message_receipt')
  AND engine = 'InnoDB';

SELECT IF(COUNT(*) = 2, 0, 1) AS invalid_receipt_unique_key_count
FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'wx_daily_vote_receipt'
      AND non_unique = 0 AND index_name IN ('uk_wx_daily_vote_message', 'uk_wx_daily_vote_user_question')
    GROUP BY index_name
) receipt_unique_indexes;

SELECT IF(COUNT(*) = 1, 0, 1) AS invalid_message_alias_unique_key_count
FROM (
    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'wx_daily_vote_message_receipt'
      AND non_unique = 0 AND index_name = 'uk_wx_daily_vote_message_alias'
    GROUP BY index_name
) message_alias_indexes
WHERE indexed_columns = 'appid,message_key';

SELECT IF(COUNT(*) > 0, 0, 1) AS missing_wx_mp_user_appid_open_id_index
FROM (
    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'wx_mp_user'
    GROUP BY index_name
) wx_indexes
WHERE indexed_columns = 'appid,open_id' OR indexed_columns LIKE 'appid,open_id,%';

SELECT IF(COUNT(*) > 0, 0, 1) AS missing_mat_vote_tick_jiacn_question_index
FROM (
    SELECT index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') AS indexed_columns
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'mat_vote_tick'
    GROUP BY index_name
) tick_indexes
WHERE indexed_columns = 'jiacn,question_id' OR indexed_columns LIKE 'jiacn,question_id,%';
