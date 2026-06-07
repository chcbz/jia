CREATE TABLE IF NOT EXISTS ability_evaluation (
    id                   BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    agent_name           VARCHAR(100) NOT NULL COMMENT 'agent name',
    evaluation_type      VARCHAR(20) NOT NULL COMMENT 'MANUAL/AUTO/COMPARISON',
    task_context         VARCHAR(1000) DEFAULT NULL COMMENT 'task context',
    power_score          INT DEFAULT 0 COMMENT 'power score',
    intelligence_score   INT DEFAULT 0 COMMENT 'intelligence score',
    leadership_score     INT DEFAULT 0 COMMENT 'leadership score',
    ability_match_score  INT DEFAULT 0 COMMENT 'ability match score',
    overall_score        INT DEFAULT 0 COMMENT 'overall score',
    evaluation_content   TEXT COMMENT 'evaluation content json',
    comment              VARCHAR(1000) DEFAULT NULL COMMENT 'comment',
    evaluation_time      BIGINT DEFAULT NULL COMMENT 'evaluation time',
    create_time          BIGINT DEFAULT NULL COMMENT 'create time',
    update_time          BIGINT DEFAULT NULL COMMENT 'update time',
    tenant_id            VARCHAR(50) DEFAULT NULL COMMENT 'tenant id',
    client_id            VARCHAR(50) DEFAULT NULL COMMENT 'client id',
    PRIMARY KEY (id),
    KEY idx_ability_evaluation_agent_name (agent_name),
    KEY idx_ability_evaluation_type (evaluation_type),
    KEY idx_ability_evaluation_time (evaluation_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='agent ability evaluation';

CREATE TABLE IF NOT EXISTS ability_comparison (
    id                  BIGINT NOT NULL AUTO_INCREMENT COMMENT 'primary key',
    agent_names         TEXT NOT NULL COMMENT 'agent names json',
    task_context        VARCHAR(1000) DEFAULT NULL COMMENT 'task context',
    best_for_task       VARCHAR(100) DEFAULT NULL COMMENT 'best agent',
    comparison_result   TEXT COMMENT 'comparison result json',
    comparison_time     BIGINT DEFAULT NULL COMMENT 'comparison time',
    create_time         BIGINT DEFAULT NULL COMMENT 'create time',
    update_time         BIGINT DEFAULT NULL COMMENT 'update time',
    tenant_id           VARCHAR(50) DEFAULT NULL COMMENT 'tenant id',
    client_id           VARCHAR(50) DEFAULT NULL COMMENT 'client id',
    PRIMARY KEY (id),
    KEY idx_ability_comparison_best_for_task (best_for_task),
    KEY idx_ability_comparison_time (comparison_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='agent ability comparison';
