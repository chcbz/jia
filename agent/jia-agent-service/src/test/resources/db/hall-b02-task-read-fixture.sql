-- ISOLATED TEST DATABASE ONLY. Minimal tables for B02 reads and actual task create transaction.
-- Uses production column meanings/types; no migration, backfill or production installer hook.
CREATE TABLE agent_task_meta (
 id BIGINT NOT NULL AUTO_INCREMENT, task_id VARCHAR(100) NOT NULL,
 owner_jiacn VARCHAR(50) NOT NULL, tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 reward_status VARCHAR(20) NOT NULL, assigned_agent_id VARCHAR(100), required_abilities TEXT,
 reward INT, assigned_at BIGINT, started_at BIGINT, completed_at BIGINT, failure_reason VARCHAR(1000),
 collaboration_mode VARCHAR(20) NOT NULL, risk_level VARCHAR(20) NOT NULL, max_agents INT NOT NULL,
 coordinator_agent_id VARCHAR(100), review_required TINYINT NOT NULL,
 task_version BIGINT NOT NULL, current_event_version BIGINT NOT NULL,
 create_time BIGINT, update_time BIGINT, PRIMARY KEY(id),
 UNIQUE KEY uk_task_scope (tenant_id,client_id,owner_jiacn,task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE task_plan (
  id bigint NOT NULL AUTO_INCREMENT,
  jiacn varchar(32) NOT NULL COMMENT 'JIA账号',
  type int NOT NULL COMMENT '任务类型 1常规提醒 2目标 3还款计划 4固定收入',
  period int NOT NULL DEFAULT '0' COMMENT '周期类型 0长期 1每年 2每月 3每周 5每日 11每小时 12每分钟 13每秒 6指定日期',
  crond varchar(20) DEFAULT NULL COMMENT '周期表达式',
  name varchar(30) NOT NULL COMMENT '任务名称',
  description varchar(200) DEFAULT NULL COMMENT '任务描述',
  lunar int DEFAULT '0' COMMENT '是否农历日期 1是 0否',
  start_time bigint DEFAULT NULL COMMENT '开始时间',
  end_time bigint DEFAULT NULL COMMENT '结束时间',
  amount decimal(10,2) DEFAULT NULL COMMENT '数量/金额',
  remind int NOT NULL DEFAULT '0' COMMENT '是否需要提醒 1是 0否',
  remind_phone varchar(20) DEFAULT NULL COMMENT '提醒手机号码',
  remind_msg varchar(200) DEFAULT NULL COMMENT '提醒信息',
  status int NOT NULL DEFAULT '1' COMMENT '状态 1有效 0无效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT '0' COMMENT '租户ID',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE task_item (
  id bigint NOT NULL AUTO_INCREMENT,
  plan_id bigint DEFAULT NULL COMMENT '计划ID',
  execute_time bigint DEFAULT NULL COMMENT '时间',
  status int DEFAULT '1' COMMENT '状态 1正常 0已失效',
  create_time bigint DEFAULT NULL COMMENT '创建时间',
  update_time bigint DEFAULT NULL COMMENT '最后更新时间',
  client_id varchar(50) DEFAULT NULL COMMENT '应用标识符',
  tenant_id varchar(50) DEFAULT '0' COMMENT '租户ID',
  PRIMARY KEY (id),
  KEY plan_id (plan_id),
  CONSTRAINT task_item_ibfk_1 FOREIGN KEY (plan_id) REFERENCES task_plan (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;


CREATE TABLE IF NOT EXISTS agent_task_event (
    id              BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
    task_id         VARCHAR(100) NOT NULL COMMENT 'Task ID',
    event_version   BIGINT NOT NULL COMMENT 'Monotonic event version scoped to (tenant,client,task)',
    event_id        VARCHAR(100) NOT NULL COMMENT 'Deterministic stable event identifier',
    event_type      VARCHAR(64) NOT NULL COMMENT 'Event type (SCREAMING_SNAKE_CASE)',
    actor_type      VARCHAR(20) NOT NULL COMMENT 'Actor classification: agent/role/system',
    actor_id        VARCHAR(100) DEFAULT NULL COMMENT 'Actor identity (canonical agentId or role key)',
    aggregate_type  VARCHAR(30) NOT NULL COMMENT 'Aggregate type: task/member/work_item/request/artifact/thread/message/formal_delivery',
    aggregate_id    VARCHAR(100) NOT NULL COMMENT 'Aggregate instance ID',
    event_json      MEDIUMTEXT NOT NULL COMMENT 'Event payload JSON',
    occurred_at     BIGINT NOT NULL COMMENT 'Event occurrence timestamp (epoch millis)',
    owner_jiacn     VARCHAR(50) NOT NULL,
    tenant_id       VARCHAR(50) NOT NULL COMMENT 'Owner jiacn scope',
    client_id       VARCHAR(50) NOT NULL COMMENT 'OAuth/API client scope',
    create_time     BIGINT DEFAULT NULL COMMENT 'Create time',
    update_time     BIGINT DEFAULT NULL COMMENT 'Update time',
    PRIMARY KEY (id),
    UNIQUE KEY uk_task_event_version (tenant_id, client_id, owner_jiacn, task_id, event_version),
    UNIQUE KEY uk_task_event_id (tenant_id, client_id, owner_jiacn, event_id),
    KEY idx_task_event_occurred (tenant_id, client_id, task_id, occurred_at),
    KEY idx_event_actor_time (tenant_id, client_id, actor_type, actor_id, occurred_at),
    KEY idx_event_type_time (tenant_id, client_id, event_type, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='Scoped task event journal for M2 collaboration replay and SSE';

-- Only read-model columns: this fixture does not claim Provider/output-content integration.
CREATE TABLE agent_personal_workspace_execution (
 execution_id VARCHAR(100) NOT NULL, tenant_id VARCHAR(50) NOT NULL, client_id VARCHAR(50) NOT NULL,
 owner_jiacn VARCHAR(50) NOT NULL, execution_mode VARCHAR(16) NOT NULL, execution_state VARCHAR(32) NOT NULL,
 target_agent_id VARCHAR(100), created_at BIGINT NOT NULL, update_time BIGINT, failed_at BIGINT, revoked_at BIGINT,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Minimal original formal-read/decision evidence fields. No private mark can update these tables.
CREATE TABLE agent_task_work_item (
 work_item_id VARCHAR(100) NOT NULL,task_id VARCHAR(100) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,
 status VARCHAR(32) NOT NULL,result_artifact_id VARCHAR(100),lease_token VARCHAR(100),lease_until BIGINT,version BIGINT,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,work_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_task_formal_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT,task_id VARCHAR(100) NOT NULL,work_item_id VARCHAR(100) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,delivery_id VARCHAR(100) NOT NULL,
 revision BIGINT NOT NULL,version BIGINT NOT NULL,state VARCHAR(32) NOT NULL,
 manifest_artifact_id VARCHAR(100) NOT NULL,manifest_artifact_version INT NOT NULL,
 submitted_at BIGINT,reviewed_at BIGINT,review_reason TEXT,PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_task_formal_delivery_item (
 tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,delivery_id VARCHAR(100) NOT NULL,
 artifact_id VARCHAR(100) NOT NULL,artifact_version INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

-- Fixed-result metadata used by the real B03 manifest/ACL service in mark transaction tests.
ALTER TABLE agent_personal_workspace_execution ADD COLUMN task_id VARCHAR(100) NOT NULL DEFAULT 'private-task';
ALTER TABLE agent_personal_workspace_execution ADD COLUMN run_id VARCHAR(100) NOT NULL DEFAULT 'private-run';
CREATE TABLE agent_personal_workspace_execution_output (
 output_id VARCHAR(100) NOT NULL,execution_id VARCHAR(100) NOT NULL,
 tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,
 workspace_file_id VARCHAR(100) NOT NULL,workspace_file_version INT NOT NULL,
 output_state VARCHAR(32) NOT NULL,publication_state VARCHAR(32) NOT NULL,original_filename VARCHAR(255) NOT NULL,
 content_mime_type VARCHAR(160) NOT NULL,byte_length BIGINT NOT NULL,content_hash CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,execution_id,output_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_personal_workspace_file (
 file_id VARCHAR(100) NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,state VARCHAR(32) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE agent_personal_workspace_file_version (
 file_id VARCHAR(100) NOT NULL,version INT NOT NULL,tenant_id VARCHAR(50) NOT NULL,client_id VARCHAR(50) NOT NULL,owner_jiacn VARCHAR(50) NOT NULL,
 original_filename VARCHAR(255) NOT NULL,content_mime_type VARCHAR(160) NOT NULL,byte_length BIGINT NOT NULL,content_hash CHAR(64) NOT NULL,
 PRIMARY KEY(tenant_id,client_id,owner_jiacn,file_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
