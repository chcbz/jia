-- WX-DAILY-VOTE-RECEIPT-SCHEMA-V1
-- Additive, repeatable MySQL 8 migration for the durable receipt tables only.
-- It intentionally contains no data mutation, DROP, or ALTER of non-WX tables.

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
    KEY idx_wx_daily_vote_created (create_time, id)
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
        FOREIGN KEY (receipt_id) REFERENCES wx_daily_vote_receipt (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='Maps every retried WeChat message to its canonical daily vote receipt';
