-- Test-only prerequisite schema. Production initializers own identity, runtime extensions,
-- command transport, platform-skill, archive and reader tables.
CREATE TABLE IF NOT EXISTS user_info (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(32), password VARCHAR(64), jiacn VARCHAR(32),
 account_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', auth_epoch BIGINT NOT NULL DEFAULT 0,
 create_time BIGINT, update_time BIGINT, tenant_id VARCHAR(50) DEFAULT '0', client_id VARCHAR(50),
 UNIQUE KEY uk_fixture_user_jiacn (jiacn)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS oauth_api_key (
 id VARCHAR(100) NOT NULL PRIMARY KEY, client_id VARCHAR(100) NOT NULL, jiacn VARCHAR(32),
 api_key VARCHAR(255) NOT NULL, key_name VARCHAR(100), status INT DEFAULT 1,
 expire_time BIGINT, description VARCHAR(500), create_time BIGINT, update_time BIGINT,
 tenant_id VARCHAR(100) DEFAULT '0', KEY idx_api_key(api_key), KEY idx_client_id(client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_persona (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL, title VARCHAR(100),
 avatar VARCHAR(500), abilities TEXT, personality VARCHAR(500), speaking_style VARCHAR(500),
 background TEXT, power INT DEFAULT 0, intelligence INT DEFAULT 0, leadership INT DEFAULT 0,
 active TINYINT(1) DEFAULT 1, create_time BIGINT, update_time BIGINT,
 tenant_id VARCHAR(50) DEFAULT '0', client_id VARCHAR(50),
 UNIQUE KEY uk_agent_persona_name(name), KEY idx_agent_persona_active(active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS agent_runtime (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, agent_id VARCHAR(100) NOT NULL, name VARCHAR(100) NOT NULL,
 avatar VARCHAR(500), persona_name VARCHAR(50), abilities TEXT, endpoint VARCHAR(500), token_hash VARCHAR(200),
 status VARCHAR(20) NOT NULL DEFAULT 'offline', current_task_id VARCHAR(100), current_task_title VARCHAR(200),
 last_seen_at BIGINT, error_message VARCHAR(1000), create_time BIGINT, update_time BIGINT,
 tenant_id VARCHAR(50) DEFAULT '0', client_id VARCHAR(50), UNIQUE KEY uk_agent_runtime_agent_id(agent_id),
 KEY idx_agent_runtime_status(status), KEY idx_agent_runtime_persona_name(persona_name), KEY idx_agent_runtime_last_seen_at(last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- ChatSchemaInitializer upgrades and validates these durable shared-chat roots.
-- Keep the binary collation so owner/scope/target identifiers remain byte-exact.
CREATE TABLE IF NOT EXISTS chat_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT,
 title VARCHAR(500) DEFAULT NULL,
 jiacn VARCHAR(50) DEFAULT NULL,
 status INT DEFAULT 0,
 conversation_type VARCHAR(20) DEFAULT 'normal',
 conversation_scope_type VARCHAR(20) DEFAULT NULL,
 conversation_scope_key VARCHAR(120) DEFAULT NULL,
 task_id VARCHAR(64) DEFAULT NULL,
 target_agent_id VARCHAR(100) DEFAULT NULL,
 target_agent_ids VARCHAR(2000) DEFAULT NULL,
 deleted_at BIGINT DEFAULT NULL,
 lifecycle_generation BIGINT NOT NULL DEFAULT 1,
 create_time BIGINT DEFAULT NULL,
 update_time BIGINT DEFAULT NULL,
 client_id VARCHAR(50) DEFAULT NULL,
 tenant_id VARCHAR(50) DEFAULT '0',
 PRIMARY KEY (id),
 KEY idx_jiacn (jiacn),
 KEY idx_tenant_id (tenant_id),
 KEY idx_status (status),
 KEY idx_create_time (create_time),
 KEY idx_conversation_type (conversation_type),
 KEY idx_chat_conversation_scope (conversation_type, conversation_scope_type, conversation_scope_key),
 KEY idx_chat_conversation_live_owner (jiacn, client_id, deleted_at, lifecycle_generation, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS chat_message (
 id BIGINT NOT NULL AUTO_INCREMENT,
 conversation_id VARCHAR(100) NOT NULL,
 message_type VARCHAR(20) DEFAULT NULL,
 content TEXT,
 metadata TEXT,
 create_time BIGINT DEFAULT NULL,
 update_time BIGINT DEFAULT NULL,
 client_id VARCHAR(50) DEFAULT NULL,
 tenant_id VARCHAR(50) DEFAULT '0',
 jiacn VARCHAR(50) DEFAULT NULL,
 sync_status VARCHAR(20) DEFAULT NULL,
 conversation_type VARCHAR(20) DEFAULT NULL,
 sender_type VARCHAR(20) DEFAULT NULL,
 sender_name VARCHAR(100) DEFAULT NULL,
 PRIMARY KEY (id),
 KEY idx_conversation_id (conversation_id),
 KEY idx_tenant_id (tenant_id),
 KEY idx_jiacn (jiacn),
 KEY idx_conversation_type (conversation_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Existing shared task root, copied verbatim from agent mapper db/schema.sql.
CREATE TABLE IF NOT EXISTS agent_task_meta (
    id                      BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id                 VARCHAR(100) NOT NULL COMMENT '关联Task ID',
    owner_jiacn             VARCHAR(50) NOT NULL COMMENT 'Authenticated task owner',
    reward_status           VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/assigned/running/completed/failed',
    assigned_agent_id       VARCHAR(100) DEFAULT NULL COMMENT '兼容期首要Agent ID；新多人关系以成员表为准',
    required_abilities      TEXT COMMENT '所需能力(JSON数组)',
    reward                  INT DEFAULT NULL COMMENT '奖励或权重',
    assigned_at             BIGINT DEFAULT NULL COMMENT '分配时间',
    started_at              BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at            BIGINT DEFAULT NULL COMMENT '完成时间',
    failure_reason          VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    collaboration_mode      VARCHAR(20) NOT NULL DEFAULT 'single' COMMENT 'single/team',
    risk_level              VARCHAR(20) NOT NULL DEFAULT 'low' COMMENT 'low/medium/high',
    max_agents              INT NOT NULL DEFAULT 1 COMMENT '最大协作Agent数量',
    coordinator_agent_id    VARCHAR(100) DEFAULT NULL COMMENT 'Canonical coordinator agentId',
    review_required         TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否需要独立验收',
    task_version            BIGINT NOT NULL DEFAULT 0 COMMENT '任务聚合乐观锁版本',
    current_event_version   BIGINT NOT NULL DEFAULT 0 COMMENT '任务持久事件最新版本',
    create_time             BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time             BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id               VARCHAR(50) DEFAULT NULL COMMENT 'Owner jiacn scope；历史记录兼容可空',
    client_id               VARCHAR(50) DEFAULT NULL COMMENT 'OAuth/API client；历史记录兼容可空',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_meta_scope (tenant_id, client_id, owner_jiacn, task_id),
    KEY idx_agent_task_meta_status (reward_status),
    KEY idx_agent_task_meta_agent_id (assigned_agent_id),
    KEY idx_agent_task_meta_scope_status (tenant_id, client_id, owner_jiacn, reward_status, update_time, id),
    KEY idx_agent_task_meta_scope_coordinator (tenant_id, client_id, owner_jiacn, coordinator_agent_id, reward_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务扩展元数据表';
