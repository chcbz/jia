# 数据库 CHECK 规则归档与撤除规格（2026-11-02）

## 决策与边界

用户明确：CYF 其他业务表也取消数据库 CHECK，具体约束体现在规格，不再以数据库硬性 CHECK 承载。该决定覆盖旧规格中要求创建、修复、精确校验 CHECK 的描述；不影响主键、唯一键、外键、非空、字段类型、事务、锁和幂等。

- SQL 中的状态、数值范围、JSON 结构、scope 组合等原表达式下列逐项保存，供业务合同查阅；历史表达式不自动成为新增 API 拒绝规则，也不机械搬到每个 Controller/DAO。
- 必要的授权、资金和事实完整性规则继续在既有责任层执行。旧 CHECK 的删除不等于已经证明所有对应业务路径都覆盖，未经回归不得宣称安全验收完成。
- 新建表不创建 CHECK；历史升级脚本不再新增/替换 CHECK。schema initializer 必须同步删除 CHECK 自动补建及“缺 CHECK 即失败”的检查，不能把修复 SQL 绕开后留死代码。
- 已有库按只读目录快照固定真实表名/约束名，再通过既有发布与 DB 锁执行受控 DROP CHECK；不在应用启动时自动全库修改，不删除业务数据，不操作第三方引擎表。
- 生产切换前先确认维护/启动路径不会补回 CHECK；保留实际 schema 前值、执行摘要和索引/非空前后核验。不得因文档或建表脚本已改，就宣称生产约束已删除。

## 当前实现状态

基线 API `673891f698ed08381fbecbf60150077e3966b94d`，SQL资源、Java内嵌DDL及schema catalog/initializer已移除CHECK创建和存在性/表达式校验，相关测试已适配。资金/任务回滚测试改用保留的真实约束触发失败；未删除事务断言。正式Flow编译/回归与生产清理尚未完成，不以静态检查代替发布验收。

以下按表归档原 SQL 表达式（包含旧升级变体及测试夹具；不是需要同时成立的一组新约束）。相同表达式来自多个脚本时保留来源，以免把历史迁移目标误当现行事实。

## 原 SQL 规则明细

### `agent_command_delivery`

- 原约束：`chk_delivery_single_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_delivery_owner_nonempty`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-command-transport-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
OCTET_LENGTH(owner_jiacn) BETWEEN 1 AND 50
```

### `agent_controlled_image_bridge_operation`

- 原约束：`chk_acibo_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
tenant_id='0' AND owner_jiacn<>'0'
```

- 原约束：`chk_acibo_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
wrapper_digest REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_acibo_consent`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
consent_id REGEXP '^consent_[0-9a-f]{32}$'
```

- 原约束：`chk_acibo_locator`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
authority_locator=CONCAT('mmd-ci-v1:',consent_id)
```

- 原约束：`chk_acibo_protocol`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
(execution_protocol_version=2 AND operation_grant_id IS NULL) OR (execution_protocol_version=3 AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$')
```

- 原约束：`chk_acibo_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
expected_consent_version>0 AND grant_version>0 AND assignment_revision>=0
```

- 原约束：`chk_acibo_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-bridge-v1.sql`

```sql
created_at>0
```

### `agent_controlled_image_execution_source_v3`

- 原约束：`chk_acies_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
tenant_id='0' AND owner_jiacn<>'0'
```

- 原约束：`chk_acies_common`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
input_ordinal BETWEEN 1 AND 16 AND content_mime_type IN ('image/jpeg','image/png') AND byte_length>0 AND content_sha256 REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_acies_union`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
(source_kind='TASK_LINKED_WORKSPACE_VERSION' AND file_id IS NOT NULL AND file_version>0 AND purpose IN ('INPUT','REFERENCE') AND conversation_id IS NULL AND conversation_generation IS NULL AND asset_id IS NULL AND asset_revision IS NULL AND producer_request_id IS NULL AND producer_request_revision IS NULL AND producer_step_id IS NULL AND producer_execution_id IS NULL AND producer_run_id IS NULL AND producer_output_id IS NULL) OR (source_kind='CURRENT_CONVERSATION_ASSET' AND file_id IS NULL AND file_version IS NULL AND purpose IS NULL AND conversation_id IS NOT NULL AND conversation_generation>0 AND asset_id IS NOT NULL AND asset_revision>0 AND producer_request_id IS NOT NULL AND producer_request_revision>0 AND producer_step_id IS NOT NULL AND producer_execution_id IS NOT NULL AND producer_run_id IS NOT NULL AND producer_output_id IS NOT NULL)
```

### `agent_controlled_image_intent_operation_grant`

- 原约束：`chk_aciiog_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
tenant_id='0' AND owner_jiacn<>'0'
```

- 原约束：`chk_aciiog_ids`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' AND consent_id REGEXP '^consent_[0-9a-f]{32}$'
```

- 原约束：`chk_aciiog_hashes`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
requirement_sha256 REGEXP '^[0-9a-f]{64}$' AND instruction_sha256 REGEXP '^[0-9a-f]{64}$' AND source_snapshot_sha256 REGEXP '^[0-9a-f]{64}$' AND owner_payload_sha256 REGEXP '^[0-9a-f]{64}$' AND issue_request_digest REGEXP '^[0-9a-f]{64}$' AND (revoke_request_digest IS NULL OR revoke_request_digest REGEXP '^[0-9a-f]{64}$')
```

- 原约束：`chk_aciiog_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
conversation_generation>0 AND baseline_grant_version>0 AND task_version>=0 AND assignment_revision>=0 AND requirement_revision>0 AND version>0
```

- 原约束：`chk_aciiog_operation`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
operation IN ('GENERATE_IMAGE','EDIT_IMAGE')
```

- 原约束：`chk_aciiog_sources`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
JSON_TYPE(source_snapshot_json)='ARRAY' AND ((operation='GENERATE_IMAGE' AND JSON_LENGTH(source_snapshot_json) BETWEEN 0 AND 16) OR (operation='EDIT_IMAGE' AND JSON_LENGTH(source_snapshot_json)=1))
```

- 原约束：`chk_aciiog_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
state IN ('AUTHORIZED','RESERVED','CONSUMED','REVOKED')
```

- 原约束：`chk_aciiog_lifecycle`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-controlled-image-followup-v3.sql`

```sql
(state='AUTHORIZED' AND reserved_execution_id IS NULL AND reserved_run_id IS NULL AND consumed_lease_id IS NULL AND revoke_idempotency_key IS NULL AND revoke_request_digest IS NULL AND revoked_at IS NULL) OR (state='RESERVED' AND reserved_execution_id IS NOT NULL AND reserved_run_id IS NOT NULL AND consumed_lease_id IS NULL AND revoke_idempotency_key IS NULL AND revoke_request_digest IS NULL AND revoked_at IS NULL) OR (state='CONSUMED' AND reserved_execution_id IS NOT NULL AND reserved_run_id IS NOT NULL AND consumed_lease_id IS NOT NULL AND revoke_idempotency_key IS NULL AND revoke_request_digest IS NULL AND revoked_at IS NULL) OR (state='REVOKED' AND ((reserved_execution_id IS NULL AND reserved_run_id IS NULL) OR (reserved_execution_id IS NOT NULL AND reserved_run_id IS NOT NULL)) AND consumed_lease_id IS NULL AND revoke_idempotency_key IS NOT NULL AND revoke_request_digest IS NOT NULL AND revoked_at IS NOT NULL)
```

### `agent_execution_report_head`

- 原约束：`chk_execution_report_head_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_execution_report_head_numbers`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
attempt>0 AND last_sequence>=0 AND committed_version>=0
```

- 原约束：`chk_execution_report_head_terminal`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
(terminal_report_id IS NULL AND terminal_result_ref IS NULL) OR (terminal_report_id IS NOT NULL AND terminal_result_ref IS NOT NULL)
```

### `agent_execution_report_inbox`

- 原约束：`chk_execution_report_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_execution_report_numbers`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
grant_revision>0 AND attempt>0 AND sequence>0 AND occurred_at>0 AND committed_version>0
```

- 原约束：`chk_execution_report_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
OCTET_LENGTH(semantic_hash)=32
```

- 原约束：`chk_execution_report_type`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-execution-report-v1.sql`

```sql
message_type IN ('work.progress','work.heartbeat','work.result','help.request','artifact.publish')
```

### `agent_external_service_policy`

- 原约束：`chk_external_policy_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-external-service-policy-v1.sql`

```sql
expected_revision>=0 AND revision=expected_revision+1
```

- 原约束：`chk_external_policy_enabled`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-external-service-policy-v1.sql`

```sql
enabled IN (0,1)
```

- 原约束：`chk_external_policy_binding`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-external-service-policy-v1.sql`

```sql
binding_id>0
```

### `agent_hosted_profile`

- 原约束：`chk_hosted_single_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-business-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_hosted_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/hosted-persona-binding-schema.sql`

```sql
lifecycle_state IN ('PREPARED','STAGED_DISABLED','FILE_ENABLED','ACTIVE','SUSPENDING','SUSPENDED','REPAIR_REQUIRED')
```

- 原约束：`chk_hosted_generation`；来源：`agent/jia-agent-mapper/src/main/resources/db/hosted-persona-binding-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
generation >= 0
```

- 原约束：`chk_hosted_repair`；来源：`agent/jia-agent-mapper/src/main/resources/db/hosted-persona-binding-schema.sql`

```sql
(lifecycle_state='REPAIR_REQUIRED' AND resume_state IS NOT NULL) OR (lifecycle_state<>'REPAIR_REQUIRED' AND resume_state IS NULL)
```

- 原约束：`chk_hosted_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
lifecycle_state IN
      ('PREPARED','STAGED_DISABLED','FILE_ENABLED','ACTIVE','SUSPENDING','SUSPENDED','REPAIR_REQUIRED')
```

- 原约束：`chk_hosted_repair`；来源：`agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
(lifecycle_state = 'REPAIR_REQUIRED' AND resume_state IS NOT NULL)
      OR (lifecycle_state <> 'REPAIR_REQUIRED' AND resume_state IS NULL)
```

### `agent_identity_alias`

- 原约束：`chk_identity_alias_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-business-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_identity_alias_type`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
alias_type = 'LEGACY_AGENT_ID'
```

- 原约束：`chk_identity_alias_status`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
alias_status IN ('ACTIVE', 'REVOKED')
```

- 原约束：`chk_identity_alias_no_blank_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
TRIM(client_id) <> '' AND TRIM(owner_jiacn) <> ''
```

- 原约束：`chk_identity_alias_window`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
(alias_status = 'ACTIVE' AND valid_to IS NULL)
        OR (alias_status = 'REVOKED' AND valid_to IS NOT NULL)
```

- 原约束：`chk_identity_alias_not_system`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
alias_value <> 'builtin-songjiang' AND canonical_agent_id <> 'builtin-songjiang'
```

### `agent_identity_legacy_apply_run`

- 原约束：`chk_a08_apply_status`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql`

```sql
run_status IN ('RUNNING', 'SUCCEEDED', 'FAILED')
```

### `agent_identity_legacy_manifest`

- 原约束：`chk_a08_manifest_approval`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql`

```sql
approval_status = 'APPROVED'
```

- 原约束：`chk_a08_manifest_type`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql`

```sql
canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL')
```

- 原约束：`chk_a08_manifest_lifecycle`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql`

```sql
lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
```

- 原约束：`chk_a08_manifest_sha`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-legacy-manifest-schema.sql`

```sql
source_row_sha256 REGEXP '^[0-9a-f]{64}$'
        AND source_snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
        AND approved_report_sha256 REGEXP '^[0-9a-f]{64}$'
```

### `agent_identity_registry`

- 原约束：`chk_identity_registry_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-business-tenant-20261030.sql`

```sql
(canonical_type = 'SYSTEM'
      AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id = '0')
    OR (canonical_type <> 'SYSTEM'
      AND client_id IS NOT NULL AND TRIM(client_id) <> ''
      AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
      AND tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)))
```

- 原约束：`chk_identity_registry_type`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
canonical_type IN ('OPAQUE', 'LEGACY_CANONICAL', 'SYSTEM')
```

- 原约束：`chk_identity_registry_lifecycle`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
lifecycle_status IN ('PROVISIONED', 'ACTIVE', 'SUSPENDED', 'RETIRED')
```

- 原约束：`chk_identity_registry_canonical`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
(canonical_type = 'OPAQUE'
            AND canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'LEGACY_CANONICAL'
            AND canonical_agent_id <> 'builtin-songjiang'
            AND canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'SYSTEM'
            AND canonical_agent_id = 'builtin-songjiang')
```

- 原约束：`chk_identity_registry_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
(canonical_type = 'SYSTEM'
            AND client_id IS NULL AND owner_jiacn IS NULL AND tenant_id = '0')
        OR (canonical_type <> 'SYSTEM'
            AND client_id IS NOT NULL AND TRIM(client_id) <> ''
            AND owner_jiacn IS NOT NULL AND TRIM(owner_jiacn) <> ''
            AND tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id)))
```

- 原约束：`chk_identity_registry_retired`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
(lifecycle_status = 'RETIRED' AND retired_at IS NOT NULL)
        OR (lifecycle_status <> 'RETIRED' AND retired_at IS NULL)
```

- 原约束：`chk_identity_registry_canonical`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`

```sql
(canonical_type = 'OPAQUE' AND canonical_agent_id REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'LEGACY_CANONICAL'
            AND canonical_agent_id <> 'builtin-songjiang'
            AND canonical_agent_id NOT REGEXP '^agt_[0-9a-f]{32}$')
        OR (canonical_type = 'SYSTEM' AND canonical_agent_id = 'builtin-songjiang')
```

### `agent_persona`

- 原约束：`chk_agent_persona_private_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-persona-private-scope-20261030.sql`

```sql
CHAR_LENGTH(tenant_id) > 0 AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(TRIM(tenant_id))
            AND (client_id IS NULL OR (CHAR_LENGTH(client_id) > 0
                AND OCTET_LENGTH(client_id) = OCTET_LENGTH(TRIM(client_id))))
```

- 原约束：`chk_agent_persona_private_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
CHAR_LENGTH(tenant_id) > 0 AND OCTET_LENGTH(tenant_id) = OCTET_LENGTH(TRIM(tenant_id))
        AND (client_id IS NULL OR (CHAR_LENGTH(client_id) > 0
            AND OCTET_LENGTH(client_id) = OCTET_LENGTH(TRIM(client_id))))
```

### `agent_persona_binding`

- 原约束：`chk_agent_binding_single_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-business-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
tenant_id IS NOT NULL AND CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_agent_binding_status`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`, `agent/jia-agent-mapper/src/main/resources/db/schema.sql`

```sql
status IN (0, 1, 2, 3)
```

- 原约束：`chk_agent_binding_single_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-identity-schema.sql`

```sql
tenant_id = '0'
```

### `agent_personal_workspace_conversation_file_link`

- 原约束：`chk_pwcl_file_version`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
file_version >= 1
```

- 原约束：`chk_pwcl_revision`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
relation_revision >= 1
```

- 原约束：`chk_pwcl_role`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
link_role IN ('INPUT','REFERENCE','OUTPUT')
```

- 原约束：`chk_pwcl_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
link_state IN ('ACTIVE','DETACHED')
```

- 原约束：`chk_pwcl_detached_at`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
(link_state='ACTIVE' AND detached_at IS NULL)
    OR (link_state='DETACHED' AND detached_at IS NOT NULL)
```

### `agent_personal_workspace_conversation_link_operation`

- 原约束：`chk_pwcl_operation_type`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
operation_type IN ('CREATE','DELETE')
```

- 原约束：`chk_pwcl_operation_hash`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
CHAR_LENGTH(request_hash)=64 AND request_hash REGEXP BINARY '^[0-9a-f]{64}$'
```

- 原约束：`chk_pwcl_operation_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
operation_state IN ('PROCESSING','COMMITTED')
```

- 原约束：`chk_pwcl_operation_result`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-conversation-links.sql`

```sql
(operation_state='PROCESSING' AND relation_id IS NULL AND completed_at IS NULL)
    OR (operation_state='COMMITTED' AND relation_id IS NOT NULL AND completed_at IS NOT NULL)
```

### `agent_personal_workspace_execution`

- 原约束：`chk_pwex_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-execution-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_pwex_grant_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
grant_revision >= 1
```

- 原约束：`chk_pwex_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_13-execution-terminal-state.sql`

```sql
execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_COMMITTED','FAILED')
```

- 原约束：`chk_pwex_failure`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_13-execution-terminal-state.sql`

```sql
(execution_state='FAILED' AND failure_code='AGENT_DELIVERY_FAILED' AND failure_message IS NOT NULL AND failed_at IS NOT NULL) OR (execution_state<>'FAILED' AND failure_code IS NULL AND failure_message IS NULL AND failed_at IS NULL)
```

- 原约束：`chk_pwex_output_mime`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_13-execution-output-mime.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_17-text-output-mime.sql`

```sql
output_content_mime_type IN ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain')
```

- 原约束：`chk_pwex_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(request_hash)=64
```

- 原约束：`chk_pwex_revoke_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
(revoke_idempotency_key IS NULL AND revoke_request_hash IS NULL) OR (revoke_idempotency_key IS NOT NULL AND CHAR_LENGTH(revoke_request_hash)=64)
```

- 原约束：`chk_pwex_output_mime`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-native-v5.sql`

```sql
(execution_protocol_version IS NOT NULL AND execution_protocol_version IN (4,5))
    OR (output_content_mime_type IS NOT NULL AND output_content_mime_type IN
      ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain'))
```

- 原约束：`chk_pwex_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-native-v5.sql`

```sql
execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
    OR (execution_state='RESULT_COMMITTED' AND execution_protocol_version IS NOT NULL AND execution_protocol_version IN (4,5))
```

- 原约束：`chk_pwex_execution_protocol`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-native-v5.sql`

```sql
(execution_protocol_version=1 AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=2 AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=3 AND execution_mode='CONVERSATION' AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'
      AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') AND output_content_mime_type='image/png')
    OR (execution_protocol_version IN (4,5) AND execution_mode='CONVERSATION' AND permitted_operation='EXECUTE_SKILL'
      AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NOT NULL
      AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$')
```

- 原约束：`chk_pwex_output_mime`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-skill-v1.sql`

```sql
(execution_protocol_version IS NOT NULL AND execution_protocol_version=4)
    OR (output_content_mime_type IS NOT NULL AND output_content_mime_type IN
      ('image/png','image/jpeg','application/pdf','application/vnd.openxmlformats-officedocument.wordprocessingml.document','application/vnd.openxmlformats-officedocument.spreadsheetml.sheet','application/vnd.openxmlformats-officedocument.presentationml.presentation','text/plain'))
```

- 原约束：`chk_pwex_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-skill-v1.sql`

```sql
execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
    OR (execution_state='RESULT_COMMITTED' AND execution_protocol_version IS NOT NULL AND execution_protocol_version=4)
```

- 原约束：`chk_pwex_execution_protocol`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-skill-v1.sql`

```sql
(execution_protocol_version=1 AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=2 AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$' AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NULL)
    OR (execution_protocol_version=3 AND execution_mode='CONVERSATION' AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND operation_grant_id REGEXP '^opgrant_[0-9a-f]{32}$' AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$'
      AND permitted_operation IN ('GENERATE_IMAGE','EDIT_IMAGE') AND output_content_mime_type='image/png')
    OR (execution_protocol_version=4 AND execution_mode='CONVERSATION' AND permitted_operation='EXECUTE_SKILL'
      AND controlled_consent_id IS NULL AND operation_grant_id IS NULL AND runtime_input_snapshot_digest IS NOT NULL
      AND runtime_input_snapshot_digest REGEXP '^[0-9a-f]{64}$')
```

- 原约束：`chk_pwex_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-execution.sql`

```sql
execution_state IN ('QUEUED','INPUTS_REVOKED','OUTPUT_STAGED','OUTPUT_COMMITTED','FAILED')
```

- 原约束：`chk_pwex_mode`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-execution.sql`

```sql
execution_mode IN ('PRIVATE','TASK')
```

- 原约束：`chk_pwex_task_bridge`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-execution.sql`

```sql
(execution_mode='PRIVATE' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL)
    OR
    (execution_mode='TASK' AND work_item_id IS NOT NULL AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version >= 0
      AND lease_expires_at IS NOT NULL AND lease_expires_at > 0)
```

- 原约束：`chk_pwex_mode`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-execution.sql`

```sql
execution_mode IN ('PRIVATE','TASK','CONVERSATION')
```

- 原约束：`chk_pwex_task_bridge`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-execution.sql`

```sql
(execution_mode='PRIVATE' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL)
    OR (execution_mode='TASK' AND work_item_id IS NOT NULL AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version >= 0
      AND lease_expires_at IS NOT NULL AND lease_expires_at > 0)
    OR (execution_mode='CONVERSATION' AND work_item_id IS NULL AND lease_token IS NULL
      AND lease_work_item_version IS NULL AND lease_expires_at IS NULL
      AND conversation_id IS NOT NULL AND task_grant_id IS NOT NULL
      AND task_grant_version >= 1 AND assignment_revision >= 0
      AND permitted_operation IS NOT NULL)
```

- 原约束：`chk_pwex_conversation_grant`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-execution.sql`

```sql
execution_mode='CONVERSATION' OR (task_grant_id IS NULL AND task_grant_version IS NULL
      AND assignment_revision IS NULL AND permitted_operation IS NULL)
```

- 原约束：`chk_pwex_conversation_lease`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_15-conversation-lease.sql`

```sql
(execution_mode<>'CONVERSATION' AND conversation_lease_version=0
      AND conversation_lease_token IS NULL AND conversation_lease_runtime_id IS NULL
      AND conversation_lease_expires_at IS NULL)
    OR (execution_mode='CONVERSATION' AND
      ((conversation_lease_version=0 AND conversation_lease_token IS NULL
        AND conversation_lease_runtime_id IS NULL AND conversation_lease_expires_at IS NULL)
       OR (conversation_lease_version>=1 AND conversation_lease_token IS NOT NULL
        AND conversation_lease_runtime_id IS NOT NULL
        AND conversation_lease_expires_at IS NOT NULL AND conversation_lease_expires_at>0)))
```

- 原约束：`chk_pwex_provider_start`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_16-conversation-provider-start.sql`

```sql
(execution_mode<>'CONVERSATION' AND conversation_provider_started_at IS NULL
      AND conversation_provider_lease_version IS NULL)
    OR (execution_mode='CONVERSATION' AND
      ((conversation_provider_started_at IS NULL AND conversation_provider_lease_version IS NULL)
        OR (conversation_provider_started_at>0 AND conversation_provider_lease_version>=1
          AND conversation_provider_lease_version<=conversation_lease_version)))
```

- 原约束：`chk_pwex_controlled_consent`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_17-controlled-image.sql`

```sql
controlled_consent_id IS NULL OR
    (execution_mode='CONVERSATION'
      AND controlled_consent_id REGEXP '^consent_[0-9a-f]{32}$'
      AND permitted_operation='GENERATE_IMAGE'
      AND output_content_mime_type='image/png')
```

### `agent_personal_workspace_execution_input`

- 原约束：`chk_pwexi_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-execution-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_pwexi_version`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
file_version >= 1 AND byte_length >= 0
```

- 原约束：`chk_pwexi_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
grant_state IN ('ACTIVE','REVOKED')
```

- 原约束：`chk_pwexi_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(content_hash)=64
```

### `agent_personal_workspace_execution_output`

- 原约束：`chk_pwexo_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-execution-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_pwexo_lengths`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
byte_length >= 0
```

- 原约束：`chk_pwexo_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
output_state IN ('STAGED','COMMITTED')
```

- 原约束：`chk_pwexo_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
CHAR_LENGTH(content_hash)=64
```

- 原约束：`chk_pwexo_commit`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-executions.sql`

```sql
(output_state='STAGED' AND workspace_file_id IS NULL AND workspace_file_version IS NULL AND committed_at IS NULL) OR (output_state='COMMITTED' AND workspace_file_id IS NOT NULL AND workspace_file_version=1 AND committed_at IS NOT NULL)
```

- 原约束：`chk_pwexo_publication_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-publication.sql`

```sql
publication_state IN ('PENDING','PUBLISHED','FAILED')
```

- 原约束：`chk_pwexo_publication_mapping`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_13-task-publication.sql`

```sql
(publication_state='PENDING' AND artifact_id IS NULL AND artifact_version IS NULL
      AND formal_delivery_id IS NULL AND publication_failure_code IS NULL)
    OR
    (publication_state='PUBLISHED' AND artifact_id IS NOT NULL AND artifact_version IS NOT NULL
      AND artifact_version > 0 AND formal_delivery_id IS NOT NULL AND publication_failure_code IS NULL)
    OR
    (publication_state='FAILED' AND publication_failure_code IS NOT NULL)
```

- 原约束：`chk_pwexo_purpose`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-output.sql`

```sql
output_purpose IN ('FILE','CONVERSATION')
```

- 原约束：`chk_pwexo_commit`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-output.sql`

```sql
(output_state='STAGED' AND workspace_file_id IS NULL
      AND workspace_file_version IS NULL AND committed_at IS NULL)
    OR (output_state='COMMITTED' AND output_purpose='FILE'
      AND workspace_file_id IS NOT NULL AND workspace_file_version=1 AND committed_at IS NOT NULL)
    OR (output_state='COMMITTED' AND output_purpose='CONVERSATION'
      AND workspace_file_id IS NULL AND workspace_file_version IS NULL AND committed_at IS NOT NULL)
```

- 原约束：`chk_pwexo_conversation`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_14-conversation-output.sql`

```sql
output_purpose='FILE' OR (publication_state='PENDING' AND publication_revision=0
      AND artifact_id IS NULL AND artifact_version IS NULL AND formal_delivery_id IS NULL
      AND publication_failure_code IS NULL)
```

### `agent_personal_workspace_file`

- 原约束：`chk_pws_file_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
metadata_revision >= 1 AND latest_version >= 1
```

- 原约束：`chk_pws_file_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
state IN ('ACTIVE', 'TRASHED')
```

- 原约束：`chk_pws_file_source`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
source_kind = 'UPLOAD'
```

- 原约束：`chk_pws_file_origin`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1_11-origin.sql`

```sql
origin_kind IN ('USER_UPLOAD','AGENT_DELIVERY')
```

### `agent_personal_workspace_file_version`

- 原约束：`chk_pws_version_positive`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
version >= 1 AND byte_length >= 0
```

- 原约束：`chk_pws_version_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
CHAR_LENGTH(content_hash)=64
```

### `agent_personal_workspace_operation`

- 原约束：`chk_pws_operation_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
CHAR_LENGTH(request_hash)=64
```

- 原约束：`chk_pws_operation_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-personal-workspace-v1.sql`

```sql
state IN ('PROCESSING','COMMITTED','FAILED')
```

### `agent_personal_workspace_task_file_link`

- 原约束：`chk_pwtl_file_version`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
file_version >= 1
```

- 原约束：`chk_pwtl_revision`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
relation_revision >= 1
```

- 原约束：`chk_pwtl_role`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
link_role IN ('INPUT','REFERENCE','OUTPUT')
```

- 原约束：`chk_pwtl_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
link_state IN ('ACTIVE','DETACHED')
```

- 原约束：`chk_pwtl_detached_at`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
(link_state='ACTIVE' AND detached_at IS NULL)
    OR (link_state='DETACHED' AND detached_at IS NOT NULL)
```

### `agent_personal_workspace_task_link_operation`

- 原约束：`chk_pwtl_operation_type`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
operation_type IN ('CREATE','DELETE')
```

- 原约束：`chk_pwtl_operation_hash`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
CHAR_LENGTH(request_hash)=64 AND request_hash REGEXP BINARY '^[0-9a-f]{64}$'
```

- 原约束：`chk_pwtl_operation_state`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
operation_state IN ('PROCESSING','COMMITTED')
```

- 原约束：`chk_pwtl_operation_result`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-v1_11-task-links.sql`

```sql
(operation_state='PROCESSING' AND relation_id IS NULL AND completed_at IS NULL)
    OR (operation_state='COMMITTED' AND relation_id IS NOT NULL AND completed_at IS NOT NULL)
```

### `agent_position_state`

- 原约束：`(数据库自动命名)`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-position-skills.sql`

```sql
revision >= 0
```

### `agent_runtime_v1_installation`

- 原约束：`chk_runtime_v1_status`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-runtime-v1-schema.sql`

```sql
status IN ('PENDING','ACTIVE','REVOKED','REBINDS_REQUIRED')
```

### `agent_selected_output_finalization`

- 原约束：`chk_asof_contract`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-contract-v2.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql`

```sql
contract_version IN (1,2)
```

- 原约束：`chk_asof_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql`

```sql
expected_task_version BETWEEN 0 AND 9007199254740991
    AND expected_assignment_revision BETWEEN 0 AND 9007199254740991
    AND conversation_generation BETWEEN 1 AND 9007199254740991
    AND grant_version BETWEEN 1 AND 9007199254740991 AND version>=0
```

- 原约束：`chk_asof_digest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql`

```sql
CHAR_LENGTH(immutable_digest)=64 AND CHAR_LENGTH(source_facts_digest)=64
```

- 原约束：`chk_asof_phase`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql`

```sql
phase IN ('CLAIMING','LEASED','READY','SUBMITTED','ACCEPTED')
```

- 原约束：`chk_asof_lease`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-selected-output-finalization-v1.sql`

```sql
(phase='CLAIMING' AND lease_token IS NULL AND lease_work_item_version IS NULL)
    OR (phase IN ('LEASED','READY') AND lease_token IS NOT NULL
      AND lease_work_item_version IS NOT NULL AND lease_work_item_version>=0)
    OR (phase IN ('SUBMITTED','ACCEPTED') AND lease_token IS NULL AND lease_work_item_version IS NOT NULL)
```

### `agent_skill_execution`

- 原约束：`chk_skill_execution_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
tenant_id='0' AND owner_jiacn<>'0'
```

- 原约束：`chk_skill_execution_version`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
version>=0
```

- 原约束：`chk_skill_execution_hashes`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
package_sha256 REGEXP '^[0-9a-f]{64}$' AND
    manifest_sha256 REGEXP '^[0-9a-f]{64}$' AND
    snapshot_sha256 REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_skill_execution_json`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
JSON_TYPE(snapshot_json)='OBJECT' AND JSON_TYPE(effects_json)='OBJECT' AND
    (result_manifest_json IS NULL OR JSON_TYPE(result_manifest_json)='OBJECT')
```

- 原约束：`chk_skill_execution_phase`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
phase IN (
    'READY','RUNNING','WAITING_PROVIDER','RESULT_UNKNOWN','RESULT_READY',
    'ARCHIVING','COMPLETE','FAILED','CANCELLED')
```

- 原约束：`chk_skill_execution_result`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-skill-execution-v1.sql`

```sql
phase NOT IN ('RESULT_READY','ARCHIVING','COMPLETE') OR result_manifest_json IS NOT NULL
```

- 原约束：`chk_skill_execution_hashes`；来源：`agent/jia-agent-service/src/main/resources/db/agent-personal-workspace-native-v5.sql`

```sql
snapshot_sha256 REGEXP '^[0-9a-f]{64}$' AND (
      (skill_key IS NOT NULL AND skill_version IS NOT NULL AND package_sha256 IS NOT NULL AND manifest_sha256 IS NOT NULL
        AND package_sha256 REGEXP '^[0-9a-f]{64}$' AND manifest_sha256 REGEXP '^[0-9a-f]{64}$')
      OR (skill_key IS NULL AND skill_version IS NULL AND package_sha256 IS NULL AND manifest_sha256 IS NULL
        AND JSON_EXTRACT(snapshot_json,'$.executionProtocolVersion') IS NOT NULL
        AND JSON_EXTRACT(snapshot_json,'$.executionProtocolVersion')=5
        AND JSON_EXTRACT(snapshot_json,'$.skills') IS NOT NULL
        AND JSON_TYPE(JSON_EXTRACT(snapshot_json,'$.skills'))='ARRAY'
        AND task_skill_binding_json IS NOT NULL AND JSON_TYPE(task_skill_binding_json)='ARRAY'))
```

### `agent_task_artifact_outcome`

- 原约束：`chk_artifact_outcome_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
outcome_state IN ('accepted', 'superseded')
```

- 原约束：`chk_artifact_outcome_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
artifact_version >= 1 AND version >= 1 AND decided_at > 0
```

- 原约束：`chk_artifact_outcome_digest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
CHAR_LENGTH(decision_digest) = 64
```

- 原约束：`chk_artifact_outcome_supersession`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
(outcome_state = 'accepted'
             AND superseded_by_artifact_id IS NULL
             AND superseded_by_artifact_version IS NULL)
            OR
            (outcome_state = 'superseded'
             AND superseded_by_artifact_id IS NOT NULL
             AND superseded_by_artifact_version >= 1
             AND (artifact_id <> superseded_by_artifact_id
                  OR artifact_version <> superseded_by_artifact_version))
```

### `agent_task_artifact_outcome_decision`

- 原约束：`chk_artifact_outcome_decision_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
accepted_artifact_version >= 1
               AND accepted_outcome_version >= 1
               AND decided_at > 0
```

- 原约束：`chk_artifact_outcome_decision_digest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-artifact-outcome-f06.sql`

```sql
CHAR_LENGTH(decision_digest) = 64
```

### `agent_task_bounty_bootstrap_outbox`

- 原约束：`chk_atbbo_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
requirement_revision>=1 AND assignment_revision>=0 AND grant_version>=1
   AND attempt_count>=0 AND version>=0
```

- 原约束：`chk_atbbo_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
payload_hash REGEXP '^[0-9a-f]{64}$'
   AND reference_summary_sha256 REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_atbbo_references`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
JSON_TYPE(reference_summary_json)='ARRAY'
```

- 原约束：`chk_atbbo_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
status IN ('PENDING','CLAIMED','RETRY','ADMITTED','DEAD')
```

- 原约束：`chk_atbbo_lifecycle`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
(status='PENDING' AND attempt_count=0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NULL AND reconciled_at IS NULL)
   OR (status='CLAIMED' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NOT NULL AND lease_until IS NOT NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NULL AND reconciled_at IS NULL)
   OR (status='RETRY' AND attempt_count>0 AND next_retry_at IS NOT NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NOT NULL AND reconciled_at IS NULL)
   OR (status='ADMITTED' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NOT NULL AND admitted_request_id IS NOT NULL
      AND last_error_code IS NULL AND reconciled_at IS NOT NULL)
   OR (status='DEAD' AND attempt_count>0 AND next_retry_at IS NULL
      AND lease_owner IS NULL AND lease_until IS NULL
      AND admitted_conversation_id IS NULL AND admitted_request_id IS NULL
      AND last_error_code IS NOT NULL AND reconciled_at IS NOT NULL)
```

- 原约束：`chk_atbbo_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-bootstrap-outbox-v1.sql`

```sql
created_at>0 AND (next_retry_at IS NULL OR next_retry_at>=created_at)
   AND (lease_until IS NULL OR lease_until>created_at)
   AND (reconciled_at IS NULL OR reconciled_at>=created_at)
```

### `agent_task_bounty_claim_operation`

- 原约束：`chk_bounty_claim_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
OCTET_LENGTH(request_hash)=32 AND OCTET_LENGTH(idempotency_key)=36
```

- 原约束：`chk_bounty_claim_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
(status='POSTING' AND receipt_task_version IS NULL AND claimed_at IS NULL) OR (status='COMPLETED' AND receipt_task_version IS NOT NULL AND receipt_task_version>0 AND claimed_at IS NOT NULL AND claimed_at>0)
```

### `agent_task_bounty_quote`

- 原约束：`chk_bounty_quote_hashes`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
OCTET_LENGTH(request_hash)=32 AND OCTET_LENGTH(idempotency_key)=36 AND task_input_hash LIKE 'sha256:%' AND skill_set_hash LIKE 'sha256:%'
```

- 原约束：`chk_bounty_quote_amounts`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
task_version>=0 AND estimated_input_tokens>=0 AND estimated_cached_input_tokens>=0 AND estimated_output_tokens>=0 AND estimated_reasoning_tokens>=0 AND estimated_compute_micro>=0 AND worst_compute_micro>=estimated_compute_micro AND platform_fee_micro>=0 AND gross_allocation_micro>0 AND estimated_agent_payout_micro>=0 AND worst_agent_payout_micro>=0 AND minimum_accepted_payout_micro>=0 AND budget_headroom_micro>=0
```

- 原约束：`chk_bounty_quote_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
(status='OPEN' AND claimed_at IS NULL) OR (status='CLAIMED' AND claimed_at IS NOT NULL AND claimed_at>0)
```

- 原约束：`chk_bounty_quote_recommendation`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
recommendation IN ('recommended','caution','reject')
```

- 原约束：`chk_bounty_quote_expiry`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-quote-v0.sql`

```sql
expires_at>create_time
```

### `agent_task_bounty_settlement`

- 原约束：`chk_bounty_settlement_identity`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-settlement-v0.sql`

```sql
principal_type='USER' AND OCTET_LENGTH(idempotency_key)=36 AND OCTET_LENGTH(request_hash)=32
```

- 原约束：`chk_bounty_settlement_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-settlement-v0.sql`

```sql
status='SETTLED' AND task_version>0 AND funding_version>=2 AND escrow_version>1 AND settled_at>0 AND OCTET_LENGTH(transaction_ids)>2
```

- 原约束：`chk_bounty_settlement_amount`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bounty-settlement-v0.sql`

```sql
gross_micro>0 AND actual_compute_micro>=0 AND platform_fee_micro>=0 AND agent_payout_micro>=0 AND refunded_micro=0 AND gross_micro=actual_compute_micro + platform_fee_micro + agent_payout_micro
```

### `agent_task_creation_operation`

- 原约束：`chk_atco_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-tenant-20261030.sql`

```sql
owner_jiacn<>'0'
```

- 原约束：`chk_atco_identity`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`

```sql
CHAR_LENGTH(client_id) BETWEEN 1 AND 50
    AND CHAR_LENGTH(owner_jiacn) BETWEEN 1 AND 50
    AND CHAR_LENGTH(operation_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 100
    AND operation_state IN ('PROCESSING','COMMITTED')
```

- 原约束：`chk_atco_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`

```sql
CHAR_LENGTH(request_hash)=64 AND request_hash REGEXP BINARY '^[0-9a-f]{64}$'
```

- 原约束：`chk_atco_refs`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v2-unbounded-materials.sql`

```sql
JSON_TYPE(input_refs_json)='ARRAY'
```

- 原约束：`chk_atco_receipt`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`

```sql
(operation_state='PROCESSING' AND task_id IS NULL
      AND requirement_revision IS NULL AND completed_at IS NULL)
    OR
    (operation_state='COMMITTED' AND task_id IS NOT NULL
      AND requirement_revision=1 AND completed_at IS NOT NULL)
```

- 原约束：`chk_atco_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-creation-operation-v1.sql`

```sql
created_at>0 AND (completed_at IS NULL OR completed_at>=created_at)
```

### `agent_task_execution_grant`

- 原约束：`chk_atxg_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-tenant-20261030.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
CHAR_LENGTH(tenant_id)>0 AND OCTET_LENGTH(tenant_id)=OCTET_LENGTH(TRIM(tenant_id))
```

- 原约束：`chk_atxg_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
requirement_revision>=1 AND assignment_revision>=0 AND grant_version>=1
```

- 原约束：`chk_atxg_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
state IN ('ACTIVE','REVOKED','SUPERSEDED')
```

- 原约束：`chk_atxg_derived`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
allow_own_task_derived_assets IN (0,1)
```

- 原约束：`chk_atxg_revoke`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
(state='ACTIVE' AND revoked_at IS NULL AND revoke_idempotency_key IS NULL AND revoke_request_hash IS NULL)
   OR (state='SUPERSEDED' AND revoked_at IS NOT NULL AND revoke_idempotency_key IS NULL AND revoke_request_hash IS NULL)
   OR (state='REVOKED' AND revoked_at IS NOT NULL AND revoke_idempotency_key IS NOT NULL AND revoke_request_hash IS NOT NULL)
```

- 原约束：`chk_atxg_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-execution-grant-v1.sql`

```sql
created_at>=0 AND (revoked_at IS NULL OR revoked_at>=created_at)
```

### `agent_task_formal_delivery`

- 原约束：`chk_formal_delivery_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
revision >= 1 AND version >= 0
```

- 原约束：`chk_formal_delivery_manifest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
manifest_artifact_version >= 1
```

- 原约束：`chk_formal_delivery_submission_digest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
CHAR_LENGTH(submission_digest) = 64
```

- 原约束：`chk_formal_delivery_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
state IN ('submitted', 'accepted', 'changes_requested')
```

- 原约束：`chk_formal_delivery_review`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
(state = 'submitted' AND reviewed_by_jiacn IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)
        OR (state = 'accepted' AND reviewed_by_jiacn IS NOT NULL AND review_reason IS NULL AND reviewed_at > 0)
        OR (state = 'changes_requested' AND reviewed_by_jiacn IS NOT NULL
            AND review_reason IS NOT NULL AND CHAR_LENGTH(review_reason) > 0 AND reviewed_at > 0)
```

### `agent_task_formal_delivery_item`

- 原约束：`chk_formal_delivery_item_version`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
artifact_version >= 1 AND item_order >= 0
```

- 原约束：`chk_formal_delivery_item_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
CHAR_LENGTH(content_hash) = 64
```

- 原约束：`chk_formal_delivery_item_purpose`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-formal-delivery-r2.sql`

```sql
CHAR_LENGTH(purpose) > 0
```

### `agent_task_funding`

- 原约束：`chk_agent_task_funding_amount`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
gross_bounty_amount_micro > 0 AND remaining_micro >= 0
        AND remaining_micro <= gross_bounty_amount_micro
```

- 原约束：`chk_agent_task_funding_mode`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
funding_mode = 'FUNDED_SINGLE_AGENT'
```

- 原约束：`chk_agent_task_funding_policy`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
settlement_policy = 'GROSS_INCLUSIVE'
```

- 原约束：`chk_agent_task_funding_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w06/settlement-integration-h2.sql`

```sql
(funding_status = 'RESERVING' AND version = 0 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'FUNDS_HELD' AND version >= 1 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 0 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'REFUNDED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NOT NULL AND OCTET_LENGTH(cancel_idempotency_key) = 36
            AND cancel_request_hash IS NOT NULL AND OCTET_LENGTH(cancel_request_hash) = 32
            AND refund_transaction_id IS NOT NULL
            AND cancel_refunded_micro IS NOT NULL AND cancel_refunded_micro > 0
            AND cancel_refunded_micro <= gross_bounty_amount_micro
            AND cancel_task_version IS NOT NULL AND cancel_task_version > 0
            AND refunded_at IS NOT NULL AND refunded_at > 0)
        OR
        (funding_status = 'SETTLED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
```

- 原约束：`chk_agent_task_funding_state`；来源：`agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
(funding_status = 'RESERVING' AND version = 0 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NULL AND escrow_version IS NULL AND reserve_transaction_id IS NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'FUNDS_HELD' AND version >= 1 AND remaining_micro = gross_bounty_amount_micro
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 0 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NULL AND cancel_request_hash IS NULL
            AND refund_transaction_id IS NULL AND cancel_refunded_micro IS NULL
            AND cancel_task_version IS NULL AND refunded_at IS NULL)
        OR
        (funding_status = 'REFUNDED' AND version >= 2 AND remaining_micro = 0
            AND escrow_id IS NOT NULL AND escrow_version IS NOT NULL AND escrow_version > 1 AND reserve_transaction_id IS NOT NULL
            AND cancel_idempotency_key IS NOT NULL AND OCTET_LENGTH(cancel_idempotency_key) = 36
            AND cancel_request_hash IS NOT NULL AND OCTET_LENGTH(cancel_request_hash) = 32
            AND refund_transaction_id IS NOT NULL
            AND cancel_refunded_micro IS NOT NULL AND cancel_refunded_micro > 0
            AND cancel_refunded_micro <= gross_bounty_amount_micro
            AND cancel_task_version IS NOT NULL AND cancel_task_version > 0
            AND refunded_at IS NOT NULL AND refunded_at > 0)
```

### `agent_task_funding_operation`

- 原约束：`chk_task_funding_operation_key`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36
```

- 原约束：`chk_task_funding_operation_hash`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
OCTET_LENGTH(request_hash) = 32
```

- 原约束：`chk_task_funding_operation_status`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-funding-v0.sql`, `agent/jia-agent-service/src/test/resources/w04/funded-integration-h2.sql`

```sql
(status = 'POSTING' AND reserve_transaction_id IS NULL
            AND receipt_task_version IS NULL AND receipt_created_at IS NULL AND receipt_updated_at IS NULL)
        OR
        (status = 'COMPLETED' AND reserve_transaction_id IS NOT NULL
            AND receipt_task_version IS NOT NULL AND receipt_task_version = 0
            AND receipt_created_at IS NOT NULL AND receipt_created_at > 0
            AND receipt_updated_at IS NOT NULL AND receipt_updated_at > 0)
```

### `agent_task_provider_cost_consent`

- 原约束：`chk_atpcc_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
tenant_id='0' AND owner_jiacn<>'0'
```

- 原约束：`chk_atpcc_identity`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
CHAR_LENGTH(client_id) BETWEEN 1 AND 50
    AND CHAR_LENGTH(owner_jiacn) BETWEEN 1 AND 50
    AND CHAR_LENGTH(consent_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(task_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(target_agent_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(idempotency_key) BETWEEN 1 AND 100
    AND CHAR_LENGTH(assignment_idempotency_key) BETWEEN 1 AND 100
    AND CHAR_LENGTH(binding_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(model_id) BETWEEN 1 AND 100
    AND CHAR_LENGTH(operator_issuer) BETWEEN 1 AND 100
    AND CHAR_LENGTH(operator_policy_revision) BETWEEN 1 AND 100
```

- 原约束：`chk_atpcc_hashes`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
request_digest REGEXP '^[0-9a-f]{64}$'
    AND assignment_base_hash REGEXP '^[0-9a-f]{64}$'
    AND requirement_sha256 REGEXP '^[0-9a-f]{64}$'
    AND input_snapshot_digest REGEXP '^[0-9a-f]{64}$'
    AND (revoke_request_digest IS NULL
      OR revoke_request_digest REGEXP '^[0-9a-f]{64}$')
```

- 原约束：`chk_atpcc_provider`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
provider_lane='CONTROLLED_IMAGE_HTTP_V1'
    AND pricing_mode='UNPRICED_EXTERNAL_ACCOUNT'
    AND max_outbound_request_attempts=1
    AND binding_epoch>0 AND expires_at>0
```

- 原约束：`chk_atpcc_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
task_version>=0 AND requirement_revision>0 AND version>0
    AND (bound_grant_version IS NULL OR bound_grant_version>0)
    AND (bound_assignment_revision IS NULL OR bound_assignment_revision>=0)
```

- 原约束：`chk_atpcc_inputs`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
JSON_TYPE(input_snapshot_json)='ARRAY'
    AND JSON_LENGTH(input_snapshot_json) BETWEEN 0 AND 16
```

- 原约束：`chk_atpcc_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
state IN ('ISSUED','BOUND','RESERVED','CONSUMED','REVOKED')
```

- 原约束：`chk_atpcc_bound`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
(bound_grant_id IS NULL AND bound_grant_version IS NULL
      AND bound_assignment_revision IS NULL)
    OR
    (bound_grant_id IS NOT NULL AND bound_grant_version IS NOT NULL
      AND bound_assignment_revision IS NOT NULL
      AND state IN ('BOUND','RESERVED','CONSUMED','REVOKED'))
```

- 原约束：`chk_atpcc_reserved`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
(reserved_execution_id IS NULL AND reserved_run_id IS NULL)
    OR
    (reserved_execution_id IS NOT NULL AND reserved_run_id IS NOT NULL
      AND bound_grant_id IS NOT NULL
      AND state IN ('RESERVED','CONSUMED','REVOKED'))
```

- 原约束：`chk_atpcc_consumed`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
(state='CONSUMED' AND consumed_lease_id IS NOT NULL AND consumed_at IS NOT NULL
      AND reserved_execution_id IS NOT NULL)
    OR
    (state<>'CONSUMED' AND consumed_lease_id IS NULL AND consumed_at IS NULL)
```

- 原约束：`chk_atpcc_revoked`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
(state='REVOKED' AND revoke_idempotency_key IS NOT NULL
      AND revoke_request_digest IS NOT NULL AND revoked_at IS NOT NULL)
    OR
    (state<>'REVOKED' AND revoke_idempotency_key IS NULL
      AND revoke_request_digest IS NULL AND revoked_at IS NULL)
```

- 原约束：`chk_atpcc_issued`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
state<>'ISSUED' OR (bound_grant_id IS NULL AND reserved_execution_id IS NULL
      AND consumed_lease_id IS NULL AND revoke_idempotency_key IS NULL)
```

- 原约束：`chk_atpcc_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-provider-cost-consent-v1.sql`

```sql
created_at>0
    AND (consumed_at IS NULL OR consumed_at>=created_at)
    AND (revoked_at IS NULL OR revoked_at>=created_at)
```

### `agent_task_requirement_snapshot`

- 原约束：`chk_atrs_scope`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-bootstrap-tenant-20261029.sql`, `agent/jia-agent-mapper/src/main/resources/db/agent-task-requirement-snapshot-v1.sql`

```sql
owner_jiacn<>'0'
```

- 原约束：`chk_atrs_content`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-task-requirement-snapshot-v1.sql`

```sql
revision>=1 AND task_version_at_confirmation>=0 AND CHAR_LENGTH(title)>0
   AND content_sha256 REGEXP '^[0-9a-f]{64}$' AND source IN ('CREATE','RECONFIRM')
   AND created_at>0
```

### `agent_work_item_reassignment`

- 原约束：`chk_work_item_reassignment_digest`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-work-item-reassignment-e05.sql`, `agent/jia-agent-service/src/ur04E05Http/resources/ur04/e05-http-db-h2.sql`

```sql
CHAR_LENGTH(request_sha256)=64 AND CHAR_LENGTH(lease_fence_sha256)=64
```

- 原约束：`chk_work_item_reassignment_versions`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-work-item-reassignment-e05.sql`, `agent/jia-agent-service/src/ur04E05Http/resources/ur04/e05-http-db-h2.sql`

```sql
expected_work_item_version >= 0
               AND result_work_item_version = expected_work_item_version + 1
               AND task_version >= 0
```

- 原约束：`chk_work_item_reassignment_agents`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-work-item-reassignment-e05.sql`, `agent/jia-agent-service/src/ur04E05Http/resources/ur04/e05-http-db-h2.sql`

```sql
previous_agent_id <> target_agent_id
```

- 原约束：`chk_work_item_reassignment_lease`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-work-item-reassignment-e05.sql`, `agent/jia-agent-service/src/ur04E05Http/resources/ur04/e05-http-db-h2.sql`

```sql
previous_lease_until > 0 AND lease_until > previous_lease_until
               AND attempt_count > 0 AND attempt_count < max_attempts
```

- 原约束：`chk_work_item_reassignment_immutable_clock`；来源：`agent/jia-agent-mapper/src/main/resources/db/agent-work-item-reassignment-e05.sql`, `agent/jia-agent-service/src/ur04E05Http/resources/ur04/e05-http-db-h2.sql`

```sql
create_time > 0 AND update_time = create_time
```

### `archive_bookmark`

- 原约束：`chk_archive_bookmark_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_bookmark_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
bookmark_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_bookmark_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
state IN ('ACTIVE', 'DELETED')
```

- 原约束：`chk_archive_bookmark_version`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
version >= 1
```

- 原约束：`chk_archive_bookmark_offset`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
byte_offset IS NULL OR byte_offset >= 0
```

- 原约束：`chk_archive_bookmark_payload`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
(state = 'ACTIVE' AND edition_manifest_sha256 IS NOT NULL AND block_type IS NOT NULL AND block_id IS NOT NULL AND paragraph_id IS NOT NULL AND byte_offset IS NOT NULL AND paragraph_sha256 IS NOT NULL AND deleted_at IS NULL) OR (state = 'DELETED' AND edition_manifest_sha256 IS NULL AND block_type IS NULL AND block_id IS NULL AND paragraph_id IS NULL AND byte_offset IS NULL AND paragraph_sha256 IS NULL AND deleted_at IS NOT NULL)
```

### `archive_chapter`

- 原约束：`chk_archive_chapter_shape`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-multi-work-migration.sql`, `chat/jia-chat-mapper/src/main/resources/db/archive-schema.sql`

```sql
(block_type = 'PREFACE' AND reader_ordinal = 0 AND chapter_number IS NULL) OR (block_type = 'CHAPTER' AND reader_ordinal >= 1 AND chapter_number = reader_ordinal)
```

- 原约束：`chk_archive_chapter_metrics`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-schema.sql`

```sql
paragraph_count > 0 AND utf8_byte_length > 0
```

### `archive_edition`

- 原约束：`chk_archive_edition_counts`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-multi-work-migration.sql`, `chat/jia-chat-mapper/src/main/resources/db/archive-schema.sql`

```sql
chapter_count >= 1 AND preface_paragraph_count >= 0 AND chapter_paragraph_count >= 1
        AND reader_paragraph_count = preface_paragraph_count + chapter_paragraph_count
        AND source_utf8_byte_length > 0 AND preface_utf8_byte_length >= 0
        AND chapter_utf8_byte_length > 0
        AND reader_utf8_byte_length = preface_utf8_byte_length + chapter_utf8_byte_length
```

- 原约束：`chk_archive_edition_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-schema.sql`

```sql
import_state IN ('STAGING', 'READY')
```

### `archive_idempotency`

- 原约束：`chk_archive_idempotency_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_idempotency_method`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
http_method IN ('PUT', 'DELETE')
```

- 原约束：`chk_archive_idempotency_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
state IN ('PENDING', 'COMPLETED')
```

- 原约束：`chk_archive_idempotency_key`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
idempotency_key REGEXP '^[!-~]{1,128}$'
```

- 原约束：`chk_archive_idempotency_response`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
(state = 'PENDING' AND response_status IS NULL AND response_content_type IS NULL AND response_body IS NULL) OR (state = 'COMPLETED' AND response_status BETWEEN 200 AND 299 AND response_content_type IS NOT NULL AND response_body IS NOT NULL)
```

- 原约束：`chk_archive_idempotency_expiry`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
expires_at >= created_at
```

### `archive_note`

- 原约束：`chk_archive_note_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_note_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
note_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_note_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
state IN ('ACTIVE', 'DELETED')
```

- 原约束：`chk_archive_note_version`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
version >= 1
```

- 原约束：`chk_archive_note_text_bytes`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
text IS NULL OR OCTET_LENGTH(text) <= 20000
```

- 原约束：`chk_archive_note_payload`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
(state = 'ACTIVE' AND text IS NOT NULL AND deleted_at IS NULL AND ((block_id IS NULL AND anchor_json IS NULL) OR (block_id IS NOT NULL AND anchor_json IS NOT NULL))) OR (state = 'DELETED' AND text IS NULL AND block_id IS NULL AND anchor_json IS NULL AND deleted_at IS NOT NULL)
```

### `archive_outbox`

- 原约束：`chk_archive_outbox_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_outbox_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
question_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_outbox_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
state IN ('READY', 'LEASED', 'WAITING_RETRY', 'DONE')
```

- 原约束：`chk_archive_outbox_counters`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
attempt_count BETWEEN 0 AND 3 AND fencing_token >= 0 AND published_sequence >= 0
```

- 原约束：`chk_archive_outbox_lease`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
(state = 'LEASED' AND lease_until IS NOT NULL) OR (state IN ('READY', 'WAITING_RETRY', 'DONE') AND lease_until IS NULL)
```

- 原约束：`chk_archive_outbox_error`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
(state IN ('READY', 'LEASED') AND last_error_code IS NULL) OR (state = 'WAITING_RETRY' AND last_error_code IS NOT NULL AND last_error_code REGEXP '^[A-Z][A-Z0-9_]{0,63}$') OR (state = 'DONE' AND (last_error_code IS NULL OR last_error_code REGEXP '^[A-Z][A-Z0-9_]{0,63}$'))
```

### `archive_paragraph`

- 原约束：`chk_archive_paragraph_metrics`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-schema.sql`

```sql
ordinal >= 1 AND utf8_byte_length > 0
```

### `archive_question`

- 原约束：`chk_archive_question_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_question_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
question_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_question_status`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED_RETRYABLE', 'FAILED_FINAL')
```

- 原约束：`chk_archive_question_content`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
edition_manifest_sha256 REGEXP '^[0-9a-f]{64}$' AND block_type IN ('PREFACE', 'CHAPTER') AND JSON_VALID(anchor_json)
```

- 原约束：`chk_archive_question_responder`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
responder_id = 'archive-clerk-v1' AND responder_name = CONVERT(0xE6A188E58DB7E4B9A6E5908F USING utf8mb4) AND responder_mode = 'fallback'
```

- 原约束：`chk_archive_question_lengths`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
OCTET_LENGTH(selected_text) BETWEEN 1 AND 8192 AND OCTET_LENGTH(question_text) BETWEEN 1 AND 8192 AND OCTET_LENGTH(answer) <= 131072
```

- 原约束：`chk_archive_question_counters`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
retry_count BETWEEN 0 AND 2 AND version >= 1 AND current_sequence >= 1
```

- 原约束：`chk_archive_question_terminal`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
((status IN ('SUCCEEDED', 'FAILED_FINAL')) AND completed_at IS NOT NULL) OR ((status IN ('QUEUED', 'RUNNING', 'FAILED_RETRYABLE')) AND completed_at IS NULL)
```

- 原约束：`chk_archive_question_error`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
((status IN ('FAILED_RETRYABLE', 'FAILED_FINAL')) AND last_error_code IS NOT NULL AND last_error_code REGEXP '^[A-Z][A-Z0-9_]{0,63}$') OR ((status IN ('QUEUED', 'RUNNING', 'SUCCEEDED')) AND last_error_code IS NULL)
```

- 原约束：`chk_archive_question_queued_answer`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
status <> 'QUEUED' OR answer = ''
```

### `archive_question_event`

- 原约束：`chk_archive_question_event_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_question_event_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
question_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_question_event_sequence`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
sequence >= 1
```

- 原约束：`chk_archive_question_event_type`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
event_type IN ('QUESTION_QUEUED', 'QUESTION_RUNNING', 'ANSWER_DELTA', 'QUESTION_SUCCEEDED', 'QUESTION_FAILED_RETRYABLE', 'QUESTION_FAILED_FINAL', 'QUESTION_RETRY_QUEUED')
```

- 原约束：`chk_archive_question_event_payload`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
JSON_VALID(payload_json) AND OCTET_LENGTH(payload_json) BETWEEN 2 AND 32768
```

### `archive_question_mutation`

- 原约束：`chk_archive_question_mutation_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_question_mutation_id`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
question_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
```

- 原约束：`chk_archive_question_mutation_method`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
http_method IN ('PUT', 'POST')
```

- 原约束：`chk_archive_question_mutation_path`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
canonical_path REGEXP '^[!-~]{1,512}$' AND ((http_method = 'PUT' AND canonical_path = CONCAT('/archive/v1/me/questions/', question_id)) OR (http_method = 'POST' AND canonical_path = CONCAT('/archive/v1/me/questions/', question_id, '/retry')))
```

- 原约束：`chk_archive_question_mutation_hash`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
request_sha256 REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_archive_question_mutation_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
state IN ('PENDING', 'COMPLETED')
```

- 原约束：`chk_archive_question_mutation_key`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
idempotency_key REGEXP '^[!-~]{1,128}$'
```

- 原约束：`chk_archive_question_mutation_response`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
(state = 'PENDING' AND question_row_id IS NULL AND response_status IS NULL AND response_content_type IS NULL AND response_body IS NULL) OR (state = 'COMPLETED' AND question_row_id IS NOT NULL AND response_status = 202 AND response_content_type IS NOT NULL AND response_body IS NOT NULL)
```

- 原约束：`chk_archive_question_mutation_expiry`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-question-schema.sql`

```sql
expires_at >= created_at
```

### `archive_reader_progress`

- 原约束：`chk_archive_progress_single_tenant`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_archive_progress_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
state IN ('IN_PROGRESS', 'COMPLETED')
```

- 原约束：`chk_archive_progress_values`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
byte_offset >= 0 AND version >= 1
```

- 原约束：`chk_archive_progress_completion`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-reader-data-schema.sql`

```sql
(state = 'IN_PROGRESS' AND completed_at IS NULL) OR (state = 'COMPLETED' AND completed_at IS NOT NULL)
```

### `archive_skill_maintenance_action`

- 原约束：`(数据库自动命名)`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-maintenance-jobs.sql`

```sql
action_key='PUBLISH_DRAFT'
```

### `archive_skill_maintenance_job`

- 原约束：`(数据库自动命名)`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-maintenance-jobs.sql`

```sql
state IN ('WAITING','DRAFT','PUBLISHED','FAILED')
```

- 原约束：`(数据库自动命名)`；来源：`chat/jia-chat-mapper/src/main/resources/db/archive-maintenance-jobs.sql`

```sql
JSON_VALID(specification)
```

### `chat_bounty_binding`

- 原约束：`chk_chat_bounty_binding_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-deliberation-schema.sql`

```sql
assignment_revision>=0
```

### `chat_conversation_archive_operation`

- 原约束：`chk_chat_archive_asset_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-text-selection-migration.sql`

```sql
asset_revision IS NULL OR asset_revision>=1
```

- 原约束：`chk_chat_archive_generation`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
conversation_generation IS NULL OR conversation_generation>=1
```

- 原约束：`chk_chat_archive_key_length`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160
```

- 原约束：`chk_chat_archive_request_sha`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-text-selection-migration.sql`

```sql
request_sha256 REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_chat_archive_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
state IN ('PENDING','SAVING','SAVED','PARTIAL_FAILED')
```

- 原约束：`chk_chat_archive_row_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
row_revision>=1
```

- 原约束：`chk_chat_archive_source_union`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
(
    (source_kind='assetRef' AND asset_id IS NOT NULL AND asset_revision>=1
      AND message_id IS NULL AND message_revision IS NULL
      AND selection_start_code_point IS NULL AND selection_end_code_point IS NULL
      AND source_sha256 IS NULL AND source_snapshot_key IS NULL AND source_text IS NULL)
    OR
    (source_kind='textSelection' AND asset_id IS NULL AND asset_revision IS NULL
      AND conversation_generation>=1
      AND message_id REGEXP '^[1-9][0-9]{0,18}$' AND message_revision>=1
      AND selection_start_code_point>=0
      AND selection_end_code_point>selection_start_code_point
      AND source_sha256 REGEXP '^[0-9a-f]{64}$'
      AND source_snapshot_key REGEXP '^[0-9a-f]{64}$'
      AND source_text IS NOT NULL AND OCTET_LENGTH(source_text)>0
      AND CHAR_LENGTH(source_text)=selection_end_code_point-selection_start_code_point)
  ) IS TRUE
```

- 原约束：`chk_chat_archive_saved_receipt`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-schema.sql`

```sql
state<>'SAVED' OR (conversation_generation>=1 AND workspace_operation_id IS NOT NULL
      AND file_id IS NOT NULL AND file_version>=1)
```

- 原约束：`chk_chat_archive_source_union`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-conversation-archive-text-selection-migration.sql`

```sql
(((source_kind='assetRef') AND asset_id IS NOT NULL AND asset_revision>=1 AND message_id IS NULL AND message_revision IS NULL AND selection_start_code_point IS NULL AND selection_end_code_point IS NULL AND source_sha256 IS NULL AND source_snapshot_key IS NULL AND source_text IS NULL) OR ((source_kind='textSelection') AND asset_id IS NULL AND asset_revision IS NULL AND conversation_generation>=1 AND message_id REGEXP '^[1-9][0-9]{0,18}$' AND message_revision>=1 AND selection_start_code_point>=0 AND selection_end_code_point>selection_start_code_point AND source_sha256 REGEXP '^[0-9a-f]{64}$' AND source_snapshot_key REGEXP '^[0-9a-f]{64}$' AND source_text IS NOT NULL AND OCTET_LENGTH(source_text)>0 AND CHAR_LENGTH(source_text)=selection_end_code_point-selection_start_code_point)) IS TRUE
```

### `chat_selected_output_finalization`

- 原约束：`chk_csof_contract`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-contract-v2.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
contract_version IN (1,2)
```

- 原约束：`chk_csof_progress`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-submission-v2.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
(stage IN ('PROMOTING','READY_TO_SUBMIT')
      AND delivery_id IS NULL AND delivery_state IS NULL)
    OR (stage='SUBMITTED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','accepted','changes_requested'))
    OR (stage='ACCEPTING' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state IN ('submitted','accepted','changes_requested'))
    OR (stage='TASK_COMPLETED' AND delivery_id IS NOT NULL AND delivery_state IS NOT NULL
      AND delivery_state='accepted')
```

- 原约束：`chk_csof_terminal`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-submission-v2.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
(state='completed' AND stage='SUBMITTED' AND delivery_id IS NOT NULL
    AND delivery_state IS NOT NULL AND delivery_state IN ('submitted','accepted','changes_requested')
    AND error_code IS NULL AND retryable=0)
    OR (state='completed' AND stage='TASK_COMPLETED' AND delivery_id IS NOT NULL
    AND delivery_state IS NOT NULL AND delivery_state='accepted'
    AND task_state='completed' AND error_code IS NULL AND retryable=0)
    OR (state<>'completed' AND stage<>'TASK_COMPLETED')
```

- 原约束：`chk_csof_versions`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
expected_task_version BETWEEN 0 AND 9007199254740991
    AND expected_assignment_revision BETWEEN 0 AND 9007199254740991
    AND state_version>0 AND task_version BETWEEN 0 AND 9007199254740991
```

- 原约束：`chk_csof_key`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
CHAR_LENGTH(idempotency_key) BETWEEN 8 AND 160
    AND REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9][A-Za-z0-9._:-]{7,159}$','c')
```

- 原约束：`chk_csof_delivery_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
delivery_state IS NULL
    OR delivery_state IN ('submitted','accepted','changes_requested')
```

- 原约束：`chk_csof_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
state IN ('pending','completed','failed')
```

- 原约束：`chk_csof_stage`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
stage IN ('PROMOTING','READY_TO_SUBMIT','SUBMITTED','ACCEPTING','TASK_COMPLETED')
```

- 原约束：`chk_csof_outcome`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
(state='pending' AND error_code IS NULL AND retryable=0)
    OR (state='failed' AND error_code IS NOT NULL)
    OR (state='completed' AND error_code IS NULL AND retryable=0)
```

### `chat_selected_output_finalization_item`

- 原约束：`chk_csofi_source`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-completed-message-v1.sql`

```sql
(source_kind='OUTPUT' AND step_id IS NOT NULL AND output_id IS NOT NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL)
    OR (source_kind='COMPLETED_MESSAGE' AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NOT NULL AND message_id IS NOT NULL AND snapshot_id IS NOT NULL AND final_digest IS NOT NULL
      AND REGEXP_LIKE(turn_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(snapshot_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(message_id,'^[1-9][0-9]{0,18}$','c')
      AND (CHAR_LENGTH(message_id)<19 OR message_id<='9223372036854775807')
      AND REGEXP_LIKE(final_digest,'^sha256:[0-9a-f]{64}$','c'))
```

- 原约束：`chk_csofi_source`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-formal-source-v2.sql`, `chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
(source_kind='OUTPUT' AND step_id IS NOT NULL AND output_id IS NOT NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL
      AND request_id IS NOT NULL AND source_delivery_id IS NULL AND source_artifact_id IS NULL AND source_artifact_version IS NULL)
    OR (source_kind='COMPLETED_MESSAGE' AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NOT NULL AND message_id IS NOT NULL AND snapshot_id IS NOT NULL AND final_digest IS NOT NULL
      AND REGEXP_LIKE(turn_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(snapshot_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(message_id,'^[1-9][0-9]{0,18}$','c')
      AND (CHAR_LENGTH(message_id)<19 OR message_id<='9223372036854775807')
      AND REGEXP_LIKE(final_digest,'^sha256:[0-9a-f]{64}$','c')
      AND request_id IS NOT NULL AND source_delivery_id IS NULL AND source_artifact_id IS NULL AND source_artifact_version IS NULL)
    OR (source_kind='FORMAL_ARTIFACT' AND request_id IS NULL AND step_id IS NULL AND output_id IS NULL
      AND turn_id IS NULL AND message_id IS NULL AND snapshot_id IS NULL AND final_digest IS NULL
      AND source_delivery_id IS NOT NULL AND source_artifact_id IS NOT NULL AND source_artifact_version IS NOT NULL
      AND source_artifact_version>0
      AND REGEXP_LIKE(source_delivery_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c')
      AND REGEXP_LIKE(source_artifact_id,'^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$','c'))
```

- 原约束：`chk_csofi_order`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
item_order>=0 AND item_order<99
```

- 原约束：`chk_csofi_hash`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-selected-output-finalization-v1.sql`

```sql
CHAR_LENGTH(sha256)=64
```

### `chat_task_delivery_review_operation`

- 原约束：`chk_ctdro_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
state IN ('PROCESSING','COMMITTED','REJECTED') AND state_version>0
```

- 原约束：`chk_ctdro_decision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
decision IN ('ACCEPT','REQUEST_CHANGES')
```

- 原约束：`chk_ctdro_json`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
JSON_VALID(request_json) AND JSON_VALID(target_artifact_ids_json)
   AND JSON_TYPE(CAST(target_artifact_ids_json AS JSON))='ARRAY'
```

- 原约束：`chk_ctdro_receipt`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
(state='PROCESSING' AND receipt_json IS NULL)
   OR (state IN ('COMMITTED','REJECTED') AND receipt_json IS NOT NULL AND JSON_VALID(receipt_json))
```

- 原约束：`chk_ctdro_hash`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
REGEXP_LIKE(request_sha256,'^[0-9a-f]{64}$','c')
   AND REGEXP_LIKE(batch_sha256,'^[0-9a-f]{64}$','c')
```

- 原约束：`chk_ctdro_key`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
REGEXP_LIKE(idempotency_key,'^[A-Za-z0-9][A-Za-z0-9._:-]{7,99}$','c')
```

- 原约束：`chk_ctdro_time`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
created_at>=0 AND updated_at>=created_at
```

- 原约束：`chk_ctdro_origin`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-task-delivery-review-v1.sql`

```sql
(origin='USER_ACCEPT' AND decision='ACCEPT' AND source_request_id IS NULL AND source_message_id IS NULL
    AND parent_outcome_id IS NULL AND parent_final_digest IS NULL AND child_request_id IS NULL
    AND JSON_LENGTH(target_artifact_ids_json)=0)
   OR (origin='MESSAGE_REVISION' AND decision='REQUEST_CHANGES' AND source_request_id IS NOT NULL
    AND source_message_id IS NOT NULL AND parent_outcome_id IS NOT NULL AND parent_final_digest IS NOT NULL
    AND JSON_LENGTH(target_artifact_ids_json)>0 AND (state<>'COMMITTED' OR child_request_id IS NOT NULL))
```

### `chat_typed_admission`

- 原约束：`chk_chat_typed_admission_generation`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
conversation_generation>=1
```

- 原约束：`chk_chat_typed_admission_digest`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
request_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'
      AND body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'
```

- 原约束：`chk_chat_typed_admission_intent`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
intent IN ('DISCUSSION','CLARIFICATION_REPLY')
```

- 原约束：`chk_chat_typed_admission_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
request_revision=1 AND assignment_revision>=0 AND state_version>=0 AND event_cursor>=0
```

- 原约束：`chk_chat_typed_admission_turns`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
JSON_VALID(turn_ids_json) AND JSON_VALID(source_catalog_json)
```

- 原约束：`chk_chat_typed_admission_reply`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
(intent='DISCUSSION' AND pending_question_id IS NULL)
      OR (intent='CLARIFICATION_REPLY' AND parent_outcome_id IS NOT NULL
        AND pending_question_id IS NOT NULL)
```

### `chat_typed_outcome`

- 原约束：`chk_chat_typed_outcome_generation`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
conversation_generation>=1
```

- 原约束：`chk_chat_typed_outcome_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
request_revision=1 AND assignment_revision>=0
```

- 原约束：`chk_chat_typed_outcome_digest`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
final_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$'
```

- 原约束：`chk_chat_typed_outcome_kind`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
kind IN ('ANSWER','CLARIFY','EXECUTION_PROPOSAL','ACTION_REQUEST','REVISION_REQUEST','SUBMISSION_REQUEST')
```

- 原约束：`chk_chat_typed_outcome_json`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
JSON_VALID(binding_json) AND JSON_VALID(facts_json) AND JSON_VALID(outcome_json)
      AND JSON_VALID(source_catalog_json)
```

### `chat_typed_pending_question`

- 原约束：`chk_chat_typed_pending_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
state IN ('OPEN','ANSWERED')
```

- 原约束：`chk_chat_typed_pending_version`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
(state='OPEN' AND state_version=0 AND reply_request_id IS NULL
      AND reply_idempotency_key IS NULL AND reply_body_digest IS NULL)
     OR (state='ANSWERED' AND state_version=1 AND reply_request_id IS NOT NULL
      AND reply_idempotency_key IS NOT NULL
      AND reply_body_digest REGEXP BINARY '^sha256:[0-9a-f]{64}$')
```

- 原约束：`chk_chat_typed_pending_required`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
JSON_VALID(required_facts_json)
```

### `chat_typed_proposal`

- 原约束：`chk_chat_typed_proposal_state`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
state='PROPOSED' AND state_version=0
```

- 原约束：`chk_chat_typed_proposal_operation`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
operation IN ('GENERATE_IMAGE','EDIT_IMAGE')
```

- 原约束：`chk_chat_typed_proposal_sources`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
JSON_VALID(source_ref_ids_json) AND JSON_VALID(source_selectors_json)
```

- 原约束：`chk_chat_typed_proposal_parent`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-deliberation-schema-v1.sql`

```sql
(parent_request_id IS NULL AND parent_step_id IS NULL)
      OR (parent_request_id IS NOT NULL AND parent_step_id IS NOT NULL)
```

### `chat_typed_review_context`

- 原约束：`chk_ctrc_revision`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-review-context-v1.sql`

```sql
request_revision>0
```

- 原约束：`chk_ctrc_json`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-review-context-v1.sql`

```sql
JSON_VALID(basis_json) AND JSON_TYPE(CAST(basis_json AS JSON)) IN ('OBJECT','NULL')
```

- 原约束：`chk_ctrc_hash`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-review-context-v1.sql`

```sql
REGEXP_LIKE(basis_sha256,'^[0-9a-f]{64}$','c')
```

- 原约束：`chk_ctrc_time`；来源：`chat/jia-chat-mapper/src/main/resources/db/chat-typed-review-context-v1.sql`

```sql
created_at>=0
```

### `economy_account`

- 原约束：`chk_economy_account_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
currency = 'SILVER'
```

- 原约束：`chk_economy_account_negative`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
allow_negative IN (0,1)
        AND (
            (allow_negative = 0 AND balance_micro >= 0)
            OR
            (allow_negative = 1
             AND owner_type = 'SYSTEM'
             AND purpose IN ('SILVER_ISSUANCE','PROVIDER_VARIANCE'))
        )
```

- 原约束：`chk_economy_account_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
status IN ('ACTIVE','FROZEN','CLOSED')
```

- 原约束：`chk_economy_account_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
version >= 0
```

### `economy_entry`

- 原约束：`chk_economy_entry_sequence`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
entry_sequence > 0
```

- 原约束：`chk_economy_entry_amount`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
signed_amount_micro <> 0
```

- 原约束：`chk_economy_entry_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
currency = 'SILVER'
```

- 原约束：`chk_economy_entry_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
status = 'POSTED'
```

### `economy_escrow`

- 原约束：`chk_economy_escrow_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
currency = 'SILVER'
```

- 原约束：`chk_economy_escrow_amounts`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
gross_micro > 0 AND captured_micro >= 0 AND refunded_micro >= 0
        AND captured_micro <= gross_micro - refunded_micro
```

- 原约束：`chk_economy_escrow_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
status IN ('ACTIVE','PARTIALLY_CAPTURED','CAPTURED','REFUNDED','EXPIRED')
```

- 原约束：`chk_economy_escrow_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
version > 0
```

### `economy_escrow_funding_lot`

- 原约束：`chk_economy_funding_sequence`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
funding_sequence > 0
```

- 原约束：`chk_economy_funding_amount`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
amount_micro > 0 AND escrow_gross_after_micro >= amount_micro
```

- 原约束：`chk_economy_funding_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
escrow_version_after > 0
```

- 原约束：`chk_economy_funding_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
currency = 'SILVER'
```

### `economy_hosting_lease`

- 原约束：`chk_hosting_lease_values`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND version > 0
```

- 原约束：`chk_hosting_lease_actor`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
principal_type = 'USER'
```

- 原约束：`chk_hosting_lease_live_slot`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(status = 'REFUNDED' AND live_slot IS NULL)
        OR (status IN ('PROVISIONING','ACTIVE') AND live_slot IS NOT NULL AND live_slot = 1)
```

- 原约束：`chk_hosting_lease_state`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(status = 'PROVISIONING' AND paid_from IS NULL AND paid_through IS NULL)
        OR
        (status = 'ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from)
        OR
        (status = 'REFUNDED' AND paid_from IS NULL AND paid_through IS NULL)
```

### `economy_hosting_provisioning_intent`

- 原约束：`chk_hosting_intent_runtime`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`, `economy/jia-economy-mapper/src/main/resources/db/migration-gss-hosting-runtime-v1-20261010.sql`

```sql
(runtime_installation_id IS NULL AND runtime_manifest_sha256 IS NULL AND runtime_provision_generation = 0)
        OR (quote_purpose = 'INITIAL' AND runtime_installation_id IS NOT NULL
            AND runtime_manifest_sha256 IS NOT NULL AND OCTET_LENGTH(runtime_manifest_sha256) = 64
            AND runtime_provision_generation > 0)
```

- 原约束：`chk_hosting_intent_values`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
amount_micro > 0 AND period_seconds > 0 AND escrow_version > 0 AND version > 0
```

- 原约束：`chk_hosting_intent_actor`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
principal_type = 'USER'
```

- 原约束：`chk_hosting_intent_reserve_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
OCTET_LENGTH(reserve_idempotency_key) = 36
```

- 原约束：`chk_hosting_intent_reserve_hash`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
OCTET_LENGTH(reserve_request_hash) = 32
```

- 原约束：`chk_hosting_intent_capture_pair`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(capture_idempotency_key IS NULL AND capture_request_hash IS NULL AND capture_transaction_id IS NULL AND captured_at IS NULL)
        OR
        (capture_idempotency_key IS NOT NULL AND capture_request_hash IS NOT NULL
         AND OCTET_LENGTH(capture_idempotency_key) = 36 AND OCTET_LENGTH(capture_request_hash) = 32
         AND capture_transaction_id IS NOT NULL AND captured_at IS NOT NULL)
```

- 原约束：`chk_hosting_intent_refund_pair`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(refund_idempotency_key IS NULL AND refund_request_hash IS NULL AND refund_transaction_id IS NULL AND refunded_at IS NULL)
        OR
        (refund_idempotency_key IS NOT NULL AND refund_request_hash IS NOT NULL
         AND OCTET_LENGTH(refund_idempotency_key) = 36 AND OCTET_LENGTH(refund_request_hash) = 32
         AND refund_transaction_id IS NOT NULL AND refunded_at IS NOT NULL)
```

- 原约束：`chk_hosting_intent_paid_period`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(status = 'ACTIVE' AND paid_from IS NOT NULL AND paid_through IS NOT NULL AND paid_through > paid_from)
        OR (status <> 'ACTIVE' AND paid_from IS NULL AND paid_through IS NULL)
```

- 原约束：`chk_hosting_intent_state`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(status = 'FUNDS_RESERVED' AND service_ready_at IS NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status IN ('PROVISIONING_UNKNOWN','FAILED_NO_EFFECT') AND service_ready_at IS NULL
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'SERVICE_READY' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'ACTIVE' AND service_ready_at IS NOT NULL AND service_ready_at >= reserved_at
         AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NOT NULL AND refund_transaction_id IS NULL)
        OR
        (status = 'REFUNDED' AND service_ready_at IS NULL AND outcome_evidence_ref IS NOT NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NOT NULL)
```

### `economy_hosting_rent_plan`

- 原约束：`chk_hosting_plan_values`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND quote_ttl_seconds > 0
```

- 原约束：`chk_hosting_plan_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
currency = 'SILVER'
```

- 原约束：`chk_hosting_plan_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
status IN ('ACTIVE','DISABLED')
```

### `economy_hosting_rent_quote`

- 原约束：`chk_hosting_quote_values`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
plan_version > 0 AND amount_micro > 0 AND period_seconds > 0 AND expires_at > create_time
```

- 原约束：`chk_hosting_quote_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36
```

- 原约束：`chk_hosting_quote_hash`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
OCTET_LENGTH(request_hash) = 32
```

- 原约束：`chk_hosting_quote_actor`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
principal_type = 'USER'
```

- 原约束：`chk_hosting_quote_purpose`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(quote_purpose = 'INITIAL' AND lease_id IS NULL AND expected_lease_version IS NULL)
        OR
        (quote_purpose = 'RENEWAL' AND lease_id IS NOT NULL AND expected_lease_version IS NOT NULL AND expected_lease_version > 0)
```

### `economy_hosting_reprovision`

- 原约束：`chk_hosting_reprovision_runtime`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`, `economy/jia-economy-mapper/src/main/resources/db/migration-gss-hosting-runtime-v1-20261010.sql`

```sql
runtime_target_generation IS NULL OR runtime_target_generation > 0
```

- 原约束：`chk_hosting_reprovision_values`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
lease_version > 0 AND version > 0 AND requested_at > 0 AND paid_through > requested_at
```

- 原约束：`chk_hosting_reprovision_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36 AND OCTET_LENGTH(request_hash) = 32
```

- 原约束：`chk_hosting_reprovision_state`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-hosting-rent.sql`

```sql
(status IN ('ACCEPTED','PROVISIONING_UNKNOWN') AND live_slot IS NOT NULL AND live_slot = 1 AND service_ready_at IS NULL)
        OR (status = 'SERVICE_READY' AND live_slot IS NULL AND service_ready_at IS NOT NULL AND service_ready_at >= requested_at AND evidence_ref IS NOT NULL)
        OR (status = 'FAILED_NO_EFFECT' AND live_slot IS NULL AND service_ready_at IS NULL AND evidence_ref IS NOT NULL)
```

### `economy_skill_agent_version`

- 原约束：`chk_skill_agent_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-application.sql`

```sql
version > 0
```

### `economy_skill_change`

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
version>0
```

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
JSON_VALID(dto_json)
```

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
JSON_VALID(permissions_json)
```

### `economy_skill_change_command`

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
JSON_VALID(data_json)
```

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
evidence_json IS NULL OR JSON_VALID(evidence_json)
```

### `economy_skill_credential_operation`

- 原约束：`chk_skill_credential_key_length`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-application.sql`

```sql
OCTET_LENGTH(idempotency_key)=36
```

### `economy_skill_entitlement`

- 原约束：`chk_skill_entitlement_permission`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
permission_grant_version = 1 AND OCTET_LENGTH(approved_permissions_sha256) = 32
```

- 原约束：`chk_skill_entitlement_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
status IN ('PENDING_INSTALLATION','ACTIVE','FAILED')
```

- 原约束：`chk_skill_entitlement_state_time`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
(status = 'PENDING_INSTALLATION' AND activated_at IS NULL AND failed_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'FAILED' AND activated_at IS NULL AND failed_at IS NOT NULL)
```

- 原约束：`chk_skill_entitlement_version_number`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
version > 0
```

### `economy_skill_installation`

- 原约束：`chk_skill_installation_envelope`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
schema_version IN (1,2) AND message_type='command.dispatch' AND command_type='SKILL_INSTALL'
 AND request_id=message_id AND attempt>0 AND fencing_token>0 AND delivery_epoch>0
```

- 原约束：`chk_skill_installation_envelope`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
schema_version = 1 AND message_type = 'command.dispatch' AND command_type = 'SKILL_INSTALL'
        AND request_id = message_id AND attempt > 0 AND fencing_token > 0 AND delivery_epoch > 0
```

- 原约束：`chk_skill_installation_package`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
package_size > 0 AND OCTET_LENGTH(package_sha256) = 32
```

- 原约束：`chk_skill_installation_path`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
download_path = CONCAT('/internal/agent/skill-installations/', installation_id, '/package')
```

- 原约束：`chk_skill_installation_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
status IN ('REQUESTED','INSTALLING','SUCCEEDED','FAILED')
```

- 原约束：`chk_skill_installation_state_time`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
(status = 'REQUESTED' AND started_at IS NULL AND installed_at IS NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'INSTALLING' AND started_at IS NOT NULL AND installed_at IS NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'SUCCEEDED' AND started_at IS NOT NULL AND installed_at IS NOT NULL AND failed_at IS NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND installed_at IS NULL AND failed_at IS NOT NULL AND failure_code IS NOT NULL)
```

- 原约束：`chk_skill_installation_version_number`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
version > 0
```

### `economy_skill_managed_credential`

- 原约束：`chk_skill_credential_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-application.sql`

```sql
binding_id > 0 AND version > 0
```

- 原约束：`chk_skill_credential_lifecycle`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-application.sql`

```sql
(live_slot IS NOT NULL AND live_slot=1 AND retired_at IS NULL)
  OR (live_slot IS NULL AND retired_at IS NOT NULL)
```

### `economy_skill_managed_state`

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
skill_revision>=0
```

### `economy_skill_management_quote`

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
JSON_VALID(request_json)
```

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
JSON_VALID(dto_json)
```

### `economy_skill_order`

- 原约束：`chk_skill_order_parties`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
buyer_type = 'USER' AND seller_type = 'SYSTEM' AND seller_id = 'SKILL_STORE'
```

- 原约束：`chk_skill_order_amount`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
price_micro >= 0 AND expected_agent_version >= 0
```

- 原约束：`chk_skill_order_permission`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
permission_grant_version = 1 AND OCTET_LENGTH(approved_permissions_sha256) = 32
```

- 原约束：`chk_skill_order_funding`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
(price_micro = 0 AND escrow_id IS NULL AND reserve_transaction_id IS NULL
         AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR
        (price_micro > 0 AND escrow_id IS NOT NULL AND reserve_transaction_id IS NOT NULL)
```

- 原约束：`chk_skill_order_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
status IN ('FUNDS_HELD','INSTALLING','ACTIVE','REFUNDED')
```

- 原约束：`chk_skill_order_state_time`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
(status = 'FUNDS_HELD' AND installing_at IS NULL AND active_at IS NULL AND refunded_at IS NULL)
        OR (status = 'INSTALLING' AND installing_at IS NOT NULL AND active_at IS NULL AND refunded_at IS NULL)
        OR (status = 'ACTIVE' AND installing_at IS NOT NULL AND active_at IS NOT NULL AND refunded_at IS NULL)
        OR (status = 'REFUNDED' AND active_at IS NULL AND refunded_at IS NOT NULL)
```

- 原约束：`chk_skill_order_paid_terminal`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
price_micro = 0
        OR (status IN ('FUNDS_HELD','INSTALLING') AND capture_transaction_id IS NULL AND refund_transaction_id IS NULL)
        OR (status = 'ACTIVE' AND capture_transaction_id IS NOT NULL AND refund_transaction_id IS NULL)
        OR (status = 'REFUNDED' AND capture_transaction_id IS NULL AND refund_transaction_id IS NOT NULL)
```

- 原约束：`chk_skill_order_version_number`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
version > 0
```

### `economy_skill_order_receipt`

- 原约束：`chk_skill_receipt_actor`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
actor_type = 'USER'
```

- 原约束：`chk_skill_receipt_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36
```

- 原约束：`chk_skill_receipt_hashes`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
OCTET_LENGTH(request_hash) = 32 AND OCTET_LENGTH(approved_permissions_sha256) = 32
```

- 原约束：`chk_skill_receipt_snapshot`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
order_version = 1 AND order_status = 'FUNDS_HELD'
        AND price_micro >= 0 AND permission_grant_version = 1
        AND ((price_micro = 0 AND escrow_id IS NULL) OR (price_micro > 0 AND escrow_id IS NOT NULL))
```

### `economy_skill_product`

- 原约束：`chk_skill_product_seller`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
seller_type = 'SYSTEM' AND seller_id = 'SKILL_STORE'
```

- 原约束：`chk_skill_product_status`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
status IN ('DRAFT','PUBLISHED','RETIRED')
```

- 原约束：`chk_skill_product_current`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
(status = 'DRAFT' AND current_product_version_id IS NULL)
        OR (status IN ('PUBLISHED','RETIRED') AND current_product_version_id IS NOT NULL)
```

- 原约束：`chk_skill_product_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
version > 0
```

### `economy_skill_product_version`

- 原约束：`chk_skill_product_version_sequence`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
version_sequence > 0
```

- 原约束：`chk_skill_product_version_price`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
price_micro >= 0
```

- 原约束：`chk_skill_product_version_package`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
package_size > 0 AND OCTET_LENGTH(package_sha256) = 32
        AND OCTET_LENGTH(approved_permissions_sha256) = 32
```

- 原约束：`chk_skill_product_version_restriction`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
deployment_restriction IN ('NONE','ADMIN_ONLY')
        AND (skill_key <> 'deploy-runner' OR deployment_restriction = 'ADMIN_ONLY')
```

- 原约束：`chk_skill_product_version_review`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
review_status = 'APPROVED'
```

### `economy_skill_purchase_quote`

- 原约束：`chk_skill_quote_actor`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
actor_type = 'USER'
```

- 原约束：`chk_skill_quote_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36
```

- 原约束：`chk_skill_quote_hashes`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
OCTET_LENGTH(request_hash) = 32 AND OCTET_LENGTH(approved_permissions_sha256) = 32
```

- 原约束：`chk_skill_quote_amount_version`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
expected_price_micro >= 0 AND expected_agent_version >= 0
```

- 原约束：`chk_skill_quote_restriction`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
deployment_restriction IN ('NONE','ADMIN_ONLY')
```

- 原约束：`chk_skill_quote_expiry`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-marketplace.sql`

```sql
expires_at > create_time
```

### `economy_skill_result_receipt`

- 原约束：`chk_skill_result_outcome`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-skill-application.sql`

```sql
outcome IN ('ACTIVE','REFUNDED','UNKNOWN')
```

### `economy_skill_upgrade_policy`

- 原约束：`(数据库自动命名)`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-skill-management-sm-c1.sql`

```sql
policy IN ('COVERED','PAID','NOT_ALLOWED')
```

### `economy_transaction`

- 原约束：`chk_economy_transaction_key`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
OCTET_LENGTH(idempotency_key) = 36
```

- 原约束：`chk_economy_transaction_hash`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
OCTET_LENGTH(request_hash) = 32
```

- 原约束：`chk_economy_transaction_currency`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
currency = 'SILVER'
```

- 原约束：`chk_economy_transaction_totals`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
entry_count >= 0 AND debit_total_micro >= 0 AND credit_total_micro >= 0
```

- 原约束：`chk_economy_transaction_state`；来源：`economy/jia-economy-mapper/src/main/resources/db/economy-v0-foundation.sql`, `economy/jia-economy-mapper/src/main/resources/db/schema.sql`

```sql
(status = 'POSTING' AND posted_at IS NULL AND entry_count = 0 AND debit_total_micro = 0 AND credit_total_micro = 0)
        OR
        (status = 'POSTED' AND posted_at IS NOT NULL AND entry_count >= 2 AND debit_total_micro > 0 AND debit_total_micro = credit_total_micro)
```

### `hall_case_execution`

- 原约束：`chk_hall_case_execution_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
tenant_id='0'
```

- 原约束：`chk_hall_case_execution_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
revision_no>=1 AND revision_no<=9007199254740991
```

- 原约束：`chk_hall_case_execution_lineage`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
(revision_no=1 AND parent_execution_id IS NULL AND source_output_ref_json IS NULL)
        OR (revision_no>1 AND parent_execution_id IS NOT NULL AND source_output_ref_json IS NOT NULL)
```

- 原约束：`chk_hall_case_execution_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
created_at>=0
```

### `hall_private_case`

- 原约束：`chk_hall_case_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
tenant_id='0'
```

- 原约束：`chk_hall_case_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
revision>=1 AND revision<=9007199254740991
```

- 原约束：`chk_hall_case_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-case-v1.sql`

```sql
created_at>=0 AND updated_at>=created_at
```

### `hall_private_mark`

- 原约束：`chk_hall_private_mark_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
tenant_id='0'
```

- 原约束：`chk_hall_private_mark_type`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
source_type IN ('PRIVATE_CASE','LEGACY_EXECUTION')
```

- 原约束：`chk_hall_private_mark_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
revision>=1 AND revision<=9007199254740991
```

- 原约束：`chk_hall_private_mark_archived`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
archived IN (0,1)
```

- 原约束：`chk_hall_private_mark_view`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
(viewed_execution_id IS NULL AND viewed_manifest_id IS NULL)
   OR (viewed_execution_id IS NOT NULL AND viewed_manifest_id IS NOT NULL)
```

- 原约束：`chk_hall_private_mark_time`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-private-mark-v1.sql`

```sql
snapshot_updated_at>=0 AND updated_at>=0
```

### `hall_request_draft`

- 原约束：`chk_hall_draft_tenant`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
tenant_id = '0'
```

- 原约束：`chk_hall_draft_kind`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
kind IN ('CREATE','REVISION','TASK_CREATE','TASK_ACTION')
```

- 原约束：`chk_hall_draft_state`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
state IN ('EDITING','SUBMITTED','DISCARDED')
```

- 原约束：`chk_hall_draft_revision`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
revision >= 1 AND revision <= 9007199254740991
```

- 原约束：`chk_hall_draft_source_tuple`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
(source_type IS NULL AND source_id IS NULL AND source_version IS NULL)
        OR (source_type='FILE' AND source_id IS NOT NULL AND source_version >= 1)
        OR (source_type IN ('CONVERSATION','TASK','EXECUTION_OUTPUT')
            AND source_id IS NOT NULL AND source_version IS NULL)
```

- 原约束：`chk_hall_draft_kind_refs`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
(kind='REVISION' AND source_output_ref_json IS NOT NULL AND task_id IS NULL)
        OR (kind='CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL)
        OR (kind='TASK_CREATE' AND source_output_ref_json IS NULL AND task_id IS NULL)
        OR (kind='TASK_ACTION' AND source_output_ref_json IS NULL AND task_id IS NOT NULL)
```

- 原约束：`chk_hall_draft_submit_pair`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
(submit_key IS NULL AND submit_hash IS NULL)
        OR (submit_key IS NOT NULL AND submit_hash IS NOT NULL)
```

- 原约束：`chk_hall_draft_discard_pair`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
(discard_key IS NULL AND discard_hash IS NULL)
        OR (discard_key IS NOT NULL AND discard_hash IS NOT NULL)
```

- 原约束：`chk_hall_draft_lifecycle`；来源：`agent/jia-agent-mapper/src/main/resources/db/hall-request-draft-v1.sql`

```sql
(state='EDITING' AND submission_ref IS NULL AND submitted_execution_id IS NULL
            AND discard_key IS NULL AND discard_hash IS NULL)
        OR (state='DISCARDED' AND submission_ref IS NULL AND submitted_execution_id IS NULL
            AND discard_key IS NOT NULL AND discard_hash IS NOT NULL)
        OR (state='SUBMITTED' AND submission_ref IS NOT NULL AND submit_key IS NOT NULL
            AND submit_hash IS NOT NULL)
```

### `point_account`

- 原约束：`chk_point_account_balance`；来源：`point/jia-point-mapper/src/main/resources/db/point-account.sql`

```sql
balance >= 0
```

- 原约束：`chk_point_account_version`；来源：`point/jia-point-mapper/src/main/resources/db/point-account.sql`

```sql
version >= 0
```

### `wx_daily_vote_message_receipt`

- 原约束：`chk_wx_daily_vote_alias_message_key`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
message_key REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_wx_daily_vote_alias_user_key`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
user_key REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_wx_daily_vote_alias_fingerprint`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
request_fingerprint REGEXP '^[0-9a-f]{64}$'
```

### `wx_daily_vote_receipt`

- 原约束：`chk_wx_daily_vote_message_key`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
message_key REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_wx_daily_vote_user_key`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
user_key REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_wx_daily_vote_fingerprint`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
request_fingerprint REGEXP '^[0-9a-f]{64}$'
```

- 原约束：`chk_wx_daily_vote_result`；来源：`wx/jia-wx-mapper/src/main/resources/db/wx-daily-vote-opt-01a08daa.sql`, `wx/jia-wx-service/src/main/resources/db/wx-daily-vote-receipt-schema-v1.sql`

```sql
(status = 'PROCESSING' AND correct IS NULL AND point_awarded IS NULL AND reply_content IS NULL)
        OR
        (status = 'COMPLETED' AND correct IN (0, 1) AND point_awarded >= 0 AND reply_content IS NOT NULL)
```


### `agent_position_authority`（本轮前已单独清理）

- 来源：`agent/jia-agent-mapper/src/main/resources/db/agent-position-skills.sql`。原规则为权限键枚举；用户已明确由业务层授权，不以数据库CHECK限制。

```sql
permission_key IN ('MANAGE_POSITIONS','ARCHIVE_WRITE','ARCHIVE_PUBLISH','SKILL_REVIEW','SKILL_PUBLISH')
```

生产只读目录仍为原三项枚举，未应用五项旧候选。全库撤除将覆盖该表，不重复执行岗位专项DROP。
