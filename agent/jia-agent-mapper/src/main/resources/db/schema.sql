-- Juyiting agent module schema

CREATE TABLE IF NOT EXISTS agent_persona (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    name            VARCHAR(50) NOT NULL COMMENT '水浒人物名称',
    title           VARCHAR(100) DEFAULT NULL COMMENT '称号',
    avatar          VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    abilities       TEXT COMMENT '能力列表(JSON数组)',
    personality     VARCHAR(500) DEFAULT NULL COMMENT '性格描述',
    speaking_style  VARCHAR(500) DEFAULT NULL COMMENT '说话风格',
    background      TEXT COMMENT '背景故事',
    power           INT DEFAULT 0 COMMENT '武力值',
    intelligence    INT DEFAULT 0 COMMENT '智力值',
    leadership      INT DEFAULT 0 COMMENT '领导力',
    active          TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time     BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time     BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id       VARCHAR(50) DEFAULT NULL COMMENT '租户ID',
    client_id       VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_persona_name (name),
    KEY idx_agent_persona_active (active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent人设表';

CREATE TABLE IF NOT EXISTS agent_runtime (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    agent_id            VARCHAR(100) NOT NULL COMMENT 'Agent唯一标识',
    name                VARCHAR(100) NOT NULL COMMENT 'Agent显示名称',
    avatar              VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    persona_name        VARCHAR(50) DEFAULT NULL COMMENT '关联人设名称',
    abilities           TEXT COMMENT '能力列表(JSON数组)',
    endpoint            VARCHAR(500) DEFAULT NULL COMMENT 'Agent回调或WebSocket地址',
    token_hash          VARCHAR(200) DEFAULT NULL COMMENT 'Agent token摘要',
    status              VARCHAR(20) NOT NULL DEFAULT 'offline' COMMENT 'online/busy/offline/error',
    current_task_id     VARCHAR(100) DEFAULT NULL COMMENT '当前任务ID',
    current_task_title  VARCHAR(200) DEFAULT NULL COMMENT '当前任务标题',
    last_seen_at        BIGINT DEFAULT NULL COMMENT '最后心跳时间',
    error_message       VARCHAR(1000) DEFAULT NULL COMMENT '错误信息',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_runtime_agent_id (agent_id),
    KEY idx_agent_runtime_status (status),
    KEY idx_agent_runtime_persona_name (persona_name),
    KEY idx_agent_runtime_last_seen_at (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent运行时注册表';

CREATE TABLE IF NOT EXISTS dialogue_template (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    persona_id          BIGINT DEFAULT NULL COMMENT '人设ID',
    persona_name        VARCHAR(50) NOT NULL COMMENT '人设名称',
    dialogue_type       VARCHAR(20) NOT NULL COMMENT '对话类型',
    content             VARCHAR(1000) NOT NULL COMMENT '台词内容',
    trigger_condition   VARCHAR(200) DEFAULT NULL COMMENT '触发条件',
    priority            INT DEFAULT 0 COMMENT '优先级',
    active              TINYINT(1) DEFAULT 1 COMMENT '是否启用',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    KEY idx_dialogue_persona (persona_id),
    KEY idx_dialogue_persona_name (persona_name),
    KEY idx_dialogue_type (dialogue_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话模板表';

CREATE TABLE IF NOT EXISTS agent_task_meta (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id             VARCHAR(100) NOT NULL COMMENT '关联Task ID',
    reward_status       VARCHAR(20) NOT NULL DEFAULT 'open' COMMENT 'open/assigned/running/completed/failed',
    assigned_agent_id   VARCHAR(100) DEFAULT NULL COMMENT '分配Agent ID',
    required_abilities  TEXT COMMENT '所需能力(JSON数组)',
    reward              INT DEFAULT NULL COMMENT '奖励或权重',
    assigned_at         BIGINT DEFAULT NULL COMMENT '分配时间',
    started_at          BIGINT DEFAULT NULL COMMENT '开始时间',
    completed_at        BIGINT DEFAULT NULL COMMENT '完成时间',
    failure_reason      VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_task_meta_task_id (task_id),
    KEY idx_agent_task_meta_status (reward_status),
    KEY idx_agent_task_meta_agent_id (assigned_agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务扩展元数据表';
CREATE TABLE IF NOT EXISTS agent_task_note (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id             VARCHAR(100) NOT NULL COMMENT '关联悬赏任务ID',
    author_id           VARCHAR(100) DEFAULT NULL COMMENT '纪要作者ID',
    author_type         VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT 'user/agent/system',
    note_type           VARCHAR(20) NOT NULL DEFAULT 'summary' COMMENT 'summary/report/meeting/system',
    content             TEXT NOT NULL COMMENT '纪要内容',
    created_at          BIGINT NOT NULL COMMENT '纪要创建时间',
    create_time         BIGINT DEFAULT NULL COMMENT '创建时间',
    update_time         BIGINT DEFAULT NULL COMMENT '更新时间',
    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT '租户ID',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT '客户端ID',
    PRIMARY KEY (id),
    KEY idx_agent_task_note_task_id (task_id),
    KEY idx_agent_task_note_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务纪要表';
